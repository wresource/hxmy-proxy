package com.mzstd.hxmyproxy.core.network

import android.net.Network
import com.mzstd.hxmyproxy.core.log.Ev
import com.mzstd.hxmyproxy.core.log.FileLog
import com.mzstd.hxmyproxy.core.log.LogCat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    /**
     * 距离这张网下次复检还有几秒，供 `Retry-After` 用。**下限 1 秒**。
     *
     * 下限不是保守，是必须的：0818 实测 OkHttp 是唯一会读 `Retry-After` 的 HTTP 栈，
     * 而它的判据恰恰是 `== 0` —— 收到 0 就**立即无退避重试**。在「整张网不通」的
     * 当口发 0，等于把我们自己的故障放大成一轮重试风暴。
     *
     * 没被摘的网返回 null（调用方不该为它发 `Retry-After`）。
     */
    fun retryAfterSeconds(net: Network?): Int? {
        val id = net?.networkHandle ?: return null
        val at = sidelined[id] ?: return null
        val remainMs = RECHECK_MS - (nowMs() - at)
        return ((remainMs + 999) / 1000).coerceAtLeast(1).toInt()
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
         * 概率远低于「我们这张网坏了」。三个 IP 都免 DNS，直接 TCP 建连、不做 TLS 握手，2 秒足够。
         *
         * ## 端口为什么是 443 而不是 53（0815 现场的教训）
         *
         * 原来三个目标全是 **53**，理由是「DNS 服务器可用性最高」。这个理由本身没错，
         * 错的是**它测的不是业务走的那条路**：
         *
         * 17:24 换 WiFi（192.168.50.x → 192.168.1.x）后，VPN 的 Network 句柄不变、底层链路却
         * 换了，隧道进入「53 能过、443 过不去」的半死状态。于是——
         *  · 业务侧：100+ 条 443 建连失败（`ms=1~3` 的 ENETUNREACH，或干脆卡满 10 秒）
         *  · 探测侧：53 通 ⇒ 判定「这张网是好的」⇒ 不摘 ⇒ 继续拿坏句柄硬撞
         *  · 心跳里 `probe=ok/ok` 全程绿着，而 Chrome 一直 ERR_TUNNEL_CONNECTION_FAILED
         * 整整 12 分钟没有自愈。**为区分「站点坏」与「整张网坏」而建的机制，
         * 因为探错了端口，在最需要它的时刻站到了错误的一边。**
         *
         * 443 是代理流量的实际承载端口（CONNECT 隧道几乎全是它），探它才代表业务可用性。
         * 这三个地址的 443 都对外提供 DoH，可用性与 53 同级。
         *
         * 代价：若某网络恰好只封 443 而放行 53，会被判为不可用——但那种网络上业务本来就用不了，
         * 判「不可用」正是我们要的结论。
         */
        /**
         * 端口全部是 **443**（原来是 53，见上方说明），目标集则必须同时覆盖两种出口：
         *
         * · **物理网出口**（DIRECT 流量）走的是本地链路 —— 0816 在用户网络实测：
         *   `1.1.1.1:443`、`8.8.8.8:443` 双双超时 2010ms，而同样三个地址的 **53 全通**。
         *   也就是说国外 443 在这条链路上不可用。只用国外目标会把好的物理网判成坏网。
         * · **VPN 出口**（PROXY 流量）走隧道 —— 国外目标在这里才是有意义的判据。
         *
         * 所以国内三家 + 国外两家，**任一通即认为可用**。三家国内 443 都是 0816 实测通过的
         * DoH 端点（阿里 115ms / 腾讯 32ms / 360 48ms），彼此独立，一家被限不影响判定。
         */
        @androidx.annotation.VisibleForTesting
        internal val PROBE_TARGETS = listOf(
            "223.5.5.5" to 443,     // 阿里 DoH，国内直连可达
            "120.53.53.53" to 443,  // 腾讯 DoH
            "101.226.4.6" to 443,   // 360 DoH
            "1.1.1.1" to 443,       // Cloudflare —— 物理网通常不可达，用于判 VPN 出口
            "8.8.8.8" to 443,       // Google —— 同上
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
            // **并行**：原来是串行 map，且不因某个目标成功而短路（逐目标记结果是刻意的）。
            // 目标集扩到 5 个之后串行最坏要 5×2s = 10 秒，而这段时间里业务全在等判定。
            // 并行后总耗时 = 最慢的那个（PROBE_TIMEOUT_MS 封顶 2 秒），逐目标结果照样拿得到。
            val results = coroutineScope {
                PROBE_TARGETS.map { (ip, port) ->
                    async {
                        val t0 = System.nanoTime()
                        val ok = runCatching {
                            net.socketFactory.createSocket().use { s ->
                                s.connect(InetSocketAddress(ip, port), PROBE_TIMEOUT_MS)
                                true
                            }
                        }.getOrDefault(false)
                        Triple(ip, ok, (System.nanoTime() - t0) / 1_000_000L)
                    }
                }.awaitAll()
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
