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

    /**
     * 最近 [WINDOW] 次探测的**成败**（true=通）。
     *
     * 必须与 [window] 分开记：那个窗口只收成功样本的耗时，失败的探测**根本不进去**——
     * 于是「p50 很漂亮但一半的包丢了」这种情况在时延数字上完全看不出来。
     * 8-01 真机日志正是这个形状：p50 <20ms 占 34.8%（看着很好），而自愈突发实测每 10 发
     * 只回 3~5 发。丢包率是这条链路真实质量的唯一直接指标。
     */
    private val okWindow = ArrayDeque<Boolean>()
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
        ips.forEach { ip -> record(ip.hostAddress, probe(ip)) }
    }

    /**
     * 记一次探测结果。从 [sample] 里抽出来是为了可测——[probe] 要真的建连，
     * 单测无法构造「同一台设备连续失败 N 次后又恢复」这种序列。
     *
     * @param ms 成功时的耗时；null = 本次探测失败
     */
    @androidx.annotation.VisibleForTesting
    internal fun record(key: String?, ms: Long?) {
        // **已判定「不在」的客户端，其后续失败不再计入丢包率**（见 [stats] 的口径说明）。
        // 前 [LOST_THRESHOLD] 次失败照常入窗——那是真实的链路信号；之后这台设备被认定为
        // 离线，再把它的失败算成「丢包」只会污染链路质量。
        //
        // **只跳过失败，不跳过成功**：探测成功本身就证明设备回来了，那一次属于
        // 「在线设备的探测结果」，必须入窗。跳掉它会让丢包率偏高（少一个成功样本）。
        val skipOutcome = ms == null && key != null && isLost(key)
        if (!skipOutcome) addOutcome(ms != null)
        if (ms != null) add(ms)
        key?.let { if (ms != null) onOk(it) else onFail(it) }
    }

    /** 该客户端是否已连续失败到「不在」的程度。 */
    private fun isLost(key: String): Boolean = synchronized(lock) {
        (failStreak[key] ?: 0) >= LOST_THRESHOLD
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

    private fun addOutcome(ok: Boolean) = synchronized(lock) {
        okWindow.addLast(ok)
        while (okWindow.size > WINDOW) okWindow.removeFirst()
    }

    /**
     * 当前窗口的 p50/p95 与丢包率；**无成功样本时也可能有丢包数据**——
     * 全丢的链路 [window] 是空的，但那恰恰是最该报出来的情况，所以判空只看 [okWindow]。
     *
     * ## lossPct 的口径：**在线设备的探测丢失率**，不含已离线的设备
     *
     * 0816 两台设备的对照暴露过一次误读：A 机 `loss=0%` 全程，B 机 `p50=28% / max=56%`，
     * 同一个 WiFi、同一套探测。差别不在链路，而在**被探的对象**——
     * A 探的是一直在用的 Mac，B 探的客户端反复离线（`inboundSilenceSec` 到过 371 秒，
     * 同期 70 次 `client.unreachable`）。设备休眠时探测必然失败，若照单计入，
     * 一台睡着的设备就能把丢包率拉到接近 100%，而链路其实好得很。
     *
     * 所以 [sample] 里对已判定 lost 的客户端停止入窗：这个数只回答
     * **「还在线的设备，探测丢了多少」**。它不回答「设备在不在」——那是
     * `linkprobe.lost` / `client.unreachable` 的职责。
     */
    fun stats(): LinkStats? = synchronized(lock) {
        if (window.isEmpty() && okWindow.isEmpty()) return null
        val loss = if (okWindow.isEmpty()) 0 else 100 * okWindow.count { !it } / okWindow.size
        if (window.isEmpty()) return LinkStats(lossPct = loss, lossSamples = okWindow.size)
        val sorted = window.sorted()
        LinkStats(
            p50Ms = sorted[sorted.size / 2],
            p95Ms = sorted[(sorted.size - 1) * 95 / 100],
            samples = sorted.size,
            lossPct = loss,
            lossSamples = okWindow.size,
        )
    }

    /** 会话边界清空（stop/start 时调用），避免上次会话的数字带到这次。 */
    fun reset() = synchronized(lock) {
        window.clear()
        okWindow.clear()
        failStreak.clear()
        lastOkAt.clear()
    }
}
