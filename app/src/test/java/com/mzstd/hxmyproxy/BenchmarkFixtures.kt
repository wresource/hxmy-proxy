package com.mzstd.hxmyproxy

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/** 源数据预生成；延迟是每流发送间隔，不声称模拟TCP RTT。共享限速显式传入同一pacer。 */
internal class BenchmarkOrigin(
    private val chunkBytes: Int,
    private val perChunkDelayMs: Long,
    private val shared: SharedBandwidth? = null,
) : Closeable {
    private val resources = BenchmarkResources()
    private val server = resources.own(ServerSocket(0, 256, InetAddress.getByName("127.0.0.1")))
    private val active = AtomicInteger()
    val port: Int get() = server.localPort
    private val payload = ByteArray(chunkBytes) { (it and 0x7f).toByte() }
    init {
        require(chunkBytes > 0 && chunkBytes % 128 == 0)
        resources.launch("bench-origin-accept") {
            while (!server.isClosed) {
                val socket = try { resources.own(server.accept()) } catch (_: Exception) { break }
                try {
                    active.incrementAndGet()
                    resources.launch("bench-origin-serve") {
                        try {
                            socket.tcpNoDelay = true; socket.soTimeout = 5000
                            check(ProxyTestKit.readLine(socket.getInputStream()) == "GO")
                            val out = socket.getOutputStream()
                            while (!socket.isClosed) {
                                if (perChunkDelayMs > 0) Thread.sleep(perChunkDelayMs)
                                shared?.transfer(payload.size)
                                out.write(payload); out.flush()
                            }
                        } catch (_: Exception) {
                            // 客户端读取目标字节数后关闭；测量端负责校验完整性及失败。
                        } finally { socket.close(); active.decrementAndGet() }
                    }
                } catch (_: IllegalStateException) { socket.close(); active.decrementAndGet(); break }
            }
        }
    }
    fun awaitIdle() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (active.get() != 0 && System.nanoTime() < deadline) Thread.sleep(2)
        check(active.get() == 0) { "previous origin load still active" }
    }
    override fun close() = resources.close()
}

/** 每流、每方向独立的延迟与在途字节上限。不能据此推断共享WAN容量或内核TCP窗口。 */
internal class BenchmarkDelayLink(
    private val targetPort: Int,
    private val delayMs: Long,
    private val inFlightBytes: Int,
) : Closeable {
    private val resources = BenchmarkResources()
    private val server = resources.own(ServerSocket(0, 256, InetAddress.getByName("127.0.0.1")))
    val port: Int get() = server.localPort
    init {
        require(inFlightBytes > 0 && delayMs >= 0)
        resources.launch("bench-link-accept") {
            while (!server.isClosed) {
                val c = try { resources.own(server.accept()) } catch (_: Exception) { break }
                try { resources.launch("bench-link-open") {
                    try {
                        BenchmarkResources().use { pair ->
                            pair.own(c)
                            val u = BenchmarkSupport.socket(targetPort, pair)
                            c.tcpNoDelay = true
                            val ended = CountDownLatch(1)
                            pipe(c, u, pair, ended); pipe(u, c, pair, ended)
                            ended.await()
                        }
                    } catch (_: InterruptedException) { c.close() }
                    catch (_: java.io.IOException) { c.close() }
                } } catch (_: IllegalStateException) { c.close(); break }
            }
        }
    }
    private fun pipe(from: Socket, to: Socket, pair: BenchmarkResources, ended: CountDownLatch) {
        val queue = LinkedBlockingQueue<Pair<Long, ByteArray>>()
        val credit = Semaphore(inFlightBytes)
        pair.launch("bench-link-read") {
            try {
                val buf = ByteArray(minOf(32768, inFlightBytes))
                while (true) {
                    val count = from.getInputStream().read(buf)
                    if (count < 0) break
                    credit.acquire(count)
                    queue.put(System.nanoTime() + delayMs * 1_000_000 to buf.copyOf(count))
                }
                queue.put(0L to ByteArray(0))
            } catch (_: Exception) { ended.countDown() }
        }
        pair.launch("bench-link-write") {
            try {
                while (true) {
                    val (at, bytes) = queue.take()
                    if (bytes.isEmpty()) break
                    while (true) {
                        if (Thread.interrupted()) throw InterruptedException()
                        val remaining = at - System.nanoTime()
                        if (remaining <= 0) break
                        LockSupport.parkNanos(remaining)
                    }
                    to.getOutputStream().write(bytes); to.getOutputStream().flush()
                    credit.release(bytes.size)
                }
            } catch (_: Exception) {
                // 测量端会将EOF/内容缺失报告为失败。
            } finally { ended.countDown() }
        }
    }
    override fun close() = resources.close()
}
