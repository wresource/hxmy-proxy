package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.proxy.NioRelayReactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.concurrent.thread

/**
 * relay 层并发扩展性探针：**绕开 HTTP/建连/规则**，只让 [NioRelayReactor] 搬字节，
 * 与「两个线程阻塞 pump」（= [com.mzstd.hxmyproxy.core.proxy.RelayEngine] 的模型）对照。
 *
 * 目的：把「8 并发只扩展 1.9 倍」这件事二分到 relay 层还是别处。
 * 拓扑（每条隧道 4 个 loopback SocketChannel，两端 peer 都是普通阻塞线程）：
 *
 *     upstreamPeer --write--> upstreamCh ==relay==> clientCh --read--> clientPeer
 *
 * 两个可控旋钮，用来把 loopback 上「水管永远满」的假象拆掉：
 * - [rcvBuf]：client 侧 socket 接收缓冲——调小即制造 **partial write**，让 relay 进入背压
 *   （draining=true → 上游 OP_READ 被关掉），这是真实链路上每时每刻都在发生、
 *   而 loopback 默认几乎从不发生的状态。
 * - [readerDelayUs]：client 读取节奏——模拟"下游没那么快收"。
 */
class RelayScalingProbeTest {

    @Test fun nioReactorScalesWithConcurrency() {
        println("===== NIO reactor 纯 relay 吞吐（无背压：默认 socket buffer、reader 全速）=====")
        header()
        for (workers in intArrayOf(1, 2, 4)) {
            for (n in intArrayOf(1, 2, 4, 8)) {
                row("nio(w=$workers)", n, best(3) { nioRun(workers, n, MB * 24, rcvBuf = 0, readerDelayUs = 0) })
            }
        }
        println()
        println("===== 阻塞 pump 对照（每隧道 2 线程，等价 RelayEngine）=====")
        header()
        for (n in intArrayOf(1, 2, 4, 8)) {
            row("blocking", n, best(3) { blockingRun(n, MB * 24, rcvBuf = 0, readerDelayUs = 0) })
        }
        println()
        println("===== 裸 loopback 基线（不经 relay）=====")
        header()
        for (n in intArrayOf(1, 2, 4, 8)) {
            row("direct", n, best(3) { directRun(n, MB * 24, rcvBuf = 0, readerDelayUs = 0) })
        }
    }

    /** 关键场景：client 侧接收缓冲很小 → relay 每次写都写不完 → 背压路径全程生效。 */
    @Test fun nioReactorUnderBackpressure() {
        val rb = 32 * 1024
        println("===== 背压场景：client SO_RCVBUF=${rb / 1024}KB，reader 每 64KB 停 200us =====")
        header()
        for (workers in intArrayOf(1, 2, 4)) {
            for (n in intArrayOf(1, 2, 4, 8)) {
                row("nio(w=$workers)", n, best(3) { nioRun(workers, n, MB * 4, rcvBuf = rb, readerDelayUs = 200) })
            }
        }
        println()
        header()
        for (n in intArrayOf(1, 2, 4, 8)) {
            row("blocking", n, best(3) { blockingRun(n, MB * 4, rcvBuf = rb, readerDelayUs = 200) })
        }
        println()
        header()
        for (n in intArrayOf(1, 2, 4, 8)) {
            row("direct", n, best(3) { directRun(n, MB * 4, rcvBuf = rb, readerDelayUs = 200) })
        }
    }

    // ============================== 三种被测拓扑 ==============================

    private fun nioRun(workers: Int, n: Int, bytes: Long, rcvBuf: Int, readerDelayUs: Long): Double {
        val reactor = NioRelayReactor(workerCount = workers)
        reactor.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            return runTopology(n, bytes, rcvBuf, readerDelayUs) { clientCh, upstreamCh ->
                clientCh.configureBlocking(false)
                upstreamCh.configureBlocking(false)
                scope.launch { reactor.relay(clientCh, upstreamCh, RELAY_BUF, 0) { _, _ -> } }
            }
        } finally {
            scope.cancel()
            reactor.stop()
        }
    }

    private fun blockingRun(n: Int, bytes: Long, rcvBuf: Int, readerDelayUs: Long): Double =
        runTopology(n, bytes, rcvBuf, readerDelayUs) { clientCh, upstreamCh ->
            // 只测下行方向（上行没有数据），一条线程阻塞 pump —— RelayEngine 的模型
            thread(isDaemon = true, name = "pump") {
                val buf = ByteBuffer.allocate(RELAY_BUF)
                try {
                    while (true) {
                        buf.clear()
                        val r = upstreamCh.read(buf)
                        if (r < 0) break
                        buf.flip()
                        while (buf.hasRemaining()) clientCh.write(buf)
                    }
                } catch (e: Exception) {
                    // 收尾期对端关闭
                } finally {
                    runCatching { clientCh.close() }; runCatching { upstreamCh.close() }
                }
            }
        }

    /** 不经 relay：把 upstreamCh/clientCh 这对中间 channel 丢掉，peer 直连 peer。 */
    private fun directRun(n: Int, bytes: Long, rcvBuf: Int, readerDelayUs: Long): Double {
        val barrier = CyclicBarrier(2 * n + 1)
        val got = AtomicLongArray(n)
        val threads = ArrayList<Thread>()
        val closeables = ArrayList<SocketChannel>()
        repeat(n) { i ->
            val (writer, reader) = connectedPair(rcvBufOnSecond = rcvBuf)
            closeables += writer; closeables += reader
            threads += writerThread(writer, bytes, barrier)
            threads += readerThread(reader, bytes, barrier, got, i, readerDelayUs)
        }
        return finish(barrier, threads, got, n, bytes) { closeables.forEach { runCatching { it.close() } } }
    }

    // ============================== 骨架 ==============================

    private fun runTopology(
        n: Int,
        bytes: Long,
        rcvBuf: Int,
        readerDelayUs: Long,
        wire: (clientCh: SocketChannel, upstreamCh: SocketChannel) -> Unit,
    ): Double {
        val barrier = CyclicBarrier(2 * n + 1)
        val got = AtomicLongArray(n)
        val threads = ArrayList<Thread>()
        val closeables = ArrayList<SocketChannel>()
        repeat(n) { i ->
            // clientPeer <-> clientCh（clientPeer 侧限接收缓冲，制造 relay 的 partial write）
            val (clientCh, clientPeer) = connectedPair(rcvBufOnSecond = rcvBuf)
            val (upstreamPeer, upstreamCh) = connectedPair(rcvBufOnSecond = 0)
            closeables += listOf(clientCh, clientPeer, upstreamPeer, upstreamCh)
            wire(clientCh, upstreamCh)
            threads += writerThread(upstreamPeer, bytes, barrier)
            threads += readerThread(clientPeer, bytes, barrier, got, i, readerDelayUs)
        }
        return finish(barrier, threads, got, n, bytes) { closeables.forEach { runCatching { it.close() } } }
    }

    private fun finish(
        barrier: CyclicBarrier,
        threads: List<Thread>,
        got: AtomicLongArray,
        n: Int,
        bytes: Long,
        cleanup: () -> Unit,
    ): Double {
        barrier.await()
        val t0 = System.nanoTime()
        threads.forEach { it.join(180_000) }
        val elapsed = (System.nanoTime() - t0) / 1e9
        var total = 0L
        for (i in 0 until n) {
            val v = got.get(i)
            check(v == bytes) { "第 $i 条只收到 $v 字节（期望 $bytes）" }
            total += v
        }
        cleanup()
        return total / 1024.0 / 1024.0 / elapsed
    }

    private fun writerThread(ch: SocketChannel, bytes: Long, barrier: CyclicBarrier): Thread =
        thread(isDaemon = true, name = "writer") {
            val buf = ByteBuffer.allocate(CHUNK)
            runCatching { barrier.await() }
            var sent = 0L
            try {
                while (sent < bytes) {
                    val take = minOf(CHUNK.toLong(), bytes - sent).toInt()
                    buf.clear(); buf.limit(take)
                    while (buf.hasRemaining()) ch.write(buf)
                    sent += take
                }
            } catch (e: Exception) {
                // 对端提前关闭
            }
        }

    private fun readerThread(
        ch: SocketChannel,
        bytes: Long,
        barrier: CyclicBarrier,
        got: AtomicLongArray,
        idx: Int,
        delayUs: Long,
    ): Thread = thread(isDaemon = true, name = "reader") {
        val buf = ByteBuffer.allocate(CHUNK)
        runCatching { barrier.await() }
        var g = 0L
        try {
            while (g < bytes) {
                buf.clear()
                if (bytes - g < CHUNK) buf.limit((bytes - g).toInt())
                val r = ch.read(buf)
                if (r < 0) break
                g += r
                if (delayUs > 0) java.util.concurrent.locks.LockSupport.parkNanos(delayUs * 1000)
            }
        } catch (e: Exception) {
            // 对端提前关闭
        }
        got.set(idx, g)
    }

    /** 一对已连接的阻塞 SocketChannel；[rcvBufOnSecond]>0 时给第二个（=接收侧）设小 SO_RCVBUF。 */
    private fun connectedPair(rcvBufOnSecond: Int): Pair<SocketChannel, SocketChannel> {
        val ss = ServerSocketChannel.open()
        if (rcvBufOnSecond > 0) ss.setOption(StandardSocketOptions.SO_RCVBUF, rcvBufOnSecond)
        ss.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val a = SocketChannel.open()
        a.configureBlocking(true)
        a.connect(ss.localAddress as InetSocketAddress)
        val b = ss.accept()
        ss.close()
        a.configureBlocking(true); b.configureBlocking(true)
        a.setOption(StandardSocketOptions.TCP_NODELAY, true)
        b.setOption(StandardSocketOptions.TCP_NODELAY, true)
        // a = 写入侧，b = 接收侧
        return a to b
    }

    // ============================== 输出 ==============================

    private fun best(rounds: Int, block: () -> Double): Double {
        var b = 0.0
        repeat(rounds) { b = maxOf(b, block()) }
        return b
    }

    private var baseline = HashMap<String, Double>()

    private fun header() = println(String.format("%-14s %6s %14s %10s", "topology", "conc", "MB/s", "scale"))

    private fun row(tag: String, n: Int, mbps: Double) {
        if (n == 1) baseline[tag] = mbps
        val b = baseline[tag] ?: mbps
        println(String.format("%-14s %6d %14.1f %10.2f", tag, n, mbps, mbps / b))
    }

    private companion object {
        const val MB = 1024L * 1024L
        const val CHUNK = 64 * 1024
        /** 与真机同配置（lim_buffer=131072）。 */
        const val RELAY_BUF = 128 * 1024
    }
}
