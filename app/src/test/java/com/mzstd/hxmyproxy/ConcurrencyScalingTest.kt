package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.proxy.ConnectionRegistry
import com.mzstd.hxmyproxy.core.proxy.HttpProxyServer
import com.mzstd.hxmyproxy.core.proxy.NioRelayReactor
import com.mzstd.hxmyproxy.core.proxy.OutboundConnector
import com.mzstd.hxmyproxy.core.proxy.ProxyServer
import com.mzstd.hxmyproxy.core.proxy.RelayEngine
import com.mzstd.hxmyproxy.core.proxy.TrafficAccounting
import com.mzstd.hxmyproxy.core.security.AllowAllAccessController
import com.mzstd.hxmyproxy.core.security.EgressGuard
import com.mzstd.hxmyproxy.core.security.NoAuthAuthenticator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.Ignore
import org.junit.Test
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext

/**
 * 并发扩展性复现测试：同一个本地 origin，分别用 1 条 / N 条并发连接下载，
 * 比较「经代理」与「直连」的扩展比。不依赖真机与外网。
 *
 * 设计要点（缺一不可，否则测不出来）：
 * - **origin 不能是瓶颈**：payload 预生成、每连接独立线程、直接 write，无现场生成/无共享锁。
 * - **loopback RTT≈0 会掩盖问题**：origin 侧每写一块 sleep 一段，模拟真实链路的 RTT/BDP
 *   （数据以「每 RTT 一个窗口」的突发形式到达，而不是一条永远可读的水管）。
 * - **必须真的走 NIO**：显式传 nioReactor + useNioRelay=true，并用「毒化的 relayDispatcher」
 *   把「偷偷回退到阻塞 RelayEngine」变成可见的断言失败（项目有过这个前科）。
 * - **稳定性**：proxy/direct 同稳定态交替、多轮取中位数（见 perf-ab-methodology）。
 */
/**
 * **暂停使用（2026-08-04）**：本测试的 harness 存在阳性偏差，任何阴性结论都不可引用。
 *
 * `BulkOrigin` 的 `perChunkDelayMs` 是**每条连接各自**限速，聚合容量按构造随并发线性放大
 * ——直连基线因此必然是完美线性（实测 8 并发 = 8.00×）。用一个「按构造就线性扩展」的装置
 * 去复现「扩展不上去」，结构上不可能出阳性。
 * 另外它独占 304 秒（全部单测时长的 95%），会严重拖慢每次改动的反馈。
 *
 * 要重新启用，先把限速改成**跨连接共享的令牌桶**（整条链路一个信用池），再删掉这个 @Ignore。
 */
@Ignore("harness 有阳性偏差 + 独占 304s，见类注释")
class ConcurrencyScalingTest {

    private val allowAll = object : EgressGuard {
        override fun isAllowed(addr: InetAddress) = true
    }

    // ============================== 场景 ==============================

    /** 全速 origin（loopback 无注入延迟）：测代理在「水管永远满」时的并发扩展。 */
    @Test fun scaling_fastOrigin() {
        report("fastOrigin", perChunkDelayMs = 0, chunkBytes = 64 * 1024, bytesPerConn = 8L * 1024 * 1024)
    }

    /** 慢链路 origin（每块注入延迟，模拟 RTT/BDP）：贴近真机 0.27MB/s 的量级。 */
    @Test fun scaling_slowLink() {
        report("slowLink(16KB/8ms≈2MB/s per conn)", perChunkDelayMs = 8, chunkBytes = 16 * 1024, bytesPerConn = 2L * 1024 * 1024)
    }

    /** 更贴近真机的极慢链路：单连接 ≈0.26MB/s。 */
    @Test fun scaling_verySlowLink() {
        report("verySlowLink(16KB/60ms≈0.27MB/s per conn)", perChunkDelayMs = 60, chunkBytes = 16 * 1024, bytesPerConn = 768L * 1024)
    }

    /**
     * **BDP 链路上的并发扩展**：上游侧插一个延迟链路（单向 50ms、在途字节封顶 = 带宽×延迟），
     * 让「窗口 / RTT」而不是 CPU 成为限速者 —— 这是 loopback 与真机最大的结构性差异。
     * 直连基线走同一条延迟链路，代理只是多了一跳 loopback，两者可直接比。
     */
    @Test fun scaling_bdpLink() {
        BulkOrigin(64 * 1024, 0).use { origin ->
            DelayLink(origin.port, delayMs = 50, inFlightBytes = 256 * 1024).use { link ->
                Harness(useNio = true).use { h ->
                    val conc = intArrayOf(1, 2, 4, 8)
                    val bytes = 3L * 1024 * 1024
                    measure(2, bytes / 4) { proxyDownload(h.port, link.port, bytes / 4) }
                    measure(2, bytes / 4) { directDownload(link.port, bytes / 4) }
                    val p = HashMap<Int, Double>(); val d = HashMap<Int, Double>()
                    repeat(ROUNDS) {
                        for (n in conc) {
                            p.merge(n, measure(n, bytes) { proxyDownload(h.port, link.port, bytes) }) { a, b -> maxOf(a, b) }
                            d.merge(n, measure(n, bytes) { directDownload(link.port, bytes) }) { a, b -> maxOf(a, b) }
                        }
                    }
                    println("===== BDP 链路(单向 50ms, 在途封顶 256KB) =====")
                    println(String.format("%-6s %12s %12s %10s %10s %8s", "conc", "proxy MB/s", "direct MB/s", "p-scale", "d-scale", "ratio"))
                    for (n in conc) {
                        println(String.format("%-6d %12.3f %12.3f %10.2f %10.2f %8.2f", n, p[n]!!, d[n]!!, p[n]!! / p[1]!!, d[n]!! / d[1]!!, p[n]!! / d[n]!!))
                    }
                    check(!h.blockingRelayUsed.get()) { "走到了阻塞 RelayEngine —— NIO 路径没生效" }
                }
            }
        }
    }

    /**
     * **双跳 BDP**：入口段（client↔proxy，短 RTT 窄在途）与出口段（proxy↔origin，长 RTT）各插一条延迟链路，
     * 代理正好插在两条链路中间；直连基线把两条链路**串联**走一遍，两者经过的链路完全相同。
     * 这是最贴近真机的拓扑：只有这里，relay 的「一个 128KB 单缓冲 + 半双工」会同时受两段窗口挤压。
     */
    @Test fun scaling_twoHopBdp() {
        BulkOrigin(64 * 1024, 0).use { origin ->
            // 出口段：长 RTT（proxy → CDN）
            DelayLink(origin.port, delayMs = 50, inFlightBytes = 256 * 1024).use { far ->
                // 入口段：短 RTT（Mac → 手机 WiFi）。直连基线：client → near → far → origin
                DelayLink(far.port, delayMs = 3, inFlightBytes = 128 * 1024).use { nearForDirect ->
                    Harness(useNio = true).use { h ->
                        // 经代理：client → nearForProxy → proxy →(CONNECT far)→ far → origin
                        DelayLink(h.port, delayMs = 3, inFlightBytes = 128 * 1024).use { nearForProxy ->
                            val conc = intArrayOf(1, 2, 4, 8)
                            val bytes = 3L * 1024 * 1024
                            measure(2, bytes / 4) { proxyDownload(nearForProxy.port, far.port, bytes / 4) }
                            measure(2, bytes / 4) { directDownload(nearForDirect.port, bytes / 4) }
                            val p = HashMap<Int, Double>(); val d = HashMap<Int, Double>()
                            repeat(ROUNDS) {
                                for (n in conc) {
                                    p.merge(n, measure(n, bytes) { proxyDownload(nearForProxy.port, far.port, bytes) }) { a, b -> maxOf(a, b) }
                                    d.merge(n, measure(n, bytes) { directDownload(nearForDirect.port, bytes) }) { a, b -> maxOf(a, b) }
                                }
                            }
                            println("===== 双跳 BDP(入口 3ms/128KB + 出口 50ms/256KB) =====")
                            println(String.format("%-6s %12s %12s %10s %10s %8s", "conc", "proxy MB/s", "direct MB/s", "p-scale", "d-scale", "ratio"))
                            for (n in conc) {
                                println(String.format("%-6d %12.3f %12.3f %10.2f %10.2f %8.2f", n, p[n]!!, d[n]!!, p[n]!! / p[1]!!, d[n]!! / d[1]!!, p[n]!! / d[n]!!))
                            }
                            check(!h.blockingRelayUsed.get()) { "走到了阻塞 RelayEngine —— NIO 路径没生效" }
                        }
                    }
                }
            }
        }
    }

    /**
     * **有效窗口是否被 relayBufferBytes 钳死**：relay 是半双工单缓冲（读满一块 → 停止读上游 →
     * 全部写出 → 才恢复读），所以代理对上游通告的**有效**接收窗口不会超过 relayBufferBytes。
     * 若吞吐随该参数成比例变化，就说明「有效窗口 = 缓冲大小」这条钳制是真的；
     * 真实链路 BDP（带宽×RTT）一旦超过 128KB，吞吐就被硬钳在 buffer/RTT。
     */
    @Test fun effectiveWindowIsClampedByRelayBuffer() {
        BulkOrigin(64 * 1024, 0).use { origin ->
            DelayLink(origin.port, delayMs = 50, inFlightBytes = 1024 * 1024).use { far ->
                println("===== 出口段 50ms 单向、在途上限 1MB（远大于任何 relay 缓冲）=====")
                println(String.format("%-12s %10s %12s %12s", "relayBuf", "conc", "proxy MB/s", "direct MB/s"))
                val bytes = 3L * 1024 * 1024
                val direct1 = best { measure(1, bytes) { directDownload(far.port, bytes) } }
                val direct8 = best { measure(8, bytes) { directDownload(far.port, bytes) } }
                for (buf in intArrayOf(8 * 1024, 16 * 1024, 32 * 1024, 64 * 1024, 128 * 1024)) {
                    Harness(useNio = true, bufBytes = buf).use { h ->
                        measure(2, bytes / 4) { proxyDownload(h.port, far.port, bytes / 4) }
                        val p1 = best { measure(1, bytes) { proxyDownload(h.port, far.port, bytes) } }
                        val p8 = best { measure(8, bytes) { proxyDownload(h.port, far.port, bytes) } }
                        println(String.format("%-12s %10d %12.3f %12.3f", "${buf / 1024}KB", 1, p1, direct1))
                        println(String.format("%-12s %10d %12.3f %12.3f", "${buf / 1024}KB", 8, p8, direct8))
                        check(!h.blockingRelayUsed.get()) { "走到了阻塞 RelayEngine" }
                    }
                }
            }
        }
    }

    private fun best(block: () -> Double): Double {
        var b = 0.0
        repeat(ROUNDS) { b = maxOf(b, block()) }
        return b
    }

    /**
     * **转发延迟随并发负载的变化** —— 真正能解释「单连接持平、8 并发只扩展 1.9 倍」的指标。
     *
     * BDP 链路上单连接吞吐 = 窗口 / RTT。代理给每一块数据额外加 L 的转发延迟，两段各加一次，
     * 于是 8 并发时若 L 从微秒涨到毫秒，吞吐就会成倍塌下来——而 CPU 依然很闲（都在等）。
     * loopback 上吞吐测不出问题（内存带宽饱和），延迟能。
     */
    @Test fun forwardingLatencyUnderLoad() {
        val echo = ProxyTestKit.startEcho()
        try {
            BulkOrigin(16 * 1024, 2).use { slowBulk ->       // 每条背景 ≈8MB/s，机器不饱和
                BulkOrigin(64 * 1024, 0).use { fastBulk ->
                    Harness(useNio = true).use { h ->
                        println("===== 1 字节乒乓 RTT（微秒）随背景并发变化 =====")
                        println(String.format("%-22s %4s %10s %10s %10s %10s", "background", "bg", "proxy p50", "proxy p99", "dir p50", "dir p99"))
                        for ((label, bulk) in listOf("限速背景(8MB/s/条)" to slowBulk, "全速背景" to fastBulk)) {
                            for (bg in intArrayOf(0, 1, 2, 4, 8, 16)) {
                                val p = withBackground(h, bulk, bg) { probeViaProxy(h.port, echo.localPort) }
                                val d = withBackground(h, bulk, bg) { probeDirect(echo.localPort) }
                                println(
                                    String.format(
                                        "%-22s %4d %10.0f %10.0f %10.0f %10.0f",
                                        label, bg, p[0], p[1], d[0], d[1],
                                    )
                                )
                            }
                        }
                        check(!h.blockingRelayUsed.get()) { "走到了阻塞 RelayEngine —— NIO 路径没生效" }
                    }
                }
            }
        } finally {
            echo.close()
        }
    }

    /** 起 [n] 条持续下载做背景负载，稳定后跑 [block]，再收尾。 */
    private fun <T> withBackground(h: Harness, bulk: BulkOrigin, n: Int, block: () -> T): T {
        val stop = AtomicBoolean(false)
        val socks = java.util.Collections.synchronizedList(ArrayList<Socket>())
        val threads = (0 until n).map {
            thread(isDaemon = true, name = "bg-$it") {
                runCatching {
                    val s = Socket("127.0.0.1", h.port).apply { tcpNoDelay = true; soTimeout = 30_000 }
                    socks.add(s)
                    val out = s.getOutputStream(); val inp = s.getInputStream()
                    out.write("CONNECT 127.0.0.1:${bulk.port} HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()); out.flush()
                    ProxyTestKit.readLine(inp)
                    while (ProxyTestKit.readLine(inp).isNotEmpty()) { }
                    out.write("GO\n".toByteArray()); out.flush()
                    val buf = ByteArray(64 * 1024)
                    while (!stop.get()) { if (inp.read(buf) < 0) break }
                }
            }
        }
        Thread.sleep(500)   // 让背景连接进入稳定态
        try {
            return block()
        } finally {
            stop.set(true)
            synchronized(socks) { socks.forEach { runCatching { it.close() } } }
            threads.forEach { it.join(5_000) }
            Thread.sleep(200)
        }
    }

    private fun probeViaProxy(proxyPort: Int, echoPort: Int): DoubleArray =
        Socket("127.0.0.1", proxyPort).use { s ->
            s.tcpNoDelay = true; s.soTimeout = 30_000
            val out = s.getOutputStream(); val inp = s.getInputStream()
            out.write("CONNECT 127.0.0.1:$echoPort HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()); out.flush()
            check(ProxyTestKit.readLine(inp).contains("200"))
            while (ProxyTestKit.readLine(inp).isNotEmpty()) { }
            pingPong(s)
        }

    private fun probeDirect(echoPort: Int): DoubleArray =
        Socket("127.0.0.1", echoPort).use { s ->
            s.tcpNoDelay = true; s.soTimeout = 30_000
            pingPong(s)
        }

    private fun pingPong(s: Socket): DoubleArray {
        val out = s.getOutputStream(); val inp = s.getInputStream()
        repeat(100) { out.write(0x41); out.flush(); inp.read() }        // warmup
        val n = 500
        val samples = LongArray(n)
        for (i in 0 until n) {
            val t = System.nanoTime()
            out.write(0x41); out.flush()
            inp.read()
            samples[i] = System.nanoTime() - t
        }
        samples.sort()
        return doubleArrayOf(samples[n / 2] / 1000.0, samples[(n * 99) / 100] / 1000.0)
    }

    // ============================== 测量骨架 ==============================

    private fun report(name: String, perChunkDelayMs: Long, chunkBytes: Int, bytesPerConn: Long) {
        val conc = intArrayOf(1, 2, 4, 8)
        BulkOrigin(chunkBytes, perChunkDelayMs).use { origin ->
            Harness(useNio = true).use { h ->
                // 预热：JIT + 线程池 + selector 起来
                measure(2, bytesPerConn / 4) { proxyDownload(h.port, origin.port, bytesPerConn / 4) }
                measure(2, bytesPerConn / 4) { directDownload(origin.port, bytesPerConn / 4) }

                val proxyMbps = HashMap<Int, Double>()
                val directMbps = HashMap<Int, Double>()
                repeat(ROUNDS) {
                    for (n in conc) {
                        val p = measure(n, bytesPerConn) { proxyDownload(h.port, origin.port, bytesPerConn) }
                        val d = measure(n, bytesPerConn) { directDownload(origin.port, bytesPerConn) }
                        proxyMbps.merge(n, p) { a, b -> maxOf(a, b) }
                        directMbps.merge(n, d) { a, b -> maxOf(a, b) }
                    }
                }
                println("===== $name  (nio=${h.useNio}) =====")
                println(String.format("%-6s %12s %12s %10s %10s %8s", "conc", "proxy MB/s", "direct MB/s", "p-scale", "d-scale", "ratio"))
                val p1 = proxyMbps[1]!!
                val d1 = directMbps[1]!!
                for (n in conc) {
                    val p = proxyMbps[n]!!
                    val d = directMbps[n]!!
                    println(
                        String.format(
                            "%-6d %12.3f %12.3f %10.2f %10.2f %8.2f",
                            n, p, d, p / p1, d / d1, p / d,
                        )
                    )
                }
                check(!h.blockingRelayUsed.get()) { "走到了阻塞 RelayEngine —— NIO 路径没生效，这个测试无效" }
                println("[ok] 全程走 NIO relay（阻塞 relayDispatcher 一次都没被派发）")
            }
        }
    }

    /** 起 [n] 条并发下载，同步起跑，返回聚合吞吐（MB/s）。 */
    private fun measure(n: Int, bytesPerConn: Long, task: () -> Long): Double {
        val barrier = CyclicBarrier(n + 1)
        val got = AtomicLongArray(n)
        val threads = (0 until n).map { i ->
            thread(isDaemon = true, name = "load-$i") {
                runCatching { barrier.await() }
                got.set(i, runCatching { task() }.getOrElse { -1L })
            }
        }
        barrier.await()
        val t0 = System.nanoTime()
        threads.forEach { it.join(120_000) }
        val elapsed = (System.nanoTime() - t0) / 1e9
        var total = 0L
        for (i in 0 until n) {
            val v = got.get(i)
            check(v == bytesPerConn) { "第 $i 条只收到 $v 字节（期望 $bytesPerConn）" }
            total += v
        }
        return total / 1024.0 / 1024.0 / elapsed
    }

    // ============================== 客户端 ==============================

    private fun proxyDownload(proxyPort: Int, originPort: Int, bytes: Long): Long =
        Socket("127.0.0.1", proxyPort).use { s ->
            s.tcpNoDelay = true
            s.soTimeout = 60_000
            val out = s.getOutputStream()
            val inp = s.getInputStream()
            out.write("CONNECT 127.0.0.1:$originPort HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            out.flush()
            val status = ProxyTestKit.readLine(inp)
            check(status.contains("200")) { "CONNECT 失败: $status" }
            while (ProxyTestKit.readLine(inp).isNotEmpty()) { /* drain headers */ }
            out.write("GO\n".toByteArray()); out.flush()
            drain(inp, bytes)
        }

    private fun directDownload(originPort: Int, bytes: Long): Long =
        Socket("127.0.0.1", originPort).use { s ->
            s.tcpNoDelay = true
            s.soTimeout = 60_000
            s.getOutputStream().write("GO\n".toByteArray())
            s.getOutputStream().flush()
            drain(s.getInputStream(), bytes)
        }

    private fun drain(inp: InputStream, bytes: Long): Long {
        val buf = ByteArray(64 * 1024)
        var got = 0L
        while (got < bytes) {
            val r = inp.read(buf, 0, minOf(buf.size.toLong(), bytes - got).toInt())
            if (r < 0) break
            got += r
        }
        return got
    }

    // ============================== 被测装配 ==============================

    /** 完整 HttpProxyServer + NioRelayReactor，配置对齐真机（512/512/32/128KB/120s）。 */
    private inner class Harness(val useNio: Boolean, bufBytes: Int = 128 * 1024) : Closeable {
        val blockingRelayUsed = AtomicBoolean(false)
        private val limits = ConnectionLimits(
            maxGlobalConnections = 512,
            maxPerClientConnections = 512,
            relayParallelism = 32,
            relayBufferBytes = bufBytes,
            idleTimeoutSeconds = 120,
        )
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val accExec = Executors.newFixedThreadPool(64) { r -> Thread(r, "hxmy-accept").apply { isDaemon = true } }
        private val relExec = Executors.newFixedThreadPool(2 * limits.relayParallelism) { r ->
            Thread(r, "hxmy-relay").apply { isDaemon = true }
        }
        private val realRelayDispatcher = relExec.asCoroutineDispatcher()

        /** 一旦阻塞 RelayEngine 被用到就留痕（仍然照常派发，避免测试挂死）。 */
        private val watchedRelayDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                blockingRelayUsed.set(true)
                realRelayDispatcher.dispatch(context, block)
            }
        }

        private val reactor = NioRelayReactor(
            workerCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
        ).also { if (useNio) it.start() }

        private val server: ProxyServer = HttpProxyServer(
            acceptDispatcher = accExec.asCoroutineDispatcher(),
            accessController = AllowAllAccessController,
            registry = ConnectionRegistry(512, 512),
            connector = OutboundConnector(allowAll),
            relay = RelayEngine(),
            authProvider = { NoAuthAuthenticator },
            limitsProvider = { limits },
            relayDispatcher = watchedRelayDispatcher,
            accounting = TrafficAccounting(),
            ruleEngine = null,
            nioReactor = if (useNio) reactor else null,
            useNioRelay = useNio,
        ).also { it.start(scope, 0) }

        val port: Int = ProxyTestKit.awaitPort(server)

        override fun close() {
            server.stop()
            scope.cancel()
            if (useNio) reactor.stop()
            accExec.shutdownNow()
            relExec.shutdownNow()
        }
    }

    // ============================== 本地 origin ==============================

    /**
     * 大文件源站：客户端发一行任意内容即开始推流，直到对端关闭。
     * payload **预生成**，每连接一个线程直接 write —— origin 自己绝不能是瓶颈。
     * [perChunkDelayMs] > 0 时每写一块 sleep，模拟真实链路的 RTT（loopback 上必须人为注入，
     * 否则「水管永远满」会把调度缺陷完全掩盖）。
     */
    private class BulkOrigin(
        private val chunkBytes: Int,
        private val perChunkDelayMs: Long,
    ) : Closeable {
        private val server = ServerSocket(0, 256, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        private val payload = ByteArray(chunkBytes) { (it and 0x7F).toByte() }

        init {
            thread(isDaemon = true, name = "origin-accept") {
                while (!server.isClosed) {
                    val c = try { server.accept() } catch (e: Exception) { break }
                    thread(isDaemon = true, name = "origin-serve") { serve(c) }
                }
            }
        }

        private fun serve(c: Socket) {
            try {
                c.tcpNoDelay = true
                val inp = c.getInputStream()
                while (true) {
                    val b = inp.read()
                    if (b < 0) return
                    if (b == '\n'.code) break
                }
                val out = c.getOutputStream()
                while (true) {
                    out.write(payload)
                    out.flush()
                    if (perChunkDelayMs > 0) Thread.sleep(perChunkDelayMs)
                }
            } catch (e: Exception) {
                // 客户端读够了就关，是正常结局
            } finally {
                runCatching { c.close() }
            }
        }

        override fun close() {
            runCatching { server.close() }
        }
    }

    /**
     * 延迟链路模拟器：把到 [targetPort] 的连接包装成「单向延迟 [delayMs]、在途字节封顶
     * [inFlightBytes]」的窄管。这两个参数就是 BDP —— 有效带宽上限 ≈ inFlightBytes / delayMs，
     * 且**在途满了就停止从源读**（源侧 TCP 窗口随之关闭），这正是真实广域链路的行为，
     * 而 loopback 上永远不会发生。
     */
    private class DelayLink(
        private val targetPort: Int,
        private val delayMs: Long,
        private val inFlightBytes: Int,
    ) : Closeable {
        private val server = ServerSocket(0, 256, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true, name = "link-accept") {
                while (!server.isClosed) {
                    val c = try { server.accept() } catch (e: Exception) { break }
                    thread(isDaemon = true, name = "link-open") {
                        val u = try {
                            Socket("127.0.0.1", targetPort)
                        } catch (e: Exception) {
                            runCatching { c.close() }; return@thread
                        }
                        runCatching { c.tcpNoDelay = true; u.tcpNoDelay = true }
                        pipe(c, u)
                        pipe(u, c)
                    }
                }
            }
        }

        private fun pipe(from: Socket, to: Socket) {
            val q = java.util.concurrent.LinkedBlockingQueue<Pair<Long, ByteArray>>()
            val credit = java.util.concurrent.Semaphore(inFlightBytes)
            thread(isDaemon = true, name = "link-read") {
                val buf = ByteArray(32 * 1024)
                try {
                    while (true) {
                        val n = from.getInputStream().read(buf)
                        if (n < 0) break
                        credit.acquire(n)                       // 在途满 → 停止读源 → 源侧窗口关闭
                        q.put(System.nanoTime() + delayMs * 1_000_000L to buf.copyOf(n))
                    }
                } catch (e: Exception) {
                    // 对端关闭
                }
                q.put(0L to ByteArray(0))                       // EOF 哨兵
            }
            thread(isDaemon = true, name = "link-write") {
                try {
                    while (true) {
                        val (deadline, b) = q.take()
                        if (b.isEmpty()) break
                        val wait = deadline - System.nanoTime()
                        if (wait > 0) java.util.concurrent.locks.LockSupport.parkNanos(wait)
                        to.getOutputStream().write(b)
                        to.getOutputStream().flush()
                        credit.release(b.size)
                    }
                } catch (e: Exception) {
                    // 对端关闭
                }
                runCatching { to.shutdownOutput() }
                runCatching { from.close() }
                runCatching { to.close() }
            }
        }

        override fun close() {
            runCatching { server.close() }
        }
    }

    private companion object {
        const val ROUNDS = 3
    }
}
