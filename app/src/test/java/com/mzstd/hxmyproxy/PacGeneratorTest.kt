package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ProxyEntry
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.core.proxy.PacGenerator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacGeneratorTest {

    @Test fun httpBeforeSocksAndAllInterfacesListed() {
        val entries = listOf(
            ProxyEntry("192.168.1.34", 1080, ProxyProtocol.SOCKS5, "wlan0"),
            ProxyEntry("192.168.43.1", 1080, ProxyProtocol.SOCKS5, "ap0"),
            ProxyEntry("192.168.1.34", 8080, ProxyProtocol.HTTP, "wlan0"),
        )
        val pac = PacGenerator.generate(entries)
        // iOS 友好：PROXY 最前(HTTP 两端都稳认)、再 SOCKS5(桌面)、再裸 SOCKS(iOS 当 SOCKS5)、DIRECT 兜底。
        // 多接口(WiFi + 热点)全部列出，客户端按序回退。
        assertTrue(
            pac.contains(
                "PROXY 192.168.1.34:8080; " +
                    "SOCKS5 192.168.1.34:1080; SOCKS5 192.168.43.1:1080; " +
                    "SOCKS 192.168.1.34:1080; SOCKS 192.168.43.1:1080; DIRECT",
            ),
        )
        assertTrue(pac.contains("function FindProxyForURL(url, host)"))
    }

    /** 入口只给具体 IP —— `.local` 便利名已随 mDNS 删除，PAC 正文里不该再出现任何主机名。 */
    @Test fun onlyIpLiteralsNoHostnames() {
        val entries = listOf(ProxyEntry("192.168.1.34", 1080, ProxyProtocol.SOCKS5, "wlan0"))
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
