package com.mzstd.hxmyproxy

import android.net.Network
import com.mzstd.hxmyproxy.core.network.EgressHealth
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.SocketFactory

/** 实际defaultProbe与健康状态机，只有传输边界替换为可控的本地TCP/端口故障。 */
class EgressProbeFaultTest {
    private class FaultNetwork(private val destinationPort: Int) : SocketFactory() {
        @Volatile var httpsAvailable = false
        var ipv6Only = false
        val targets = CopyOnWriteArrayList<InetSocketAddress>()
        val sockets = CopyOnWriteArrayList<Socket>()
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                val address = endpoint as InetSocketAddress
                targets += address
                if (address.port == 443 && (!httpsAvailable || (ipv6Only && address.address !is Inet6Address))) throw SocketTimeoutException("controlled 443 blackhole")
                check(address.port == 53 || address.port == 443)
                super.connect(InetSocketAddress("127.0.0.1", destinationPort), timeout)
            }
        }.also { sockets += it }
        override fun createSocket(h: String, p: Int): Socket = error("not used")
        override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int): Socket = error("not used")
        override fun createSocket(h: InetAddress, p: Int): Socket = error("not used")
        override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int): Socket = error("not used")
    }

    @Test fun openDnsPortDoesNotHideBrokenHttpsAndRecoveryRequiresSuccessfulHttpsProbe() = runBlocking {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            val transport = FaultNetwork(server.localPort)
            val net = mockk<Network> {
                every { networkHandle } returns 77L
                every { socketFactory } returns transport
            }
            // 正向对照：相同传输明确允许53，但此结果不应影响业务443的健康判定。
            transport.createSocket().use { it.connect(InetSocketAddress("203.0.113.1", 53), 1000); assertTrue(it.isConnected) }
            var now = 1000L
            val health = EgressHealth(nowMs = { now })
            repeat(3) { assertFalse(health.recordFailure(net, "host$it.example")) }
            assertTrue(health.recordFailure(net, "fourth.example"))
            health.confirmOrSideline(net)
            assertTrue(health.isSidelined(net))
            assertEquals(listOf(53) + List(EgressHealth.PROBE_TARGETS.size) { 443 }, transport.targets.map { it.port })
            assertTrue(transport.sockets.all { it.isClosed })
            transport.httpsAvailable = true
            now += EgressHealth.RECHECK_MS
            assertTrue(health.needsRecheck(net))
            health.confirmOrSideline(net)
            assertFalse(health.isSidelined(net))
            assertEquals(EgressHealth.PROBE_TARGETS.map { InetAddress.getByName(it.first) }.toSet(),
                transport.targets.filter { it.port == 443 }.map { it.address }.toSet())
            assertTrue(transport.sockets.all { it.isClosed })
        }
    }
    @Test fun ipv4ProbeFailuresCannotSidelineReachableIpv6() = runBlocking {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            val transport = FaultNetwork(server.localPort).apply { httpsAvailable = true; ipv6Only = true }
            val net = mockk<Network> {
                every { networkHandle } returns 78L
                every { socketFactory } returns transport
            }
            val health = EgressHealth(nowMs = { 1000L })
            health.confirmOrSideline(net)
            assertFalse(health.isSidelined(net))
            assertEquals(7, transport.targets.size)
            assertEquals(2, transport.targets.count { it.address is Inet6Address })
            assertTrue(transport.sockets.all { it.isClosed })
        }
    }

}
