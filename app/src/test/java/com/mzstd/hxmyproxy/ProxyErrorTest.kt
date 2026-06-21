package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.proxy.ProxyError
import org.junit.Assert.assertEquals
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
}
