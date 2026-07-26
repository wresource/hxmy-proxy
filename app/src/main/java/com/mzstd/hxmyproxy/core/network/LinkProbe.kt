package com.mzstd.hxmyproxy.core.network

import com.mzstd.hxmyproxy.core.log.Ev
import com.mzstd.hxmyproxy.core.log.LogCat
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

    /** 连续这么多次探测失败 → 判定该客户端「手机→客户端」方向不通，落一条 key.log。 */
    private const val LOST_THRESHOLD = 3
    /** 失败状态表上限（客户端数天然个位数，防御性设界）。 */
    private const val MAX_TRACKED = 64

    private val window = ArrayDeque<Long>()
    /** 各客户端连续失败次数 / 最近一次成功时刻（ms）。会话边界随 [reset] 清空。 */
    private val failStreak = HashMap<String, Int>()
    private val lastOkAt = HashMap<String, Long>()
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

    /**
     * 依次探测各客户端并入窗（调用方负责放到 IO 线程、控制频率）。
     * 失败**不再静默**（7-26 排障教训：故障期探针必然探过客户端，但结果没落盘，
     * 「手机→客户端」方向通不通无从判定——那正是分辨「双向断/单向断」的关键一手）：
     * 连续 [LOST_THRESHOLD] 次失败落 `linkprobe.lost`（带距最近成功的秒数），恢复落
     * `linkprobe.recovered`。只在状态翻转时各记一条，持续失败/持续正常都零噪音。
     */
    suspend fun sample(ips: List<InetAddress>) {
        ips.forEach { ip ->
            val ms = probe(ip)
            if (ms != null) add(ms)
            ip.hostAddress?.let { key -> if (ms != null) onOk(key) else onFail(key) }
        }
    }

    private fun onOk(key: String) {
        val streakBefore: Int
        synchronized(lock) {
            streakBefore = failStreak.remove(key) ?: 0
            lastOkAt[key] = System.currentTimeMillis()
        }
        if (streakBefore >= LOST_THRESHOLD) {
            Ev.k(LogCat.CONN, "linkprobe.recovered", "client" to key, "failedProbes" to streakBefore)
        }
    }

    private fun onFail(key: String) {
        val n: Int
        val lastOkSecAgo: Long
        synchronized(lock) {
            if (failStreak.size >= MAX_TRACKED && key !in failStreak) return
            n = (failStreak[key] ?: 0) + 1
            failStreak[key] = n
            lastOkSecAgo = lastOkAt[key]?.let { (System.currentTimeMillis() - it) / 1000 } ?: -1
        }
        if (n == LOST_THRESHOLD) {   // 恰好翻转到「不通」时记一次；继续失败不重复
            Ev.kw(LogCat.CONN, "linkprobe.lost", "client" to key, "fails" to n, "lastOkSecAgo" to lastOkSecAgo)
        }
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
    fun reset() = synchronized(lock) {
        window.clear()
        failStreak.clear()
        lastOkAt.clear()
    }
}
