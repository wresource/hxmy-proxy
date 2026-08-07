package com.mzstd.hxmyproxy.core.proxy

/**
 * 错误分类（design §11.3）。映射到 SOCKS5 REP 码（RFC1928）与 HTTP 状态，
 * 便于客户端区分原因、UI 诊断展示。
 */
sealed class ProxyError(val label: String) {
    data object VpnUnavailable : ProxyError("VPN unavailable")
    data object LocalNetworkPermissionDenied : ProxyError("local network permission denied")
    data object PortInUse : ProxyError("port in use")
    data object DnsFailure : ProxyError("DNS resolution failed")
    data object RemoteTimeout : ProxyError("remote connect timeout")
    data object RemoteUnreachable : ProxyError("remote unreachable")
    data object ConnectionRefused : ProxyError("connection refused")
    data object AccessDenied : ProxyError("denied by access control / egress guard")
    data object TooManyConnections : ProxyError("too many connections")
    data class Unknown(val detail: String) : ProxyError(detail)

    /**
     * 稳定的机器可读标识，供 UI 查本地化文案。
     *
     * **不要拿 [label] 上界面**：它是**英文**排障文案（给日志用的），直接显示会在中文界面上冒出
     * 英文串——实测过一次，端到端截图才发现，单测抓不到。
     */
    val code: String
        get() = when (this) {
            RemoteTimeout -> "timeout"
            DnsFailure -> "dns"
            ConnectionRefused -> "refused"
            RemoteUnreachable -> "unreachable"
            AccessDenied -> "denied"
            VpnUnavailable -> "vpn"
            LocalNetworkPermissionDenied -> "perm"
            PortInUse -> "port"
            TooManyConnections -> "limit"
            is Unknown -> "other"
        }

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

    /**
     * 与 [httpStatus] 配对的 reason phrase。
     *
     * 调用方此前把短语写死成 "Bad Gateway"，于是 403 会发出 `403 Bad Gateway`、
     * 504 发出 `504 Bad Gateway` 这种自相矛盾的状态行。客户端只看状态码所以不影响行为，
     * 但抓包排障时会把人往"上游挂了"的方向带——而 403 的真实原因是被规则/护栏拒绝。
     */
    val httpReason: String
        get() = when (httpStatus) {
            403 -> "Forbidden"
            504 -> "Gateway Timeout"
            else -> "Bad Gateway"
        }
}

/** 携带 [ProxyError] 的受检异常，贯穿握手/连接路径。 */
class ProxyException(val error: ProxyError) : Exception(error.label)
