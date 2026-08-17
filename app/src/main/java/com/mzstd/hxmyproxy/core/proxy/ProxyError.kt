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

    /**
     * **我们自己把这条出口摘了**（[com.mzstd.hxmyproxy.core.network.EgressHealth] 判定整张网不通），
     * 请求根本没被发出去。与 [RemoteUnreachable]「那个目标连不上」是两件事。
     *
     * 分开的理由是**可诊断性**，不是客户端行为——0818 实测过一轮，CONNECT 路径上
     * Chrome / CFNetwork / OkHttp / Cronet / mihomo / sing-box 对 502、503、504
     * 的反应完全一致（Chrome 统一成 ERR_TUNNEL_CONNECTION_FAILED，CFNetwork 统一成
     * 310，连「代理端口有没有人监听」都传不出去）。指望换个状态码去驱动别人的行为是幻想。
     *
     * 真正的收益在我们自己这侧：此前摘网与目标不可达共用 `err=unreachable`，
     * 一份日志里根本分不开「我们主动拒的」和「那个站点真连不上」。
     */
    data object EgressSidelined : ProxyError("egress temporarily unavailable")
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
            EgressSidelined -> "sidelined"
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
            // RFC 1928 的 REP 码里没有「暂时不可用」这个概念，最接近的 0x03(network
            // unreachable)并不比 0x04 多传递任何信息。刻意保持与改动前一致，
            // 免得为一个零收益的语义调整改变既有 SOCKS5 客户端的行为。
            EgressSidelined -> 0x04
            AccessDenied -> 0x02                     // not allowed by ruleset
            else -> 0x01                             // general SOCKS server failure
        }

    /** 普通 HTTP 转发失败时的状态码。 */
    val httpStatus: Int
        get() = when (this) {
            AccessDenied -> 403
            RemoteTimeout -> 504
            // 503：我方此刻主动拒绝，且是暂时的。不用 502(它硬性要求「收到了上游的
            // 无效响应」，而摘网时我们一个响应都没收到)，也不用 504(那已经被
            // 「目标连接超时」占着，混用会让两件事在日志里再也分不开)。
            EgressSidelined -> 503
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
            503 -> "Service Unavailable"
            else -> "Bad Gateway"
        }
}

/**
 * 携带 [ProxyError] 的受检异常，贯穿握手/连接路径。
 *
 * [stage] 是**失败发生在哪一步**，随异常一路带到 server 层由那里统一落盘。
 *
 * 为什么不让抛出点自己记日志:此前 [OutboundConnector] 在 STRICT 分支自己调了一次
 * `trace.failed(host, "connect-strict", …)`，而 server 层的 catch 又记一次 ——
 * 0814 日志里 750 行 `req.failed` 实际只对应 **432 次**失败（318 个 id 落两行），
 * 直接数行数会把失败量高估 74%。**失败只能有一个所有者**，多一个记录点就多一份重复。
 *
 * @param retryAfterSeconds 仅 [ProxyError.EgressSidelined] 使用：距离下次出口复检还有多久。
 * **必须 ≥ 1**——0818 实测 OkHttp 是唯一会读 `Retry-After` 的栈，而它的判据恰恰是 `== 0`，
 * 反应是「立即无退避重试」。在「整张网不通」的当口发 0，等于喊一声「马上再来」。
 */
class ProxyException(
    val error: ProxyError,
    val stage: String? = null,
    val retryAfterSeconds: Int? = null,
) : Exception(error.label)
