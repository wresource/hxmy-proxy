package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.proxy.SetupPageGenerator
import com.mzstd.hxmyproxy.core.proxy.SetupPageGenerator.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupPageGeneratorTest {

    @Test fun detectsPlatformFromUserAgent() {
        assertEquals(Platform.APPLE, SetupPageGenerator.detectPlatform("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0)"))
        assertEquals(Platform.APPLE, SetupPageGenerator.detectPlatform("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15)"))
        assertEquals(Platform.WINDOWS, SetupPageGenerator.detectPlatform("Mozilla/5.0 (Windows NT 10.0; Win64)"))
        assertEquals(Platform.OTHER, SetupPageGenerator.detectPlatform("Mozilla/5.0 (Linux; Android 14)"))
        assertEquals(Platform.OTHER, SetupPageGenerator.detectPlatform(""))
    }

    @Test fun htmlIsSelfContainedAndCarriesPacUrl() {
        val html = SetupPageGenerator.html("http://192.168.1.34:8899", "iPhone")
        assertTrue("应含 PAC 地址", html.contains("http://192.168.1.34:8899/proxy.pac"))
        assertTrue("Apple UA 应给描述文件入口", html.contains("/hxmy.mobileconfig"))
        // 不联网：不得引用任何外部 http(s) 资源（PAC/回链是本机地址，不算外链）。
        assertTrue("不应引用 https 外链", !html.contains("https://"))
        assertTrue("不应 <script src= 外部脚本", !html.contains("<script src"))
        assertTrue("不应 <link href= 外部样式", !html.contains("<link "))
    }

    @Test fun mobileconfigBindsSsidAndPacUrlWithStableUuid() {
        val a = SetupPageGenerator.mobileconfig("http://192.168.1.34:8899", "MyWiFi")
        assertTrue(a.contains("<key>ProxyType</key><string>Auto</string>"))
        assertTrue(a.contains("ProxyPACURL"))
        assertTrue(a.contains("http://192.168.1.34:8899/proxy.pac"))
        assertTrue("SSID 应写入载荷", a.contains("<string>MyWiFi</string>"))
        // 确定性：同输入同输出（无随机/时间）。
        val b = SetupPageGenerator.mobileconfig("http://192.168.1.34:8899", "MyWiFi")
        assertEquals(a, b)
    }

    @Test fun escapesSpecialCharsInSsid() {
        val cfg = SetupPageGenerator.mobileconfig("http://10.0.0.2:8899", "A&B<C>")
        assertTrue(cfg.contains("A&amp;B&lt;C&gt;"))
    }
}
