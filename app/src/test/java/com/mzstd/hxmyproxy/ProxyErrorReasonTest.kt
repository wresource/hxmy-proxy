package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.proxy.ProxyError
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HTTP 错误响应的状态码与 reason phrase 必须配套。
 *
 * 此前调用方把短语写死成 "Bad Gateway"，于是被规则拒绝会发出 `403 Bad Gateway`、
 * 上游超时会发出 `504 Bad Gateway` —— 状态行自相矛盾。客户端只看状态码所以不影响行为，
 * 但抓包排障时会把人往「上游挂了」的方向带，而 403 的真实原因是被规则/出口护栏拒绝。
 */
class ProxyErrorReasonTest {

    @Test fun `403 的短语是 Forbidden 而不是 Bad Gateway`() {
        assertEquals(403, ProxyError.AccessDenied.httpStatus)
        assertEquals("Forbidden", ProxyError.AccessDenied.httpReason)
    }

    @Test fun `504 的短语是 Gateway Timeout`() {
        assertEquals(504, ProxyError.RemoteTimeout.httpStatus)
        assertEquals("Gateway Timeout", ProxyError.RemoteTimeout.httpReason)
    }

    @Test fun `其余错误仍是 502 Bad Gateway`() {
        listOf(
            ProxyError.DnsFailure,
            ProxyError.RemoteUnreachable,
            ProxyError.ConnectionRefused,
            ProxyError.VpnUnavailable,
            ProxyError.TooManyConnections,
            ProxyError.Unknown("boom"),
        ).forEach {
            assertEquals("${it.label} 应为 502", 502, it.httpStatus)
            assertEquals("${it.label} 的短语", "Bad Gateway", it.httpReason)
        }
    }

    @Test fun `每个错误的状态码都有配套短语——新增错误时不会漏`() {
        // httpReason 按 httpStatus 分派，所以只要 httpStatus 有值就必有短语；
        // 这条断言的意义是：将来给 httpStatus 加新分支（比如 502 之外的第四种）时，
        // 若忘了在 httpReason 里同步，这里会拿到 "Bad Gateway" 兜底而与状态码不符。
        val errors = listOf(
            ProxyError.VpnUnavailable, ProxyError.LocalNetworkPermissionDenied,
            ProxyError.PortInUse, ProxyError.DnsFailure, ProxyError.RemoteTimeout,
            ProxyError.RemoteUnreachable, ProxyError.ConnectionRefused,
            ProxyError.AccessDenied, ProxyError.TooManyConnections,
        )
        val expected = mapOf(403 to "Forbidden", 504 to "Gateway Timeout", 502 to "Bad Gateway")
        errors.forEach {
            assertEquals("${it.label}(${it.httpStatus}) 的短语", expected[it.httpStatus], it.httpReason)
        }
    }
}
