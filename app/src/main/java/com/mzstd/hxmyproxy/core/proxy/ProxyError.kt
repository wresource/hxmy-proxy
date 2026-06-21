package com.mzstd.hxmyproxy.core.proxy

/**
 * 错误分类（design §11.3）。映射到 SOCKS5 REP 码（RFC1928）与 HTTP 状态，
 * 便于客户端区分原因、UI 诊断展示。
 */
sealed class ProxyError(val label: String) {
    data object VpnUnavailable : ProxyError("VPN 不可用")
    data object LocalNetworkPermissionDenied : ProxyError("本地网络权限未授权")
    data object PortInUse : ProxyError("端口被占用")
    data object DnsFailure : ProxyError("DNS 解析失败")
    data object RemoteTimeout : ProxyError("远程连接超时")
    data object RemoteUnreachable : ProxyError("远程不可达")
    data object ConnectionRefused : ProxyError("连接被拒绝")
    data object AccessDenied : ProxyError("被访问控制/出口护栏拒绝")
    data object TooManyConnections : ProxyError("连接数超限")
    data class Unknown(val detail: String) : ProxyError(detail)

    /** SOCKS5 REP 码 (RFC1928 §6)。 */
    val socksReply: Int
        get() = when (this) {
            ConnectionRefused -> 0x05
            RemoteUnreachable, DnsFailure -> 0x04   // host unreachable
            AccessDenied -> 0x02                     // not allowed by ruleset
            else -> 0x01                             // general SOCKS server failure
        }

    /** 普通 HTTP 转发失败时的状态码。 */
    val httpStatus: Int
        get() = when (this) {
            AccessDenied -> 403
            RemoteTimeout -> 504
            else -> 502
        }
}

/** 携带 [ProxyError] 的受检异常，贯穿握手/连接路径。 */
class ProxyException(val error: ProxyError) : Exception(error.label)
