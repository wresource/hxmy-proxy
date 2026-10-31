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
import java.io.DataInputStream
import java.net.*
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class SocksUdpIntegrationTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val allow = object : EgressGuard { override fun isAllowed(addr: InetAddress) = true }

    private inner class Peer(val ip: InetAddress = loopback) : AutoCloseable {
        val socket = DatagramSocket(InetSocketAddress(ip, 0))
        val packets = AtomicInteger()
        @Volatile var lastSourcePort = 0
        val worker = thread(isDaemon = true) {
            try { while (!socket.isClosed) {
                val packet = DatagramPacket(ByteArray(65535), 65535); socket.receive(packet); lastSourcePort=packet.port; packets.incrementAndGet()
                socket.send(DatagramPacket(packet.data, packet.length, packet.address, packet.port))
            } } catch (_: SocketException) {}
        }
        override fun close() { socket.close(); worker.join(2000); assertFalse(worker.isAlive) }
    }
    private inner class Fixture(
        val auth: Authenticator = NoAuthAuthenticator,
        capacity: Int = 16,
        perClient: Int = 32,
        val beforeOpen: () -> Unit = {},
        dns: SystemDnsLookup = SystemDnsLookup { _, _ -> listOf(loopback) },
        idleSeconds: Int = 5,
        val expectedReplyAddress: InetAddress? = null,
    ) : AutoCloseable {
        val session = ConnectUdpSession(capacity, enableSocks = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val registry = ConnectionRegistry(64, perClient)
        val rules = RuleEngine()
        val accounting = TrafficAccounting()
        val opened = AtomicInteger(); val closed = AtomicInteger(); val ingresses = AtomicInteger(); val ingressClosed = AtomicInteger()
        val channels = Collections.synchronizedList(mutableListOf<DatagramChannel>())
        val connector = OutboundConnector(allow, systemDns = dns, datagramTargetAllowed = { true }, datagramFactory = DatagramEndpointFactory { ip, port, _ ->
            beforeOpen()
            val channel = DatagramChannel.open(if (ip is Inet4Address) StandardProtocolFamily.INET else StandardProtocolFamily.INET6)
            channel.bind(InetSocketAddress(if (ip is Inet4Address) loopback else InetAddress.getByName("::1"), 0)); channel.configureBlocking(false)
            opened.incrementAndGet(); channels.add(channel)
            object : DatagramEndpoint {
                override val channel = channel
                override val peer = InetSocketAddress(expectedReplyAddress ?: ip, port)
                override val supportsMultipleTargets = true
                private var done = false
                override fun send(payload: ByteArray): DatagramSendResult = sendTo(payload, peer)
                override fun sendTo(payload: ByteArray, destination: InetSocketAddress): DatagramSendResult =
                    if (expectedReplyAddress != null || channel.send(ByteBuffer.wrap(payload), destination) == payload.size) DatagramSendResult.SENT else DatagramSendResult.WOULD_BLOCK
                @Synchronized override fun close() { if (!done) { done = true; channel.close(); closed.incrementAndGet() } }
            }
        }).apply { backupDnsEnabled = false }
        val server = Socks5ProxyServer(Dispatchers.IO, AllowAllAccessController, registry, connector, RelayEngine(), { auth },
            { ConnectionLimits(idleTimeoutSeconds = idleSeconds) }, Dispatchers.IO, accounting = accounting, ruleEngine = rules,
            udpSession = session, udpIngressFactory = SocksUdpIngressFactory { local ->
                val channel = DatagramChannel.open(StandardProtocolFamily.INET).apply { bind(InetSocketAddress(local, 0)); configureBlocking(false) }
                ingresses.incrementAndGet()
                object : SocksUdpIngress {
                    override val channel = channel
                    private var done = false
                    override fun send(payload: ByteArray, peer: InetSocketAddress): DatagramSendResult =
                        if (channel.send(ByteBuffer.wrap(payload), peer) == payload.size) DatagramSendResult.SENT else DatagramSendResult.WOULD_BLOCK
                    @Synchronized override fun close() { if (!done) { done = true; channel.close(); ingressClosed.incrementAndGet() } }
                }
            })
        val port: Int
        init { server.start(scope, 0, loopback); port = ProxyTestKit.awaitPort(server) }
        fun socket() = Socket(loopback, port).apply { soTimeout = 3000 }
        fun associate(udpPort: Int = 0): Pair<Socket, InetSocketAddress> {
            val socket = socket()
            try {
                socket.getOutputStream().write(byteArrayOf(5, 1, 0)); assertArrayEquals(byteArrayOf(5, 0), bytes(socket, 2))
                val request = byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, (udpPort ushr 8).toByte(), udpPort.toByte())
                socket.getOutputStream().write(request)
                val header = bytes(socket, 4); assertArrayEquals(byteArrayOf(5, 0, 0, 1), header)
                val ip = InetAddress.getByAddress(bytes(socket, 4)); val port = DataInputStream(socket.getInputStream()).readUnsignedShort()
                assertEquals(loopback, ip); assertTrue(port > 0)
                return socket to InetSocketAddress(ip, port)
            } catch (error: Throwable) { socket.close(); throw error }
        }
        override fun close() {
            session.close(); server.stop(); connector.cancelPendingDns(); scope.cancel()
            assertTrue("All real quota released", ProxyTestKit.await(5000) { session.activeLeases == 0 && registry.activeGlobal == 0 })
            assertEquals(opened.get(), closed.get()); assertEquals(ingresses.get(), ingressClosed.get())
            assertEquals(0, session.socksReactor!!.activeAssociations); assertEquals(0, session.socksReactor!!.bufferedBytes)
        }
    }
    private fun bytes(socket: Socket, count: Int) = ByteArray(count).also { DataInputStream(socket.getInputStream()).readFully(it) }
    private fun udp() = DatagramSocket(InetSocketAddress(loopback, 0)).apply { soTimeout = 2500 }
    private fun wire(host: String, port: Int, data: ByteArray): ByteArray {
        val name = host.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(0, 0, 0, 3, name.size.toByte()) + name + byteArrayOf((port ushr 8).toByte(), port.toByte()) + data
    }
    private fun send(socket: DatagramSocket, relay: InetSocketAddress, host: String, port: Int, data: ByteArray) {
        val wire = wire(host, port, data); socket.send(DatagramPacket(wire, wire.size, relay))
    }
    private fun received(socket: DatagramSocket): SocksUdpCodec.Packet {
        val packet = DatagramPacket(ByteArray(65535), 65535); socket.receive(packet)
        return checkNotNull(SocksUdpCodec.decode(ByteBuffer.wrap(packet.data, 0, packet.length)))
    }

    @Test(timeout=15000) fun sameAssociationKeepsTheSourcePortAcrossStunAndANewPeer() {
        Peer().use { stun -> Peer().use { peer -> Fixture().use { f ->
            val (control,relay)=f.associate()
            control.use { udp().use { client ->
                send(client,relay,"127.0.0.1",stun.socket.localPort,byteArrayOf(1));assertArrayEquals(byteArrayOf(1),received(client).payload)
                send(client,relay,"127.0.0.1",peer.socket.localPort,byteArrayOf(2));assertArrayEquals(byteArrayOf(2),received(client).payload)
                assertEquals("STUN mapping must also identify traffic sent to the new peer",stun.lastSourcePort,peer.lastSourcePort)
                val firstSource=stun.lastSourcePort
                val (otherControl,otherRelay)=f.associate()
                otherControl.use { udp().use { other ->
                    send(other,otherRelay,"127.0.0.1",stun.socket.localPort,byteArrayOf(3));assertArrayEquals(byteArrayOf(3),received(other).payload)
                    assertNotEquals("Different client associations must never share an exit socket",firstSource,stun.lastSourcePort)
                } }
            } }
        } } }
    }

    @Test fun codecPreservesEmptyAndDualStackAndRejectsFragmentsMalformedNamesAndPorts() {
        for (ip in listOf("127.0.0.1", "::1")) for (size in listOf(0, 1, 1200)) {
            val peer = InetSocketAddress(InetAddress.getByName(ip), 53000); val payload = ByteArray(size) { it.toByte() }
            val encoded = SocksUdpCodec.encode(peer, payload); val decoded = SocksUdpCodec.decode(ByteBuffer.wrap(encoded))!!
            assertEquals(peer.address, InetAddress.getByName(decoded.host)); assertEquals(peer.port, decoded.port); assertArrayEquals(payload, decoded.payload)
            encoded[2] = 1; assertNull(SocksUdpCodec.decode(ByteBuffer.wrap(encoded)))
        }
        for (host in listOf("http://bad.example", "127.0.0.1/8", "bad example", "a%2eb.example", ""))
            assertNull(SocksUdpCodec.decode(ByteBuffer.wrap(wire(host, 53, byteArrayOf(1)))))
        assertNull(SocksUdpCodec.decode(ByteBuffer.wrap(wire("good.example", 0, byteArrayOf(1)))))
        assertNotNull(SocksUdpCodec.decode(ByteBuffer.wrap(wire("good.example", 53, ByteArray(0)))))
    }

    @Test fun declaredWireCeilingIncludesTheTargetHeaderForBothAddressFamilies() {
        for (host in listOf("127.0.0.1","::1")) {
            val endpoint=InetSocketAddress(InetAddress.getByName(host),53000)
            val overhead=6+endpoint.address.address.size
            val valid=SocksUdpCodec.encode(endpoint,ByteArray(SocksUdpCodec.MAX_WIRE_BYTES-overhead))
            assertEquals(SocksUdpCodec.MAX_WIRE_BYTES,valid.size)
            assertNotNull(SocksUdpCodec.decode(ByteBuffer.wrap(valid)))
            assertNull(SocksUdpCodec.decode(ByteBuffer.wrap(SocksUdpCodec.encode(endpoint,ByteArray(SocksUdpCodec.MAX_WIRE_BYTES-overhead+1)))))
        }
    }

    @Test(timeout = 15000) fun actualMultipleTargetsAndEmptyPayloadPreserveAccountingAndOneAssociation() {
        Peer().use { a -> Peer().use { b -> Fixture().use { f ->
            val (control, relay) = f.associate()
            control.use { udp().use { client ->
                var total = 0L
                for (peer in listOf(a, b)) for (size in listOf(0, 1, 1200)) {
                    val data = ByteArray(size) { (it % 251).toByte() }; send(client, relay, "same.example", peer.socket.localPort, data)
                    val reply = received(client); assertEquals(peer.socket.localPort, reply.port); assertArrayEquals(data, reply.payload); total += size
                }
                assertTrue(ProxyTestKit.await { f.accounting.snapshot().topDomains.sumOf { it.uploadBytes } == total })
                val domains = f.accounting.snapshot().topDomains; assertEquals(1, domains.size)
                assertEquals(total, domains.single().uploadBytes); assertEquals(total, domains.single().downloadBytes)
                assertEquals(2, f.opened.get()); assertEquals(3, f.registry.activeGlobal)
            } }
        } } }
    }

    @Test(timeout = 15000) fun ipv6TargetUsesSameIpv4IngressAndRemoteAddressInReply() {
        Peer(InetAddress.getByName("::1")).use { peer -> Fixture().use { f ->
            val (control, relay) = f.associate()
            control.use { udp().use { client ->
                val packet = SocksUdpCodec.encode(InetSocketAddress(peer.ip, peer.socket.localPort), byteArrayOf(4, 6))
                client.send(DatagramPacket(packet, packet.size, relay)); val reply = received(client)
                assertEquals(peer.ip, InetAddress.getByName(reply.host)); assertArrayEquals(byteArrayOf(4, 6), reply.payload)
            } }
        } }
    }

    @Test(timeout = 15000) fun rejectedAuthenticationAndDifferentDeclaredClientDoNotOpenUdp() {
        Fixture(auth = SingleCredentialAuthenticator("u", "p", true)).use { f ->
            f.socket().use { s -> s.getOutputStream().write(byteArrayOf(5, 1, 0)); assertArrayEquals(byteArrayOf(5, 255.toByte()), bytes(s, 2)) }
            assertEquals(0, f.ingresses.get())
        }
        Fixture().use { f ->
            f.socket().use { s ->
                s.getOutputStream().write(byteArrayOf(5, 1, 0)); bytes(s, 2)
                s.getOutputStream().write(byteArrayOf(5, 3, 0, 1, 127, 0, 0, 2, 0, 0)); assertEquals(2, bytes(s, 10)[1].toInt())
            }; assertEquals(0, f.ingresses.get())
        }
    }

    @Test(timeout = 15000) fun credentialRotationAfterVerificationCannotCreateANewNativeAssociation() {
        val credentials = SingleCredentialAuthenticator("u", "old", true)
        var rotate: () -> Unit = {}
        val auth = object : Authenticator {
            override val enabled get() = credentials.enabled
            override fun verify(username: String, password: String): Boolean {
                val accepted = credentials.verify(username, password)
                if (accepted) rotate()
                return accepted
            }
        }
        Fixture(auth = auth).use { f ->
            rotate = { credentials.password = "new"; f.session.invalidate("credentials-changed"); rotate = {} }
            fun request(password: String): Pair<Socket, ByteArray> {
                val s = f.socket()
                try {
                    s.getOutputStream().write(byteArrayOf(5,1,2)); assertArrayEquals(byteArrayOf(5,2),bytes(s,2))
                    s.getOutputStream().write(byteArrayOf(1,1,'u'.code.toByte(),password.length.toByte())+password.toByteArray())
                    assertArrayEquals(byteArrayOf(1,0),bytes(s,2))
                    s.getOutputStream().write(byteArrayOf(5,3,0,1,0,0,0,0,0,0))
                    return s to bytes(s,10)
                } catch (failure: Throwable) { s.close(); throw failure }
            }
            request("old").let { (s,reply) -> s.use { assertEquals("Old handshake must not enter a new epoch",1,reply[1].toInt()) } }
            assertEquals(0, f.ingresses.get()); assertEquals(0, f.opened.get())
            request("new").let { (s,reply) -> s.use { assertEquals(0,reply[1].toInt()) } }
            assertTrue(ProxyTestKit.await { f.ingresses.get()==1 && f.ingressClosed.get()==1 })
        }
    }

    @Test(timeout = 15000) fun nativeMalformedUtf8CannotAuthenticateAsAReplacementCharacter() {
        Fixture(auth=SingleCredentialAuthenticator("u","\uFFFD",true)).use { f ->
            fun authenticate(password: ByteArray, expected: Int) {
                f.socket().use { s ->
                    s.getOutputStream().write(byteArrayOf(5,1,2));assertArrayEquals(byteArrayOf(5,2),bytes(s,2))
                    s.getOutputStream().write(byteArrayOf(1,1,'u'.code.toByte(),password.size.toByte())+password)
                    assertArrayEquals(byteArrayOf(1,expected.toByte()),bytes(s,2))
                    if(expected!=0)assertEquals(-1,s.getInputStream().read())
                }
            }
            authenticate(byteArrayOf(0xff.toByte()),1)
            authenticate("\uFFFD".toByteArray(),0)
            assertEquals(0,f.ingresses.get());assertEquals(0,f.opened.get())
        }
    }

    @Test(timeout = 15000) fun sourcePortIsPinnedMalformedAndFragmentedPacketsCannotCreateTargets() {
        Peer().use { peer -> Fixture().use { f -> udp().use { client -> udp().use { stranger ->
            val (control, relay) = f.associate(client.localPort)
            control.use {
                send(stranger, relay, "wrong.example", peer.socket.localPort, byteArrayOf(99))
                val fragmented = wire("fragment.example", peer.socket.localPort, byteArrayOf(9)).apply { this[2] = 1 }
                client.send(DatagramPacket(fragmented, fragmented.size, relay))
                send(client, relay, "right.example", peer.socket.localPort, byteArrayOf(42)); assertArrayEquals(byteArrayOf(42), received(client).payload)
                assertEquals(1, f.opened.get()); assertEquals(1, peer.packets.get())
                assertEquals(listOf("right.example"), f.accounting.snapshot().topDomains.map { it.host })
            }
        } } } }
    }

    @Test(timeout = 15000) fun ruleChangeRevokesOnlyAffectedTargetAndControlCloseOwnsEverySocket() {
        Peer().use { peer -> Fixture().use { f ->
            val (control, relay) = f.associate()
            udp().use { client ->
                for (host in listOf("a.example", "b.example")) { send(client, relay, host, peer.socket.localPort, byteArrayOf(1)); received(client) }
                f.rules.update(RuleEngine.Snapshot(userReject = RuleMatcher().apply { add("a.example") }))
                assertTrue(ProxyTestKit.await { f.closed.get() == 1 })
                send(client, relay, "a.example", peer.socket.localPort, byteArrayOf(8))
                send(client, relay, "b.example", peer.socket.localPort, byteArrayOf(9)); assertArrayEquals(byteArrayOf(9), received(client).payload)
                assertEquals(3, peer.packets.get()); assertEquals(2, f.opened.get())
                control.close(); assertTrue(ProxyTestKit.await { f.closed.get() == 2 && f.registry.activeGlobal == 0 && f.ingressClosed.get() == 1 })
            }
        } }
    }

    @Test(timeout = 15000) fun perClientAndSharedUdpQuotaBoundTargetsWithoutBreakingExistingFlow() {
        Peer().use { peer -> Fixture(capacity = 3, perClient = 2).use { f ->
            val (control, relay) = f.associate()
            control.use { udp().use { client ->
                send(client, relay, "first.example", peer.socket.localPort, byteArrayOf(1)); received(client)
                send(client, relay, "excess.example", peer.socket.localPort, byteArrayOf(2))
                send(client, relay, "first.example", peer.socket.localPort, byteArrayOf(3)); assertArrayEquals(byteArrayOf(3), received(client).payload)
                assertEquals(1, f.opened.get()); assertEquals(2, f.registry.activeGlobal); assertEquals(2, f.session.activeLeases)
            } }
        } }
    }

    @Test(timeout = 15000) fun oneControlClosingDoesNotAffectAnotherAssociation() {
        Peer().use { peer -> Fixture().use { f ->
            val (controlA, relayA) = f.associate(); val (controlB, relayB) = f.associate()
            controlA.use { controlB.use { udp().use { a -> udp().use { b ->
                send(a, relayA, "a.example", peer.socket.localPort, byteArrayOf(1)); received(a)
                send(b, relayB, "b.example", peer.socket.localPort, byteArrayOf(2)); received(b)
                send(a, relayA, "a.example", peer.socket.localPort, byteArrayOf(3)); assertArrayEquals(byteArrayOf(3), received(a).payload)
                controlA.close(); assertTrue(ProxyTestKit.await { f.closed.get() == 1 })
                send(b, relayB, "b.example", peer.socket.localPort, byteArrayOf(4)); assertArrayEquals(byteArrayOf(4), received(b).payload)
                assertEquals(2, f.opened.get()); assertEquals(4, peer.packets.get())
            } } } }
        } }
    }

    @Test(timeout = 15000) fun replyFromAnUnrequestedIpIsStillRejected() {
        // Keep the send boundary inert for an unbound documentation IP; receive is a real
        // UDP socket. Native SOCKS permits transfer ports, never arbitrary source addresses.
        Fixture(expectedReplyAddress = InetAddress.getByName("192.0.2.9")).use { f ->
            val (control, relay) = f.associate()
            control.use { udp().use { client -> udp().use { stranger ->
                send(client,relay,"guard.example",53000,byteArrayOf(1))
                assertTrue(ProxyTestKit.await { f.opened.get()==1 })
                val endpoint=f.channels.single().localAddress as InetSocketAddress
                stranger.send(DatagramPacket(byteArrayOf(99),1,endpoint))
                client.soTimeout=250
                assertTrue(runCatching { received(client) }.exceptionOrNull() is SocketTimeoutException)
                assertEquals(0,f.closed.get());assertEquals(2,f.session.activeLeases)
            } } }
        }
    }

    @Test(timeout = 15000) fun offRetainsPendingNativeQuotaUntilLateFactoryActuallyCloses() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Fixture(beforeOpen = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }).use { f ->
            val (control, relay) = f.associate()
            control.use { udp().use { client ->
                send(client, relay, "late.example", 53000, byteArrayOf(1)); assertTrue(entered.await(3, TimeUnit.SECONDS))
                try {
                    f.session.close(); assertEquals(-1, control.getInputStream().read())
                    assertTrue("Pending native creation still owns quota", f.session.activeLeases > 0)
                } finally { release.countDown() }
                assertTrue(ProxyTestKit.await(5000) { f.opened.get() == 1 && f.closed.get() == 1 && f.session.activeLeases == 0 })
            } }
        }
    }

    @Test(timeout = 15000) fun cancellingDnsAndAnIdleAssociationLeavesNoSelectorOrQuota() {
        val entered = CountDownLatch(1)
        Fixture(dns = SystemDnsLookup { _, _ -> entered.countDown(); awaitCancellation() }, idleSeconds = 1).use { f ->
            val (control, relay) = f.associate()
            control.use { udp().use { client ->
                send(client, relay, "waiting.example", 53000, byteArrayOf(1)); assertTrue(entered.await(3, TimeUnit.SECONDS))
                control.close(); assertTrue(ProxyTestKit.await { f.session.activeLeases == 0 && f.registry.activeGlobal == 0 })
                assertEquals(0, f.opened.get())
            } }
            f.associate().first.use { control2 -> assertEquals(-1, control2.getInputStream().read()) }
        }
    }

    @Test(timeout = 15000) fun tftpTransferPortReplyAndAckReuseTheOriginalUdpSource() {
        DatagramSocket(InetSocketAddress(loopback, 0)).use { listener ->
            DatagramSocket(InetSocketAddress(loopback, 0)).use { transfer ->
                listener.soTimeout = 5000; transfer.soTimeout = 5000
                val completed = java.util.concurrent.CompletableFuture<InetSocketAddress>()
                val body = "actual-tftp-content".toByteArray()
                val request = byteArrayOf(0,1) + "file.bin\u0000octet\u0000".toByteArray()
                val worker = thread(isDaemon = true) {
                    try {
                        val incoming = DatagramPacket(ByteArray(1024),1024); listener.receive(incoming)
                        check(incoming.data.copyOf(incoming.length).contentEquals(request))
                        val original = incoming.socketAddress as InetSocketAddress
                        val data = byteArrayOf(0,3,0,1) + body
                        transfer.send(DatagramPacket(data,data.size,original))
                        val ack = DatagramPacket(ByteArray(32),32); transfer.receive(ack)
                        check(ack.socketAddress == original) { "TFTP client transfer ID changed: $original -> ${ack.socketAddress}" }
                        check(ack.data.copyOf(ack.length).contentEquals(byteArrayOf(0,4,0,1)))
                        completed.complete(original)
                    } catch (error: Throwable) { completed.completeExceptionally(error) }
                }
                try { Fixture().use { f ->
                    val (control,relay) = f.associate()
                    control.use { udp().use { client ->
                        send(client,relay,"tftp.example",listener.localPort,request)
                        val reply = received(client)
                        assertEquals(transfer.localPort,reply.port)
                        assertArrayEquals(byteArrayOf(0,3,0,1)+body,reply.payload)
                        send(client,relay,reply.host,reply.port,byteArrayOf(0,4,0,1))
                        completed.get(3,TimeUnit.SECONDS)
                        assertEquals("Transfer handoff must preserve the existing UDP socket",1,f.opened.get())
                    } }
                } } finally { listener.close();transfer.close();worker.join(2000);assertFalse(worker.isAlive) }
            }
        }
    }

    @Test(timeout=15000) fun sharedSourcePreservesTftpTransferWhileAnotherTargetIsActive() {
        Peer().use { other -> DatagramSocket(InetSocketAddress(loopback,0)).use { listener ->
            DatagramSocket(InetSocketAddress(loopback,0)).use { transfer -> Fixture().use { f ->
                listener.soTimeout=3000;transfer.soTimeout=3000
                val(control,relay)=f.associate()
                control.use { udp().use { client ->
                    send(client,relay,"shared.example",listener.localPort,byteArrayOf(0,1,0))
                    val request=DatagramPacket(ByteArray(32),32);listener.receive(request)
                    send(client,relay,"shared.example",other.socket.localPort,byteArrayOf(7));assertArrayEquals(byteArrayOf(7),received(client).payload)
                    assertEquals(request.port,other.lastSourcePort)
                    val data=byteArrayOf(0,3,0,1,99);transfer.send(DatagramPacket(data,data.size,request.socketAddress))
                    val reply=received(client);assertEquals(transfer.localPort,reply.port);assertArrayEquals(data,reply.payload)
                    val ack=byteArrayOf(0,4,0,1);send(client,relay,reply.host,reply.port,ack)
                    val returned=DatagramPacket(ByteArray(32),32);transfer.receive(returned)
                    assertEquals(request.socketAddress,returned.socketAddress);assertArrayEquals(ack,returned.data.copyOf(returned.length))
                } }
            } }
        } }
    }

    @Test(timeout = 15000) fun learnedTransferPortCannotBypassAnExplicitIpRejectRule() {
        DatagramSocket(InetSocketAddress(loopback,0)).use { listener ->
            DatagramSocket(InetSocketAddress(loopback,0)).use { transfer ->
                listener.soTimeout=3000;transfer.soTimeout=400
                val worker=thread(isDaemon=true) {
                    try {
                        val request=DatagramPacket(ByteArray(1024),1024);listener.receive(request)
                        val data=byteArrayOf(0,3,0,1,42)
                        transfer.send(DatagramPacket(data,data.size,request.socketAddress))
                    } catch (_: SocketException) {}
                }
                try { Peer().use { healthy -> Fixture().use { f ->
                    val (control,relay)=f.associate()
                    control.use { udp().use { client ->
                        send(client,relay,"tftp.example",listener.localPort,byteArrayOf(0,1)+"file\u0000octet\u0000".toByteArray())
                        val reply=received(client);assertEquals(transfer.localPort,reply.port)
                        f.rules.update(RuleEngine.Snapshot(userReject=RuleMatcher().apply { add(loopback.hostAddress!!) }))
                        send(client,relay,reply.host,reply.port,byteArrayOf(0,4,0,1))
                        assertTrue(ProxyTestKit.await { f.closed.get()==1 })
                        assertTrue(runCatching { transfer.receive(DatagramPacket(ByteArray(32),32)) }.exceptionOrNull() is SocketTimeoutException)
                        // The existing rule contract still evaluates a domain as a domain;
                        // an unrelated named target and the control association remain usable.
                        send(client,relay,"healthy.example",healthy.socket.localPort,byteArrayOf(9))
                        assertArrayEquals(byteArrayOf(9),received(client).payload)
                        assertEquals(1,f.accounting.snapshot().blockedTotal)
                    } }
                } } } finally { listener.close();transfer.close();worker.join(2000);assertFalse(worker.isAlive) }
            }
        }
    }
}
