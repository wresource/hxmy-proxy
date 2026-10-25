package com.mzstd.hxmyproxy.core.proxy

import android.net.Network
import com.mzstd.hxmyproxy.core.security.EgressGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 创建到目标的上游 TCP 连接。
 *
 * AUTO 跟随系统默认网络；指定出口按 socket 绑定，缺失时严格执行 STRICT/DEGRADE。
 * 禁止 `bindProcessToNetwork`。系统 DNS 使用所选网络，目标经 [EgressGuard] 反 SSRF 过滤。
 *
 * **Happy Eyeballs（RFC 8305）**：解析出全部地址后**交错并行**连接——起第一个地址，等 250ms 仍未成功
 * （或当前尝试已失败）就并行起下一个，首个成功者胜出、其余立即中止。IPv4 优先（本网络 IPv6 常不可达）。
 * 相比"逐个回退"，双栈/多 anycast 站点（如 Cloudflare/Stripe）首地址慢或不可达时不再干等满超时，显著降低尾延迟。
 */
private const val TAG = "hxmyproxy"

class OutboundConnector(
    private val egressGuard: EgressGuard,
    // 上游建连专用调度器：阻塞 connect（含 Happy Eyeballs 扇出，每地址最长 CONNECT_TIMEOUT_MS）走此
    // 独立有界池，不再挤占 Dispatchers.IO；并对并发建连线程数设硬上限，首屏几十域名同时建连也不无界扩张。
    private val connectDispatcher: CoroutineDispatcher = DEFAULT_CONNECT_DISPATCHER,
    /** 非 VPN 底层网络提供者；为 DIRECT 出口分流把 socket 绑定到真实网络（绕过共享 VPN）。null=不支持分流。 */
    private val underlyingNetworkProvider: com.mzstd.hxmyproxy.core.network.UnderlyingNetworkProvider? = null,
    /**
     * 出口归类器（历史流量统计用）：把本次连接实际绑定的 `Network` 归成 VPN/WiFi/蜂窝/以太网。
     * 只有这里知道**降级后**真正走的是哪张网（非 bypass 的出口分流失败会回落默认路由），
     * 所以归类必须在这一层做、而不是让调用方按 `bypassVpn` 自己猜。null=不归类。
     */
    private val egressClassifier: ((Network?) -> com.mzstd.hxmyproxy.core.stats.EgressKind)? = null,
    /**
     * 出口网健康判定。见 [com.mzstd.hxmyproxy.core.network.EgressHealth]：
     * Android 只在网络**消失**时回调 onLost，链路静默死亡时句柄照旧有效，
     * 绑上去的连接全部失败却无人知晓——这一层负责把它认出来并临时摘掉。
     */
    private val egressHealth: com.mzstd.hxmyproxy.core.network.EgressHealth =
        com.mzstd.hxmyproxy.core.network.EgressHealth(),
    private val systemDns: com.mzstd.hxmyproxy.core.network.SystemDnsLookup =
        com.mzstd.hxmyproxy.core.network.AndroidSystemDnsLookup,
) {
    /**
     * 进程级短 TTL DNS 缓存：首屏同域名多次建连只解析一次，VPN 切换/DNS 漂移在 TTL 内自然失效。
     *
     * **键必须带 netId**。此前键只有 host，而缓存的读写又都写在 `network == null` 分支里，
     * 于是只要用户选了具体出口（egress=VPN 或 direct=WIFI，两者都让 network 非空），
     * **整个缓存就是死代码、命中率恒为 0**——每条连接都要重新解析一次，
     * 6 条并发打同一域名就是 6 次独立解析。0804 实测的卡死正是这么攒出来的。
     * 按 netId 分桶之后两个分支可以共用同一套读写：不同网络的结果本来就该分开存，
     * 而不是「整条分支干脆不缓存」。
     */
    private val dnsCache = ConcurrentHashMap<DnsKey, CachedAddrs>()

    /** 同一代网络、同一 host 的并发查询单飞；后来的请求只等待已有查询。 */
    private val dnsLock = Any()
    private val inflightDns = HashMap<DnsKey, DnsFlight>()
    private val recoveringDns = HashMap<DnsKey, DnsFlight>()
    private var dnsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private class DnsFlight(val task: Deferred<DnsAnswer>, var waiters: Int = 0)
    private data class DnsAnswer(
        val addrs: List<InetAddress>, val source: DnsSource, val resolverNetId: Long?,
        val detail: String? = null,
        val lease: DnsLease? = null,
    )
    private class DnsLease(val key: DnsKey, val id: Long) {
        val connected = AtomicBoolean(false)
        // Guarded by dnsLock. A failed refresh gets one primary-query interval of quiet;
        // cancellation is not a negative DNS result and must not set this cooldown.
        var recoverAfterNs: Long = 0
    }

    /** 系统异步查询的全路径并发闸门，主路与互援共用；满载立即判负，不排队。 */
    private val dnsSlots = java.util.concurrent.Semaphore(DNS_MAX_INFLIGHT)
    private val dnsRejected = java.util.concurrent.atomic.AtomicLong()
    private val dnsTimedOut = java.util.concurrent.atomic.AtomicLong()

    // 成功解析的耗时观测（0806 补）。见 [recordDnsOk] 的注释：没有这份分布，
    // DNS_STEP_TIMEOUT_MS 该定多少完全是猜。
    private val dnsOkCount = java.util.concurrent.atomic.AtomicLong()
    private val dnsOkTotalMs = java.util.concurrent.atomic.AtomicLong()
    private val dnsOkMaxMs = java.util.concurrent.atomic.AtomicLong()
    private val dnsOkBuckets = java.util.concurrent.atomic.AtomicLongArray(DNS_OK_BUCKET_UPPER_MS.size + 1)
    /** 主路失败后实际采用备用答案的查询数（不计缓存/共享等待者重复使用）。 */
    private val dnsRescueUses = java.util.concurrent.atomic.AtomicLong()
    /** DoH 兜底调用次数，以及其中按预算取消的次数。见 resolveLastResort 内注释。 */
    private val dohCalls = java.util.concurrent.atomic.AtomicLong()
    private val dohTimeouts = java.util.concurrent.atomic.AtomicLong()

    /** 缓存/单飞的键：netId 为 0 表示进程默认网络。 */
    private data class DnsKey(val netId: Long, val host: String, val generation: Long, val backupEnabled: Boolean, val route: String)

    private val dnsGeneration = java.util.concurrent.atomic.AtomicLong()
    private val dnsAnswerSequence = java.util.concurrent.atomic.AtomicLong()

    private fun dnsKeyOf(network: Network?, host: String, route: String = "PROXY:$egressFallback") =
        DnsKey(network?.networkHandle ?: 0L, host, dnsGeneration.get(), backupDnsEnabled, route)

    /** 备用 DNS（DoH）开关；由设置层经 applyTunables 推入。 */
    @Volatile var backupDnsEnabled: Boolean = true

    /**
     * 指定出口连不通时的策略；由设置层推入。默认 [EgressFallback.STRICT]。
     *
     * 见 [EgressFallback] 的注释：降级保住的是「这次能用」，赔上的是**出口身份**，
     * 而后者的代价（风控封禁）不可逆。这里只影响**连接路径**，
     * 指定网络的系统 DNS 不跨网抢答；备用 DoH 遵守用户的独立 DNS 出口配置并保留来源。
     */
    @Volatile var egressFallback: com.mzstd.hxmyproxy.core.model.EgressFallback =
        com.mzstd.hxmyproxy.core.model.EgressFallback.STRICT

    /** 会话停止/重置时取消共享查询；独立调用者取消只撤销自己的等待。 */
    fun cancelPendingDns() {
        synchronized(dnsLock) {
            dnsScope.cancel()
            inflightDns.clear()
            recoveringDns.clear()
            dnsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            dnsGeneration.incrementAndGet()
            dnsCache.clear()
        }
    }

    /** 网络变化时由编排层调用：旧网络下解析的 IP 可能已不可达，清掉让 TTL 内的条目立即失效。 */
    fun clearDnsCache() {
        invalidateDnsPolicy()
        // 网络变化影响链路健康；仅修改 DNS 选项不重置健康判定。
        egressHealth.reset()
    }

    fun invalidateDnsPolicy() {
        synchronized(dnsLock) {
            dnsGeneration.incrementAndGet()
            dnsCache.clear()
        }
    }


    /**
     * 上游失败日志节流：断网风暴时每个失败连接都落盘会把 512KB 日志冲掉，同 key（阶段:域名）
     * [LOG_THROTTLE_MS] 内只记一条。map 超限整体清空（域名基数有限，粗暴够用）。
     */
    private val logThrottle = ConcurrentHashMap<String, Long>()
    private fun throttledFileLog(key: String, msg: String) {
        val now = System.currentTimeMillis()
        val last = logThrottle[key]
        if (last != null && now - last < LOG_THROTTLE_MS) return
        if (logThrottle.size > LOG_THROTTLE_MAX_KEYS) logThrottle.clear()
        logThrottle[key] = now
        com.mzstd.hxmyproxy.core.log.FileLog.w(TAG, msg)
    }

    /**
     * 出口网络选择。**bypass(DIRECT 规则)=严格物理网**：拿不到非 VPN 物理网(current() 为 null，即仅 VPN 在线)
     * 就 **fail-closed** 抛 [ProxyError.DirectEgressUnavailable]，**绝不回落默认路由**——因为 egress=VPN 或 always-on/lockdown
     * VPN 下「默认路由」正是那条 VPN，回落等于把本要绕开 VPN 的直连流量又送进 VPN(豆包「国家不符合」根因)。
     * 非 bypass(PROXY)：走用户选定出口(AUTO=null=系统默认)。current() 内部已按 WiFi→以太网→蜂窝 兜底。
     */
    private suspend fun egressNetworkFor(bypassVpn: Boolean, host: String): Network? {
        if (!bypassVpn) {
            val provider = underlyingNetworkProvider ?: return null
            var selection = provider.egressSelection()
            if (selection.network == null && selection.choice != com.mzstd.hxmyproxy.core.model.EgressNetworkChoice.AUTO) {
                provider.awaitInitialNetwork(false)
                selection = provider.egressSelection()
            }
            if (selection.choice == com.mzstd.hxmyproxy.core.model.EgressNetworkChoice.AUTO) return null
            selection.network?.let { return it }
            // 缺少指定出口也受同一策略约束；DEGRADE 只能选明确的物理替代网。
            if (egressFallback == com.mzstd.hxmyproxy.core.model.EgressFallback.DEGRADE) {
                provider.fallbackEgress(null)?.let {
                    throttledFileLog("egress-missing:$host", "egress ${selection.choice} unavailable - using physical net ${it.networkHandle}")
                    return it
                }
            }
            throttledFileLog("egress-missing:$host", "egress ${selection.choice} unavailable - refusing default route")
            throw ProxyException(ProxyError.RemoteUnreachable, "egress-unavailable")
        }
        if (underlyingNetworkProvider?.current() == null) underlyingNetworkProvider?.awaitInitialNetwork(true)
        return underlyingNetworkProvider?.current() ?: run {
            throttledFileLog("direct-noeg:$host",
                "DIRECT $host: selected direct egress unavailable, selected=${underlyingNetworkProvider?.directEgressChoice()}" +
                    " availablePhysical=${underlyingNetworkProvider?.availablePhysicalTransports()}; fail-closed (no VPN fallback)")
            // Preserve the historical stage/counter, but do not claim every physical network is
            // absent: a pinned Wi-Fi route can be missing while cellular is still available.
            // 调用方据此把它记进全局态而不是 per-host 账本 —— 0814 那 96 次涉及 20+ 个域名，
            // 逐 host 记会把一个根因显示成二十几个「坏域名」，且 per-host 的清除要等各自下次成功。
            throw ProxyException(ProxyError.DirectEgressUnavailable, "no-physical-egress")
        }
    }

    /**
     * 出口分流失败后改走物理替代网的重试包装：**结局必须落盘**。
     *
     * 此前这次重试没有 try/catch，成功与失败在日志里**完全同形**（只看得到降级前那条 `egress fail`），
     * 而两者对用户天差地别：成功 = 多等一个 [ProxyTuning.CONNECT_TIMEOUT_MS]；失败 = 等两倍超时后仍然失败。
     * 这个缺口曾让人凭「`upstream fail = 0`」误判成「降级都成功了」——那是没有根据的，
     * 最终结局根本没被记录过。带上耗时才能回答「那次多等买到了什么」。
     *
     * 节流 key 与降级前那条刻意不同：同 key 在 [LOG_THROTTLE_MS] 内只记一条，
     * 复用会让结局被降级前的日志顶掉，等于没修。
     */
    private suspend fun <T> retryOnPhysicalEgress(host: String, original: ProxyError, block: suspend () -> T): T {
        val startMs = System.currentTimeMillis()
        return try {
            block().also {
                throttledFileLog(
                    "egress-ok:$host",
                    "degrade ok $host in ${System.currentTimeMillis() - startMs}ms (was $original)",
                )
            }
        } catch (e: ProxyException) {
            // resolve() 的 DnsFailure 也在 block 内，一并覆盖——降级失败常常就死在 DNS 上。
            throttledFileLog(
                "egress-dead:$host",
                "degrade FAILED $host: ${e.error} after ${System.currentTimeMillis() - startMs}ms (was $original)",
            )
            throw e
        }
    }

    /**
     * 建连成功后把**实际出口**告诉调用方（历史流量统计据此分类累加）。
     * 归类器缺席时不报告——由计量侧的默认值兜底，宁可归进「其他」也不假装知道走了哪张网。
     */
    private fun reportEgress(sink: ((com.mzstd.hxmyproxy.core.stats.EgressKind) -> Unit)?, network: Network?) {
        val classifier = egressClassifier ?: return
        sink?.invoke(classifier(network))
    }


    /**
     * 出口网已被判定不通时的短路：**别再为它白等一次建连超时**。
     *
     * 返回 true 表示「这张网当前不可用，调用方应按 [egressFallback] 处理」。
     * 顺带承担复检：到了 [com.mzstd.hxmyproxy.core.network.EgressHealth.RECHECK_MS]
     * 就再探一次，通了立刻放回——恢复不能等用户切网，那正是 0806 现场里最难受的一点
     * （「重开窗口也没用，只有切换网络才恢复」）。
     */
    private suspend fun egressUnusable(network: Network?, bypassVpn: Boolean): Boolean {
        if (bypassVpn || network == null) return false
        if (egressHealth.needsRecheck(network)) egressHealth.confirmOrSideline(network)
        return egressHealth.isSidelined(network)
    }

    /** 解析域名（全部地址）并连接，IPv4 优先 + Happy Eyeballs。[bypassVpn]=true 时绕过共享 VPN 走真实网络。 */
    suspend fun connect(
        host: String,
        port: Int,
        bypassVpn: Boolean = false,
        onEgress: ((com.mzstd.hxmyproxy.core.stats.EgressKind) -> Unit)? = null,
        /** 请求级追踪；null=不记。见 [RequestTrace]。 */
        trace: RequestTrace? = null,
    ): Socket {
        // **这两步必须在 try 内**：egressNetworkFor 与 egressUnusable 都会抛，而它们此前在 try 之外，
        // 于是下面 catch 里的 DirectEgressFailures.recordFailure 永远收不到 ——
        // 0814 实测 96 次 DIRECT 失败（全部 err=AccessDenied、0-2ms 内返回）一次都没进账本，
        // 防护页上完全看不见，用户只表现为「某个 app 有时候卡」。
        var network: Network? = null
        val physicalFallback by lazy { underlyingNetworkProvider?.fallbackEgress(network) }
        return try {
            network = egressNetworkFor(bypassVpn, host)
            if (egressUnusable(network, bypassVpn)) {
                throttledFileLog("egress-down:$host", "egress marked down, skipping wait - $host")
                // 统一交给下面的 STRICT/DEGRADE 分支；这里不能直接回落默认路由。
                throw ProxyException(
                    ProxyError.EgressSidelined, "egress-down",
                    retryAfterSeconds = egressHealth.retryAfterSeconds(network),
                )
            }
            run {
                dialWithDnsRecovery(host, network, bypassVpn, trace,
                    preferAlternate = { preferAlternateEgress(network, bypassVpn) && physicalFallback != null }) { addresses ->
                    connectAny(orderAddresses(addresses), port, network)
                }
            }
                .also {
                    trace?.connected(host, it.inetAddress?.hostAddress, port, network?.networkHandle)
                    reportEgress(onEgress, network)
                    // 通了就清零：连续失败被打断说明该 host 的直连当前是好的（见 DirectEgressFailures）。
                    if (bypassVpn) DirectEgressFailures.recordSuccess(host)
                }
        } catch (e: ProxyException) {
            // DIRECT(bypass) fail-closed：建连失败也**不**降级默认网络(=VPN)，宁可断、不泄漏。
            if (bypassVpn) {
                // 计数给 UI：fail-closed 的失败对用户是静默的（只表现为"这个 app 有时候卡"），
                // 必须让它在防护页可见，否则用户不导出日志根本不知道自己设的「直连」连不通。
                //
                // 但**全局状况不进 per-host 账本**：no-physical-egress 的原因是「一张物理网都没有」，
                // 与访问哪个域名无关。逐 host 记会把一个根因摊成二十几条「坏域名」，
                // 而且 recordSuccess 只在该 host 下次 DIRECT 成功时才清 —— 遥测类域名几小时才来一次，
                // 物理网早恢复了条目还挂在 UI 上，与「条目表示现在仍然不通」的语义直接矛盾。
                if (e.stage == "no-physical-egress") DirectEgressFailures.recordNoEgress()
                else DirectEgressFailures.recordFailure(host, e.error.code)
                throttledFileLog("direct:$host", "DIRECT egress fail $host: ${e.error} - fail-closed (no VPN fallback)")
                throw e
            }
            if (e.stage == "egress-unavailable") throw e
            if (network == null) {
                throttledFileLog("connect:$host", "upstream fail $host (default egress): ${e.error}")
                throw e
            }
            // 记一笔到出口健康账上：同一张网在 60s 内有 4 个**不同域名**连不上，
            // 才值得怀疑是「整张网坏了」而非「这个站点坏了」（阈值依据见 EgressHealth）。
            // 主动短路没有发起联网，不能重新计作真实失败来提前触发复检。
            if (e.error !== ProxyError.EgressSidelined && egressHealth.recordFailure(network, host)) {
                egressHealth.confirmOrSideline(network)
            }

            // 非 bypass 的出口分流(指定 VPN/WiFi/蜂窝出口)连不通。
            if (egressFallback == com.mzstd.hxmyproxy.core.model.EgressFallback.STRICT) {
                // **sincePhys / vpnAge 是关联字段**：0815 现场里一整分钟的 STRICT 失败，
                // 全部发生在底层链路刚换过之后，而出口用的 VPN 句柄一次没变（存在但不通）。
                // 把「距上次换网多久」直接写在失败行上，才能验证这个模式是否稳定复现 ——
                // 附在已有的行上而不是新增事件，不增加日志量。
                throttledFileLog(
                    "egress-strict:$host",
                    "egress fail $host: ${e.error} - STRICT abort (no fallback, egress identity preserved)" +
                        "; sincePhys=${underlyingNetworkProvider?.sincePhysChangeSec() ?: -1}s" +
                        " vpnAge=${underlyingNetworkProvider?.vpnAgeSec() ?: -1}s",
                )
                // 阶段随异常走、由 server 层统一落盘 —— 这里再记一次就是双写（见 ProxyException.stage）。
                throw ProxyException(e.error, e.stage ?: "connect-strict", e.retryAfterSeconds)
            }
            throttledFileLog("egress:$host", "egress fail $host: ${e.error} — trying another physical egress")
            retryOnPhysicalEgress(host, e.error) {
                // **不再回落系统默认路由**:egress=VPN 时那正是刚失败的同一条 VPN,
                // 降级等于原地打转。改为按用户配置的物理出口优先级挑一条,且排除刚失败的那条。
                val alt = physicalFallback
                    ?: throw ProxyException(e.error, "degrade-no-route")
                dialWithDnsRecovery(host, alt, false, trace) { addresses ->
                    connectAny(orderAddresses(addresses), port, alt)
                }
                    .also {
                        trace?.connected(host, it.inetAddress?.hostAddress, port, alt.networkHandle)
                        reportEgress(onEgress, alt)
                    }
            }
        }
    }

    /** Retained as a narrow test/lookup boundary; production keeps provenance through dialing. */
    private suspend fun resolve(host: String, network: Network?, trace: RequestTrace? = null): List<InetAddress> =
        resolveDetailed(host, network, trace).addrs

    private suspend fun resolveDetailed(host: String, network: Network?, trace: RequestTrace? = null, route: String = "PROXY:$egressFallback"): DnsAnswer {
        val name = host.trimEnd('.').lowercase(java.util.Locale.ROOT)
        if (DnsNamePolicy.isInvalid(name)) throw ProxyException(ProxyError.DnsFailure, "dns-special-invalid")
        numericAddressOrNull(name)?.let {
            trace?.dns(host, DnsSource.LITERAL, network?.networkHandle, 1, "literal", it.hostAddress)
            return DnsAnswer(listOf(it), DnsSource.LITERAL, network?.networkHandle)
        }
        val key = dnsKeyOf(network, name, route)
        cachedAt(key)?.let { cached ->
            val r = cached.answer
            trace?.dns(host, DnsSource.CACHE_SAME_NET, r.resolverNetId, r.addrs.size,
                "entry=${r.lease?.id};generation=${key.generation};source=${r.source.code};age=${(System.currentTimeMillis() - cached.atMs) / 1000}s;${r.detail.orEmpty()}", r.addrs.first().hostAddress)
            return r
        }
        var joined = false
        val flight = synchronized(dnsLock) {
            val existing = inflightDns[key]
            val entry = if (existing != null) {
                joined = true
                existing
            } else {
                if (inflightDns.size + recoveringDns.size >= DNS_MAX_SHARED_QUERIES) {
                    dnsRejected.incrementAndGet()
                    throw ProxyException(ProxyError.DnsFailure, "dns-busy")
                }
                DnsFlight(dnsScope.async(start = CoroutineStart.LAZY) {
                    val answer = withTimeoutOrNull(DNS_QUERY_TIMEOUT_MS) {
                        resolveUncached(name, network, key.backupEnabled)
                    } ?: throw ProxyException(ProxyError.DnsFailure, "dns-total-timeout")
                    val r = answer.copy(lease = DnsLease(key, dnsAnswerSequence.incrementAndGet()))
                    currentCoroutineContext().ensureActive()
                    synchronized(dnsLock) {
                        if (key.generation == dnsGeneration.get()) {
                            if (dnsCache.size >= DNS_MAX_CACHE_ENTRIES) dnsCache.clear()
                            dnsCache[key] = CachedAddrs(r, System.currentTimeMillis())
                        }
                    }
                    r
                }).also { inflightDns[key] = it }
            }
            entry.waiters++
            entry
        }
        flight.task.start()
        try {
            val r = flight.task.await()
            trace?.dns(host, if (joined) DnsSource.INFLIGHT else r.source, r.resolverNetId,
                r.addrs.size, "entry=${r.lease?.id};generation=${key.generation};source=${r.source.code};${r.detail.orEmpty()}", r.addrs.first().hostAddress)
            return r
        } finally {
            val last = synchronized(dnsLock) {
                flight.waiters--
                if (flight.waiters == 0) {
                    if (inflightDns[key] === flight) inflightDns.remove(key)
                    true
                } else false
            }
            if (last) withContext(NonCancellable) { flight.task.cancelAndJoin() }
        }
    }

    /** Feedback is an opportunity to recheck the primary, never proof that DNS is poisoned.
     * The old usable rescue remains available. Only an exact, unproven rescue entry can
     * request one shared primary refresh; each caller owns its own sockets and may redial once.
     * All work remains inside the caller's existing acquisition deadline. */
    private suspend fun <T : Closeable> dialWithDnsRecovery(
        host: String, network: Network?, bypassVpn: Boolean, trace: RequestTrace?,
        preferAlternate: () -> Boolean = { false },
        dial: suspend (List<InetAddress>) -> T,
    ): T {
        val route = if (bypassVpn) "DIRECT" else "PROXY:$egressFallback"
        val original = resolveDetailed(host, network, trace, route)
        fun describe(answer: DnsAnswer, attempt: Int) {
            if (trace != null && com.mzstd.hxmyproxy.core.log.FileLog.enabled) trace.dialCandidates(host, attempt,
                answer.lease?.id, network?.networkHandle,
                orderAddresses(answer.addrs).take(MAX_HE_CANDIDATES + 2).mapNotNull { it.hostAddress })
        }
        try {
            describe(original, 1)
            return dial(original.addrs).also { recordDnsConnection(original) }
        } catch (failure: ProxyException) {
            if (!failure.dnsRecoveryEligible || preferAlternate()) throw failure
            val started = System.nanoTime()
            val next = try { refreshFailedRescue(original, network) }
            catch (e: CancellationException) {
                trace?.dnsRecovery(host, original.lease?.id, original.source, "cancelled", (System.nanoTime() - started) / 1_000_000L)
                throw e
            }
            trace?.dnsRecovery(host, original.lease?.id, original.source,
                if (next == null || next === original) "not-replaced" else "new-result", (System.nanoTime() - started) / 1_000_000L)
            if (next == null) throw failure
            if (next === original || next.addrs == original.addrs) throw failure
            trace?.dns(host, next.source, next.resolverNetId, next.addrs.size,
                "attempt=2;recovery=primary;${next.detail.orEmpty()}", next.addrs.first().hostAddress)
            describe(next, 2)
            return dial(next.addrs).also { recordDnsConnection(next) }
        }
    }

    /** Existing DEGRADE gets first use of the remaining deadline, not a new DNS detour. */
    private fun preferAlternateEgress(network: Network?, bypassVpn: Boolean): Boolean =
        !bypassVpn && network != null && egressFallback == com.mzstd.hxmyproxy.core.model.EgressFallback.DEGRADE

    private fun recordDnsConnection(answer: DnsAnswer) {
        synchronized(dnsLock) {
            val lease = answer.lease ?: return
            if (cachedAt(lease.key)?.answer === answer) lease.connected.set(true)
        }
    }

    private suspend fun refreshFailedRescue(failed: DnsAnswer, network: Network?): DnsAnswer? {
        if (failed.source !in setOf(DnsSource.DOH, DnsSource.MIXED_RESCUE,
                DnsSource.RESCUE_DEFAULT, DnsSource.RESCUE_PHYSICAL)) return null
        val lease = failed.lease ?: return null
        val key = lease.key
        val flight = synchronized(dnsLock) {
            if (key.generation != dnsGeneration.get() || key.backupEnabled != backupDnsEnabled ||
                lease.connected.get()) return null
            val current = cachedAt(key) ?: return null
            // A late failing dial can use a newer answer, but cannot evict or renew it.
            if (current.answer !== failed) return current.answer
            recoveringDns[key]?.let { it.waiters++; return@synchronized it }
            if (System.nanoTime() < lease.recoverAfterNs ||
                recoveringDns.size + inflightDns.size >= DNS_MAX_SHARED_QUERIES) return null
            DnsFlight(dnsScope.async(start = CoroutineStart.LAZY) {
                val addresses = resolveOnOrEmpty(network, key.host)
                currentCoroutineContext().ensureActive()
                synchronized(dnsLock) {
                    if (key.generation != dnsGeneration.get() || key.backupEnabled != backupDnsEnabled ||
                        cachedAt(key)?.answer !== failed || lease.connected.get()) return@synchronized failed
                    if (addresses.isEmpty()) {
                        lease.recoverAfterNs = System.nanoTime() + DNS_STEP_TIMEOUT_MS * 1_000_000L
                        failed
                    } else {
                        val refreshed = DnsAnswer(addresses,
                            if (network == null) DnsSource.SYS_DEFAULT else DnsSource.SYS_EGRESS,
                            network?.networkHandle, "primary-recheck", DnsLease(key, dnsAnswerSequence.incrementAndGet()))
                        // New primary observation gets its own TTL; hits/successes never renew TTL.
                        dnsCache[key] = CachedAddrs(refreshed, System.currentTimeMillis())
                        refreshed
                    }
                }
            }, waiters = 1).also { recoveringDns[key] = it }
        }
        flight.task.start()
        try { return flight.task.await() }
        finally {
            val last = synchronized(dnsLock) {
                flight.waiters--
                if (flight.waiters == 0) {
                    if (recoveringDns[key] === flight) recoveringDns.remove(key)
                    true
                } else false
            }
            if (last) withContext(NonCancellable) { flight.task.cancelAndJoin() }
        }
    }

    /**
     * 目标本身就是 IP 字面量时返回它，否则 null。
     *
     * 刻意**不使用** `InetAddress.getByName`——那对非字面量会触发真实解析，正是要避免的事。
     * `parseNumericAddress` 只做字面量解析，非字面量抛 IllegalArgumentException。
     * 方括号形式的 IPv6 已由 `HttpParsing.bareHost` 在上游剥掉，这里不再处理。
     */
    private fun numericAddressOrNull(host: String): InetAddress? {
        // 刻意不用 android.net.InetAddresses.parseNumericAddress:它在 JVM 单测里是 stub,
        // runCatching 会把 stub 异常吞成 null —— 短路静默失效，而测试照样绿。
        // 纯 JVM 的等价判据:含 ':' 必是 IPv6 字面量;全为数字与点必是 IPv4 字面量
        // （合法域名的 TLD 不能全是数字，见 RFC 1123 §2.1）。
        val looksNumeric = host.contains(':') || (host.isNotEmpty() && host.all { it.isDigit() || it == '.' })
        if (!looksNumeric) return null
        // getByName 对字面量**不做 DNS 查询**（JDK 契约），预筛之后调用是安全的。
        return runCatching { InetAddress.getByName(host) }.getOrNull()
    }

    private fun cachedAt(key: DnsKey): CachedAddrs? {
        val c = dnsCache[key] ?: return null
        if (System.currentTimeMillis() - c.atMs >= DNS_TTL_MS) {
            dnsCache.remove(key, c)
            return null
        }
        return c
    }

    /**
     * Preserve delayed rescue (1.24/1.25) but do not let a different resolver cancel a
     * still-viable primary. Rescue is prefetched after 400ms or an early primary failure.
     * Only a failed/expired primary makes rescue eligible. All work has the same bounds.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun resolveUncached(host: String, network: Network?, backupEnabled: Boolean): DnsAnswer {
        if (DnsNamePolicy.isLocalhost(host)) return DnsAnswer(
            listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("::1")),
            DnsSource.LITERAL, null, "localhost",
        )
        return coroutineScope {
            val started = System.nanoTime()
            val primary = async { resolveOnOrEmpty(network, host) }
            val rescue = async {
                val completedPrimary = if (network == null) primary.await() else select<List<InetAddress>?> {
                    primary.onAwait { it }
                    onTimeout(DNS_HEDGE_DELAY_MS) { null }
                }
                if (!completedPrimary.isNullOrEmpty()) DnsAnswer(emptyList(), DnsSource.SYS_DEFAULT, null)
                else resolveBackup(host, network, backupEnabled)
            }
            try {
                val p = primary.await()
                if (p.isNotEmpty()) DnsAnswer(
                    p, if (network == null) DnsSource.SYS_DEFAULT else DnsSource.SYS_EGRESS,
                    network?.networkHandle, "rtt=${(System.nanoTime() - started) / 1_000_000}ms",
                ) else rescue.await().takeIf { it.addrs.isNotEmpty() }?.also { dnsRescueUses.incrementAndGet() }
                    ?: throw ProxyException(ProxyError.DnsFailure, "dns-no-answer")
            } finally {
                primary.cancel()
                rescue.cancel()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun resolveBackup(host: String, network: Network?, backupEnabled: Boolean): DnsAnswer = coroutineScope {
        val physical = underlyingNetworkProvider?.current()?.takeIf { it != network }
        val default = async { if (network != null) resolveOnOrEmpty(null, host) else emptyList() }
        val alt = async { if (physical != null) resolveOnOrEmpty(physical, host) else emptyList() }
        val doh = async { if (backupEnabled && DnsNamePolicy.allowPublicBackup(host)) resolveDohWithinBudget(host) else null }
        fun ready(task: Deferred<List<InetAddress>>): List<InetAddress> =
            if (task.isCompleted && !task.isCancelled) task.getCompleted() else emptyList()
        val system = async {
            select<Pair<List<InetAddress>, List<InetAddress>>> {
                default.onAwait { a -> if (a.isNotEmpty()) a to ready(alt) else a to alt.await() }
                alt.onAwait { p -> if (p.isNotEmpty()) ready(default) to p else default.await() to p }
            }
        }
        try {
            val d = doh.await()
            val (a, p) = if (!d?.addrs.isNullOrEmpty() && !system.isCompleted) ready(default) to ready(alt)
                else system.await()
            // DoH gets candidate slots first; provenance survives caching and joining.
            val merged = (d?.addrs.orEmpty() + a + p).distinct()
            val source = when {
                listOf(!d?.addrs.isNullOrEmpty(), a.isNotEmpty(), p.isNotEmpty()).count { it } > 1 -> DnsSource.MIXED_RESCUE
                !d?.addrs.isNullOrEmpty() -> DnsSource.DOH
                a.isNotEmpty() -> DnsSource.RESCUE_DEFAULT
                else -> DnsSource.RESCUE_PHYSICAL
            }
            val resolver = when(source) {
                DnsSource.MIXED_RESCUE -> null
                DnsSource.DOH -> d?.resolverNetId
                DnsSource.RESCUE_DEFAULT -> null
                else -> physical?.networkHandle
            }
            DnsAnswer(merged, source, resolver,
                "${d?.detail.orEmpty()};doh=${d?.addrs?.size ?: 0};dohNet=${d?.resolverNetId};default=${a.size};physical=${p.size};physicalNet=${physical?.networkHandle}")
        } finally {
            default.cancel(); alt.cancel(); doh.cancel(); system.cancel()
        }
    }


    /** 每条系统解析支路可取消，并受相同的名额和 1.5 秒预算约束。 */
    private suspend fun <T> dnsStep(block: suspend () -> T): T? {
        if (!dnsSlots.tryAcquire()) {
            val n = dnsRejected.incrementAndGet()
            throttledFileLog("dns-busy", "DNS inflight full ($DNS_MAX_INFLIGHT), failing fast; $n total")
            return null
        }
        val start = System.nanoTime()
        try {
            val result = withTimeoutOrNull(DNS_STEP_TIMEOUT_MS) { block() }
            if (result == null) {
                val n = dnsTimedOut.incrementAndGet()
                throttledFileLog("dns-timeout", "DNS step timeout (${DNS_STEP_TIMEOUT_MS}ms); $n total; async=true")
            } else {
                recordDnsOk((System.nanoTime() - start) / 1_000_000L)
            }
            return result
        } finally {
            dnsSlots.release()
        }
    }

    /**
     * 记录一次**成功**解析的耗时。
     *
     * 0806 日志暴露的观测盲区：此前这条路径只记失败（超时 102 次、拒绝 N 次），
     * 成功耗时一个字都没有。于是「1500ms 的单步超时是否合理」根本无从判断——
     * 调短可能误伤「慢但能成功」的解析，调长则继续白等。没有这份分布就只能拍脑袋。
     *
     * 用固定分桶而不是留样本数组：心跳每 12 秒打一次，分桶是 O(1) 写入、常数内存，
     * 且「超过 800ms 的占比」这类问题直接读桶就能答，比存 P50/P90 更有用。
     */
    private fun recordDnsOk(ms: Long) {
        dnsOkCount.incrementAndGet()
        dnsOkTotalMs.addAndGet(ms)
        while (true) {
            val cur = dnsOkMaxMs.get()
            if (ms <= cur || dnsOkMaxMs.compareAndSet(cur, ms)) break
        }
        val i = DNS_OK_BUCKET_UPPER_MS.indexOfFirst { ms < it }
        dnsOkBuckets.incrementAndGet(if (i >= 0) i else DNS_OK_BUCKET_UPPER_MS.size)
    }


    private fun bucketPercentile(p: Double): Long {
        val total = dnsOkCount.get()
        if (total == 0L) return 0
        val want = (total * p).toLong().coerceAtLeast(1)
        var acc = 0L
        for (i in 0..DNS_OK_BUCKET_UPPER_MS.size) {
            acc += dnsOkBuckets.get(i)
            if (acc >= want) {
                return if (i < DNS_OK_BUCKET_UPPER_MS.size) DNS_OK_BUCKET_UPPER_MS[i]
                else DNS_STEP_TIMEOUT_MS
            }
        }
        return DNS_STEP_TIMEOUT_MS
    }

    /** DNS 侧健康指标，供 PERF 心跳打点——排队/超时此前完全不可观测。 */
    data class DnsStats(
        val rejected: Long,
        val timedOut: Long,
        val inflight: Int,
        val cached: Int,
        /** 成功解析次数 */
        val okCount: Long,
        /** 成功解析耗时：均值 / 估算 p50 / 估算 p90 / 最大，单位 ms */
        val okAvgMs: Long,
        val okP50Ms: Long,
        val okP90Ms: Long,
        val okMaxMs: Long,
        /** 主路失败后采用备用答案的次数，不等于旧的抢答胜出数。 */
        val rescueUses: Long,
        /** DoH 兜底:调用次数 / 其中按预算取消的次数。 */
        val dohCalls: Long,
        val dohTimeouts: Long,
    )

    fun dnsStats(): DnsStats {
        val n = dnsOkCount.get()
        return DnsStats(
            rejected = dnsRejected.get(),
            timedOut = dnsTimedOut.get(),
            inflight = DNS_MAX_INFLIGHT - dnsSlots.availablePermits(),
            cached = dnsCache.size,
            okCount = n,
            okAvgMs = if (n == 0L) 0 else dnsOkTotalMs.get() / n,
            okP50Ms = bucketPercentile(0.50),
            okP90Ms = bucketPercentile(0.90),
            okMaxMs = dnsOkMaxMs.get(),
            rescueUses = dnsRescueUses.get(),
            dohCalls = dohCalls.get(),
            dohTimeouts = dohTimeouts.get(),
        )
    }

    /**
     * 互援用的单路解析：解析不出返回空（那是互援的正常结局）。
     * **非解析类异常不静默吞掉**——它意味着救援机制本身坏了（句柄失效/权限），
     * 而不是「这个域名解析不出来」，两者混为一谈会让这条路径变成又一个黑箱。
     */
    private suspend fun resolveOnOrEmpty(net: Network?, host: String): List<InetAddress> =
        try {
            dnsStep { systemDns.resolve(net, host) }.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: UnknownHostException) {
            emptyList()
        } catch (e: Exception) {
            throttledFileLog("dns-rescue-err:$host", "DNS rescue failed: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }



    /** AUTO 和指定出口的 DoH 共用同一预算、有限工作池及统计口径。 */
    private suspend fun resolveDohWithinBudget(host: String): DnsAnswer? {
        dohCalls.incrementAndGet()
        val started = System.nanoTime()
        val deadline = started + DOH_TOTAL_BUDGET_MS * 1_000_000L
        val dohNetwork = underlyingNetworkProvider?.dohNetwork()
        val answers = try {
            dohResolve(host, deadline, dohNetwork)
        } catch (e: CancellationException) {
            throw e
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            dnsRejected.incrementAndGet()
            throttledFileLog("doh-busy", "DoH workers busy; backup skipped without queuing")
            null
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000L
        val timedOut = elapsed >= DOH_TOTAL_BUDGET_MS
        if (timedOut) dohTimeouts.incrementAndGet()
        if (answers != null && answers.addrs.isNotEmpty()) throttledFileLog("doh-rescued:$host", "DNS rescued $host via DoH backup (${elapsed}ms)")
        else throttledFileLog("doh-fail:$host", "DoH backup failed for $host (${elapsed}ms, timeout=$timedOut, attemptedNet=${dohNetwork?.networkHandle ?: 0L})")
        return answers
    }

    /** Scheduling may overlap, acceptance remains ordered. Give later providers a chance without
     * shortening the first provider's connect+read allowance. Each provider retains A-before-AAAA
     * and 1200ms per I/O, all sharing one 3s deadline. Starts are staggered to let other hosts enter
     * the bounded FIFO; four actual workers total, not four additional workers per cancellation.
     * Coordinators never occupy HTTP workers. A ready backup survives the primary deadline. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun dohResolve(host: String, deadlineNs: Long, dohNet: Network?): DnsAnswer {
        return coroutineScope {
            val queries = DOH_ENDPOINTS.mapIndexed { index, endpoint ->
                async {
                    delay(index * DOH_PROVIDER_STAGGER_MS)
                    try {
                        DOH_WORKERS.execute {
                            dohQuery(host, deadlineNs, dohNet, endpoint.first, endpoint.second, 1)
                                ?: dohQuery(host, deadlineNs, dohNet, endpoint.first, endpoint.second, 28)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: java.util.concurrent.RejectedExecutionException) {
                        dnsRejected.incrementAndGet()
                        throttledFileLog("doh-busy", "DoH workers/queue full; backup task rejected")
                        null
                    }
                }
            }
            try {
                val preferred = withTimeoutOrNull(((deadlineNs - System.nanoTime()) / 1_000_000L).coerceAtLeast(0)) {
                    queries.firstNotNullOfOrNull { it.await() }
                }
                preferred ?: queries.firstNotNullOfOrNull {
                    if (it.isCompleted && !it.isCancelled) it.getCompleted() else null
                } ?: DnsAnswer(emptyList(), DnsSource.DOH, dohNet?.networkHandle)
            } finally { queries.forEach { it.cancel() } }
        }
    }

    /** Only blocking HTTP I/O occupies DOH_WORKERS. Deadline checked before starting queued work. */
    private fun dohQuery(host: String, deadlineNs: Long, dohNet: Network?, base: String, accept: String?, type: Int): DnsAnswer? {
        val started = System.nanoTime()
        var outcome = "empty"
        // Caller cancellation never waits for this worker. Bound its own socket work too,
        // so an interrupted request cannot keep consuming a worker across endpoints.
        if (Thread.currentThread().isInterrupted) return null
        val remainMs = (deadlineNs - System.nanoTime()) / 1_000_000L
        if (remainMs <= 0) return null
        val stepMs = minOf(DOH_TIMEOUT_MS.toLong(), remainMs).toInt()
        var conn: javax.net.ssl.HttpsURLConnection? = null
        try {
            val url = java.net.URL("$base?name=${java.net.URLEncoder.encode(host, "UTF-8")}&type=$type")
            conn = (dohNet?.openConnection(url) ?: url.openConnection()) as javax.net.ssl.HttpsURLConnection
            conn.connectTimeout = stepMs
            conn.readTimeout = stepMs
            if (accept != null) conn.setRequestProperty("Accept", accept)
            conn.connect()
            val readBudget = ((deadlineNs - System.nanoTime()) / 1_000_000L).toInt()
            if (readBudget <= 0 || Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("DoH deadline reached")
            conn.readTimeout = minOf(stepMs, readBudget)
            val body = conn.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    if (Thread.currentThread().isInterrupted || System.nanoTime() >= deadlineNs) {
                        throw java.io.InterruptedIOException("DoH deadline reached")
                    }
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (output.size() + n > 64 * 1024) throw IOException("DoH response too large")
                    output.write(buffer, 0, n)
                }
                output.toString("UTF-8")
            }
            val answers = org.json.JSONObject(body).optJSONArray("Answer") ?: return null
            val out = ArrayList<InetAddress>()
            for (i in 0 until answers.length()) {
                val a = answers.getJSONObject(i)
                if (a.optInt("type") == type) {
                    // 数字字面量不触发系统 DNS 查询，不会递归回失败路径。
                    numericAddressOrNull(a.getString("data"))?.let(out::add)
                }
            }
            if (out.isNotEmpty() && !Thread.currentThread().isInterrupted && System.nanoTime() < deadlineNs) {
                outcome = "answer"
                return DnsAnswer(out, DnsSource.DOH, dohNet?.networkHandle, "endpoint=$base;type=$type")
            }
        } catch (e: Exception) {
            outcome = e.javaClass.simpleName
        } finally {
            runCatching { conn?.disconnect() }
            throttledFileLog("doh-attempt:$host:$base:$type", "DoH attempt host=$host endpoint=$base type=$type net=${dohNet?.networkHandle ?: 0L} outcome=$outcome ms=${(System.nanoTime() - started) / 1_000_000L} budget=$stepMs")
        }
        return null
    }

    /**
     * 连接到已解析地址（SOCKS5 ATYP=IPv4/IPv6）。[bypassVpn]=true 时绕过共享 VPN 走真实网络。
     *
     * [trace] 不能省：不传它，IP 字面量目标的请求就只有 `req.rule` 一行、连 `req.connected` 都没有，
     * 在按事件签名做一致性检查时表现为「拿到规则判定后彻底消失」的孤儿。
     */
    suspend fun connect(
        addr: InetAddress,
        port: Int,
        bypassVpn: Boolean = false,
        onEgress: ((com.mzstd.hxmyproxy.core.stats.EgressKind) -> Unit)? = null,
        trace: RequestTrace? = null,
    ): Socket = connect(addr.hostAddress ?: throw ProxyException(ProxyError.DnsFailure), port, bypassVpn, onEgress, trace)

    /**
     * 同 [connect]，但产出已连接的（阻塞模式）[SocketChannel]，供非阻塞 relay 使用（调用方在进入 relay 前
     * 切 `configureBlocking(false)`）。所选出口在 connect 前通过公开的 Network.bindSocket(Socket) 绑定。
     * 绑定能力失败保持 IOException，调用方仍可回退到同一出口的 socketFactory + 阻塞 relay。
     */
    suspend fun connectChannel(
        host: String,
        port: Int,
        bypassVpn: Boolean = false,
        onEgress: ((com.mzstd.hxmyproxy.core.stats.EgressKind) -> Unit)? = null,
        /** 请求级追踪；null=不记。见 [RequestTrace]。 */
        trace: RequestTrace? = null,
    ): SocketChannel {
        // 同 connect()：两个抛点必须在 try 内，否则 catch 里的 DirectEgressFailures 收不到。
        var network: Network? = null
        val physicalFallback by lazy { underlyingNetworkProvider?.fallbackEgress(network) }
        return try {
            network = egressNetworkFor(bypassVpn, host)
            if (egressUnusable(network, bypassVpn)) {
                throttledFileLog("egress-down:$host", "egress marked down, skipping wait - $host")
                // 统一交给下面的 STRICT/DEGRADE 分支；这里不能直接回落默认路由。
                throw ProxyException(
                    ProxyError.EgressSidelined, "egress-down",
                    retryAfterSeconds = egressHealth.retryAfterSeconds(network),
                )
            }
            run {
                dialWithDnsRecovery(host, network, bypassVpn, trace,
                    preferAlternate = { preferAlternateEgress(network, bypassVpn) && physicalFallback != null }) { addresses ->
                    connectAnyChannel(orderAddresses(addresses), port, network)
                }
            }
                .also {
                    trace?.connected(
                        host,
                        (it.remoteAddress as? InetSocketAddress)?.address?.hostAddress,
                        port, network?.networkHandle,
                    )
                    reportEgress(onEgress, network)
                    if (bypassVpn) DirectEgressFailures.recordSuccess(host)
                }
        } catch (e: ProxyException) {
            // DIRECT(bypass) fail-closed：不降级默认(=VPN)。仅捕 ProxyException——IOException 须继续冒泡
            // 让调用方走「NIO绑定不可用 → 同一出口的阻塞路径」的既有逻辑。
            if (bypassVpn) {
                // 见 connect() 里同处注释：全局状况不进 per-host 账本。
                if (e.stage == "no-physical-egress") DirectEgressFailures.recordNoEgress()
                else DirectEgressFailures.recordFailure(host, e.error.code)
                throttledFileLog("direct:$host", "DIRECT egress fail $host: ${e.error} - fail-closed (no VPN fallback)")
                throw e
            }
            if (e.stage == "egress-unavailable") throw e
            if (network == null) {
                throttledFileLog("connect:$host", "upstream fail $host (default egress): ${e.error}")
                throw e
            }
            // 主动短路没有发起联网，不能重新计作真实失败来提前触发复检。
            if (e.error !== ProxyError.EgressSidelined && egressHealth.recordFailure(network, host)) {
                egressHealth.confirmOrSideline(network)
            }

            if (egressFallback == com.mzstd.hxmyproxy.core.model.EgressFallback.STRICT) {
                // **sincePhys / vpnAge 是关联字段**：0815 现场里一整分钟的 STRICT 失败，
                // 全部发生在底层链路刚换过之后，而出口用的 VPN 句柄一次没变（存在但不通）。
                // 把「距上次换网多久」直接写在失败行上，才能验证这个模式是否稳定复现 ——
                // 附在已有的行上而不是新增事件，不增加日志量。
                throttledFileLog(
                    "egress-strict:$host",
                    "egress fail $host: ${e.error} - STRICT abort (no fallback, egress identity preserved)" +
                        "; sincePhys=${underlyingNetworkProvider?.sincePhysChangeSec() ?: -1}s" +
                        " vpnAge=${underlyingNetworkProvider?.vpnAgeSec() ?: -1}s",
                )
                // 阶段随异常走、由 server 层统一落盘 —— 这里再记一次就是双写（见 ProxyException.stage）。
                throw ProxyException(e.error, e.stage ?: "connect-strict", e.retryAfterSeconds)
            }
            throttledFileLog("egress:$host", "egress fail $host: ${e.error} — trying another physical egress")
            retryOnPhysicalEgress(host, e.error) {
                // 同 connect():降级绝不回落系统默认路由(那可能就是刚失败的 VPN)。
                val alt = physicalFallback
                    ?: throw ProxyException(e.error, "degrade-no-route")
                dialWithDnsRecovery(host, alt, false, trace) { addresses ->
                    connectAnyChannel(orderAddresses(addresses), port, alt)
                }
                    .also {
                        trace?.connected(
                            host, (it.remoteAddress as? InetSocketAddress)?.address?.hostAddress,
                            port, alt.networkHandle,
                        )
                        reportEgress(onEgress, alt)
                    }
            }
        }
    }

    /** [connectChannel] 的已解析地址版（SOCKS5 ATYP）。[trace] 的必要性见同名的 Socket 版。 */
    suspend fun connectChannel(
        addr: InetAddress,
        port: Int,
        bypassVpn: Boolean = false,
        onEgress: ((com.mzstd.hxmyproxy.core.stats.EgressKind) -> Unit)? = null,
        trace: RequestTrace? = null,
    ): SocketChannel = connectChannel(addr.hostAddress ?: throw ProxyException(ProxyError.DnsFailure), port, bypassVpn, onEgress, trace)

    /** IPv4 优先排序（IPv6 在 NAT/移动网常不可达，放后面）。 */
    internal fun orderAddresses(addrs: List<InetAddress>): List<InetAddress> =
        addrs.sortedBy { if (it is Inet4Address) 0 else 1 }

    /**
     * 扇出截断：候选超过 [MAX_HE_CANDIDATES] 时按**地址族配额**保留，而不是一刀切取前 N 个。
     *
     * 修的是一个会改变**可达集合**（而不只是尝试顺序）的缺陷：截断发生在 [orderAddresses] 的
     * IPv4 优先排序**之后**，于是域名解析出 7 个以上地址、前 6 个恰好都是 IPv4 时，
     * **IPv6 被整体删除** —— 前面那些 IPv4 全是黑洞时，本来能救场的 IPv6 已经不在池子里了。
     * 依据 RFC 8305 §3.1（全文唯一一处谈「不得不截断时怎么办」的规范文字）：
     * "If such a limit is required by hardware limitations, the client SHOULD use at least one
     * address from each address family from the available list."
     *
     * 刻意只给次要族**保底 [MIN_MINORITY_SLOTS] 个名额**而不是对半分：本项目的「系统解析 + 互援 + DoH」
     * 合并池正是靠多个 IPv4 并行竞速来对抗 DNS 污染（见 [resolve] 里那段合并逻辑），把 IPv4 名额腰斩
     * 会让「前几个是污染死 IP」从能救回来变成全失败。RFC 要的是「至少一个」，不是 50/50。
     *
     * **只改保留哪些、不改先试哪个**：保留下来的地址按原下标回填，族间先后与族内顺序一律不变，
     * 所以起跑顺序完全不受本函数影响。IPv4 优先这个**策略**该不该改是另一件事（RFC 8305 §4 要求
     * 交错、并把排序 MUST 委托给 RFC 6724，而 Android 的 getaddrinfo 已按当前网络+uid 排过一遍），
     * 那需要真机数据支撑，别混在截断里顺手做掉。
     */
    internal fun capByFamily(addrs: List<InetAddress>, max: Int = MAX_HE_CANDIDATES): List<InetAddress> {
        if (addrs.size <= max) return addrs
        // 主要族 = 排头地址所属的族（当前排序下即 IPv4）；另一族即次要族。
        val leadIsV4 = addrs[0] is Inet4Address
        val majority = ArrayList<Int>(addrs.size)
        val minority = ArrayList<Int>()
        addrs.forEachIndexed { i, a ->
            (if ((a is Inet4Address) == leadIsV4) majority else minority).add(i)
        }
        if (minority.isEmpty()) return addrs.take(max)   // 单族：没有族要保，原样截断
        // 次要族名额不得挤光主要族（至少给它留 1 个），也不超过次要族实际有的个数。
        val minorityQuota = minOf(MIN_MINORITY_SLOTS, minority.size, max - 1)
        val keep = majority.take(max - minorityQuota) + minority.take(minorityQuota)
        return keep.sorted().map { addrs[it] }           // 按原下标回填 ⇒ 顺序不变
    }

    /**
     * Happy Eyeballs 交错并行连接：首个成功者胜出，其余在途连接立即关闭（中止其阻塞中的 connect）。
     * 全部失败抛最后一次错误；候选为空（DNS 空 / 全被护栏拦）抛对应错误。
     */
    /** 阻塞 [Socket] 版（HTTP 明文路径 / 现有调用）。出口分流靠 `network.socketFactory` 建已绑定 socket。 */
    internal suspend fun connectAny(addrs: List<InetAddress>, port: Int, network: Network? = null): Socket =
        connectAnyGeneric(
            addrs, port,
            create = { network?.socketFactory?.createSocket() ?: Socket() },
            connect = { s, a -> s.tcpNoDelay = true; s.keepAlive = true; s.connect(a, ProxyTuning.CONNECT_TIMEOUT_MS) },
        )

    /**
     * 非阻塞 relay 用：产出已连接的**阻塞** [SocketChannel]（调用方进入 relay 前切非阻塞）。
     * 出口分流（[network] 非空）使用公开的 bindSocket(Socket)，必须在 connect 之前。
     * Android SocketAdaptor 由 FileDescriptorHolderSocketImpl 持有 channel 的同一个 fd。
     * 绑定失败必须关闭尚未交接的 channel，并保留 IOException 让调用方回退到绑定出口的阻塞路径。
     */
    internal suspend fun connectAnyChannel(addrs: List<InetAddress>, port: Int, network: Network?): SocketChannel {
        return connectAnyGeneric(
            addrs, port,
            create = {
                val ch = SocketChannel.open()
                try {
                    ch.configureBlocking(true)
                    if (network != null) {
                        try { network.bindSocket(ch.socket()) }
                        catch (failure: Exception) {
                            throttledFileLog("nio-bind:$network", "NIO socket binding failed; using bound socketFactory fallback: $failure")
                            throw ChannelBindingUnavailable(failure)
                        }
                    }
                    ch
                } catch (failure: Throwable) {
                    ch.closeQuietly()
                    throw failure
                }
            },
            // keepAlive：让内核主动探测「链路没了但没有 FIN/RST」的死连接。
            // 注意它只是辅助——Java 无法设置探测间隔，系统默认约 2 小时。
            // 曾经指望 relay 侧的「上游静默判死」来快速发现，但实测那条判据抓到的
            // 全是闲置的池化连接、没有一次真故障，已删除（见 NioRelayReactor.sweepIdle）。
            // 这类死连接现在由 idle 超时回收。
            connect = { ch, a ->
                ch.socket().tcpNoDelay = true
                ch.socket().keepAlive = true
                ch.socket().connect(a, ProxyTuning.CONNECT_TIMEOUT_MS)
            },
        )
    }

    /**
     * Happy Eyeballs（RFC 8305）交错并行连接的泛型编排：[create] 建连接对象（可含 bindSocket），[connect] 阻塞建连。
     * 首个成功者胜出、其余在途立即关闭；全失败抛最后错误。Socket 与 SocketChannel 共用这一份编排。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun <S : Closeable> connectAnyGeneric(
        addrs: List<InetAddress>,
        port: Int,
        create: () -> S,
        connect: (S, InetSocketAddress) -> Unit,
    ): S {
        var winner: S? = null
        return try { coroutineScope {
        val candidates = ArrayList<InetAddress>()
        var blocked = false
        for (a in addrs) if (egressGuard.isAllowed(a)) candidates.add(a) else blocked = true
        if (candidates.isEmpty()) {
            throw ProxyException(if (blocked) ProxyError.AccessDenied else ProxyError.DnsFailure)
        }
        // 扇出上限：解析出超多地址（个别 CDN/anycast 返回十几条）时只取前 N 个并行尝试，
        // 但**按族配额**保留，绝不让某一族被整体挤出候选池（见 capByFamily）。
        val attempts = capByFamily(candidates)

        val results = Channel<Outcome<S>>(Channel.UNLIMITED)
        // inFlight 兼作锁对象；closed=注册闸门+清理标记：胜出/清理后置 true，使后到的尝试自行关闭而非连接。
        val inFlight = ArrayList<S>()
        val closed = AtomicBoolean(false)

        // nextIdx / pending 仅在收集协程内访问 → 单线程，无需同步。
        var nextIdx = 0
        var pending = 0

        fun launchNext() {
            if (nextIdx >= attempts.size) return
            val addr = attempts[nextIdx++]
            pending++
            launch(connectDispatcher) {
                val conn = try {
                    create()
                } catch (e: Throwable) {
                    // This failure is about channel binding, not a remote address. Preserve the
                    // existing IOException fallback instead of converting it to ProxyException.
                    if (e is ChannelBindingUnavailable) throw e
                    if (!closed.get()) results.trySend(Outcome(null, mapConnectError(e)))
                    return@launch
                }
                // 注册与"是否已收尾"判定同锁：收尾后才到的尝试直接放弃，杜绝落单连接逃过清理而泄漏 FD。
                val registered = synchronized(inFlight) {
                    if (closed.get()) false else { inFlight.add(conn); true }
                }
                if (!registered) { conn.closeQuietly(); return@launch }
                try {
                    connect(conn, InetSocketAddress(addr, port))
                    if (closed.get()) conn.closeQuietly() else results.trySend(Outcome(conn, null))
                } catch (e: Throwable) {
                    conn.closeQuietly()
                    if (!closed.get()) results.trySend(Outcome(null, mapConnectError(e),
                        e is SocketTimeoutException || e is NoRouteToHostException))
                }
            }
        }

        launchNext()
        var lastError: ProxyError = ProxyError.RemoteUnreachable
        var allDialFailuresEligible = true
        try {
            // 仍有在途尝试或未起地址时继续；二者皆尽即所有候选失败 → 循环退出后抛错。
            while (pending > 0 || nextIdx < attempts.size) {
                // 还有未起地址：select 等结果或到点（select 保证已投递的结果不会被丢弃），到点则并行起下一个；
                // 地址起完：纯等结果（必有在途，故不会永久阻塞）。
                val outcome: Outcome<S>? = if (nextIdx < attempts.size) {
                    select {
                        results.onReceive { it }
                        onTimeout(ProxyTuning.HE_ATTEMPT_DELAY_MS.toLong()) { null }
                    }
                } else {
                    results.receive()
                }
                if (outcome == null) { launchNext(); continue }  // 到点仍无结果 → 并行起下一个
                pending--
                val conn = outcome.conn
                if (conn != null) {
                    synchronized(inFlight) {
                        closed.set(true)
                        inFlight.forEach { if (it !== conn) it.closeQuietly() }
                        inFlight.clear()
                    }
                    winner = conn
                    return@coroutineScope conn
                }
                outcome.error?.let { lastError = it }
                allDialFailuresEligible = allDialFailuresEligible && outcome.recoveryEligible
                launchNext()  // 失败立即补起下一个（RFC 8305：不必等满间隔）
            }
            throw ProxyException(lastError, dnsRecoveryEligible = allDialFailuresEligible)
        } finally {
            // 兜底（throw / 取消）：标记收尾并关掉所有已注册在途连接；之后才注册的尝试见 closed=true 自行关闭。
            synchronized(inFlight) {
                if (!closed.get()) {
                    closed.set(true)
                    inFlight.forEach { it.closeQuietly() }
                    inFlight.clear()
                }
            }
        }
        } } catch (e: Throwable) {
            // coroutineScope can be cancelled while joining the already-closed losing attempts.
            // Its successful value has not reached the caller yet, so we still own the winner.
            winner?.closeQuietly()
            throw e
        }
    }

    private class ChannelBindingUnavailable(cause: Exception) :
        IOException("Cannot bind NIO socket to the selected network", cause)

    private fun mapConnectError(e: Throwable): ProxyError = when (e) {
        is SocketTimeoutException -> ProxyError.RemoteTimeout
        is ConnectException -> ProxyError.ConnectionRefused
        is NoRouteToHostException -> ProxyError.RemoteUnreachable
        else -> ProxyError.Unknown(e.message ?: "connect failed")
    }

    private class Outcome<S>(val conn: S?, val error: ProxyError?, val recoveryEligible: Boolean = false)

    private class CachedAddrs(val answer: DnsAnswer, val atMs: Long)

    companion object {
        /** DNS 缓存有效期；短到 VPN 切换/DNS 漂移很快自愈，长到覆盖一次页面加载的同域名复用。 */
        private const val DNS_TTL_MS = 30_000L
        private const val DNS_QUERY_TIMEOUT_MS = 5_000L
        private const val DNS_MAX_SHARED_QUERIES = 64
        private const val DNS_MAX_CACHE_ENTRIES = 512
        /** 上游失败日志同 key 节流窗口（断网风暴时防止冲掉 512KB 滚动日志）。 */
        private const val LOG_THROTTLE_MS = 30_000L
        private const val LOG_THROTTLE_MAX_KEYS = 512

        /** DoH 端点（IP 直连免自举）：Google JSON API 与 Cloudflare（需 Accept 头）。 */
        /**
         * DoH 端点，**按序尝试、先成功者胜出**。前两个是零污染的权威源，后两个是国内直连可达的兜底。
         *
         * 顺序即策略：VPN 活着时 Google/Cloudflare 能通且答案未被污染，优先用它们；
         * 只有它们不可达（=绑了物理网、走国内直连）才轮到国内端点——不需要任何污染检测逻辑。
         * 加国内端点与「DoH 绑物理网」是配套的：绑物理网后请求变成国内直连，而 8.8.8.8 / 1.1.1.1
         * 在国内直连不可达，只改其一都会让 DoH 更糟（要么永远不可用，要么照样跟着 VPN 一起死）。
         *
         * 国内端点实测（2026-08-04）：阿里 223.5.5.5 中位 52ms、腾讯 1.12.12.12 中位 162ms，
         * 两者都用 IP 直连免自举、证书含 IP SAN 故主机名校验通过、响应是 Google 风格 JSON
         * （Answer / type / data 三个字段逐字相同）⇒ 现有解析器零改动兼容。
         * **刻意不收 360（101.226.4.6）**：它对 twitter/facebook/instagram 直接返回 127.0.0.1，
         * 而解析器会把回环地址当成有效结果交给连接层——代理会去连自己。
         */
        private val DOH_ENDPOINTS = listOf(
            "https://8.8.8.8/resolve" to null,
            "https://1.1.1.1/dns-query" to "application/dns-json",
            "https://223.5.5.5/resolve" to null,
            "https://1.12.12.12/dns-query" to null,
        )
        /**
         * 单个 DoH 请求的 connect/read 超时。3000 降到 1200：端点从 2 个增到 4 个后，
         * 全败路径的最坏耗时本会翻倍到 24s（4 端点 × 2 记录类型 × 3s）。
         * 国内端点实测 tcp+tls 中位 52~162ms，1200ms 留了 7 倍余量；
         * 墙外那两个在墙内本就必然吃满超时，缩短只有好处。
         */
        private const val DOH_TIMEOUT_MS = 1_200

        /**
         * 整个 DoH 阶段（遍历全部端点 × 记录类型）的总预算。
         * 单请求超时只约束一次往返，挡不住「4 个端点全部吃满」的累加；而 DoH 是救济手段，
         * 它花掉的每一秒都直接加在用户等待上。超预算即放弃剩余端点，由上层按 DnsFailure 收场。
         */
        private const val DOH_TOTAL_BUDGET_MS = 3_000L
        /**
         * 单步系统解析的硬上限。netd 的重试策略下 getAllByName 最坏能等几十秒，
         * 而排队是无声的——宁可这一路快速判负、让互援/DoH 接手，也不要把线程按住。
         */
        private const val DNS_STEP_TIMEOUT_MS = 1_500L

        /**
         * Preserve the historical 400ms prefetch threshold (1.27 measured DNS p90).
         * Prefetch does not replace a viable primary; only failure/expiry makes it eligible.
         */
        private const val DNS_HEDGE_DELAY_MS = 400L

        /** 成功解析耗时的分桶上界（ms）。最后一桶是 [DNS_OK_BUCKET_UPPER_MS.last, 超时) */
        private val DNS_OK_BUCKET_UPPER_MS = longArrayOf(20, 50, 100, 200, 400, 800, 1_200)
        /**
         * 同时在途系统查询上限；所有互援支路也计入。DoH 独立限制实际阻塞工作数。
         */
        private const val DNS_MAX_INFLIGHT = 12
                /** 上游建连有界池线程数：connect 是短时阻塞操作，96 足以支撑首屏几十域名并发建连且硬限线程。 */
        private const val CONNECT_THREADS = 96
        /** 单域名 Happy Eyeballs 并行尝试的地址数上限（IPv4 优先取前 N，按族配额见 [capByFamily]）。 */
        private const val MAX_HE_CANDIDATES = 6

        /**
         * 截断时给**次要地址族**保底的名额数。RFC 8305 §3.1 只要求「至少一个」，这里就给一个：
         * 目标是**不灭族**（IPv4 全挂时 IPv6 还在池子里），不是「让 IPv6 更早起跑」——
         * 后者要改的是 [orderAddresses] 的排序策略，属于另一个决策，别混在截断里顺手做掉。
         * 调大它会等量挤占 IPv4 名额，而 IPv4 的并行数正是 DNS 污染救援的本钱。
         */
        private const val MIN_MINORITY_SLOTS = 1

        private const val DOH_PROVIDER_STAGGER_MS = 250L
        private val DOH_WORKERS = BoundedBlockingExecutor(4, "hxmy-doh", queueCapacity = 32)

        /**
         * 默认建连调度器：独立 daemon 池，隔离阻塞 connect。core=max=[CONNECT_THREADS] + 无界队列 →
         * 线程数**硬顶** CONNECT_THREADS（超出排队而非扩张），即便首屏几十域名同时建连也不无界增长；
         * allowCoreThreadTimeOut + 30s keepAlive → 空闲后线程回收到 0，不在停止共享后驻留（不堆线程）。
         * connect 必须保持阻塞 socket 以支持 [Network.socketFactory] 出口分流，故无法走非阻塞 NIO。
         */
        internal val DEFAULT_CONNECT_DISPATCHER: CoroutineDispatcher =
            ThreadPoolExecutor(
                CONNECT_THREADS, CONNECT_THREADS, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(),
            ) { r -> Thread({ bumpToForegroundPriority(); r.run() }, "hxmy-connect").apply { isDaemon = true } }
                .apply { allowCoreThreadTimeOut(true) }
                .asCoroutineDispatcher()
    }
}
