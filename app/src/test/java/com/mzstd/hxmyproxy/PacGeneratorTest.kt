package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ProxyEntry
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.core.proxy.PacGenerator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacGeneratorTest {

    @Test fun mdnsFirstThenIpFallbackThenDirect() {
        val entries = listOf(
            ProxyEntry("192.168.1.34", 1080, ProxyProtocol.SOCKS5, "wlan0", "hxmyproxy.local"),
            ProxyEntry("192.168.43.1", 1080, ProxyProtocol.SOCKS5, "ap0", "hxmyproxy.local"),
            ProxyEntry("192.168.1.34", 8080, ProxyProtocol.HTTP, "wlan0", "hxmyproxy.local"),
        )
        val pac = PacGenerator.generate(entries)
        assertTrue(
            pac.contains(
                "SOCKS5 hxmyproxy.local:1080; SOCKS5 192.168.1.34:1080; SOCKS5 192.168.43.1:1080; " +
                    "PROXY hxmyproxy.local:8080; PROXY 192.168.1.34:8080; DIRECT",
            ),
        )
        assertTrue(pac.contains("function FindProxyForURL(url, host)"))
    }

    @Test fun ipOnlyWhenNoMdns() {
        val entries = listOf(ProxyEntry("192.168.1.34", 1080, ProxyProtocol.SOCKS5, "wlan0", null))
        val pac = PacGenerator.generate(entries)
        assertFalse(pac.contains(".local"))
        assertTrue(pac.contains("SOCKS5 192.168.1.34:1080; DIRECT"))
    }

    @Test fun emptyEntriesIsDirect() {
        val pac = PacGenerator.generate(emptyList())
        assertTrue(pac.contains("return \"DIRECT\""))
    }
}
