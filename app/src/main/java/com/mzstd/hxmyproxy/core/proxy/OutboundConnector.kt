package com.mzstd.hxmyproxy.core.proxy

import com.mzstd.hxmyproxy.core.security.EgressGuard
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 创建到目标的上游 TCP 连接。
 *
 * **D4 不变量**：不绑定任何 `Network`、不设本地地址 → 跟随系统默认网络（含系统 VPN）；
 * 禁止 `bindProcessToNetwork`。远程 DNS 在本机解析（随 VPN）。目标经 [EgressGuard] 反 SSRF 过滤。
 *
 * 关键修复：解析出**全部**地址并逐个尝试，**IPv4 优先**——双栈站点（如 google）常先返回 IPv6，
 * 而 NAT/移动网 IPv6 常不可达，只连第一个会干等满 connect 超时；逐个回退避免长时间卡顿。
 */
class OutboundConnector(
    private val egressGuard: EgressGuard,
) {
    /** 解析域名（全部地址）并连接，IPv4 优先。 */
    fun connect(host: String, port: Int): Socket {
        val addrs = try {
            InetAddress.getAllByName(host).toList()
        } catch (e: UnknownHostException) {
            throw ProxyException(ProxyError.DnsFailure)
        }
        return connectAny(orderAddresses(addrs), port)
    }

    /** 连接到已解析地址（SOCKS5 ATYP=IPv4/IPv6）。 */
    fun connect(addr: InetAddress, port: Int): Socket = connectAny(listOf(addr), port)

    /** IPv4 优先排序（IPv6 在 NAT/移动网常不可达，放后面）。 */
    internal fun orderAddresses(addrs: List<InetAddress>): List<InetAddress> =
        addrs.sortedBy { if (it is Inet4Address) 0 else 1 }

    /** 逐个地址尝试连接，失败回退下一个。 */
    internal fun connectAny(addrs: List<InetAddress>, port: Int): Socket {
        if (addrs.isEmpty()) throw ProxyException(ProxyError.DnsFailure)
        var lastError: ProxyError = ProxyError.RemoteUnreachable
        for (addr in addrs) {
            if (!egressGuard.isAllowed(addr)) {
                lastError = ProxyError.AccessDenied
                continue
            }
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(addr, port), ProxyTuning.CONNECT_TIMEOUT_MS)
                return socket
            } catch (e: Throwable) {
                socket.closeQuietly()
                lastError = when (e) {
                    is SocketTimeoutException -> ProxyError.RemoteTimeout
                    is ConnectException -> ProxyError.ConnectionRefused
                    is NoRouteToHostException -> ProxyError.RemoteUnreachable
                    else -> ProxyError.Unknown(e.message ?: "connect failed")
                }
                // 尝试下一个地址（如 IPv6 不可达后回退 IPv4）
            }
        }
        throw ProxyException(lastError)
    }
}
