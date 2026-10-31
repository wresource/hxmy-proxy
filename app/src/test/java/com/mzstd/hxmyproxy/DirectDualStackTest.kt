package com.mzstd.hxmyproxy

import android.net.Network
import com.mzstd.hxmyproxy.core.model.EgressNetworkChoice
import com.mzstd.hxmyproxy.core.network.SystemDnsLookup
import com.mzstd.hxmyproxy.core.network.UnderlyingNetworkProvider
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.security.EgressGuard
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.nio.channels.DatagramChannel
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory

/** Actual public connector paths; only Android Network binding/DNS are replaced. */
class DirectDualStackTest {
    private val v4 = InetAddress.getByName("127.0.0.1")
    private val v6 = InetAddress.getByName("::1")
    private val allowed = object : EgressGuard { override fun isAllowed(addr: InetAddress) = true }
    private val physical = mockk<Network>(relaxed = true) {
        every { networkHandle } returns 700L
        every { socketFactory } returns SocketFactory.getDefault()
    }
    private val provider = mockk<UnderlyingNetworkProvider>(relaxed = true) {
        every { current() } returns physical
        every { egressSelection() } returns UnderlyingNetworkProvider.EgressSelection(EgressNetworkChoice.AUTO, null)
    }
    private fun connector(answers: List<InetAddress> = listOf(v6, v4)) =
        testConnector(allowed, underlyingNetworkProvider = provider,
            systemDns = SystemDnsLookup { _, _ -> answers }).apply { backupDnsEnabled = false }

    @Test(timeout = 10000) fun directSocketUsesSystemPreferredFamilyWhenBothWork() = runBlocking {
        ServerSocket(0, 4, v4).use { four ->
            ServerSocket(four.localPort, 4, v6).use {
                val c = connector()
                try { c.connect("dual.example", four.localPort, bypassVpn = true).use { socket ->
                    assertEquals("DIRECT must retain the physical resolver preference", v6, socket.inetAddress)
                } } finally { c.cancelPendingDns() }
            }
        }
    }

    @Test(timeout = 10000) fun directChannelUsesSystemPreferredFamilyWhenBothWork() = runBlocking {
        ServerSocket(0, 4, v4).use { four ->
            ServerSocket(four.localPort, 4, v6).use {
                val c = connector()
                try { c.connectChannel("dual-channel.example", four.localPort, bypassVpn = true).use { channel ->
                    assertEquals(v6, (channel.remoteAddress as InetSocketAddress).address)
                    verify(atLeast = 1) { physical.bindSocket(any<Socket>()) }
                } } finally { c.cancelPendingDns() }
            }
        }
    }

    // Real independent Tablet comparison: IPv6 UDP443 silently failed while IPv4
    // worked. A generic datagram relay cannot race/replay application packets.
    @Test(timeout = 10000) fun directDatagramKeepsCompatibleDomainChoiceWithoutSendingProbePayload() = runBlocking {
        val targets = mutableListOf<InetAddress>()
        var sends = 0
        val c = OutboundConnector(allowed, underlyingNetworkProvider = provider,
            systemDns = SystemDnsLookup { _, _ -> listOf(v6, v4) }, datagramTargetAllowed = { true },
            datagramFactory = DatagramEndpointFactory { ip, port, net ->
                assertSame(physical, net); targets += ip
                object : DatagramEndpoint {
                    override val channel = DatagramChannel.open()
                    override val peer = InetSocketAddress(ip, port)
                    override fun send(payload: ByteArray): DatagramSendResult { sends++; return DatagramSendResult.SENT }
                    override fun close() { channel.close() }
                }
            }).apply { backupDnsEnabled = false }
        try {
            c.prepareDatagram("dual-udp.example", 443, true).endpoint.use { assertEquals(v4, it.peer.address) }
            assertEquals(listOf(v4), targets)
            assertEquals(0, sends)
        } finally { c.cancelPendingDns() }
    }

    @Test(timeout = 10000) fun directDatagramPreservesIpv6LiteralAndIpv6OnlyDomain() = runBlocking {
        val targets = mutableListOf<InetAddress>()
        val c = OutboundConnector(allowed, underlyingNetworkProvider = provider,
            systemDns = SystemDnsLookup { _, host -> assertEquals("v6-only.example", host); listOf(v6) },
            datagramTargetAllowed = { true }, datagramFactory = DatagramEndpointFactory { ip, port, net ->
                assertSame(physical, net); targets += ip
                object : DatagramEndpoint {
                    override val channel = DatagramChannel.open()
                    override val peer = InetSocketAddress(ip, port)
                    override fun send(payload: ByteArray): DatagramSendResult = error("No probe payload allowed")
                    override fun close() { channel.close() }
                }
            }).apply { backupDnsEnabled = false }
        try {
            c.prepareDatagram("v6-only.example", 443, true).close()
            c.prepareDatagram("::1", 443, true).close()
            assertEquals(listOf(v6, v6), targets)
        } finally { c.cancelPendingDns() }
    }

    @Test(timeout = 10000) fun directBindUsesPhysicalFirstFamilyBeforeAdvertisingListener() = runBlocking {
        val targets = mutableListOf<InetAddress>()
        val factory = TcpBindListenerFactory { ip, net ->
            assertSame(physical, net); targets += ip
            object : TcpBindListener {
                override val bound = InetSocketAddress(ip, 12345)
                override val networkHandle = 700L
                override fun acceptOrNull(): Socket? = null
                override fun close() {}
            }
        }
        val c = connector()
        try {
            val bound = c.prepareBind("dual-bind.example", true, factory, null, RequestTrace.open("SOCKS5", 10000))
            bound.listener.use { assertEquals(v6, it.bound.address) }
            assertEquals(listOf(v6), targets)
        } finally { c.cancelPendingDns() }
    }

    @Test fun directPoolPreservesEveryLegacyRescueAndStartsWithSystemFamily() {
        val c = connector()
        val four = (1..8).map { InetAddress.getByName("192.0.2.$it") }
        val six = (1..8).map { InetAddress.getByName("2001:db8::$it") }
        val system = six + four
        val legacy = c.capByFamily(c.orderAddresses(system))
        val direct = c.tcpCandidates(c.orderAddresses(system, true), true)
        assertEquals(legacy.toSet(), direct.toSet())
        assertEquals(listOf(six[0], four[0], four[1], four[2], four[3], four[4]), direct)
        assertEquals(legacy, c.tcpCandidates(c.orderAddresses(system)))
    }

    @Test fun directFillsSpareSlotsWithoutEvictingExistingCandidates() {
        val c = connector()
        val six = (1..8).map { InetAddress.getByName("2001:db8::$it") }
        val direct = c.tcpCandidates(six + v4, true)
        assertEquals(listOf(six[0], v4, six[1], six[2], six[3], six[4]), direct)
        assertTrue(direct.containsAll(c.capByFamily(c.orderAddresses(six + v4))))
        assertEquals(listOf(v4, six[0]), c.tcpCandidates(c.orderAddresses(six + v4)))
        assertEquals(six.take(6), c.tcpCandidates(six, true))
        assertEquals(listOf(v4), c.tcpCandidates(listOf(v4), true))
    }

    @Test fun blockedFirstAddressDoesNotChooseDirectPreferredFamily() {
        val c = testConnector(object : EgressGuard {
            override fun isAllowed(addr: InetAddress) = addr != v6
        })
        val otherSix = InetAddress.getByName("2001:db8::9")
        val otherFour = InetAddress.getByName("192.0.2.9")
        assertEquals(listOf(v4, otherSix, otherFour), c.tcpCandidates(listOf(v6, v4, otherFour, otherSix), true))
    }

    @Test(timeout = 10000) fun proxySocketRetainsIpv4Preference() = runBlocking {
        ServerSocket(0, 4, v4).use { four ->
            ServerSocket(four.localPort, 4, v6).use {
                val c = connector()
                try { c.connect("proxy-dual.example", four.localPort).use { assertEquals(v4, it.inetAddress) } }
                finally { c.cancelPendingDns() }
            }
        }
    }

    @Test(timeout = 10000) fun directChannelFallsBackToIpv4WhenIpv6IsRefused() = runBlocking {
        ServerSocket(0, 4, v4).use { four ->
            val c = connector()
            try { withTimeout(2000) { c.connectChannel("v6-refused.example", four.localPort, true) }.use {
                assertEquals(v4, (it.remoteAddress as InetSocketAddress).address)
            } } finally { c.cancelPendingDns() }
        }
    }

    @Test(timeout = 10000) fun directChannelFallsBackToIpv6WhenIpv4IsRefused() = runBlocking {
        ServerSocket(0, 4, v6).use { six ->
            val c = connector(listOf(v4, v6))
            try { withTimeout(2000) { c.connectChannel("v4-refused.example", six.localPort, true) }.use {
                assertEquals(v6, (it.remoteAddress as InetSocketAddress).address)
            } } finally { c.cancelPendingDns() }
        }
    }

    private fun controlledSockets(answers: List<InetAddress>, working: InetAddress?,
                                  opened: MutableList<Socket>, attempts: MutableList<Pair<InetAddress, Long>>): OutboundConnector {
        val factory = mockk<SocketFactory>()
        every { physical.socketFactory } returns factory
        every { factory.createSocket() } answers {
            object : Socket() {
                private val closedFlag = AtomicBoolean(false)
                private val closeSignal = CountDownLatch(1)
                private var selected: InetAddress? = null
                init { opened.add(this) }
                override fun connect(endpoint: SocketAddress?, timeout: Int) {
                    val address = (endpoint as InetSocketAddress).address
                    attempts.add(address to System.nanoTime())
                    if (address != working) {
                        check(closeSignal.await(3, TimeUnit.SECONDS)) { "Losing attempt was not cancelled" }
                        throw SocketException("owned attempt closed")
                    }
                    selected = address
                }
                override fun getInetAddress() = selected
                override fun isClosed() = closedFlag.get()
                override fun close() { closedFlag.set(true); closeSignal.countDown(); super.close() }
            }
        }
        return connector(answers)
    }

    @Test(timeout = 10000) fun silentFirstFamilyStartsOtherFamilyWithoutWaitingItsTimeout() = runBlocking {
        val opened = Collections.synchronizedList(mutableListOf<Socket>())
        val attempts = Collections.synchronizedList(mutableListOf<Pair<InetAddress, Long>>())
        val c = controlledSockets(listOf(v6, v4), v4, opened, attempts)
        try {
            withTimeout(2000) { c.connect("silent-v6.example", 443, true) }.use { assertEquals(v4, it.inetAddress) }
            assertEquals(listOf(v6, v4), attempts.map { it.first })
            val gapMs = (attempts[1].second - attempts[0].second) / 1_000_000
            assertTrue("Expected staggered bounded fallback, got $gapMs ms", gapMs in 150..1500)
            assertTrue(opened.all { it.isClosed })
        } finally { c.cancelPendingDns(); opened.forEach { it.close() } }
    }

    @Test(timeout = 10000) fun cancellationClosesBothDirectFamilies() = runBlocking {
        val opened = Collections.synchronizedList(mutableListOf<Socket>())
        val attempts = Collections.synchronizedList(mutableListOf<Pair<InetAddress, Long>>())
        val c = controlledSockets(listOf(v6, v4), null, opened, attempts)
        try {
            val failure = runCatching { withTimeout(700) { c.connect("cancel-dual.example", 443, true) } }.exceptionOrNull()
            assertTrue(failure is TimeoutCancellationException)
            assertEquals(setOf(v6, v4), attempts.map { it.first }.toSet())
            assertTrue(opened.all { it.isClosed })
        } finally { c.cancelPendingDns(); opened.forEach { it.close() } }
    }

    @Test(timeout = 10000) fun fifthIpv4RescueRemainsReachableAfterDirectInterleaving() = runBlocking {
        val four = (1..8).map { InetAddress.getByName("192.0.2.$it") }
        val six = (1..8).map { InetAddress.getByName("2001:db8::$it") }
        val opened = Collections.synchronizedList(mutableListOf<Socket>())
        val attempts = Collections.synchronizedList(mutableListOf<Pair<InetAddress, Long>>())
        val c = controlledSockets(six + four, four[4], opened, attempts)
        try {
            withTimeout(2500) { c.connect("fifth-v4-rescue.example", 443, true) }.use { assertEquals(four[4], it.inetAddress) }
            assertEquals(6, attempts.size)
            assertTrue(opened.all { it.isClosed })
        } finally { c.cancelPendingDns(); opened.forEach { it.close() } }
    }

    @Test(timeout = 10000) fun secondIpv6CanRescueWhenLegacyPoolWouldLeaveSpareSlotsUnused() = runBlocking {
        val six = (1..8).map { InetAddress.getByName("2001:db8::$it") }
        val opened = Collections.synchronizedList(mutableListOf<Socket>())
        val attempts = Collections.synchronizedList(mutableListOf<Pair<InetAddress, Long>>())
        val c = controlledSockets(six + v4, six[1], opened, attempts)
        try {
            withTimeout(2000) { c.connect("second-v6-rescue.example", 443, true) }.use { assertEquals(six[1], it.inetAddress) }
            assertEquals(listOf(six[0], v4, six[1]), attempts.map { it.first })
            assertTrue(opened.all { it.isClosed })
        } finally { c.cancelPendingDns(); opened.forEach { it.close() } }
    }
}
