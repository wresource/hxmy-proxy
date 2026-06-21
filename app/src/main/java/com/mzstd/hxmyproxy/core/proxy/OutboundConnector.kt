package com.mzstd.hxmyproxy.core.proxy

import com.mzstd.hxmyproxy.core.security.EgressGuard
import java.net.ConnectException
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
 * 禁止 `bindProcessToNetwork`。远程 DNS 在本机解析（同样走默认网络，随 VPN）。
 * 目标地址经 [EgressGuard] 反 SSRF 过滤。
 */
class OutboundConnector(
    private val egressGuard: EgressGuard,
) {
    /** 解析域名并连接。 */
    fun connect(host: String, port: Int): Socket {
        val addr = try {
            InetAddress.getByName(host)
        } catch (e: UnknownHostException) {
            throw ProxyException(ProxyError.DnsFailure)
        }
        return connect(addr, port)
    }

    /** 连接到已解析的地址（SOCKS5 ATYP=IPv4/IPv6）。 */
    fun connect(addr: InetAddress, port: Int): Socket {
        if (!egressGuard.isAllowed(addr)) throw ProxyException(ProxyError.AccessDenied)
        val socket = Socket()   // 不 bind 本地网络/地址 → 默认网络（VPN）
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(addr, port), ProxyTuning.CONNECT_TIMEOUT_MS)
            return socket
        } catch (e: Throwable) {
            socket.closeQuietly()
            throw when (e) {
                is SocketTimeoutException -> ProxyException(ProxyError.RemoteTimeout)
                is ConnectException -> ProxyException(ProxyError.ConnectionRefused)
                is NoRouteToHostException -> ProxyException(ProxyError.RemoteUnreachable)
                is ProxyException -> e
                else -> ProxyException(ProxyError.Unknown(e.message ?: "connect failed"))
            }
        }
    }
}
