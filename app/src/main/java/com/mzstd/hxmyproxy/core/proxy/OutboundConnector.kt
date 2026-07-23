package com.mzstd.hxmyproxy.core.proxy

import android.net.Network
import com.mzstd.hxmyproxy.core.security.EgressGuard
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
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
) {
    /** 进程级短 TTL DNS 缓存：首屏同域名多次建连只解析一次，VPN 切换/DNS 漂移在 TTL 内自然失效。 */
    private val dnsCache = ConcurrentHashMap<String, CachedAddrs>()

    /** 备用 DNS（DoH）开关；由设置层经 applyTunables 推入。 */
    @Volatile var backupDnsEnabled: Boolean = true

    /** 网络变化时由编排层调用：旧网络下解析的 IP 可能已不可达，清掉让 TTL 内的条目立即失效。 */
    fun clearDnsCache() {
        dnsCache.clear()
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

    /** 解析域名（全部地址）并连接，IPv4 优先 + Happy Eyeballs。[bypassVpn]=true 时绕过共享 VPN 走真实网络。 */
    suspend fun connect(host: String, port: Int, bypassVpn: Boolean = false): Socket {
        // bypassVpn(DIRECT 规则)→底层物理网络绕开共享 VPN；否则(PROXY 默认路径)→用户选定出口(AUTO=null=系统默认)。
        val network = if (bypassVpn) underlyingNetworkProvider?.current() else underlyingNetworkProvider?.egressNetwork()
        if (bypassVpn && network == null) {
            android.util.Log.w(TAG, "bypass requested for $host but no non-VPN network; using default egress")
        }
        return try {
            connectAny(orderAddresses(resolve(host, network)), port, network)
        } catch (e: ProxyException) {
            if (network == null) {
                throttledFileLog("connect:$host", "upstream fail $host (default egress): ${e.error}")
                throw e
            }
            // DIRECT 出口建连失败（stale 句柄/该网络故障）→ 降级默认网络重试一次：
            // 「能上网」优先于「严格绕过 VPN」；降级必落盘，用户可从日志发现分流路径在坏。
            throttledFileLog("direct:$host", "DIRECT egress fail $host: ${e.error} — degrading to default network")
            connectAny(orderAddresses(resolve(host, null)), port, null)
        }
    }

    /**
     * 解析域名为全部地址；解析跑在独立 [dnsDispatcher]。
     * [network] 非空（出口分流）时在该网络上解析（避免 DNS 走 VPN），且不缓存（量小、避免与默认网络结果混淆）；
     * 为空时走默认网络解析 + 短 TTL 缓存。
     * **双路互援**：一条路解析失败即换另一条路重试（换 netId 也天然绕开系统 2s 负缓存）——
     * DIRECT 失败→默认网络；默认失败→底层 WiFi。失败与援通均节流落盘（诊断「究竟哪条 DNS 在坏」）。
     */
    private suspend fun resolve(host: String, network: Network?): List<InetAddress> {
        if (network != null) {
            return try {
                withContext(dnsDispatcher) { network.getAllByName(host).toList() }
            } catch (e: UnknownHostException) {
                throttledFileLog("dns-direct:$host", "DNS fail $host on underlying network (${e.message}); retry default")
                try {
                    withContext(dnsDispatcher) { InetAddress.getAllByName(host).toList() }
                        .also { throttledFileLog("dns-direct-rescued:$host", "DNS rescued $host via default network") }
                } catch (e2: UnknownHostException) {
                    resolveLastResort(host, e2)
                }
            }
        }
        val now = System.currentTimeMillis()
        dnsCache[host]?.let { if (now - it.atMs < DNS_TTL_MS) return it.addrs }
        val addrs = try {
            withContext(dnsDispatcher) { InetAddress.getAllByName(host).toList() }
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
                    if (backupDnsEnabled) runCatching { dohResolve(host) }.getOrDefault(emptyList()) else emptyList()
                }
                val a = altD.await()
                val d = dohD.await()
                if (a.isNotEmpty()) throttledFileLog("dns-default-rescued:$host", "DNS rescued $host via underlying network")
                if (d.isNotEmpty()) throttledFileLog("doh-rescued:$host", "DNS rescued $host via DoH backup")
                (a + d).distinct()
            }
            if (merged.isEmpty()) {
                throttledFileLog("doh-fail:$host", "both underlying and DoH failed for $host")
                throw ProxyException(ProxyError.DnsFailure)
            }
            merged
        }
        dnsCache[host] = CachedAddrs(addrs, now)
        return addrs
    }

    /**
     * 最后一搏：系统解析双路全败后走 DoH 备援（关着或也失败则抛 [ProxyError.DnsFailure]）。
     * DoH 成功即「备用 DNS」救场——网络自身 DNS 坏而链路仍通的场景（用户实证痛点）。
     */
    private suspend fun resolveLastResort(host: String, cause: UnknownHostException): List<InetAddress> {
        throttledFileLog("dns-both:$host", "DNS fail $host on system paths (${cause.message})")
        if (backupDnsEnabled) {
            val doh = withContext(dnsDispatcher) { dohResolve(host) }
            if (doh.isNotEmpty()) {
                throttledFileLog("doh-rescued:$host", "DNS rescued $host via DoH backup")
                return doh
            }
            throttledFileLog("doh-fail:$host", "DoH backup also failed for $host")
        }
        throw ProxyException(ProxyError.DnsFailure)
    }

    /**
     * DoH 兜底解析（JSON API、**IP 直连端点**免 bootstrap 自举）：依次试 Google/Cloudflare，A 记录优先、
     * 空则查 AAAA。请求走系统默认网络（与代理出站同路径——出站能通则 DoH 基本能通，故障相关性一致；
     * 若默认路径整体断链，DoH 与业务同死，不做无谓挣扎）。加密 443 出去，不与 Private DNS 的
     * 「禁发明文 53」冲突。阻塞实现，调用方置于 [dnsDispatcher]。
     */
    private fun dohResolve(host: String): List<InetAddress> {
        for ((base, accept) in DOH_ENDPOINTS) {
            for (type in intArrayOf(1, 28)) {           // 1=A, 28=AAAA
                try {
                    val url = java.net.URL("$base?name=${java.net.URLEncoder.encode(host, "UTF-8")}&type=$type")
                    val conn = url.openConnection() as javax.net.ssl.HttpsURLConnection
                    conn.connectTimeout = DOH_TIMEOUT_MS
                    conn.readTimeout = DOH_TIMEOUT_MS
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

    /** 连接到已解析地址（SOCKS5 ATYP=IPv4/IPv6）。[bypassVpn]=true 时绕过共享 VPN 走真实网络。 */
    suspend fun connect(addr: InetAddress, port: Int, bypassVpn: Boolean = false): Socket {
        // bypassVpn(DIRECT 规则)→底层物理网络绕开共享 VPN；否则(PROXY 默认路径)→用户选定出口(AUTO=null=系统默认)。
        val network = if (bypassVpn) underlyingNetworkProvider?.current() else underlyingNetworkProvider?.egressNetwork()
        return connectAny(listOf(addr), port, network)
    }

    /**
     * 同 [connect]，但产出已连接的（阻塞模式）[SocketChannel]，供非阻塞 relay 使用（调用方在进入 relay 前
     * 切 `configureBlocking(false)`）。[bypassVpn]=true 时用反射取 fd + `Network.bindSocket(fd)`（connect 前）
     * 做出口分流——Phase 0 spike 已验证（见 BindSocketSpikeTest）。反射取 fd 失败则抛 [IOException]，
     * 调用方应回退到阻塞 [connect] + 阻塞 relay。
     */
    suspend fun connectChannel(host: String, port: Int, bypassVpn: Boolean = false): SocketChannel {
        // bypassVpn(DIRECT 规则)→底层物理网络绕开共享 VPN；否则(PROXY 默认路径)→用户选定出口(AUTO=null=系统默认)。
        val network = if (bypassVpn) underlyingNetworkProvider?.current() else underlyingNetworkProvider?.egressNetwork()
        if (bypassVpn && network == null) {
            android.util.Log.w(TAG, "bypass requested for $host but no non-VPN network; using default egress")
        }
        return try {
            connectAnyChannel(orderAddresses(resolve(host, network)), port, network)
        } catch (e: ProxyException) {
            if (network == null) {
                throttledFileLog("connect:$host", "upstream fail $host (default egress): ${e.error}")
                throw e
            }
            // 同阻塞版 connect：DIRECT 失败降级默认网络（仅捕 ProxyException——IOException 须继续
            // 冒泡让调用方走「反射不可用 → 回退阻塞路径」的既有逻辑）。
            throttledFileLog("direct:$host", "DIRECT egress fail $host: ${e.error} — degrading to default network")
            connectAnyChannel(orderAddresses(resolve(host, null)), port, null)
        }
    }

    /** [connectChannel] 的已解析地址版（SOCKS5 ATYP）。 */
    suspend fun connectChannel(addr: InetAddress, port: Int, bypassVpn: Boolean = false): SocketChannel {
        // bypassVpn(DIRECT 规则)→底层物理网络绕开共享 VPN；否则(PROXY 默认路径)→用户选定出口(AUTO=null=系统默认)。
        val network = if (bypassVpn) underlyingNetworkProvider?.current() else underlyingNetworkProvider?.egressNetwork()
        return connectAnyChannel(listOf(addr), port, network)
    }

    /** IPv4 优先排序（IPv6 在 NAT/移动网常不可达，放后面）。 */
    internal fun orderAddresses(addrs: List<InetAddress>): List<InetAddress> =
        addrs.sortedBy { if (it is Inet4Address) 0 else 1 }

    /**
     * Happy Eyeballs 交错并行连接：首个成功者胜出，其余在途连接立即关闭（中止其阻塞中的 connect）。
     * 全部失败抛最后一次错误；候选为空（DNS 空 / 全被护栏拦）抛对应错误。
     */
    /** 阻塞 [Socket] 版（HTTP 明文路径 / 现有调用）。出口分流靠 `network.socketFactory` 建已绑定 socket。 */
    internal suspend fun connectAny(addrs: List<InetAddress>, port: Int, network: Network? = null): Socket =
        connectAnyGeneric(
            addrs, port,
            create = { network?.socketFactory?.createSocket() ?: Socket() },
            connect = { s, a -> s.tcpNoDelay = true; s.connect(a, ProxyTuning.CONNECT_TIMEOUT_MS) },
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
            throw IOException("SocketChannel fd 反射不可用，无法 bindSocket 出口分流")
        }
        return connectAnyGeneric(
            addrs, port,
            create = {
                val ch = SocketChannel.open()
                ch.configureBlocking(true)
                if (network != null) {
                    val fd = fileDescriptorOf(ch)
                        ?: run { ch.closeQuietly(); throw IOException("取 SocketChannel fd 失败，无法 bindSocket 出口分流") }
                    network.bindSocket(fd)   // connect 之前绑定到非 VPN 网络
                }
                ch
            },
            connect = { ch, a -> ch.socket().tcpNoDelay = true; ch.socket().connect(a, ProxyTuning.CONNECT_TIMEOUT_MS) },
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
        // 扇出上限：解析出超多地址（个别 CDN/anycast 返回十几条）时只取 IPv4 优先的前 N 个并行尝试。
        if (candidates.size > MAX_HE_CANDIDATES) {
            candidates.subList(MAX_HE_CANDIDATES, candidates.size).clear()
        }

        val results = Channel<Outcome<S>>(Channel.UNLIMITED)
        // inFlight 兼作锁对象；closed=注册闸门+清理标记：胜出/清理后置 true，使后到的尝试自行关闭而非连接。
        val inFlight = ArrayList<S>()
        val closed = AtomicBoolean(false)

        // nextIdx / pending 仅在收集协程内访问 → 单线程，无需同步。
        var nextIdx = 0
        var pending = 0

        fun launchNext() {
            if (nextIdx >= candidates.size) return
            val addr = candidates[nextIdx++]
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
            while (pending > 0 || nextIdx < candidates.size) {
                // 还有未起地址：select 等结果或到点（select 保证已投递的结果不会被丢弃），到点则并行起下一个；
                // 地址起完：纯等结果（必有在途，故不会永久阻塞）。
                val outcome: Outcome<S>? = if (nextIdx < candidates.size) {
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
        private val DOH_ENDPOINTS = listOf(
            "https://8.8.8.8/resolve" to null,
            "https://1.1.1.1/dns-query" to "application/dns-json",
        )
        private const val DOH_TIMEOUT_MS = 3_000
        private const val DNS_THREADS = 16
        /** 上游建连有界池线程数：connect 是短时阻塞操作，96 足以支撑首屏几十域名并发建连且硬限线程。 */
        private const val CONNECT_THREADS = 96
        /** 单域名 Happy Eyeballs 并行尝试的地址数上限（IPv4 优先取前 N）。 */
        private const val MAX_HE_CANDIDATES = 6

        /**
         * 默认 DNS 调度器：独立的 daemon 线程池，与建连/relay/accept 池隔离，
         * 确保 relay 搬字节不会把 DNS 解析线程挤光。
         */
        private val DEFAULT_DNS_DISPATCHER: CoroutineDispatcher =
            Executors.newFixedThreadPool(DNS_THREADS) { r ->
                Thread(r, "hxmy-dns").apply { isDaemon = true }
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
            ) { r -> Thread(r, "hxmy-connect").apply { isDaemon = true } }
                .apply { allowCoreThreadTimeOut(true) }
                .asCoroutineDispatcher()
    }
}
