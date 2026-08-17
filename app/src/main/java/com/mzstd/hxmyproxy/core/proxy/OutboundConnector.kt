package com.mzstd.hxmyproxy.core.proxy

import android.net.Network
import com.mzstd.hxmyproxy.core.security.EgressGuard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.FileDescriptor
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
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 创建到目标的上游 TCP 连接。
 *
 * **D4 不变量**：不绑定任何 `Network`、不设本地地址 → 跟随系统默认网络（含系统 VPN）；
 * 禁止 `bindProcessToNetwork`。远程 DNS 在本机解析（随 VPN）。目标经 [EgressGuard] 反 SSRF 过滤。
 *
 * **Happy Eyeballs（RFC 8305）**：解析出全部地址后**交错并行**连接——起第一个地址，等 250ms 仍未成功
 * （或当前尝试已失败）就并行起下一个，首个成功者胜出、其余立即中止。IPv4 优先（本网络 IPv6 常不可达）。
 * 相比"逐个回退"，双栈/多 anycast 站点（如 Cloudflare/Stripe）首地址慢或不可达时不再干等满超时，显著降低尾延迟。
 */
private const val TAG = "hxmyproxy"

class OutboundConnector(
    private val egressGuard: EgressGuard,
    // DNS 解析专用调度器：独立 daemon 池，与 relay/accept/connect 池隔离——
    // relay 搬字节占满线程时，DNS 仍能在自己的池里解析，不被掐住（Stripe 首屏几十域名是重灾区）。
    private val dnsDispatcher: CoroutineDispatcher = DEFAULT_DNS_DISPATCHER,
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

    /**
     * 同 key 正在进行的解析（单飞/去重）。没有它，浏览器对一个域名开的 6~8 条并发连接
     * 会变成 6~8 个各自阻塞一条 [dnsDispatcher] 线程的独立解析任务——池只有
     * [DNS_THREADS] 条，一个页面几十个域名就能把它填满，而排队是**无声的**
     * （无日志、无指标、队列无界），表现就是「CONNECT 都收下了、上游一条都没建、CPU 0%」。
     */
    private val inflightDns = ConcurrentHashMap<DnsKey, CompletableDeferred<List<InetAddress>>>()

    /**
     * DNS 并发闸门。[dnsDispatcher] 的队列是无界的，光靠线程数挡不住排队——
     * 用信号量在**入口**限流，满了就快速判负（见 dnsStep），把「无声排队」变成「明确失败 + 计数」。
     */
    private val dnsSlots = java.util.concurrent.Semaphore(DNS_MAX_INFLIGHT)
    private val dnsRejected = java.util.concurrent.atomic.AtomicLong()
    private val dnsTimedOut = java.util.concurrent.atomic.AtomicLong()

    // 成功解析的耗时观测（0806 补）。见 [recordDnsOk] 的注释：没有这份分布，
    // DNS_STEP_TIMEOUT_MS 该定多少完全是猜。
    private val dnsOkCount = java.util.concurrent.atomic.AtomicLong()
    private val dnsOkTotalMs = java.util.concurrent.atomic.AtomicLong()
    private val dnsOkMaxMs = java.util.concurrent.atomic.AtomicLong()
    private val dnsOkBuckets = java.util.concurrent.atomic.AtomicLongArray(DNS_OK_BUCKET_UPPER_MS.size + 1)
    /** 并行解析中「救援路先返回并被采用」的次数 */
    private val dnsParallelWins = java.util.concurrent.atomic.AtomicLong()
    /** 派发排队耗时（进入 dnsStep 到 block 真正开始执行）。与 rtt 分开量，见 dnsStep 内注释。 */
    private val dnsQueueCount = java.util.concurrent.atomic.AtomicLong()
    private val dnsQueueTotalMs = java.util.concurrent.atomic.AtomicLong()
    private val dnsQueueMaxMs = java.util.concurrent.atomic.AtomicLong()
    /** DoH 兜底调用次数，以及其中**超出声明预算**的次数。见 resolveLastResort 内注释。 */
    private val dohCalls = java.util.concurrent.atomic.AtomicLong()
    private val dohOverBudget = java.util.concurrent.atomic.AtomicLong()

    /** 缓存/单飞的键：netId 为 0 表示进程默认网络。 */
    private data class DnsKey(val netId: Long, val host: String)

    private fun dnsKeyOf(network: Network?, host: String) =
        DnsKey(network?.networkHandle ?: 0L, host)

    /** 备用 DNS（DoH）开关；由设置层经 applyTunables 推入。 */
    @Volatile var backupDnsEnabled: Boolean = true

    /**
     * 指定出口连不通时的策略；由设置层推入。默认 [EgressFallback.STRICT]。
     *
     * 见 [EgressFallback] 的注释：降级保住的是「这次能用」，赔上的是**出口身份**，
     * 而后者的代价（风控封禁）不可逆。这里只影响**连接路径**，
     * DNS 的换网救援不受影响——解析只是拿地址，不涉及出口身份。
     */
    @Volatile var egressFallback: com.mzstd.hxmyproxy.core.model.EgressFallback =
        com.mzstd.hxmyproxy.core.model.EgressFallback.STRICT

    /** 网络变化时由编排层调用：旧网络下解析的 IP 可能已不可达，清掉让 TTL 内的条目立即失效。 */
    fun clearDnsCache() {
        dnsCache.clear()
        // 出口健康判定同样按当时的网络成立，切网后必须重新判断。
        egressHealth.reset()
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
     * 就 **fail-closed** 抛 [ProxyError.AccessDenied]，**绝不回落默认路由**——因为 egress=VPN 或 always-on/lockdown
     * VPN 下「默认路由」正是那条 VPN，回落等于把本要绕开 VPN 的直连流量又送进 VPN(豆包「国家不符合」根因)。
     * 非 bypass(PROXY)：走用户选定出口(AUTO=null=系统默认)。current() 内部已按 WiFi→以太网→蜂窝 兜底。
     */
    private fun egressNetworkFor(bypassVpn: Boolean, host: String): Network? {
        if (!bypassVpn) return underlyingNetworkProvider?.egressNetwork()
        return underlyingNetworkProvider?.current() ?: run {
            throttledFileLog("direct-noeg:$host",
                "DIRECT $host: no physical network (VPN only), fail-closed - refusing to leak via VPN egress")
            // stage 标成 no-physical-egress：这是**全局**状况（一张物理网都没有），与 host 无关。
            // 调用方据此把它记进全局态而不是 per-host 账本 —— 0814 那 96 次涉及 20+ 个域名，
            // 逐 host 记会把一个根因显示成二十几个「坏域名」，且 per-host 的清除要等各自下次成功。
            throw ProxyException(ProxyError.AccessDenied, "no-physical-egress")
        }
    }

    /**
     * 出口分流失败后降级默认路由的重试包装：**结局必须落盘**。
     *
     * 此前这次重试没有 try/catch，成功与失败在日志里**完全同形**（只看得到降级前那条 `egress fail`），
     * 而两者对用户天差地别：成功 = 多等一个 [ProxyTuning.CONNECT_TIMEOUT_MS]；失败 = 等两倍超时后仍然失败。
     * 这个缺口曾让人凭「`upstream fail = 0`」误判成「降级都成功了」——那是没有根据的，
     * 最终结局根本没被记录过。带上耗时才能回答「那次多等买到了什么」。
     *
     * 节流 key 与降级前那条刻意不同：同 key 在 [LOG_THROTTLE_MS] 内只记一条，
     * 复用会让结局被降级前的日志顶掉，等于没修。
     */
    private suspend fun <T> degradeToDefault(host: String, original: ProxyError, block: suspend () -> T): T {
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
        return try {
            network = egressNetworkFor(bypassVpn, host)
            if (egressUnusable(network, bypassVpn)) {
                throttledFileLog("egress-down:$host", "egress marked down, skipping wait - $host")
                if (egressFallback == com.mzstd.hxmyproxy.core.model.EgressFallback.STRICT) {
                    // **不是 RemoteUnreachable**：那说的是「那个目标连不上」，而这里是
                    // 「我们自己把这条出口摘了，请求根本没发出去」。共用一个码时，日志里
                    // 分不开「我们主动拒的」和「站点真挂了」——这才是分开它的理由。
                    throw ProxyException(
                        ProxyError.EgressSidelined, "egress-down",
                        retryAfterSeconds = egressHealth.retryAfterSeconds(network),
                    )
                }
                return connectAny(orderAddresses(resolve(host, null)), port, null)
                    .also { reportEgress(onEgress, null) }
            }
            connectAny(orderAddresses(resolve(host, network, trace)), port, network)
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
            if (network == null) {
                throttledFileLog("connect:$host", "upstream fail $host (default egress): ${e.error}")
                throw e
            }
            // 记一笔到出口健康账上：同一张网在 60s 内有 4 个**不同域名**连不上，
            // 才值得怀疑是「整张网坏了」而非「这个站点坏了」（阈值依据见 EgressHealth）。
            if (egressHealth.recordFailure(network, host)) egressHealth.confirmOrSideline(network)

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
                throw ProxyException(e.error, "connect-strict")
            }
            throttledFileLog("egress:$host", "egress fail $host: ${e.error} — degrading to default")
            degradeToDefault(host, e.error) {
                connectAny(orderAddresses(resolve(host, null, trace)), port, null)
                    .also {
                        trace?.connected(host, it.inetAddress?.hostAddress, port, null)
                        reportEgress(onEgress, null)
                    }
            }
        }
    }

    /**
     * 解析域名为全部地址；解析跑在独立 [dnsDispatcher]。
     * [network] 非空（出口分流）时在该网络上解析（避免 DNS 走 VPN），且不缓存（量小、避免与默认网络结果混淆）；
     * 为空时走默认网络解析 + 短 TTL 缓存。
     * **双路互援**：一条路解析失败即换另一条路重试（换 netId 也天然绕开系统 2s 负缓存）——
     * DIRECT 失败→默认网络；默认失败→底层 WiFi。失败与援通均节流落盘（诊断「究竟哪条 DNS 在坏」）。
     */
    private suspend fun resolve(host: String, network: Network?, trace: RequestTrace? = null): List<InetAddress> {
        // **数字字面量直接构造，不进解析器。** 此前它们被原样丢给 getAllByName，而那会真的走一遍
        // netd：0814 实测 `host=2403:300:1366::2:4` 花了 **4924ms**「解析」一个已经是地址的字符串
        // （5 个字面量首次 4924/3473/3215/2941/1482ms，第二轮命中缓存才 0-7ms）。
        // 用户侧表现为「某些客户端首次连接卡 5 秒后报错」。
        numericAddressOrNull(host)?.let {
            trace?.dns(host, DnsSource.LITERAL, network?.networkHandle, 1, "literal", it.hostAddress)
            return listOf(it)
        }
        val key = dnsKeyOf(network, host)
        cachedAt(key)?.let { (addrs, atMs) ->
            trace?.dns(
                host, DnsSource.CACHE_SAME_NET, network?.networkHandle, addrs.size,
                "age=${(System.currentTimeMillis() - atMs) / 1000}s", addrs.firstOrNull()?.hostAddress,
            )
            return addrs
        }
        // 单飞：同 (netId, host) 已有解析在跑就等它的结果，不再起第二次。
        inflightDns[key]?.let {
            val r = it.await()
            trace?.dns(host, DnsSource.INFLIGHT, network?.networkHandle, r.size, null, r.firstOrNull()?.hostAddress)
            return r
        }
        val mine = CompletableDeferred<List<InetAddress>>()
        inflightDns.putIfAbsent(key, mine)?.let {
            val r = it.await()
            trace?.dns(host, DnsSource.INFLIGHT, network?.networkHandle, r.size, null, r.firstOrNull()?.hostAddress)
            return r
        }
        return try {
            val addrs = resolveUncached(host, network, trace)
            dnsCache[key] = CachedAddrs(addrs, System.currentTimeMillis())
            mine.complete(addrs)
            addrs
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inflightDns.remove(key, mine)
        }
    }

    /**
     * 真正去解析（不含缓存与单飞）。**每一步系统解析都带硬 deadline** ——
     * `getAllByName` 是阻塞调用且底层交给 netd 执行，netd 的重试策略下最坏能等几十秒，
     * 而此前这条路径**全程没有任何超时**：一条慢域名就能把 [dnsDispatcher] 的线程按住，
     * 后续解析在无界队列里静默排队，直到客户端自己放弃。
     * 用 [runInterruptible] 而不是 [withContext]，超时才能真正中断那条阻塞的线程、把它还给池子。
     */
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

    /** 同 TTL 判据，连同写入时刻一起返回——溯源需要缓存年龄。 */
    private fun cachedAt(key: DnsKey): Pair<List<InetAddress>, Long>? {
        val c = dnsCache[key] ?: return null
        if (System.currentTimeMillis() - c.atMs >= DNS_TTL_MS) return null
        return c.addrs to c.atMs
    }

    private suspend fun resolveUncached(host: String, network: Network?, trace: RequestTrace? = null): List<InetAddress> {
        if (network != null) {
            val t0 = System.nanoTime()
            val raced = resolveOnEgressRacing(host, network)
            if (raced != null && raced.addrs.isNotEmpty()) {
                // 来源由抢答自己报（见 RaceResult），不再一律标成 SYS_EGRESS。
                trace?.dns(
                    host, raced.src, network.networkHandle, raced.addrs.size,
                    "rtt=${(System.nanoTime() - t0) / 1_000_000}ms", raced.addrs.firstOrNull()?.hostAddress,
                )
                return raced.addrs
            }
            // **不再在这里重跑 rescueOffEgress**：走到这一行说明抢答的两条腿都已返回空
            // （select 的两个分支都会先 await 对方），而对冲腿本身就是 rescueOffEgress。
            // 原来的重跑等于在 netd 负缓存还热着的时候，把两个阻塞 getAllByName 原样再发一遍 ——
            // 恰好发生在 DNS 最紧张的时刻，正是它把建连预算吃光的。
            throttledFileLog("dns-direct:$host", "DNS fail/timeout $host on egress network; both legs empty")
            // **跨网旧缓存兜底已移除。**
            //
            // 它的设计意图是「用一个 TTL 内、别的网络上解析成功过的地址，好过直接失败」，
            // 但那假设了地址与网络无关 —— 对 anycast 服务恰恰不成立:把 A 网解析出的地址
            // 拿到 B 网上去连，目标节点可能与出口完全不匹配。
            //
            // 移除依据是**两轮实测都为 0 次命中**:0814(1.30.0)0 次、0815 两台设备各跑一天仍是 0 次。
            // 关键在第二轮 —— 1.31.0 已经删掉了抢答之后的那次重复 rescueOffEgress，
            // 让互援腿更容易快速判负，按理到达这一级的频率**应该上升**，实测没有。
            // 「它只是这周没通电的保险丝」这个顾虑因此被数据否定，可以拆。
            //
            // 前置漏斗本身也极窄:要同时满足「出口解析失败」+「互援两条腿都空」，
            // 而这两级合起来一天只发生个位数次，其结局与移除后完全一致(都是 DnsFailure)。
            return resolveLastResort(host, UnknownHostException("egress resolve failed/timeout for $host"))
        }
        val t0d = System.nanoTime()
        val addrs = try {
            (dnsStep { InetAddress.getAllByName(host).toList() }
                ?: throw UnknownHostException("default resolve timed out for $host")).also {
                trace?.dns(
                    host, DnsSource.SYS_DEFAULT, null, it.size,
                    "rtt=${(System.nanoTime() - t0d) / 1_000_000}ms", it.firstOrNull()?.hostAddress,
                )
            }
        } catch (e: UnknownHostException) {
            throttledFileLog("dns-default:$host", "DNS fail $host on default network (${e.message}); retry underlying+DoH")
            // 主路失败（境外域名常被运营商 DNS 污染成 NXDOMAIN）→ 互援与 DoH **并行**，合并全部 IP 进
            // Happy Eyeballs 竞速池：互援可能返回污染死 IP（连不上→CONNECT_TIMEOUT 超时），DoH（8.8.8.8/
            // 1.1.1.1）返回未污染正确 IP；连接层竞速让能连上的先赢。修旧「互援解析成功即用污染 IP、
            // 干等连接超时」的性能坑（DoH 原是互援失败后才走的「最后一搏」，互援返回污染 IP 就轮不到它）。
            val alt = underlyingNetworkProvider?.current()
            val merged = coroutineScope {
                val altD = async(dnsDispatcher) {
                    if (alt == null) emptyList()
                    else runCatching { alt.getAllByName(host).toList() }.getOrDefault(emptyList())
                }
                val dohD = async(dnsDispatcher) {
                    // 这条并行支同样要守预算:它与 altD 并跑，跑过头会把整个 merged 拖住。
                    if (backupDnsEnabled) {
                        val dl = System.nanoTime() + DOH_TOTAL_BUDGET_MS * 1_000_000L
                        runCatching { dohResolve(host, dl) }.getOrDefault(emptyList())
                    } else emptyList()
                }
                val a = altD.await()
                val d = dohD.await()
                if (a.isNotEmpty()) throttledFileLog("dns-default-rescued:$host", "DNS rescued $host via underlying network")
                if (d.isNotEmpty()) throttledFileLog("doh-rescued:$host", "DNS rescued $host via DoH backup")
                val merged0 = (a + d).distinct()
                trace?.dns(
                    host,
                    if (a.isNotEmpty()) DnsSource.RESCUE_PHYSICAL else DnsSource.DOH,
                    null, merged0.size, if (d.isNotEmpty()) "doh=used" else null,
                    merged0.firstOrNull()?.hostAddress,
                )
                merged0
            }
            if (merged.isEmpty()) {
                throttledFileLog("doh-fail:$host", "both underlying and DoH failed for $host")
                throw ProxyException(ProxyError.DnsFailure)
            }
            merged
        }
        return addrs   // 缓存写入统一由 resolve() 做（带 netId 的键）
    }

    /**
     * 一步系统解析,带**硬 deadline**。超时返回 null（调用方按「这一路没成」继续）。
     *
     * 用 [runInterruptible] 而不是 [withContext]：`getAllByName` 是阻塞调用，
     * `withTimeout` 只能在挂起点取消协程、**不会中断已经阻塞住的线程**——
     * 那样调用方虽然不等了，线程却还被按在那里，池子照样会被填满。
     * runInterruptible 在取消时对该线程发 interrupt，让它有机会把线程还回池子。
     */
    private suspend fun <T> dnsStep(block: () -> T): T? {
        // 池满时**快速判负**，不进队列。此前 dnsDispatcher 是「固定 16 线程 + 无界队列」，
        // 排队完全无声：没有日志、没有指标、没有上限，第 17 个解析请求就那样静静地等着，
        // 表现出来就是「CONNECT 都收下了、上游一条都没建、CPU 0%」。
        // 快速判负让调用方立刻去走互援/DoH/缓存兜底，而不是把用户吊在那里。
        if (!dnsSlots.tryAcquire()) {
            val n = dnsRejected.incrementAndGet()
            throttledFileLog("dns-busy", "DNS inflight full ($DNS_MAX_INFLIGHT), failing fast instead of queuing; $n total")
            return null
        }
        val t0 = System.nanoTime()
        try {
            // **queueMs 与 rtt 必须分开量。** 拿到 slot 不等于线程可用:dnsDispatcher 是固定线程池，
            // 派发排队发生在 runInterruptible 进入 block 之前，而现有的 rtt 把排队和真实解析算在一起。
            // 0814 的判据性证据:同一个 IPv6 字面量首次「解析」4923ms、十分钟后 0-3ms ——
            // 字面量根本不需要查询，那 4.9 秒只可能是派发排队。
            // 这个字段决定批次 2 走哪条路:排队为主 ⇒ 该拆独立线程池;真解析慢 ⇒ 该套闸门与 deadline。
            var queueMs = -1L
            val r = withTimeoutOrNull(DNS_STEP_TIMEOUT_MS) {
                runInterruptible(dnsDispatcher) {
                    queueMs = (System.nanoTime() - t0) / 1_000_000L
                    block()
                }
            }
            if (r == null) {
                val n = dnsTimedOut.incrementAndGet()
                throttledFileLog(
                    "dns-timeout",
                    "DNS step timeout (${DNS_STEP_TIMEOUT_MS}ms); $n total; queueMs=$queueMs",
                )
            } else {
                recordDnsOk((System.nanoTime() - t0) / 1_000_000L)
                recordDnsQueue(queueMs)
            }
            return r
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

    private fun recordDnsQueue(ms: Long) {
        if (ms < 0) return
        dnsQueueCount.incrementAndGet()
        dnsQueueTotalMs.addAndGet(ms)
        while (true) {
            val cur = dnsQueueMaxMs.get()
            if (ms <= cur || dnsQueueMaxMs.compareAndSet(cur, ms)) break
        }
    }

    /** 从分桶估分位（取桶上界，偏保守——报出来的值不会低于真实分位）。 */
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
        /** 并行抢答里「救援路先返回」的次数——衡量并行是否真的省到了时间 */
        val parallelWins: Long,
        /** 派发排队：均值 / 最大，单位 ms。**与 okAvgMs 分开看**——后者含排队。 */
        val queueAvgMs: Long,
        val queueMaxMs: Long,
        /** DoH 兜底:调用次数 / 其中超出声明预算的次数。 */
        val dohCalls: Long,
        val dohOverBudget: Long,
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
            parallelWins = dnsParallelWins.get(),
            queueAvgMs = dnsQueueCount.get().let { if (it == 0L) 0 else dnsQueueTotalMs.get() / it },
            queueMaxMs = dnsQueueMaxMs.get(),
            dohCalls = dohCalls.get(),
            dohOverBudget = dohOverBudget.get(),
        )
    }

    /**
     * 出口网解析 —— **带延迟对冲(hedging)的并行抢答**，而不是「等满超时再救」。
     *
     * 0806 真机日志（1.25.0，32.4 小时）暴露的浪费：
     * 出口网解析失败 239 次、其中 **102 次是整整等满 1500ms 的真超时**，
     * 而失败后走互援救回来的耗时中位只有 **510ms**。也就是说每次都先干等 1.5 秒，
     * 再花半秒把它救回来——那 1.5 秒是纯浪费，用户感知就是「某些站点偶尔卡一下」。
     * 失败域名高度集中（captive.apple.com 18、docs.qq.com 13、腾讯遥测、keplr 的一堆 RPC），
     * 说明这不是随机抖动，是**特定域名在出口网上稳定解析不出来**。
     *
     * 做法（RFC 8305 的对冲思路用在 DNS 上）：
     *  1. 先只发出口网这一路——**正常情况下不产生任何额外查询**；
     *  2. 只有它超过 [DNS_HEDGE_DELAY_MS] 还没回，才补发互援那一路；
     *  3. 谁先拿到非空结果就用谁，另一路取消。
     *
     * 为什么要 head start 而不是无脑双发：双发会让 DNS 查询量翻倍，
     * 而绝大多数解析是快的（命中 netd 缓存时是亚毫秒级）。对冲只在慢的那部分付出代价。
     *
     * **[DNS_HEDGE_DELAY_MS] 的取值目前是保守估计**，等 [recordDnsOk] 的
     * `dnsok=` 心跳积累出成功解析的真实分布后再校准——把它定在 p90 附近最合理：
     * 正常解析几乎都不会触发对冲，慢的那批则不必等满 1500ms。
     */
    private suspend fun resolveOnEgressRacing(host: String, egress: Network): RaceResult? = coroutineScope {
        val egressD = async(dnsDispatcher) {
            runCatching { dnsStep { egress.getAllByName(host).toList() } }.getOrNull()
        }
        val hedgeD = async(dnsDispatcher) {
            delay(DNS_HEDGE_DELAY_MS)          // 出口路够快就永远走不到这里（协程被 cancel）
            rescueOffEgress(host, egress)
        }
        try {
            select {
                egressD.onAwait { r ->
                    if (!r.isNullOrEmpty()) RaceResult(r, DnsSource.SYS_EGRESS)   // 出口路赢：常态
                    else hedgeD.await().takeIf { it.isNotEmpty() }
                        ?.let { RaceResult(it, DnsSource.HEDGE_RESCUE) }          // 出口路快速失败：等对冲
                }
                hedgeD.onAwait { r ->
                    if (r.isNotEmpty()) {
                        dnsParallelWins.incrementAndGet()
                        throttledFileLog(
                            "dns-hedge:$host",
                            "DNS hedge win: $host slow on egress, using rescue result (saved up to " +
                                "${DNS_STEP_TIMEOUT_MS - DNS_HEDGE_DELAY_MS}ms)",
                        )
                        RaceResult(r, DnsSource.HEDGE_RESCUE)
                    } else {
                        // 对冲也没救出来：回到出口路的结局
                        egressD.await()?.takeIf { it.isNotEmpty() }?.let { RaceResult(it, DnsSource.SYS_EGRESS) }
                    }
                }
            }
        } finally {
            egressD.cancel()
            hedgeD.cancel()
        }
    }

    /**
     * 抢答结果 **连同它是哪条腿赢的**。
     *
     * 为什么必须让来源随结果一起返回:此前 [resolveUncached] 把结果一律标成
     * [DnsSource.SYS_EGRESS]（注释还写着「据 dnsParallelWins 区分来源」，代码根本没读），
     * 于是 0814 日志里 `hedge` / `rescue-*` 全部为 0，而同期明文行显示
     * `DNS hedge win` 188 条、`DNS rescued` 582 条 —— **仪表在撒谎**，
     * 「这次的地址是从哪张网问来的」这个问题在最需要它的场景下反而答不出。
     *
     * 也不能靠「调用前后读 dnsParallelWins 的差值」来猜:它是全局 [AtomicLong]，
     * 任何并发请求的抢答都会让差值 >0，而 hedge 恰恰是成簇发生的（实测 9ms 内 4 条）。
     */
    private class RaceResult(val addrs: List<InetAddress>, val src: DnsSource)

    /**
     * 出口网（egress）解析失败后的互援：**进程默认网络与物理网并行**取回地址。
     *
     * 修的是一个 0803 实证的缺陷。原实现只退到「进程默认网络」，而 `egress=VPN` 时
     * **那就是同一条 VPN**（本 app 的 uid 落在系统 VPN 的 uidrange 内，已用 dumpsys + ip rule 实证），
     * 于是所谓「双路互援」两路同源、必然一起失败；连第三路 DoH 也走默认网络出去，同样死在那条 VPN 上。
     * 真正的物理网 [com.mzstd.hxmyproxy.core.network.UnderlyingNetworkProvider.current] 在这个分支里
     * **一次都没被用到**——函数注释写着「默认失败→底层 WiFi」，但那描述的是 `network == null` 那个分支。
     *
     * 日志自证：同一个 `accounts.google.com`，00:08:02 在本分支三路全败；VPN 句柄消失、
     * 走到 `network == null` 分支后，00:08:30 被物理网**一次救回**。
     *
     * **能救回什么必须说清楚，别夸大**：物理网解析只对**未被墙**的域名有效
     * （实测 `gateway.icloud.com` / `ocsp2.apple.com` 拿到国内 CDN 节点、20~70ms 连通），
     * 对**被墙**域名无效（拿到的是污染地址，实测 `accounts.google.com` 连都连不上）。
     * 0803 那次三路全败的 6 个域名里，这条能救回 3 个。
     *
     * 顺序上默认网络在前、物理网在后：前者与出口同源、地址更贴合出口路由；后者是救援品，
     * 排后面可减少它在 [capByFamily] 截断时挤掉出口侧地址的概率。
     */
    private suspend fun rescueOffEgress(host: String, egress: Network): List<InetAddress> {
        // 物理网与出口网是同一张时不重复试（如 egress=WIFI 且 direct=WIFI）。
        val phy = underlyingNetworkProvider?.current()?.takeIf { it != egress }
        return coroutineScope {
            val defD = async(dnsDispatcher) { resolveOnOrEmpty(null, host) }
            val phyD = async(dnsDispatcher) { if (phy == null) emptyList() else resolveOnOrEmpty(phy, host) }
            val d = defD.await()
            val p = phyD.await()
            if (d.isNotEmpty()) throttledFileLog("dns-egress-def:$host", "DNS rescued $host via default network")
            if (p.isNotEmpty()) throttledFileLog("dns-egress-phy:$host", "DNS rescued $host via underlying network")
            (d + p).distinct()
        }
    }

    /**
     * 互援用的单路解析：解析不出返回空（那是互援的正常结局）。
     * **非解析类异常不静默吞掉**——它意味着救援机制本身坏了（句柄失效/权限），
     * 而不是「这个域名解析不出来」，两者混为一谈会让这条路径变成又一个黑箱。
     */
    private fun resolveOnOrEmpty(net: Network?, host: String): List<InetAddress> =
        try {
            if (net == null) InetAddress.getAllByName(host).toList() else net.getAllByName(host).toList()
        } catch (e: UnknownHostException) {
            emptyList()
        } catch (e: Exception) {
            throttledFileLog(
                "dns-rescue-err:$host",
                "DNS rescue on ${if (net == null) "default" else "underlying"} threw ${e.javaClass.simpleName}: ${e.message}",
            )
            emptyList()
        }


    /**
     * 最后一搏：系统解析双路全败后走 DoH 备援（关着或也失败则抛 [ProxyError.DnsFailure]）。
     * DoH 成功即「备用 DNS」救场——网络自身 DNS 坏而链路仍通的场景（用户实证痛点）。
     */
    private suspend fun resolveLastResort(host: String, cause: UnknownHostException): List<InetAddress> {
        throttledFileLog("dns-both:$host", "DNS fail $host on system paths (${cause.message})")
        if (backupDnsEnabled) {
            // 总预算封顶：单请求超时管不住 4 个端点的累加（见 DOH_TOTAL_BUDGET_MS）。
            // runInterruptible 而非 withContext——超时要能真正中断那条阻塞在 socket 上的线程。
            val t0 = System.nanoTime()
            // deadline 由内层自己守（见 dohResolve 里的说明）——外层这层 withTimeoutOrNull
            // 对阻塞的 HttpsURLConnection 无效，保留它只是兜住「内层逻辑本身出错」的极端情形。
            val deadlineNs = t0 + DOH_TOTAL_BUDGET_MS * 1_000_000L
            val doh = withTimeoutOrNull(DOH_TOTAL_BUDGET_MS * 2) {
                runInterruptible(dnsDispatcher) { dohResolve(host, deadlineNs) }
            } ?: emptyList()
            // **实测耗时必须落一次。** 声明的预算是 3000ms，而 0814 实测 5411/5568/5856ms ——
            // 三次全部超支近一倍。最可能的解释是 runInterruptible 对 HttpsURLConnection 的
            // 阻塞 SSL 读发 interrupt 是空操作（只有 InterruptibleChannel 响应），于是
            // withTimeoutOrNull 虽然不等了、4 端点 × 2 记录类型照样跑完。
            // 刻意**不做每端点一行**:DoH 触发时恰恰是 DNS 已经出问题的时刻，
            // 8 次查询 × 前后各一行 = 16 行/次，会在最糟的时刻把同期证据挤出滚动窗口。
            // 一行汇总 + over 标记，足以判断预算是否真的兜住。
            val dohMs = (System.nanoTime() - t0) / 1_000_000L
            dohCalls.incrementAndGet()
            if (dohMs > DOH_TOTAL_BUDGET_MS) dohOverBudget.incrementAndGet()
            if (doh.isNotEmpty()) {
                throttledFileLog("doh-rescued:$host", "DNS rescued $host via DoH backup (${dohMs}ms)")
                return doh
            }
            throttledFileLog(
                "doh-fail:$host",
                "DoH backup also failed for $host (${dohMs}ms" +
                    (if (dohMs > DOH_TOTAL_BUDGET_MS) ", OVER budget ${DOH_TOTAL_BUDGET_MS}ms" else "") + ")",
            )
        }
        throw ProxyException(ProxyError.DnsFailure)
    }

    /**
     * DoH 兜底解析（JSON API、**IP 直连端点**免 bootstrap 自举）：依次试 Google/Cloudflare，A 记录优先、
     * 空则查 AAAA。请求走系统默认网络（与代理出站同路径——出站能通则 DoH 基本能通，故障相关性一致；
     * 若默认路径整体断链，DoH 与业务同死，不做无谓挣扎）。加密 443 出去，不与 Private DNS 的
     * 「禁发明文 53」冲突。阻塞实现，调用方置于 [dnsDispatcher]。
     */
    private fun dohResolve(host: String, deadlineNs: Long): List<InetAddress> {
        for ((base, accept) in DOH_ENDPOINTS) {
            for (type in intArrayOf(1, 28)) {           // 1=A, 28=AAAA
                // **预算必须在这里守，外层的 withTimeoutOrNull 守不住。**
                //
                // 外层是 `withTimeoutOrNull(3000) { runInterruptible(dnsDispatcher) { dohResolve(...) } }`。
                // runInterruptible 取消时对线程发 interrupt，但 HttpsURLConnection 的阻塞 socket 读
                // **不响应 interrupt**(只有 InterruptibleChannel 响应)，于是这 8 次请求会照样跑完，
                // 而 runInterruptible 要等 block 真正返回才结束 —— 外层那个 3000ms 形同虚设。
                //
                // 实证:0814 三次调用实测 5411 / 5568 / 5856ms;0815 两台设备 **100% 超预算**
                // (doh:7/7、doh:8/8、doh:1/1)。最坏情况是 4 端点 × 2 记录类型 × 1200ms = 9.6 秒，
                // 而这 9.6 秒恰好发生在 DNS 已经出问题、用户正在等的时刻。
                //
                // 改成协作式:每次请求前检查剩余预算，并用它压单次超时。这样总耗时严格受控，
                // 且不需要任何中断机制配合。
                val remainMs = (deadlineNs - System.nanoTime()) / 1_000_000L
                if (remainMs <= 0) return emptyList()
                val stepMs = minOf(DOH_TIMEOUT_MS.toLong(), remainMs).toInt()
                try {
                    val url = java.net.URL("$base?name=${java.net.URLEncoder.encode(host, "UTF-8")}&type=$type")
                    // **绑到用户选定的那张网**（默认跟随「直连出口」）。此前用裸的 url.openConnection()，
                    // 跟随进程默认路由——egress=VPN 时那正是刚刚解析失败的那条 VPN，于是 DoH 与它要救的
                    // 业务同路同死（0803 实测救援成功率 6.5%、72 次失败）。dohNetwork() 返回 null 表示
                    // 用户选了「跟随默认路由」，保持旧行为。
                    val dohNet = underlyingNetworkProvider?.dohNetwork()
                    val conn = (dohNet?.openConnection(url) ?: url.openConnection())
                        as javax.net.ssl.HttpsURLConnection
                    // 用剩余预算压住单次超时:最后一个端点不能再花满 DOH_TIMEOUT_MS。
                    conn.connectTimeout = stepMs
                    conn.readTimeout = stepMs
                    if (accept != null) conn.setRequestProperty("Accept", accept)
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val answers = org.json.JSONObject(body).optJSONArray("Answer") ?: continue
                    val out = ArrayList<InetAddress>()
                    for (i in 0 until answers.length()) {
                        val a = answers.getJSONObject(i)
                        if (a.optInt("type") == type) {
                            // 数字字面量不触发系统 DNS 查询，不会递归回失败路径。
                            runCatching { out.add(InetAddress.getByName(a.getString("data"))) }
                        }
                    }
                    if (out.isNotEmpty()) return out
                } catch (_: Exception) {
                    // 单端点/单类型失败换下一个；全败返回空由调用方抛 DnsFailure。
                }
            }
        }
        return emptyList()
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
    ): Socket {
        // bypass 严格物理网、fail-closed(见 egressNetworkFor)；无 catch 即建连失败直接抛=不降级 VPN。
        val network = egressNetworkFor(bypassVpn, addr.hostAddress ?: "?")
        return connectAny(listOf(addr), port, network).also {
            trace?.connected(addr.hostAddress ?: "?", it.inetAddress?.hostAddress, port, network?.networkHandle)
            reportEgress(onEgress, network)
        }
    }

    /**
     * 同 [connect]，但产出已连接的（阻塞模式）[SocketChannel]，供非阻塞 relay 使用（调用方在进入 relay 前
     * 切 `configureBlocking(false)`）。[bypassVpn]=true 时用反射取 fd + `Network.bindSocket(fd)`（connect 前）
     * 做出口分流——Phase 0 spike 已验证（见 BindSocketSpikeTest）。反射取 fd 失败则抛 [IOException]，
     * 调用方应回退到阻塞 [connect] + 阻塞 relay。
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
        return try {
            network = egressNetworkFor(bypassVpn, host)
            if (egressUnusable(network, bypassVpn)) {
                throttledFileLog("egress-down:$host", "egress marked down, skipping wait - $host")
                if (egressFallback == com.mzstd.hxmyproxy.core.model.EgressFallback.STRICT) {
                    // **不是 RemoteUnreachable**：那说的是「那个目标连不上」，而这里是
                    // 「我们自己把这条出口摘了，请求根本没发出去」。共用一个码时，日志里
                    // 分不开「我们主动拒的」和「站点真挂了」——这才是分开它的理由。
                    throw ProxyException(
                        ProxyError.EgressSidelined, "egress-down",
                        retryAfterSeconds = egressHealth.retryAfterSeconds(network),
                    )
                }
                return connectAnyChannel(orderAddresses(resolve(host, null)), port, null)
                    .also { reportEgress(onEgress, null) }
            }
            connectAnyChannel(orderAddresses(resolve(host, network, trace)), port, network)
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
            // 让调用方走「反射不可用 → 回退阻塞路径」的既有逻辑。
            if (bypassVpn) {
                // 见 connect() 里同处注释：全局状况不进 per-host 账本。
                if (e.stage == "no-physical-egress") DirectEgressFailures.recordNoEgress()
                else DirectEgressFailures.recordFailure(host, e.error.code)
                throttledFileLog("direct:$host", "DIRECT egress fail $host: ${e.error} - fail-closed (no VPN fallback)")
                throw e
            }
            if (network == null) {
                throttledFileLog("connect:$host", "upstream fail $host (default egress): ${e.error}")
                throw e
            }
            if (egressHealth.recordFailure(network, host)) egressHealth.confirmOrSideline(network)

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
                throw ProxyException(e.error, "connect-strict")
            }
            throttledFileLog("egress:$host", "egress fail $host: ${e.error} — degrading to default")
            degradeToDefault(host, e.error) {
                connectAnyChannel(orderAddresses(resolve(host, null, trace)), port, null)
                    .also {
                        trace?.connected(
                            host, (it.remoteAddress as? InetSocketAddress)?.address?.hostAddress, port, null,
                        )
                        reportEgress(onEgress, null)
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
    ): SocketChannel {
        // bypass 严格物理网、fail-closed(见 egressNetworkFor)；无 catch 即建连失败直接抛=不降级 VPN。
        val network = egressNetworkFor(bypassVpn, addr.hostAddress ?: "?")
        return connectAnyChannel(listOf(addr), port, network).also {
            trace?.connected(
                addr.hostAddress ?: "?",
                (it.remoteAddress as? InetSocketAddress)?.address?.hostAddress,
                port, network?.networkHandle,
            )
            reportEgress(onEgress, network)
        }
    }

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
     * 出口分流（[network] 非空）靠反射取 fd + `network.bindSocket(fd)`（**必须 connect 之前**）；
     * 反射取 fd 失败抛 [IOException]，调用方回退阻塞路径。
     */
    private suspend fun connectAnyChannel(addrs: List<InetAddress>, port: Int, network: Network?): SocketChannel {
        // 出口分流（network 非空）的前提是反射取 fd 可用。**fail-fast**：不可用直接抛 IOException，让调用方回退
        // 阻塞 relay——否则反射失败会被 Happy Eyeballs 编排吞成 ProxyException，无法与「连接失败」区分。
        if (network != null && !ensureFdReflectionUsable()) {
            throw IOException("SocketChannel fd reflection unavailable, cannot bindSocket for egress split")
        }
        return connectAnyGeneric(
            addrs, port,
            create = {
                val ch = SocketChannel.open()
                ch.configureBlocking(true)
                if (network != null) {
                    val fd = fileDescriptorOf(ch)
                        ?: run { ch.closeQuietly(); throw IOException("failed to obtain SocketChannel fd, cannot bindSocket for egress split") }
                    network.bindSocket(fd)   // connect 之前绑定到非 VPN 网络
                }
                ch
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

    /** 反射取 SocketChannel fd 是否可用（探测一次并缓存；进程内不变）。 */
    @Volatile private var fdReflectionUsable: Boolean? = null
    private fun ensureFdReflectionUsable(): Boolean {
        fdReflectionUsable?.let { return it }
        val probe = SocketChannel.open()
        val ok = fileDescriptorOf(probe) != null
        probe.closeQuietly()
        fdReflectionUsable = ok
        // 落盘放在这个**一次性探测**里而不是调用方的 catch：后者每条走出口分流的连接都会命中，
        // 落盘会刷屏且需要节流。这里进程内只执行一次，信息还更准确（是能力探测结果，
        // 不是某条连接的偶发失败）。不可用意味着整机降级到阻塞 relay —— 性能特征完全变了，
        // 而此前这件事只写 logcat，release 下连 logcat 都没有。
        if (!ok) {
            com.mzstd.hxmyproxy.core.log.Ev.kw(
                com.mzstd.hxmyproxy.core.log.LogCat.EGRESS, "nio.fdReflect.unavailable",
                "impact" to "egress-split falls back to blocking relay",
            )
        }
        return ok
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
    ): S = coroutineScope {
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
                    if (!closed.get()) results.trySend(Outcome(null, mapConnectError(e)))
                }
            }
        }

        launchNext()
        var lastError: ProxyError = ProxyError.RemoteUnreachable
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
                    return@coroutineScope conn
                }
                outcome.error?.let { lastError = it }
                launchNext()  // 失败立即补起下一个（RFC 8305：不必等满间隔）
            }
            throw ProxyException(lastError)  // 地址用尽且无在途 → 全部失败
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
    }

    /**
     * 反射取 [SocketChannel] 底层 [FileDescriptor]（喂 `Network.bindSocket(fd)`）。
     * Phase 0 spike 实测：`socket().getFileDescriptor$()` 路径在目标 ROM 可用；`SocketChannelImpl.fd` 字段
     * 在部分 ROM 不存在，作兜底。取不到返回 null（调用方回退阻塞路径）。
     */
    private fun fileDescriptorOf(channel: SocketChannel): FileDescriptor? {
        runCatching {
            val sock = channel.socket()
            val m = sock.javaClass.getMethod("getFileDescriptor\$")
            (m.invoke(sock) as? FileDescriptor)?.let { return it }
        }
        runCatching {
            val f = Class.forName("sun.nio.ch.SocketChannelImpl").getDeclaredField("fd")
            f.isAccessible = true
            (f.get(channel) as? FileDescriptor)?.let { return it }
        }
        return null
    }

    private fun mapConnectError(e: Throwable): ProxyError = when (e) {
        is SocketTimeoutException -> ProxyError.RemoteTimeout
        is ConnectException -> ProxyError.ConnectionRefused
        is NoRouteToHostException -> ProxyError.RemoteUnreachable
        else -> ProxyError.Unknown(e.message ?: "connect failed")
    }

    private class Outcome<S>(val conn: S?, val error: ProxyError?)

    private class CachedAddrs(val addrs: List<InetAddress>, val atMs: Long)

    companion object {
        /** DNS 缓存有效期；短到 VPN 切换/DNS 漂移很快自愈，长到覆盖一次页面加载的同域名复用。 */
        private const val DNS_TTL_MS = 30_000L
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
         * 出口网解析的**对冲延迟**：超过它还没返回就补发互援那一路，谁快用谁。
         *
         * 400ms 是**保守初值，不是实测结论**——0806 日志只记了失败路径，
         * 成功解析的耗时分布当时完全没有观测（这正是本轮补 [recordDnsOk] 的原因）。
         * 取 400ms 的依据：日志里互援救回来的耗时中位是 510ms，把对冲点放在它之前，
         * 才能真正省下等待；同时又远大于命中 netd 缓存的亚毫秒级解析，
         * 保证绝大多数请求不会触发对冲、不产生额外 DNS 查询。
         * **等 dnsok= 心跳积累出真实 p90 后按它校准。**
         */
        private const val DNS_HEDGE_DELAY_MS = 400L

        /** 成功解析耗时的分桶上界（ms）。最后一桶是 [DNS_OK_BUCKET_UPPER_MS.last, 超时) */
        private val DNS_OK_BUCKET_UPPER_MS = longArrayOf(20, 50, 100, 200, 400, 800, 1_200)
        /**
         * 同时在途的解析数上限。比 [DNS_THREADS] 略小，留出余量给互援/DoH 那类并行子任务，
         * 避免闸门放行了却仍在池内排队——那样等于没限流。
         */
        private const val DNS_MAX_INFLIGHT = 12
        private const val DNS_THREADS = 16
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

        /**
         * 默认 DNS 调度器：独立的 daemon 线程池，与建连/relay/accept 池隔离，
         * 确保 relay 搬字节不会把 DNS 解析线程挤光。
         */
        private val DEFAULT_DNS_DISPATCHER: CoroutineDispatcher =
            Executors.newFixedThreadPool(DNS_THREADS) { r ->
                Thread({ bumpToForegroundPriority(); r.run() }, "hxmy-dns").apply { isDaemon = true }
            }.asCoroutineDispatcher()

        /**
         * 默认建连调度器：独立 daemon 池，隔离阻塞 connect。core=max=[CONNECT_THREADS] + 无界队列 →
         * 线程数**硬顶** CONNECT_THREADS（超出排队而非扩张），即便首屏几十域名同时建连也不无界增长；
         * allowCoreThreadTimeOut + 30s keepAlive → 空闲后线程回收到 0，不在停止共享后驻留（不堆线程）。
         * connect 必须保持阻塞 socket 以支持 [Network.socketFactory] 出口分流，故无法走非阻塞 NIO。
         */
        private val DEFAULT_CONNECT_DISPATCHER: CoroutineDispatcher =
            ThreadPoolExecutor(
                CONNECT_THREADS, CONNECT_THREADS, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(),
            ) { r -> Thread({ bumpToForegroundPriority(); r.run() }, "hxmy-connect").apply { isDaemon = true } }
                .apply { allowCoreThreadTimeOut(true) }
                .asCoroutineDispatcher()
    }
}
