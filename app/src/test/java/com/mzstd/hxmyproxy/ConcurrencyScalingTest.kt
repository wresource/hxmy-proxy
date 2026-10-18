package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.security.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.Closeable
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** 独立长基准：./gradlew :app:proxyBenchmark。常规单测保留快速测量正确性回归。 */
interface ProxyBenchmark

/**
 * 实际HTTP CONNECT/NIO与同源直连配对。每流模型用于发现代理额外瓶颈，共享容量另设实验。
 * 这些本地受控模型不等同手机VPN/WAN。样本检查内容、等待负载结束，
 * 预热后A/B与B/A交替，输出原始样本与中位数。
 */
@Category(ProxyBenchmark::class)
class ConcurrencyScalingTest {
    private val allowAll = object : EgressGuard {
        override fun isAllowed(addr: InetAddress) = true
    }
    private val conc = intArrayOf(1, 2, 4, 8)

    @Test fun scaling_fastOrigin() = report("independent-fast", 0, 65536, 8L * 1048576)
    @Test fun scaling_slowLink() = report("independent-16KiB-every-8ms", 8, 16384, 2L * 1048576)
    @Test fun scaling_verySlowLink() = report("independent-16KiB-every-60ms", 60, 16384, 768L * 1024)

    @Test fun scaling_bdpLink() {
        BenchmarkOrigin(65536, 0).use { origin ->
            BenchmarkDelayLink(origin.port, 50, 256 * 1024).use { link ->
                Harness(true).use { h ->
                    reportPaths("per-flow-delay-50ms-credit-256KiB", origin, 3L * 1048576,
                        { n, bytes -> measure(n, bytes) { proxyDownload(h.port, link.port, bytes) } },
                        { n, bytes -> measure(n, bytes) { directDownload(link.port, bytes) } })
                    check(!h.blockingRelayUsed.get())
                }
            }
        }
    }

    @Test fun scaling_twoHopBdp() {
        BenchmarkOrigin(65536, 0).use { origin ->
            BenchmarkDelayLink(origin.port, 50, 256 * 1024).use { far ->
                BenchmarkDelayLink(far.port, 3, 128 * 1024).use { direct ->
                    Harness(true).use { h ->
                        BenchmarkDelayLink(h.port, 3, 128 * 1024).use { proxy ->
                            reportPaths("two-hop-per-flow-delay", origin, 3L * 1048576,
                                { n, bytes -> measure(n, bytes) { proxyDownload(proxy.port, far.port, bytes) } },
                                { n, bytes -> measure(n, bytes) { directDownload(direct.port, bytes) } })
                        }
                        check(!h.blockingRelayUsed.get())
                    }
                }
            }
        }
    }

    /** 扫描relay缓冲影响；不把用户态缓冲直接等同内核TCP接收窗口。 */
    @Test fun effectiveWindowIsClampedByRelayBuffer() {
        BenchmarkOrigin(65536, 0).use { origin ->
            BenchmarkDelayLink(origin.port, 50, 1024 * 1024).use { far ->
                for (buf in intArrayOf(8192, 16384, 32768, 65536, 131072)) {
                    Harness(true, buf).use { h ->
                        reportPaths("relay-buffer-$buf", origin, 3L * 1048576,
                            { n, bytes -> measure(n, bytes) { proxyDownload(h.port, far.port, bytes) } },
                            { n, bytes -> measure(n, bytes) { directDownload(far.port, bytes) } },
                            intArrayOf(1, 8))
                        check(!h.blockingRelayUsed.get())
                    }
                }
            }
        }
    }

    @Test fun forwardingLatencyUnderLoad() {
        val echo = ProxyTestKit.startEcho()
        try {
            for ((label, delay) in listOf("paced" to 2L, "unpaced" to 0L)) {
                BenchmarkOrigin(16384, delay).use { origin ->
                    Harness(true).use { h ->
                        for (n in intArrayOf(0, 1, 2, 4, 8, 16)) {
                            origin.awaitIdle()
                            BenchmarkSupport.withBackground(n, { resources ->
                                openProxy(h.port, origin.port, resources).also { go(it) }
                            }) {
                                // 两种探针处于同一组代理背景流量下，避免重建负载造成不同状态。
                                val p = mutableListOf<DoubleArray>(); val d = mutableListOf<DoubleArray>()
                                repeat(ROUNDS) { round ->
                                    fun proxy() = openProxy(h.port, echo.localPort).use { BenchmarkSupport.pingPong(it) }
                                    fun direct() = BenchmarkSupport.socket(echo.localPort).use { BenchmarkSupport.pingPong(it) }
                                    if (round % 2 == 0) { p += proxy(); d += direct() } else { d += direct(); p += proxy() }
                                    println("latency-round=$round bg=$label/$n proxy=${p.last().toList()} direct=${d.last().toList()}")
                                }
                                println("latency-us bg=$label/$n proxy-p50=${BenchmarkSupport.median(p.map { it[0] })} " +
                                    "proxy-p99=${BenchmarkSupport.median(p.map { it[1] })} " +
                                    "direct-p50=${BenchmarkSupport.median(d.map { it[0] })} direct-p99=${BenchmarkSupport.median(d.map { it[1] })}")
                            }
                        }
                        check(!h.blockingRelayUsed.get())
                    }
                }
            }
        } finally { echo.close() }
    }

    @Test fun sharedCapacityAndMeasurementControls() {
        BenchmarkOrigin(16384, 16).use { origin ->
            val bytes = 128L * 1024
            val baseline = { origin.awaitIdle(); measure(4, bytes) { directDownload(origin.port, bytes) } }
            baseline() // warmup
            val aa = BenchmarkSupport.paired(ROUNDS, baseline, baseline)
            check(aa.first / aa.second in 0.5..2.0) { "A/A environment unstable: $aa" }
            Harness(true).use { h ->
                val one = Semaphore(1)
                val slow = {
                    origin.awaitIdle()
                    measure(4, bytes) {
                        one.acquire()
                        try { proxyDownload(h.port, origin.port, bytes) } finally { one.release() }
                    }
                }
                val control = BenchmarkSupport.paired(ROUNDS, slow, baseline)
                check(control.first < control.second * 0.7) { "Known serialization was not detected: $control" }
                check(!h.blockingRelayUsed.get())
            }
        }
        BenchmarkOrigin(16384, 0, SharedBandwidth(2L * 1048576)).use { origin ->
            Harness(true).use { h ->
                reportPaths("shared-total-2MiB-per-second", origin, 512L * 1024,
                    { n, bytes -> measure(n, bytes) { proxyDownload(h.port, origin.port, bytes) }.also { check(it <= 2.3) } },
                    { n, bytes -> measure(n, bytes) { directDownload(origin.port, bytes) }.also { check(it <= 2.3) } })
                check(!h.blockingRelayUsed.get())
            }
        }
    }

    private fun report(name: String, delayMs: Long, chunkBytes: Int, bytes: Long) {
        BenchmarkOrigin(chunkBytes, delayMs).use { origin ->
            Harness(true).use { h ->
                reportPaths(name, origin, bytes,
                    { n, size -> measure(n, size) { proxyDownload(h.port, origin.port, size) } },
                    { n, size -> measure(n, size) { directDownload(origin.port, size) } })
                check(!h.blockingRelayUsed.get()) { "NIO unexpectedly fell back to blocking relay" }
            }
        }
    }

    private fun reportPaths(
        label: String, origin: BenchmarkOrigin, bytes: Long,
        proxy: (Int, Long) -> Double, direct: (Int, Long) -> Double,
        concurrency: IntArray = conc,
    ) {
        origin.awaitIdle(); proxy(2, bytes / 4)
        origin.awaitIdle(); direct(2, bytes / 4)
        for (n in concurrency) {
            println("model=$label concurrency=$n bytes-per-connection=$bytes")
            val pair = BenchmarkSupport.paired(ROUNDS,
                { origin.awaitIdle(); proxy(n, bytes) },
                { origin.awaitIdle(); direct(n, bytes) })
            println("median-MiB/s model=$label concurrency=$n proxy=${pair.first} direct=${pair.second} ratio=${pair.first / pair.second}")
        }
    }

    private fun measure(n: Int, bytes: Long, task: () -> Long) = BenchmarkSupport.measure(n, bytes, task = task)
    private fun go(socket: Socket) { socket.getOutputStream().write("GO\n".toByteArray()); socket.getOutputStream().flush() }
    private fun openProxy(proxyPort: Int, targetPort: Int, resources: BenchmarkResources? = null): Socket {
        val socket = if (resources == null) BenchmarkSupport.socket(proxyPort) else BenchmarkSupport.socket(proxyPort, resources)
        try {
            val response = ProxyTestKit.sendAndReadHead(socket, "CONNECT 127.0.0.1:$targetPort HTTP/1.1\r\nHost: x\r\n\r\n")
            check(response.status == 200) { "CONNECT failed: ${response.statusLine}" }
            return socket
        } catch (error: Throwable) { socket.close(); throw error }
    }
    private fun proxyDownload(proxyPort: Int, targetPort: Int, bytes: Long): Long =
        openProxy(proxyPort, targetPort).use { go(it); BenchmarkSupport.drain(it.getInputStream(), bytes) }
    private fun directDownload(port: Int, bytes: Long): Long =
        BenchmarkSupport.socket(port).use { go(it); BenchmarkSupport.drain(it.getInputStream(), bytes) }

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
            connector = testConnector(allowAll),
            relay = RelayEngine(),
            authProvider = { NoAuthAuthenticator },
            limitsProvider = { limits },
            relayDispatcher = watchedRelayDispatcher,
            accounting = TrafficAccounting(),
            ruleEngine = null,
            nioReactor = if (useNio) reactor else null,
            useNioRelay = useNio,
        ).also { it.start(scope, 0, ProxyTestKit.loopback) }

        val port: Int = ProxyTestKit.awaitPort(server)

        override fun close() {
            server.stop()
            scope.cancel()
            if (useNio) reactor.stop()
            accExec.shutdownNow()
            relExec.shutdownNow()
        }
    }


    private companion object { const val ROUNDS = 4 }
}
