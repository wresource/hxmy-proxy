package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.proxy.ProxyError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProxyErrorTest {

    @Test fun socks5ReplyCodes() {
        assertEquals(0x05, ProxyError.ConnectionRefused.socksReply)
        assertEquals(0x04, ProxyError.RemoteUnreachable.socksReply)
        assertEquals(0x04, ProxyError.DnsFailure.socksReply)
        assertEquals(0x02, ProxyError.AccessDenied.socksReply)
        assertEquals(0x01, ProxyError.RemoteTimeout.socksReply)
        assertEquals(0x01, ProxyError.Unknown("x").socksReply)
    }

    @Test fun httpStatusCodes() {
        assertEquals(403, ProxyError.AccessDenied.httpStatus)
        assertEquals(504, ProxyError.RemoteTimeout.httpStatus)
        assertEquals(502, ProxyError.ConnectionRefused.httpStatus)
        assertEquals(502, ProxyError.DnsFailure.httpStatus)
    }

    // ==================== 摘网 vs 目标不可达:必须能区分 ====================
    //
    // 这两件事此前共用 RemoteUnreachable,于是一份日志里分不开「我们自己主动拒的」
    // 和「那个站点真连不上」。0817 排查换网断线时,正是因为这个混用多绕了一大圈。
    //
    // ⚠️ 别指望状态码去驱动客户端行为。0818 实测:CONNECT 路径上 Chrome 统一成
    // ERR_TUNNEL_CONNECTION_FAILED、CFNetwork 统一成 310(连「代理端口有没有人监听」
    // 都传不出去)、OkHttp/Cronet/mihomo/sing-box 一视同仁。改这个是为了**我们自己
    // 能把日志分开**,不是为了让别人改变行为。

    @Test fun `摘网与目标不可达必须是不同的错误码`() {
        assertEquals("sidelined", ProxyError.EgressSidelined.code)
        assertEquals("unreachable", ProxyError.RemoteUnreachable.code)
        assertNotEquals(
            "两者共用同一个 code 就等于放弃了区分——这正是要修的东西",
            ProxyError.RemoteUnreachable.code, ProxyError.EgressSidelined.code,
        )
    }

    @Test fun `摘网回 503 而目标问题回 502 或 504`() {
        // 503:我方此刻主动拒绝且是暂时的。
        assertEquals(503, ProxyError.EgressSidelined.httpStatus)
        assertEquals("Service Unavailable", ProxyError.EgressSidelined.httpReason)
        // 502 的定义硬性要求「收到了上游的无效响应」,而摘网时我们一个响应都没收到。
        assertEquals(502, ProxyError.RemoteUnreachable.httpStatus)
        // 504 已经被「目标连接超时」占着,摘网若也用它,两件事在日志里再也分不开。
        assertEquals(504, ProxyError.RemoteTimeout.httpStatus)
        assertNotEquals(
            ProxyError.RemoteTimeout.httpStatus, ProxyError.EgressSidelined.httpStatus,
        )
    }

    /** SOCKS5 侧刻意不变:RFC 1928 的 REP 码里没有「暂时」,换成 0x03 是零收益的行为变更。 */
    @Test fun `摘网的 SOCKS5 回复码与改动前保持一致`() {
        assertEquals(0x04, ProxyError.EgressSidelined.socksReply)
    }

    /** 每个状态码都必须配对正确的 reason phrase,否则抓包时会看到 `503 Bad Gateway` 这种自相矛盾的状态行。 */
    @Test fun `reason phrase 与状态码配对`() {
        assertEquals("Forbidden", ProxyError.AccessDenied.httpReason)
        assertEquals("Gateway Timeout", ProxyError.RemoteTimeout.httpReason)
        assertEquals("Service Unavailable", ProxyError.EgressSidelined.httpReason)
        assertEquals("Bad Gateway", ProxyError.RemoteUnreachable.httpReason)
    }
}
