package com.mzstd.hxmyproxy.core.network

import com.mzstd.hxmyproxy.core.model.LinkStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * **段①（客户端 → 本机）链路时延**的采样与聚合。
 *
 * 这是此前完全缺失的一段：监控页原有的「服务延迟」测的是段②（本机 → 互联网），
 * 所以当「手机自己上网正常、连它的设备却卡死」时，UI 上一个异常指标都看不到 ——
 * 而手机当代理时，客户端流量要穿过同一段无线链路更多次，段① 恰恰是最先劣化的那段。
 *
 * 探测策略（两级，尽量不打扰客户端）：
 * 1. [InetAddress.isReachable]：Android 实现先发 ICMP ECHO，失败退 TCP ECHO，都不需要 root。
 * 2. 兜底连一个**大概率关闭**的端口：很多客户端（Windows 默认防火墙）直接丢 ICMP，
 *    此时收到 RST（`ConnectException`）同样是一次完整往返，照样能量出链路时延；只有超时才算不可达。
 */
object LinkProbe {
    const val TIMEOUT_MS = 800
    /** discard 端口：绝大多数主机不监听 → 回 RST，正好用来量往返而不建立真实连接。 */
    private const val TCP_PROBE_PORT = 9
    /** 滑动窗口容量。取 p50 做主数字（抗尾延迟），p95 反映「最坏时刻」。 */
    private const val WINDOW = 32

    private val window = ArrayDeque<Long>()
    private val lock = Any()

    /** 探单个客户端；返回往返毫秒，超时/不可达返回 null。 */
    suspend fun probe(ip: InetAddress, timeoutMs: Int = TIMEOUT_MS): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val t0 = System.nanoTime()
            if (ip.isReachable(timeoutMs)) return@withContext (System.nanoTime() - t0) / 1_000_000L
        }
        val s = Socket()
        val t0 = System.nanoTime()
        try {
            s.connect(InetSocketAddress(ip, TCP_PROBE_PORT), timeoutMs)
            (System.nanoTime() - t0) / 1_000_000L      // 意外连上也是一次完整往返
        } catch (e: java.net.ConnectException) {
            (System.nanoTime() - t0) / 1_000_000L      // 收到 RST = 链路通
        } catch (e: Throwable) {
            null                                        // 超时/不可达
        } finally {
            runCatching { s.close() }
        }
    }

    /** 依次探测各客户端并入窗（调用方负责放到 IO 线程、控制频率）。 */
    suspend fun sample(ips: List<InetAddress>) {
        ips.forEach { ip -> probe(ip)?.let { add(it) } }
    }

    private fun add(ms: Long) = synchronized(lock) {
        window.addLast(ms)
        while (window.size > WINDOW) window.removeFirst()
    }

    /** 当前窗口的 p50/p95；无样本返回 null。 */
    fun stats(): LinkStats? = synchronized(lock) {
        if (window.isEmpty()) return null
        val sorted = window.sorted()
        LinkStats(
            p50Ms = sorted[sorted.size / 2],
            p95Ms = sorted[(sorted.size - 1) * 95 / 100],
            samples = sorted.size,
        )
    }

    /** 会话边界清空（stop/start 时调用），避免上次会话的数字带到这次。 */
    fun reset() = synchronized(lock) { window.clear() }
}
