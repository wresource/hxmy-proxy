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

    @Test fun bareHostStripsIpv6Brackets() {
        assertEquals("2001:db8::1", HttpParsing.bareHost("[2001:db8::1]"))
        assertEquals("::1", HttpParsing.bareHost("[::1]"))
    }

    @Test fun bareHostLeavesEverythingElseAlone() {
        assertEquals("example.com", HttpParsing.bareHost("example.com"))
        assertEquals("10.0.0.2", HttpParsing.bareHost("10.0.0.2"))
        // 只有前后成对才剥；半边括号是非法输入，原样交给下游去失败，别在这里凭空造出合法 host。
        assertEquals("[2001:db8::1", HttpParsing.bareHost("[2001:db8::1"))
        assertEquals("2001:db8::1]", HttpParsing.bareHost("2001:db8::1]"))
    }

    /**
     * 钉住整条修复赖以成立的前提：`URI.getHost()` 对 IPv6 字面量**保留方括号**。
     * 若某天 JDK 改了这个行为，这里先红，而不是等到规则对 IPv6 目标静默失效。
     */
    @Test fun uriGetHostKeepsBracketsForIpv6() {
        assertEquals("[2001:db8::1]", java.net.URI("http://[2001:db8::1]/p?q=1").host)
        assertEquals("[2001:db8::1]", java.net.URI("http://[2001:db8::1]:8080/").host)
        assertEquals("example.com", java.net.URI("http://example.com/p").host)
    }

    @Test fun rejectsMissingOrBadPort() {
        assertNull(HttpParsing.parseHostPort("example.com"))     // port mandatory for CONNECT
        assertNull(HttpParsing.parseHostPort("example.com:abc"))
        assertNull(HttpParsing.parseHostPort("example.com:0"))
        assertNull(HttpParsing.parseHostPort("example.com:70000"))
    }
}
