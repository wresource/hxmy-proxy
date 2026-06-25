package com.mzstd.hxmyproxy.core.proxy

import android.net.Network
import com.mzstd.hxmyproxy.core.security.EgressGuard
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
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
class OutboundConnector(
    private val egressGuard: EgressGuard,
    // DNS 解析专用调度器：独立于 relay 的 limitedParallelism(N) IO 池——
    // relay 搬字节占满 IO 线程时，DNS 仍能在自己的池里解析，不被掐住（Stripe 首屏几十域名是重灾区）。
    private val dnsDispatcher: CoroutineDispatcher = DEFAULT_DNS_DISPATCHER,
    /** 非 VPN 底层网络提供者；为 DIRECT 出口分流把 socket 绑定到真实网络（绕过共享 VPN）。null=不支持分流。 */
    private val underlyingNetworkProvider: com.mzstd.hxmyproxy.core.network.UnderlyingNetworkProvider? = null,
) {
    /** 进程级短 TTL DNS 缓存：首屏同域名多次建连只解析一次，VPN 切换/DNS 漂移在 TTL 内自然失效。 */
    private val dnsCache = ConcurrentHashMap<String, CachedAddrs>()

    /** 解析域名（全部地址）并连接，IPv4 优先 + Happy Eyeballs。[bypassVpn]=true 时绕过共享 VPN 走真实网络。 */
    suspend fun connect(host: String, port: Int, bypassVpn: Boolean = false): Socket {
        val network = if (bypassVpn) underlyingNetworkProvider?.current() else null
        if (bypassVpn && network == null) {
            android.util.Log.w("hxmyproxy", "bypass requested for $host but no non-VPN network; using default egress")
        }
        return connectAny(orderAddresses(resolve(host, network)), port, network)
    }

    /**
     * 解析域名为全部地址；解析跑在独立 [dnsDispatcher]。
     * [network] 非空（出口分流）时在该网络上解析（避免 DNS 走 VPN），且不缓存（量小、避免与默认网络结果混淆）；
     * 为空时走默认网络解析 + 短 TTL 缓存。
     */
    private suspend fun resolve(host: String, network: Network?): List<InetAddress> {
        if (network != null) {
            return try {
                withContext(dnsDispatcher) { network.getAllByName(host).toList() }
            } catch (e: UnknownHostException) {
                throw ProxyException(ProxyError.DnsFailure)
            }
        }
        val now = System.currentTimeMillis()
        dnsCache[host]?.let { if (now - it.atMs < DNS_TTL_MS) return it.addrs }
        val addrs = try {
            withContext(dnsDispatcher) { InetAddress.getAllByName(host).toList() }
        } catch (e: UnknownHostException) {
            throw ProxyException(ProxyError.DnsFailure)
        }
        dnsCache[host] = CachedAddrs(addrs, now)
        return addrs
    }

    /** 连接到已解析地址（SOCKS5 ATYP=IPv4/IPv6）。[bypassVpn]=true 时绕过共享 VPN 走真实网络。 */
    suspend fun connect(addr: InetAddress, port: Int, bypassVpn: Boolean = false): Socket {
        val network = if (bypassVpn) underlyingNetworkProvider?.current() else null
        return connectAny(listOf(addr), port, network)
    }

    /** IPv4 优先排序（IPv6 在 NAT/移动网常不可达，放后面）。 */
    internal fun orderAddresses(addrs: List<InetAddress>): List<InetAddress> =
        addrs.sortedBy { if (it is Inet4Address) 0 else 1 }

    /**
     * Happy Eyeballs 交错并行连接：首个成功者胜出，其余在途连接立即关闭（中止其阻塞中的 connect）。
     * 全部失败抛最后一次错误；候选为空（DNS 空 / 全被护栏拦）抛对应错误。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal suspend fun connectAny(addrs: List<InetAddress>, port: Int, network: Network? = null): Socket = coroutineScope {
        val candidates = ArrayList<InetAddress>()
        var blocked = false
        for (a in addrs) if (egressGuard.isAllowed(a)) candidates.add(a) else blocked = true
        if (candidates.isEmpty()) {
            throw ProxyException(if (blocked) ProxyError.AccessDenied else ProxyError.DnsFailure)
        }

        val results = Channel<Outcome>(Channel.UNLIMITED)
        // inFlight 兼作锁对象；closed=注册闸门+清理标记：胜出/清理后置 true，使后到的尝试自行关闭而非连接。
        val inFlight = ArrayList<Socket>()
        val closed = AtomicBoolean(false)

        // nextIdx / pending 仅在收集协程内访问 → 单线程，无需同步。
        var nextIdx = 0
        var pending = 0

        fun launchNext() {
            if (nextIdx >= candidates.size) return
            val addr = candidates[nextIdx++]
            pending++
            launch(Dispatchers.IO) {
                // 出口分流：network 非空时用其 socketFactory 建 socket（绑定到非 VPN 网络），否则普通 socket 跟随默认网络（含 VPN）。
                val socket = try {
                    network?.socketFactory?.createSocket() ?: Socket()
                } catch (e: Throwable) {
                    if (!closed.get()) results.trySend(Outcome(null, mapConnectError(e)))
                    return@launch
                }
                // 注册与"是否已收尾"判定同锁：收尾后才到的尝试直接放弃，杜绝落单连接逃过清理而泄漏 FD。
                val registered = synchronized(inFlight) {
                    if (closed.get()) false else { inFlight.add(socket); true }
                }
                if (!registered) { socket.closeQuietly(); return@launch }
                try {
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(addr, port), ProxyTuning.CONNECT_TIMEOUT_MS)
                    if (closed.get()) socket.closeQuietly() else results.trySend(Outcome(socket, null))
                } catch (e: Throwable) {
                    socket.closeQuietly()
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
                val outcome: Outcome? = if (nextIdx < candidates.size) {
                    select {
                        results.onReceive { it }
                        onTimeout(ProxyTuning.HE_ATTEMPT_DELAY_MS.toLong()) { null }
                    }
                } else {
                    results.receive()
                }
                if (outcome == null) { launchNext(); continue }  // 到点仍无结果 → 并行起下一个
                pending--
                val socket = outcome.socket
                if (socket != null) {
                    synchronized(inFlight) {
                        closed.set(true)
                        inFlight.forEach { if (it !== socket) it.closeQuietly() }
                        inFlight.clear()
                    }
                    return@coroutineScope socket
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

    private fun mapConnectError(e: Throwable): ProxyError = when (e) {
        is SocketTimeoutException -> ProxyError.RemoteTimeout
        is ConnectException -> ProxyError.ConnectionRefused
        is NoRouteToHostException -> ProxyError.RemoteUnreachable
        else -> ProxyError.Unknown(e.message ?: "connect failed")
    }

    private class Outcome(val socket: Socket?, val error: ProxyError?)

    private class CachedAddrs(val addrs: List<InetAddress>, val atMs: Long)

    companion object {
        /** DNS 缓存有效期；短到 VPN 切换/DNS 漂移很快自愈，长到覆盖一次页面加载的同域名复用。 */
        private const val DNS_TTL_MS = 30_000L
        private const val DNS_THREADS = 16

        /**
         * 默认 DNS 调度器：独立的 daemon 线程池，与 [Dispatchers.IO]（及其 limitedParallelism 视图）
         * 的底层池隔离，确保 relay 搬字节不会把 DNS 解析线程挤光。
         */
        private val DEFAULT_DNS_DISPATCHER: CoroutineDispatcher =
            Executors.newFixedThreadPool(DNS_THREADS) { r ->
                Thread(r, "hxmy-dns").apply { isDaemon = true }
            }.asCoroutineDispatcher()
    }
}
