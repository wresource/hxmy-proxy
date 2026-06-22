package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.proxy.OutboundConnector
import com.mzstd.hxmyproxy.core.proxy.ProxyException
import com.mzstd.hxmyproxy.core.security.EgressGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class OutboundConnectorTest {

    private val allowAll = object : EgressGuard { override fun isAllowed(addr: InetAddress) = true }

    @Test(timeout = 10000) fun ordersIpv4First() {
        val c = OutboundConnector(allowAll)
        val v6a = InetAddress.getByName("::1")
        val v4 = InetAddress.getByName("127.0.0.1")
        val v6b = InetAddress.getByName("2001:db8::1")
        val ordered = c.orderAddresses(listOf(v6a, v4, v6b))
        assertTrue("第一个应为 IPv4", ordered.first() is Inet4Address)
        assertEquals(v4, ordered.first())
    }

    @Test(timeout = 10000) fun fallsBackPastBlockedAddressToReachable() {
        val echo = startEcho()
        // 阻断 10.1.2.3，放行 loopback —— 验证逐地址回退
        val guard = object : EgressGuard { override fun isAllowed(addr: InetAddress) = addr.hostAddress != "10.1.2.3" }
        val c = OutboundConnector(guard)
        val s = c.connectAny(
            listOf(InetAddress.getByName("10.1.2.3"), InetAddress.getByName("127.0.0.1")),
            echo.localPort,
        )
        assertTrue(s.isConnected)
        s.getOutputStream().write("hi".toByteArray()); s.getOutputStream().flush()
        val b = ByteArray(2); s.getInputStream().read(b)
        assertEquals("hi", String(b))
        s.close(); echo.close()
    }

    @Test(timeout = 10000) fun emptyAddressesThrows() {
        try { OutboundConnector(allowAll).connectAny(emptyList(), 80); fail("应抛 ProxyException") }
        catch (e: ProxyException) { /* ok */ }
    }

    @Test(timeout = 10000) fun allBlockedThrows() {
        val guard = object : EgressGuard { override fun isAllowed(addr: InetAddress) = false }
        try { OutboundConnector(guard).connectAny(listOf(InetAddress.getByName("127.0.0.1")), 9); fail("应抛 ProxyException") }
        catch (e: ProxyException) { /* ok */ }
    }

    private fun startEcho(): ServerSocket {
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) {
                    try {
                        val i = c.getInputStream(); val o = c.getOutputStream(); val buf = ByteArray(1024)
                        while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); o.flush() }
                    } catch (e: Exception) {} finally { c.close() }
                }
            }
        }
        return s
    }
}
