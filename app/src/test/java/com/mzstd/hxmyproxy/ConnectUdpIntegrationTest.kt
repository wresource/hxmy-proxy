package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.network.SystemDnsLookup
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.rules.*
import com.mzstd.hxmyproxy.core.security.*
import com.mzstd.hxmyproxy.core.security.Authenticator
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Real loopback TCP/UDP and production HTTP/capsule/session paths. Only Android fd APIs are replaced. */
class ConnectUdpIntegrationTest {
    private val address = InetAddress.getByName("127.0.0.1")
    private val allowed = object : EgressGuard { override fun isAllowed(addr: InetAddress) = true }
    private inner class Peer : AutoCloseable {
        val socket = DatagramSocket(InetSocketAddress(address, 0)).apply { receiveBufferSize = 1024 * 1024 }
        val sources = Collections.synchronizedList(mutableListOf<Int>())
        val packets = AtomicInteger()
        private val worker = thread(isDaemon = true, name = "qa-udp-peer") {
            try {
                while (!socket.isClosed) {
                    val packet = DatagramPacket(ByteArray(65535), 65535)
                    socket.receive(packet); sources.add(packet.port); packets.incrementAndGet()
                    socket.send(DatagramPacket(packet.data, packet.length, packet.address, packet.port))
                }
            } catch (_: SocketException) { }
        }
        override fun close() { socket.close(); worker.join(2000); assertFalse(worker.isAlive) }
    }

    private inner class Fixture(
        val auth: Authenticator = NoAuthAuthenticator,
        val rules: RuleEngine = RuleEngine(),
        maxFlows: Int = 4,
        val dns: SystemDnsLookup = SystemDnsLookup { _, _ -> listOf(address) },
        val beforeOpen: () -> Unit = {},
        val maxWirePayload: Int = 65507,
    ) : AutoCloseable {
        val session = ConnectUdpSession(maxFlows)
        val registry = ConnectionRegistry(32, 32)
        val dnsCalls = AtomicInteger(); val opened = AtomicInteger(); val closed = AtomicInteger()
        val up = AtomicLong(); val down = AtomicLong()
        val channels = Collections.synchronizedList(mutableListOf<DatagramChannel>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val connector = OutboundConnector(allowed, systemDns = SystemDnsLookup { net, host -> dnsCalls.incrementAndGet(); dns.resolve(net, host) },
            datagramTargetAllowed = { true }, datagramFactory = DatagramEndpointFactory { ip, port, _ ->
                beforeOpen()
                val channel = DatagramChannel.open().apply { bind(InetSocketAddress(address, 0)); configureBlocking(false) }
                channels.add(channel); opened.incrementAndGet()
                object : DatagramEndpoint {
                    override val channel = channel
                    override val peer = InetSocketAddress(ip, port)
                    private var done = false
                    override fun send(payload: ByteArray): DatagramSendResult {
                        if (payload.size > maxWirePayload) return DatagramSendResult.TOO_LARGE
                        val n = channel.send(ByteBuffer.wrap(payload), peer)
                        return if (n == payload.size) DatagramSendResult.SENT else DatagramSendResult.WOULD_BLOCK
                    }
                    @Synchronized override fun close() { if (!done) { done = true; channel.close(); closed.incrementAndGet() } }
                }
            }).apply { backupDnsEnabled = false }
        private val accounting = TrafficAccounting(globalSink = { u, d -> up.addAndGet(u); down.addAndGet(d) })
        val server = HttpProxyServer(Dispatchers.IO, AllowAllAccessController, registry, connector, RelayEngine(),
            { auth }, { ConnectionLimits(idleTimeoutSeconds = 2) }, Dispatchers.IO, accounting = accounting,
            ruleEngine = rules, udpSession = session)
        val port: Int
        init { server.start(scope, 0); port = ProxyTestKit.awaitPort(server) }
        fun socket() = Socket(address, port).apply { soTimeout = 3000; tcpNoDelay = true }
        fun capabilities(authHeader: String = ""): Pair<Int, String> = socket().use { s ->
            s.getOutputStream().write("GET /__hxmyproxy/capabilities HTTP/1.1\r\nHost: test\r\n$authHeader\r\n".toByteArray())
            val head = headers(s)
            val length = Regex("(?i)content-length: ([0-9]+)").find(head)!!.groupValues[1].toInt()
            val body = ByteArray(length); DataInputStream(s.getInputStream()).readFully(body)
            assertEquals(1, Regex("(?i)content-type:").findAll(head).count())
            head.substringAfter(' ').substringBefore(' ').toInt() to String(body)
        }
        fun upgrade(peerPort: Int, host: String = "echo.example", token: String? = session.id, authHeader: String = ""): Pair<Socket, String> {
            val s = socket()
            val request = "GET /.well-known/masque/udp/$host/$peerPort/ HTTP/1.1\r\nHost: test\r\nConnection: Upgrade\r\nUpgrade: connect-udp\r\nCapsule-Protocol: ?1\r\n" +
                (token?.let { "X-HxmyProxy-Session: $it\r\n" } ?: "") + authHeader + "\r\n"
            try {
                s.getOutputStream().write(request.toByteArray())
                return s to headers(s)
            } catch (failure: Throwable) { s.close(); throw failure }
        }
        override fun close() {
            session.close(); server.stop(); connector.cancelPendingDns(); scope.cancel()
            assertTrue(ProxyTestKit.await(3000) { session.activeLeases == 0 && registry.activeGlobal == 0 })
            assertEquals(0, session.reactor.activeFlows); assertEquals(0, session.reactor.bufferedBytes)
            assertEquals(opened.get(), closed.get())
        }
    }

    private fun headers(socket: Socket): String {
        val text = StringBuilder();val input = socket.getInputStream()
        while (!text.endsWith("\r\n\r\n")) { val b = input.read();check(b >= 0) { "EOF before headers: $text" };text.append(b.toChar());check(text.length < 32768) }
        return text.toString()
    }
    private fun send(socket: Socket, payload: ByteArray) {
        val frame = UdpCapsuleEncoder.datagram(payload).bytes
        val wire = ByteArray(frame.remaining());frame.get(wire);socket.getOutputStream().write(wire)
    }
    private fun receive(socket: Socket): ByteArray {
        val input = socket.getInputStream()
        fun number(): Long { val first = input.read();check(first >= 0);var n = (first and 63).toLong();repeat((1 shl (first ushr 6)) - 1) { val b = input.read();check(b >= 0);n = (n shl 8) or b.toLong() };return n }
        assertEquals(0, number()); val length = number();assertEquals(0, number())
        return ByteArray((length - 1).toInt()).also { DataInputStream(input).readFully(it) }
    }

    @Test(timeout = 15000) fun capabilitySessionAndAuthenticationAreCheckedBeforeDns() {
        Fixture(auth = SingleCredentialAuthenticator("user", "secret", true)).use { f ->
            val basic = "Proxy-Authorization: Basic ${Base64.getEncoder().encodeToString("user:secret".toByteArray())}\r\n"
            //407 intentionally has no body/Content-Type; inspect directly.
            f.socket().use { s -> s.getOutputStream().write("GET /__hxmyproxy/capabilities HTTP/1.1\r\nHost: test\r\n\r\n".toByteArray());assertTrue(headers(s).contains(" 407 ")) }
            val (code, body) = f.capabilities(basic)
            assertEquals(200, code);assertTrue(body.contains("\"session_id\":\"${f.session.id}\""));assertTrue(body.contains("\"max_udp_payload\":65507"))
            for (token in listOf("", "stale")) f.upgrade(9, token = token, authHeader = basic).let { (s,h) -> s.use { assertTrue(h.contains(" 409 ")) } }
            f.upgrade(9, authHeader = "").let { (s,h) -> s.use { assertTrue(h.contains(" 407 ")) } }
            assertEquals(0, f.dnsCalls.get());assertEquals(0, f.opened.get())
        }
    }

    @Test(timeout = 15000) fun ordinaryAuthenticatedClientUsesPhoneUdpWithoutShimOrCapabilityQuery() {
        Peer().use { peer -> Fixture(auth = SingleCredentialAuthenticator("user", "secret", true)).use { f ->
            val basic = "Proxy-Authorization: Basic ${Base64.getEncoder().encodeToString("user:secret".toByteArray())}\r\n"
            val (s,h) = f.upgrade(peer.socket.localPort, token = null, authHeader = basic)
            s.use {
                assertTrue("No private session extension is required", h.contains(" 101 "))
                send(s,byteArrayOf(8,9));assertArrayEquals(byteArrayOf(8,9),receive(s))
                assertEquals(1,peer.packets.get());assertEquals(1,f.dnsCalls.get())
            }
        } }
    }

    @Test(timeout = 15000) fun credentialRotationBetweenVerificationAndLeaseCannotAdmitOldHandshake() {
        val credentials = SingleCredentialAuthenticator("user", "old", true)
        var rotate: () -> Unit = {}
        val auth = object : Authenticator {
            override val enabled get() = credentials.enabled
            override fun verify(username: String, password: String): Boolean {
                val accepted = credentials.verify(username, password)
                if (accepted) rotate()
                return accepted
            }
        }
        fun header(password: String) = "Proxy-Authorization: Basic ${Base64.getEncoder().encodeToString("user:$password".toByteArray())}\r\n"
        Peer().use { peer -> Fixture(auth = auth).use { f ->
            rotate = { credentials.password = "new"; f.session.invalidate("credentials-changed"); rotate = {} }
            f.upgrade(peer.socket.localPort, token = null, authHeader = header("old")).let { (s,h) ->
                s.use { assertTrue("A revoked authorization must not join a new session epoch: $h", h.contains(" 409 ")) }
            }
            assertEquals(0, f.dnsCalls.get()); assertEquals(0, f.opened.get())
            f.upgrade(peer.socket.localPort, token = null, authHeader = header("new")).let { (s,h) -> s.use {
                assertTrue(h.contains(" 101 ")); send(s, byteArrayOf(24)); assertArrayEquals(byteArrayOf(24), receive(s))
            } }
        } }
    }

    @Test(timeout = 15000) fun duplicateProxyAuthorizationIsRejectedBeforeDiscoveryOrDns() {
        Fixture(auth = SingleCredentialAuthenticator("user", "secret", true)).use { f ->
            val header = "Proxy-Authorization: Basic ${Base64.getEncoder().encodeToString("user:secret".toByteArray())}\r\n"
            f.socket().use { s ->
                s.getOutputStream().write(("GET /__hxmyproxy/capabilities HTTP/1.1\r\nHost: test\r\n" + header + header + "\r\n").toByteArray())
                assertTrue(headers(s).contains(" 407 "))
            }
            f.upgrade(9, token = null, authHeader = header + header).let { (s,h) -> s.use { assertTrue(h.contains(" 407 ")) } }
            assertEquals(0, f.dnsCalls.get()); assertEquals(0, f.opened.get())
            assertEquals(200, f.capabilities(header).first)
        }
    }

    @Test(timeout = 15000) fun malformedUtf8IsNotAnAlternativeEncodingOfTheReplacementCharacterPassword() {
        Fixture(auth=SingleCredentialAuthenticator("u","\uFFFD",true)).use { f ->
            val bad="Proxy-Authorization: Basic ${Base64.getEncoder().encodeToString(byteArrayOf('u'.code.toByte(),':'.code.toByte(),0xff.toByte()))}\r\n"
            f.socket().use { s ->
                s.getOutputStream().write(("GET /__hxmyproxy/capabilities HTTP/1.1\r\nHost: test\r\n"+bad+"\r\n").toByteArray())
                val h=headers(s);assertTrue(h.contains(" 407 "));assertTrue(h.contains("charset=\"UTF-8\""))
            }
            val valid="Proxy-Authorization: Basic ${Base64.getEncoder().encodeToString("u:\uFFFD".toByteArray())}\r\n"
            assertEquals(200,f.capabilities(valid).first)
            assertEquals(0,f.dnsCalls.get());assertEquals(0,f.opened.get())
        }
    }

    @Test(timeout = 15000) fun oversizedRequestLineGets414WithoutAffectingAnExistingUdpCarrier() {
        Peer().use { peer -> Fixture().use { f ->
            val (active,head)=f.upgrade(peer.socket.localPort,token=null)
            active.use {
                assertTrue(head.contains(" 101 "))
                f.socket().use { oversized ->
                    oversized.getOutputStream().write(("GET /"+"x".repeat(65523)+" HTTP/1.1").toByteArray())
                    oversized.shutdownOutput();assertTrue(headers(oversized).contains(" 414 "))
                }
                send(active,byteArrayOf(44));assertArrayEquals(byteArrayOf(44),receive(active))
                assertEquals(200,f.capabilities().first)
            }
        } }
    }

    @Test(timeout = 15000) fun oversizedHeaderLineGets431BeforeDnsOrAllocation() {
        Fixture().use { f ->
            f.socket().use { s ->
                s.getOutputStream().write(("GET /__hxmyproxy/capabilities HTTP/1.1\r\nHost: test\r\nX:"+"x".repeat(65535)).toByteArray())
                s.shutdownOutput();assertTrue(headers(s).contains(" 431 "))
            }
            assertEquals(0,f.dnsCalls.get());assertEquals(0,f.opened.get())
            assertEquals(200,f.capabilities("X-Large: "+"x".repeat(63*1024)+"\r\n").first)
        }
    }

    @Test(timeout = 15000) fun totalHeaderTextAndCountAreBoundedIndependently() {
        Fixture().use { f ->
            for (extra in listOf(("X: "+"x".repeat(4090)+"\r\n").repeat(65),"X: v\r\n".repeat(256))) {
                f.socket().use { s ->
                    s.getOutputStream().write(("GET /__hxmyproxy/capabilities HTTP/1.1\r\nHost: test\r\n"+extra).toByteArray())
                    s.shutdownOutput();assertTrue(headers(s).contains(" 431 "))
                }
            }
            assertEquals(0,f.dnsCalls.get());assertEquals(0,f.opened.get());assertEquals(200,f.capabilities().first)
        }
    }

    @Test(timeout = 15000) fun actualUdpRoundTripsPreserveBoundariesBytesAndOneStableSocket() {
        Peer().use { peer -> Fixture().use { f ->
            val (s,h) = f.upgrade(peer.socket.localPort)
            s.use {
                assertTrue(h.contains(" 101 "));assertTrue(h.contains("X-HxmyProxy-Session: ${f.session.id}"))
                var total = 0L
                for (size in listOf(0,1,63,1200,65507)) {
                    val payload = ByteArray(size) { (it % 251).toByte() };send(s,payload);assertArrayEquals(payload,receive(s));total += size
                }
                assertTrue(ProxyTestKit.await { f.up.get() == total && f.down.get() == total })
                assertEquals(5, peer.packets.get());assertEquals(1, peer.sources.toSet().size);assertEquals(1,f.opened.get())
            }
        } }
    }

    @Test(timeout = 15000) fun unknownFieldsAndEndpointOversizeAreDroppedWithoutBreakingTheNextPacket() {
        Peer().use { peer -> Fixture().use { f ->
            val (s,h)=f.upgrade(peer.socket.localPort);assertTrue(h.contains(" 101 "))
            s.use {
                s.getOutputStream().write(byteArrayOf(1,3,9,8,7,0,2,1,9))
                s.getOutputStream().write(byteArrayOf(0)+UdpCapsuleEncoder.varint(65509)+byteArrayOf(0)+ByteArray(65508))
                send(s,"valid".toByteArray());assertArrayEquals("valid".toByteArray(),receive(s))
                assertEquals(1,peer.packets.get())
                s.getOutputStream().write(byteArrayOf(0)+UdpCapsuleEncoder.varint(65529)+byteArrayOf(0))
                assertEquals(-1,s.getInputStream().read())
            }
        } }
    }

    @Test(timeout = 15000) fun unrelatedUdpSourceAndTooLargeSendDoNotCorruptOtherPackets() {
        Peer().use { peer -> Fixture(maxWirePayload=1200).use { f ->
            val (s,h)=f.upgrade(peer.socket.localPort);assertTrue(h.contains(" 101 "))
            s.use {
                assertTrue(ProxyTestKit.await { f.session.reactor.activeFlows==1 })
                val port=(f.channels.single().localAddress as InetSocketAddress).port
                DatagramSocket(InetSocketAddress(address,0)).use { wrong -> wrong.send(DatagramPacket(byteArrayOf(99),1,address,port)) }
                send(s,ByteArray(1300));send(s,byteArrayOf(42));assertArrayEquals(byteArrayOf(42),receive(s))
                assertEquals(1,peer.packets.get());assertEquals(1,f.up.get())
            }
        } }
    }

    @Test(timeout = 15000) fun rulesRejectBeforeAllocationAndActionChangesCloseOnlyMatchingUdpFlow() {
        val rules=RuleEngine()
        Peer().use { peer -> Fixture(rules=rules).use { f ->
            val (a,ha)=f.upgrade(peer.socket.localPort,"a.example");val (b,hb)=f.upgrade(peer.socket.localPort,"b.example")
            assertTrue(ha.contains(" 101 "));assertTrue(hb.contains(" 101 "))
            a.use { b.use {
                rules.update(RuleEngine.Snapshot(userReject=RuleMatcher().apply { add("a.example") }))
                assertEquals(-1,a.getInputStream().read())
                send(b,byteArrayOf(7));assertArrayEquals(byteArrayOf(7),receive(b))
                val before=f.opened.get()
                f.upgrade(peer.socket.localPort,"a.example").let { (s,h)->s.use { assertTrue(h.contains(" 403 ")) } }
                assertEquals(before,f.opened.get())
            } }
        } }
    }

    @Test(timeout = 15000) fun capacityCountsPendingSetupAndStopCannotRegisterLateWinner() = runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val f=Fixture(maxFlows=1,beforeOpen={entered.countDown();check(release.await(5,TimeUnit.SECONDS))})
        try {
            val request=async(Dispatchers.IO) { runCatching { f.upgrade(9) } }
            assertTrue(withContext(Dispatchers.IO) { entered.await(2,TimeUnit.SECONDS) })
            f.upgrade(9).let { (s,h)->s.use { assertTrue(h.contains(" 429 ")) } }
            f.session.close()
            assertNull(f.session.acquire { })
            assertEquals("Do not release pending native ownership early", 1, f.session.activeLeases)
            release.countDown();request.await().getOrNull()?.first?.close()
            assertTrue(ProxyTestKit.await { f.opened.get()==1 && f.closed.get()==1 && f.session.activeLeases==0 })
            assertEquals(0,f.session.reactor.activeFlows)
        } finally { release.countDown();f.close() }
    }

    @Test(timeout = 15000) fun networkEpochInvalidationCancelsSharedDnsWaiterAndClosesCarrier() = runBlocking {
        val entered=CompletableDeferred<Unit>()
        val f=Fixture(dns=SystemDnsLookup { _,_->entered.complete(Unit);awaitCancellation() })
        try {
            val request=async(Dispatchers.IO) { runCatching { f.upgrade(9) } }
            entered.await();f.session.invalidate("network-changed")
            request.await().getOrNull()?.first?.close()
            assertTrue(ProxyTestKit.await { f.connector.dnsStats().inflight==0 && f.session.activeLeases==0 })
            assertEquals(0,f.opened.get())
        } finally { f.close() }
    }

    @Test(timeout = 15000) fun finalBurstIsDecodedBeforeCarrierEofDespitePerTurnWorkLimit() {
        Peer().use { peer -> Fixture().use { f ->
            val (s,h)=f.upgrade(peer.socket.localPort);assertTrue(h.contains(" 101 "))
            s.use {
                val batch=ByteArrayOutputStream()
                repeat(200) { i -> val frame=UdpCapsuleEncoder.datagram(ByteBuffer.allocate(4).putInt(i).array()).bytes
                    batch.write(ByteArray(frame.remaining()).also { frame.get(it) }) }
                s.getOutputStream().write(batch.toByteArray());s.shutdownOutput()
                s.getInputStream().readBytes()
                assertTrue("complete buffered datagrams must not be abandoned at EOF",ProxyTestKit.await { peer.packets.get()==200 })
                assertEquals(800L,f.up.get())
            }
        } }
    }

    @Test(timeout = 15000) fun udpRevocationDoesNotCloseAnUnrelatedExistingTcpTunnel() {
        ServerSocket(0,1,address).use { tcpPeer ->
            val echo=thread(isDaemon=true) { tcpPeer.accept().use { remote -> remote.soTimeout=5000
                val input=remote.getInputStream();val output=remote.getOutputStream()
                while(true){val b=input.read();if(b<0)break;output.write(b);output.flush()} } }
            Peer().use { peer -> Fixture().use { f ->
                f.socket().use { tcp ->
                    tcp.getOutputStream().write("CONNECT 127.0.0.1:${tcpPeer.localPort} HTTP/1.1\r\nHost: test\r\n\r\n".toByteArray())
                    assertTrue(headers(tcp).contains(" 200 "))
                    val (udp,h)=f.upgrade(peer.socket.localPort);assertTrue(h.contains(" 101 "))
                    udp.use { f.session.invalidate("network-changed");assertEquals(-1,udp.getInputStream().read()) }
                    tcp.getOutputStream().write(81);assertEquals(81,tcp.getInputStream().read())
                }
            } }
            echo.join(3000);assertFalse(echo.isAlive)
        }
    }
}
