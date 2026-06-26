package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.proxy.HttpParsing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpParsingTest {

    @Test fun parsesHostPort() {
        assertEquals("example.com" to 443, HttpParsing.parseHostPort("example.com:443"))
        assertEquals("10.0.0.2" to 8080, HttpParsing.parseHostPort("10.0.0.2:8080"))
    }

    @Test fun parsesBracketedIpv6() {
        assertEquals("::1" to 8443, HttpParsing.parseHostPort("[::1]:8443"))
        assertEquals("2001:db8::1" to 443, HttpParsing.parseHostPort("[2001:db8::1]:443"))
    }

    @Test fun rejectsMissingOrBadPort() {
        assertNull(HttpParsing.parseHostPort("example.com"))     // port mandatory for CONNECT
        assertNull(HttpParsing.parseHostPort("example.com:abc"))
        assertNull(HttpParsing.parseHostPort("example.com:0"))
        assertNull(HttpParsing.parseHostPort("example.com:70000"))
    }
}
