package com.mzstd.hxmyproxy.core.network

import android.net.Network
import com.mzstd.hxmyproxy.core.log.Ev
import com.mzstd.hxmyproxy.core.log.FileLog
import com.mzstd.hxmyproxy.core.log.LogCat
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "hxmyproxy"

/**
 * 出口网络的健康判定：**区分「某个站点坏了」和「整张网坏了」**。
 *
 * ## 为什么需要
 *
 * Android 只在网络**消失**时回调 `onLost`。而 VPN 链路静默死亡（对端不可达、但系统仍认为
 * 这张网在线）时不会有任何回调，句柄照旧有效，于是绑上去的连接**全部失败却无人知晓**——
 * 用户侧表现为「重开窗口也没用，只有切换网络才恢复正常」（0806 实证）。
 *
 * ## 判据来自实测形态，不是拍脑袋
 *
 * 对比 0806 日志的故障期与正常期：
 *
 * |               | 正常期(08-05) | 故障期(08-06 02:00–02:40) |
 * |---------------|--------------|---------------------------|
 * | 失败密度       | 1–2 次/小时   | 31 次 / 40 分钟            |
 * | 每分钟不同域名 | 几乎总是 1    | 峰值 6，常见 4–5           |
 * | 涉及域名总数   | 每小时 1–2 个 | 40 分钟内 13 个            |
 *
 * 关键区别不在**次数**而在**域名多样性**：正常期是「同一个域名偶尔失败」（站点自己的问题），
 * 故障期是「多个互不相干的域名同时失败」（anthropic / datadog / google / xfinfr…）——
 * 只有共用的那一跳坏了才会这样。
 *
 * 所以阈值是 [WINDOW_MS] 内 **[DISTINCT_HOSTS] 个不同域名**失败。
 * **必须按域名去重**：不去重的话，一个客户端自己重试三次就能凑够数，
 * 把好网误判成坏网——日志里 `api.anthropic.com` 单域名就失败了 7 次，正是这个陷阱。
 *
 * ## 命中阈值只代表「可疑」
 *
 * 真正摘掉之前必须**探测确认**（见 [probe]），否则某个 CDN 大面积故障会被误判成本网故障。
 * 摘掉也是**临时**的：[RECHECK_MS] 后重新探测，通了立即放回——不能等用户切网。
 */
class EgressHealth(
    /** 探测某张网是否真的可用；可注入便于测试。默认实现见 [defaultProbe]。 */
    private val probe: suspend (Network) -> Boolean = ::defaultProbe,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** netId → (host → 最近一次失败时刻)。按 host 去重是这个设计的核心。 */
    private val failures = ConcurrentHashMap<Long, ConcurrentHashMap<String, Long>>()

    /** netId → 被摘掉的时刻。存在即表示「探测确认过不通，暂时不用」。 */
    private val sidelined = ConcurrentHashMap<Long, Long>()

    /** netId → 上次探测时刻，避免阈值持续命中时反复探测。 */
    private val lastProbeAt = ConcurrentHashMap<Long, Long>()

    /** 同一时刻只允许一个探测在跑（探测本身要建连，不该被放大）。 */
    private val probing = AtomicBoolean(false)

    /**
     * 记录一次「在 [net] 上连 [host] 失败」。
     * @return true 表示已达阈值、值得探测（调用方决定是否 await [confirmOrSideline]）
     */
    fun recordFailure(net: Network?, host: String): Boolean {
        val id = net?.networkHandle ?: return false
        val now = nowMs()
        val m = failures.computeIfAbsent(id) { ConcurrentHashMap() }
        m[host] = now
        // 清掉窗口外的旧记录，顺便控制 map 大小
        m.entries.removeIf { now - it.value > WINDOW_MS }
        if (m.size < DISTINCT_HOSTS) return false
        val last = lastProbeAt[id] ?: 0L
        return now - last >= PROBE_COOLDOWN_MS
    }

    /** 这张网当前是否已被摘掉（且还没到重检时刻）。 */
    fun isSidelined(net: Network?): Boolean {
        val id = net?.networkHandle ?: return false
        val at = sidelined[id] ?: return false
        if (nowMs() - at > RECHECK_MS) return false   // 到点了：由 [confirmOrSideline] 复检
        return true
    }

    /** 到了重检时刻的网络（调用方应对它们再探一次，通了就放回）。 */
    fun needsRecheck(net: Network?): Boolean {
        val id = net?.networkHandle ?: return false
        val at = sidelined[id] ?: return false
        return nowMs() - at > RECHECK_MS
    }

    /**
     * 探测确认：通了就清账放回，不通就摘掉。
     * 并发保护——同一时刻只跑一个探测，其余直接返回当前状态。
     */
    suspend fun confirmOrSideline(net: Network) {
        val id = net.networkHandle
        if (!probing.compareAndSet(false, true)) return
        try {
            lastProbeAt[id] = nowMs()
            val ok = runCatching { probe(net) }.getOrDefault(false)
            if (ok) {
                val was = sidelined.remove(id)
                failures[id]?.clear()
                if (was != null) FileLog.w(TAG, "egress $id probe ok, restored")
            } else if (sidelined.putIfAbsent(id, nowMs()) == null) {
                FileLog.w(
                    TAG,
                    "egress $id probe failed: $DISTINCT_HOSTS distinct hosts unreachable in ${WINDOW_MS / 1000}s " +
                        "and probe targets down too - dropped, recheck in ${RECHECK_MS / 1000}s",
                )
            }
        } finally {
            probing.set(false)
        }
    }

    /** 网络变化时清账：旧结论不该带到新网络上。 */
    fun reset() {
        failures.clear(); sidelined.clear(); lastProbeAt.clear()
    }

    companion object {
        /** 失败统计窗口。 */
        const val WINDOW_MS = 60_000L

        /**
         * 窗口内多少个**不同域名**失败才算可疑。
         * 4 落在实测的两段之间且靠近故障侧：正常期每分钟不同域名数从未超过 1，故障期是 4–6。
         */
        const val DISTINCT_HOSTS = 4

        /** 摘掉后多久复检。取 45s：短到不用等用户切网，长到不会把探测本身变成风暴。 */
        const val RECHECK_MS = 45_000L

        /** 两次探测的最小间隔，防止阈值持续命中时反复探测。 */
        const val PROBE_COOLDOWN_MS = 15_000L

        /** 探测超时：只是问「这张网还能不能建连」，不需要等太久。 */
        const val PROBE_TIMEOUT_MS = 2_000

        /**
         * 探测目标：**任一通即认为网络可用**。
         *
         * 刻意跨厂商跨境内外——单一目标自己故障时会把好网误判成坏网，而这几个同时挂掉的
         * 概率远低于「我们这张网坏了」。用 53 端口是因为 DNS 服务器的可用性通常最高，
         * 且不需要 TLS 握手，2 秒足够。
         */
        private val PROBE_TARGETS = listOf(
            "223.5.5.5" to 53,      // 阿里，国内直连可达
            "1.1.1.1" to 53,        // Cloudflare
            "8.8.8.8" to 53,        // Google
        )

        /**
         * 默认探测：在指定网络上试连几个高可用目标，任一成功即通。
         *
         * **逐个目标记结果**，而不是只报总的成败。三个目标分属阿里 / Cloudflare / Google，
         * 分开看才能区分两种完全不同的情况:
         *  · 三个全挂 ⇒ 这条链路本身不通(0815 现场就是这样，判定正确)
         *  · 只挂某一家 ⇒ 是那家被墙/被限，链路其实是好的 —— 此时摘网就是**误判**
         *
         * 用户报过一次「手机显示网络不可用(感叹号)但实际能上网」，那个感叹号正是系统
         * 连 Google 的连通性检测失败所致。我们的判据不依赖单一厂商(任一成功即通)，
         * 但只有把逐目标结果记下来，这一点才是**可验证的**而不是口头保证。
         */
        suspend fun defaultProbe(net: Network): Boolean = kotlinx.coroutines.withContext(
            kotlinx.coroutines.Dispatchers.IO,
        ) {
            val results = PROBE_TARGETS.map { (ip, port) ->
                val t0 = System.nanoTime()
                val ok = runCatching {
                    net.socketFactory.createSocket().use { s ->
                        s.connect(InetSocketAddress(ip, port), PROBE_TIMEOUT_MS)
                        true
                    }
                }.getOrDefault(false)
                Triple(ip, ok, (System.nanoTime() - t0) / 1_000_000L)
            }
            Ev.kw(
                LogCat.NET, "egress.probe",
                "net" to net.networkHandle,
                "detail" to results.joinToString(",") { (ip, ok, ms) -> "$ip:${if (ok) "ok" else "fail"}/${ms}ms" },
                "okCount" to results.count { it.second },
            )
            results.any { it.second }
        }
    }
}
