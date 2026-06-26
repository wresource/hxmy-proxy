package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ProxyEntry
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.core.proxy.PacGenerator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacGeneratorTest {

    @Test fun ipProxyFirstThenSocksThenMdnsThenDirect() {
        val entries = listOf(
            ProxyEntry("192.168.1.34", 1080, ProxyProtocol.SOCKS5, "wlan0", "hxmyproxy.local"),
            ProxyEntry("192.168.43.1", 1080, ProxyProtocol.SOCKS5, "ap0", "hxmyproxy.local"),
            ProxyEntry("192.168.1.34", 8080, ProxyProtocol.HTTP, "wlan0", "hxmyproxy.local"),
        )
        val pac = PacGenerator.generate(entries)
        // iOS 友好：PROXY+具体IP 最前、SOCKS5+裸SOCKS、.local 后置、DIRECT 兜底
        assertTrue(
            pac.contains(
                "PROXY 192.168.1.34:8080; " +
                    "SOCKS5 192.168.1.34:1080; SOCKS5 192.168.43.1:1080; " +
                    "SOCKS 192.168.1.34:1080; SOCKS 192.168.43.1:1080; " +
                    "PROXY hxmyproxy.local:8080; SOCKS5 hxmyproxy.local:1080; DIRECT",
            ),
        )
        assertTrue(pac.contains("function FindProxyForURL(url, host)"))
        // 具体 IP 必须排在 .local 之前(iOS 对 .local 解析慢)
        assertTrue(pac.indexOf("192.168.1.34:8080") < pac.indexOf("hxmyproxy.local"))
    }

    @Test fun ipOnlyWhenNoMdns() {
        val entries = listOf(ProxyEntry("192.168.1.34", 1080, ProxyProtocol.SOCKS5, "wlan0", null))
        val pac = PacGenerator.generate(entries)
        assertFalse(pac.contains(".local"))
        // SOCKS5(桌面) + 裸 SOCKS(iOS=SOCKS5) 两条都给
        assertTrue(pac.contains("SOCKS5 192.168.1.34:1080; SOCKS 192.168.1.34:1080; DIRECT"))
    }

    @Test fun emptyEntriesIsDirect() {
        val pac = PacGenerator.generate(emptyList())
        assertTrue(pac.contains("return \"DIRECT\""))
    }
}
