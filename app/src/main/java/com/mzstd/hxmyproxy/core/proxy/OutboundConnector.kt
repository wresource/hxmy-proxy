package com.mzstd.hxmyproxy.core.proxy

import android.net.Network
import com.mzstd.hxmyproxy.core.security.EgressGuard
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
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

    /**
     * 同 [connect]，但产出已连接的（阻塞模式）[SocketChannel]，供非阻塞 relay 使用（调用方在进入 relay 前
     * 切 `configureBlocking(false)`）。[bypassVpn]=true 时用反射取 fd + `Network.bindSocket(fd)`（connect 前）
     * 做出口分流——Phase 0 spike 已验证（见 BindSocketSpikeTest）。反射取 fd 失败则抛 [IOException]，
     * 调用方应回退到阻塞 [connect] + 阻塞 relay。
     */
    suspend fun connectChannel(host: String, port: Int, bypassVpn: Boolean = false): SocketChannel {
        val network = if (bypassVpn) underlyingNetworkProvider?.current() else null
        if (bypassVpn && network == null) {
            android.util.Log.w("hxmyproxy", "bypass requested for $host but no non-VPN network; using default egress")
        }
        return connectAnyChannel(orderAddresses(resolve(host, network)), port, network)
    }

    /** [connectChannel] 的已解析地址版（SOCKS5 ATYP）。 */
    suspend fun connectChannel(addr: InetAddress, port: Int, bypassVpn: Boolean = false): SocketChannel {
        val network = if (bypassVpn) underlyingNetworkProvider?.current() else null
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
    private suspend fun connectAnyChannel(addrs: List<InetAddress>, port: Int, network: Network?): SocketChannel =
        connectAnyGeneric(
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
