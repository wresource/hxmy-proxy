package com.mzstd.hxmyproxy

import android.net.Network
import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.model.EgressNetworkChoice
import com.mzstd.hxmyproxy.core.model.EgressFallback
import com.mzstd.hxmyproxy.core.network.SystemDnsLookup
import com.mzstd.hxmyproxy.core.network.UnderlyingNetworkProvider
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.security.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/** Real client-to-proxy sockets; denied target sockets must never be created. */
class LocalNetworkProxyIntegrationTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val privateIp = InetAddress.getByName("10.251.252.253")
    private inner class Fixture : AutoCloseable {
        val guard = DefaultEgressGuard(blockPrivateLan = true)
        val factory = mockk<SocketFactory> { every { createSocket() } throws AssertionError("Unexpected outbound socket") }
        val network = mockk<Network>(relaxed = true) {
            every { networkHandle } returns 991L
            every { socketFactory } returns factory
        }
        val provider = mockk<UnderlyingNetworkProvider>(relaxed = true) {
            every { egressSelection() } returns UnderlyingNetworkProvider.EgressSelection(EgressNetworkChoice.WIFI, network)
            every { current() } returns network
        }
        val connector = OutboundConnector(guard, underlyingNetworkProvider = provider,
            systemDns = SystemDnsLookup { _, _ -> listOf(privateIp) },
            datagramFactory = DatagramEndpointFactory { _, _, _ -> error("Denied datagram endpoint was opened") })
            .apply { backupDnsEnabled = false }
        val session = ConnectUdpSession()
        val registry = ConnectionRegistry(16, 16)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val socks = Socks5ProxyServer(Dispatchers.IO, AllowAllAccessController, registry, connector, RelayEngine(),
            { NoAuthAuthenticator }, { ConnectionLimits() }, Dispatchers.IO, udpSession = session,
            proxyLocalTargets = { !guard.blockPrivateLan })
        val http = HttpProxyServer(Dispatchers.IO, AllowAllAccessController, registry, connector, RelayEngine(),
            { NoAuthAuthenticator }, { ConnectionLimits() }, Dispatchers.IO, udpSession = session,
            socksCapabilities = { socks.capabilities() }, proxyLocalTargets = { !guard.blockPrivateLan })
        val ports: List<Int>
        init {
            http.start(scope, 0); socks.start(scope, 0)
            ports = listOf(ProxyTestKit.awaitPort(http), ProxyTestKit.awaitPort(socks))
        }
        fun request(port: Int, request: String): Pair<String, String> = Socket(loopback, port).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write(request.toByteArray())
            val input = socket.getInputStream()
            val header = buildString {
                while (!endsWith("\r\n\r\n")) {
                    val next = input.read(); check(next >= 0) { "EOF before response" }
                    append(next.toChar()); check(length < 32768)
                }
            }
            val size = Regex("(?i)content-length: ([0-9]+)").find(header)!!.groupValues[1].toInt()
            val body = ByteArray(size); DataInputStream(input).readFully(body)
            header to String(body)
        }
        override fun close() {
            session.close(); http.stop(); socks.stop(); scope.cancel(); connector.cancelPendingDns()
            assertTrue(ProxyTestKit.await(3000) { registry.activeGlobal == 0 && session.activeLeases == 0 })
        }
    }

    @Test(timeout = 15000) fun bothProtocolEntriesPublishTheAppliedPolicyAndRevision() {
        Fixture().use { f ->
            for (allowed in listOf(false, true, false)) {
                if (allowed != !f.guard.blockPrivateLan)
                    f.session.changePolicy("local-target-test") { f.guard.blockPrivateLan = !allowed }
                val revision = f.session.currentEpoch
                for (port in f.ports) {
                    val (header, body) = f.request(port, "GET /__hxmyproxy/capabilities HTTP/1.1\r\nHost: test\r\n\r\n")
                    assertTrue(header.startsWith("HTTP/1.1 200"))
                    assertTrue(body.contains("\"session_id\":\"${f.session.id}\""))
                    assertTrue(body.contains("\"client_policy\":{\"version\":1,\"revision\":$revision,\"proxy_local_targets\":$allowed}"))
                }
            }
            verify(exactly = 0) { f.factory.createSocket() }
        }
    }

    @Test(timeout = 15000) fun httpConnectForwardAndUdpUpgradeRefuseWithClientBypassGuidance() {
        Fixture().use { f ->
            val target = privateIp.hostAddress!!
            val requests = listOf(
                "CONNECT $target:9999 HTTP/1.1\r\nHost: $target:9999\r\n\r\n",
                "GET http://$target:9999/ HTTP/1.1\r\nHost: $target:9999\r\nConnection: close\r\n\r\n",
                "GET /.well-known/masque/udp/$target/9999/ HTTP/1.1\r\nHost: test\r\nConnection: Upgrade\r\nUpgrade: connect-udp\r\nCapsule-Protocol: ?1\r\n\r\n",
            )
            for (request in requests) {
                val (header, body) = f.request(f.ports[0], request)
                assertTrue(header, header.startsWith("HTTP/1.1 403"))
                assertTrue(body, body.contains("local-network-disabled"))
                assertTrue(body, body.contains("client") && body.contains("bypass"))
            }
            verify(exactly = 0) { f.factory.createSocket() }
        }
    }

    @Test(timeout = 10000) fun socksConnectUsesRulesetDeniedWithoutChangingTheInboundHandshake() {
        Fixture().use { f -> Socket(loopback, f.ports[1]).use { socket ->
            socket.soTimeout = 3000
            val input = DataInputStream(socket.getInputStream()); val output = socket.getOutputStream()
            output.write(byteArrayOf(5, 1, 0)); val greeting = ByteArray(2); input.readFully(greeting)
            assertArrayEquals(byteArrayOf(5, 0), greeting)
            output.write(byteArrayOf(5, 1, 0, 1) + privateIp.address + byteArrayOf(0x27, 0x0f))
            val reply = ByteArray(10); input.readFully(reply)
            assertEquals(5, reply[0].toInt()); assertEquals(2, reply[1].toInt())
            verify(exactly = 0) { f.factory.createSocket() }
        } }
    }

    @Test(timeout = 15000) fun publicNameWithPrivateAnswerExplainsDnsInsteadOfRecommendingLanOptIn() {
        Fixture().use { f ->
            for (request in listOf(
                "CONNECT public.example:443 HTTP/1.1\r\nHost: public.example:443\r\n\r\n",
                "GET http://public.example/ HTTP/1.1\r\nHost: public.example\r\nConnection: close\r\n\r\n",
                "GET /.well-known/masque/udp/public.example/443/ HTTP/1.1\r\nHost: test\r\nConnection: Upgrade\r\nUpgrade: connect-udp\r\nCapsule-Protocol: ?1\r\n\r\n",
            )) {
                val (header, body) = f.request(f.ports[0], request)
                assertTrue(header.startsWith("HTTP/1.1 403"))
                assertTrue(body, body.contains("dns-local-address"))
                assertFalse(body, body.contains("enable Allow local network targets"))
            }
            verify(exactly = 0) { f.factory.createSocket() }
        }
    }

    @Test(timeout = 10000) fun denialAppliesToBothRoutesNativeUdpAndBindButNotPublicTargets() = runBlocking {
        Fixture().use { f ->
            for (direct in listOf(false, true)) {
                val failure = runCatching { f.connector.connectAny(listOf(privateIp), 443, f.network, direct) }.exceptionOrNull()
                assertSame(ProxyError.LocalNetworkBlocked, (failure as ProxyException).error)
                for (native in listOf(false, true)) {
                    val udp = runCatching { f.connector.prepareDatagram(privateIp.hostAddress!!, 9999, direct, nativeSocks = native) }.exceptionOrNull()
                    assertSame(ProxyError.LocalNetworkBlocked, (udp as ProxyException).error)
                }
                val bind = runCatching { f.connector.prepareBind(privateIp.hostAddress!!, direct,
                    TcpBindListenerFactory { _, _ -> error("Denied BIND was opened") }, null, RequestTrace.open("SOCKS5", 1234)) }.exceptionOrNull()
                assertSame(ProxyError.LocalNetworkBlocked, (bind as ProxyException).error)
            }
            val socket = mockk<Socket>(relaxed = true)
            every { f.factory.createSocket() } returns socket
            val publicIp = InetAddress.getByName("8.8.8.8")
            f.connector.connectAny(listOf(publicIp), 443, f.network).close()
            verify { socket.connect(match { (it as InetSocketAddress).address == publicIp }, any()) }
            f.guard.blockPrivateLan = false
            f.connector.connectAny(listOf(privateIp), 443, f.network).close()
            verify { socket.connect(match { (it as InetSocketAddress).address == privateIp }, any()) }
            assertFalse(f.guard.isAllowed(loopback))
            assertFalse(f.guard.isAllowed(InetAddress.getByName("169.254.1.1")))
        }
    }

    @Test(timeout = 10000) fun explicitLocalNamesAndIpsRefuseBeforeDnsOrNetworkFallback() = runBlocking {
        val guard = DefaultEgressGuard(true)
        val provider = mockk<UnderlyingNetworkProvider>(relaxed = true)
        val connector = OutboundConnector(guard, underlyingNetworkProvider = provider,
            systemDns = SystemDnsLookup { _, _ -> error("Local target reached DNS") })
            .apply { backupDnsEnabled = false; egressFallback = EgressFallback.DEGRADE }
        try {
            for (host in listOf("nas", "NAS.LOCAL.", "router.home.arpa", "192.168.50.152", "100.126.147.5", "fd00::1")) {
                for (direct in listOf(false, true)) {
                    val attempts: List<suspend () -> Unit> = listOf(
                        { connector.connect(host, 445, direct).close() },
                        { connector.connectChannel(host, 445, direct).close() },
                        { connector.prepareDatagram(host, 9999, direct).close() },
                        { connector.prepareBind(host, direct, TcpBindListenerFactory { _, _ -> error("Unexpected BIND") },
                            null, RequestTrace.open("SOCKS5", 1234)).listener.close() },
                    )
                    for (attempt in attempts) {
                        val error = runCatching { attempt() }.exceptionOrNull()
                        assertSame("$host", ProxyError.LocalNetworkBlocked, (error as ProxyException).error)
                    }
                }
            }
            verify(exactly = 0) { provider.egressSelection() }
            verify(exactly = 0) { provider.current() }
            verify(exactly = 0) { provider.fallbackEgress(any()) }
        } finally { connector.cancelPendingDns() }
    }

    @Test(timeout = 10000) fun publicNameCanKeepTheExistingPhysicalDnsRecoveryWithoutDialingPrivateAnswers() = runBlocking {
        val guard = DefaultEgressGuard(true)
        val socket = mockk<Socket>(relaxed = true)
        val firstFactory = mockk<SocketFactory> { every { createSocket() } throws AssertionError("Blocked address dialed") }
        val backupFactory = mockk<SocketFactory> { every { createSocket() } returns socket }
        val first = mockk<Network>(relaxed = true) { every { networkHandle } returns 21L; every { socketFactory } returns firstFactory }
        val backup = mockk<Network>(relaxed = true) { every { networkHandle } returns 22L; every { socketFactory } returns backupFactory }
        val provider = mockk<UnderlyingNetworkProvider>(relaxed = true) {
            every { egressSelection() } returns UnderlyingNetworkProvider.EgressSelection(EgressNetworkChoice.WIFI, first)
            every { fallbackEgress(first) } returns backup
        }
        val publicIp = InetAddress.getByName("8.8.8.8")
        val connector = OutboundConnector(guard, underlyingNetworkProvider = provider,
            systemDns = SystemDnsLookup { network, _ -> if (network === first) listOf(privateIp) else listOf(publicIp) })
            .apply { backupDnsEnabled = false; egressFallback = EgressFallback.DEGRADE }
        try {
            connector.connect("public.example", 443).close()
            verify(exactly = 0) { firstFactory.createSocket() }
            verify { socket.connect(match { (it as InetSocketAddress).address == publicIp }, any()) }
        } finally { connector.cancelPendingDns() }
    }
}
