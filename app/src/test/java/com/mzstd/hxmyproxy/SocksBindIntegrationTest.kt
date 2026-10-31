package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.network.SystemDnsLookup
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.rules.*
import com.mzstd.hxmyproxy.core.security.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.*
import java.nio.channels.ServerSocketChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SocksBindIntegrationTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private data class Reply(val code: Int, val peer: InetSocketAddress)
    private fun reply(s: Socket): Reply {
        val input = DataInputStream(s.getInputStream())
        assertEquals(5,input.readUnsignedByte()); val code = input.readUnsignedByte(); assertEquals(0,input.readUnsignedByte())
        val size = when(input.readUnsignedByte()) { 1 -> 4; 4 -> 16; else -> error("Bad BIND address family") }
        val addr = InetAddress.getByAddress(ByteArray(size).also { input.readFully(it) })
        return Reply(code,InetSocketAddress(addr,input.readUnsignedShort()))
    }
    private inner class Fixture(val capacity: Int = 16, val waitMillis: Long = 2000,
                                val beforeOpen: () -> Unit = {}) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val session = ConnectUdpSession(capacity)
        val rules = RuleEngine()
        val registry = ConnectionRegistry(64,64)
        val opens = AtomicInteger(); val closes = AtomicInteger(); val dns = AtomicInteger()
        val factory = TcpBindListenerFactory { peer, _ ->
            beforeOpen()
            val ch = ServerSocketChannel.open()
            val local = InetAddress.getByName(if (peer is Inet4Address) "127.0.0.1" else "::1")
            ch.bind(InetSocketAddress(local,0)); ch.configureBlocking(false); opens.incrementAndGet()
            object : TcpBindListener {
                override val bound = ch.localAddress as InetSocketAddress
                override val networkHandle: Long? = null
                override fun acceptOrNull(): Socket? = ch.accept()?.socket()
                private var closed = false
                @Synchronized override fun close() { if (!closed) { closed=true; ch.close(); closes.incrementAndGet() } }
            }
        }
        val connector = OutboundConnector(object : EgressGuard { override fun isAllowed(addr: InetAddress) = true },
            systemDns=SystemDnsLookup { _, _ -> dns.incrementAndGet(); listOf(loopback) }).apply { backupDnsEnabled=false }
        val server = Socks5ProxyServer(Dispatchers.IO,AllowAllAccessController,registry,connector,RelayEngine(),
            { NoAuthAuthenticator },{ ConnectionLimits(idleTimeoutSeconds=2) },Dispatchers.IO,ruleEngine=rules,
            udpSession=session,bindFactory=factory,bindWaitMillis=waitMillis)
        val port: Int
        init { server.start(scope,0,loopback); port=ProxyTestKit.awaitPort(server) }
        fun request(host: String="known.example", peerPort: Int=0): Socket {
            val s=Socket(loopback,port).apply { soTimeout=4000 }
            try {
                s.getOutputStream().write(byteArrayOf(5,1,0)); val input=DataInputStream(s.getInputStream())
                assertEquals(5,input.readUnsignedByte()); assertEquals(0,input.readUnsignedByte())
                val h=host.toByteArray();s.getOutputStream().write(byteArrayOf(5,2,0,3,h.size.toByte())+h+byteArrayOf((peerPort ushr 8).toByte(),peerPort.toByte()))
                return s
            } catch (error: Throwable) { s.close(); throw error }
        }
        override fun close() {
            session.close(); server.stop(); connector.cancelPendingDns(); scope.cancel()
            assertTrue(ProxyTestKit.await(5000) { session.activeLeases==0 && registry.activeGlobal==0 && opens.get()==closes.get() })
        }
    }

    @Test(timeout=15000) fun twoRepliesPreserveEarlyClientDataAndBothHalfCloses() {
        Fixture().use { f -> f.request().use { client ->
            val first=reply(client);assertEquals(0,first.code);assertTrue(first.peer.port>0)
            val upload=ByteArray(130_000){(it%251).toByte()};val download=ByteArray(262144){(it%239).toByte()}
            client.getOutputStream().write(upload);client.shutdownOutput()
            val worker=Executors.newSingleThreadExecutor()
            try {
                val remote=worker.submit {
                    Socket().use { s -> s.soTimeout=5000;s.connect(first.peer,3000)
                        assertArrayEquals(upload,s.getInputStream().readBytes())
                        s.getOutputStream().write(download);s.shutdownOutput()
                    }
                }
                val second=reply(client);assertEquals(0,second.code);assertEquals(loopback,second.peer.address)
                assertArrayEquals(download,client.getInputStream().readBytes());remote.get(5,TimeUnit.SECONDS)
                assertTrue(ProxyTestKit.await { f.session.activeLeases==0 })
            } finally { worker.shutdownNow() }
        } }
    }

    @Test(timeout=15000) fun IPv6PeerAndReplyAddressRemainIPv6() {
        Fixture().use { f -> f.request("::1").use { client ->
            val first=reply(client);assertEquals(0,first.code);assertTrue(first.peer.address is Inet6Address)
            Socket().use { remote -> remote.soTimeout=3000;remote.connect(first.peer,3000)
                val second=reply(client);assertEquals(0,second.code);assertEquals(remote.localPort,second.peer.port)
                remote.getOutputStream().write(byteArrayOf(6));assertEquals(6,client.getInputStream().read())
                client.getOutputStream().write(byteArrayOf(4));assertEquals(4,remote.getInputStream().read())
            }
        } }
    }

    @Test(timeout=15000) fun wrongSourcePortCannotClaimTheExpectedPeer() {
        Fixture().use { f -> Socket().use { correct ->
            correct.bind(InetSocketAddress(loopback,0));correct.soTimeout=3000
            f.request(peerPort=correct.localPort).use { client ->
                val first=reply(client);assertEquals(0,first.code)
                Socket().use { wrong -> wrong.soTimeout=3000;wrong.connect(first.peer,3000);assertEquals(-1,wrong.getInputStream().read()) }
                correct.connect(first.peer,3000)
                val second=reply(client);assertEquals(0,second.code);assertEquals(correct.localPort,second.peer.port)
                correct.getOutputStream().write(17);assertEquals(17,client.getInputStream().read())
            }
        } }
    }

    @Test(timeout=15000) fun rejectAndWildcardNeverAllocateAListener() {
        Fixture().use { f ->
            f.rules.update(RuleEngine.Snapshot(userReject=RuleMatcher().apply { add("blocked.example") }))
            for(host in listOf("blocked.example","0.0.0.0","::","224.0.0.1","255.255.255.255"))
                f.request(host).use { assertEquals(2,reply(it).code) }
            assertEquals(0,f.opens.get());assertEquals(0,f.dns.get())
        }
    }

    @Test(timeout=15000) fun ruleChangeCancelsPendingAndAcceptedPeersWithoutStoppingOtherHosts() {
        Fixture().use { f -> f.request("wait.example").use { waiting -> f.request("active.example").use { active ->
            assertEquals(0,reply(waiting).code);val bound=reply(active);assertEquals(0,bound.code)
            Socket().use { peer -> peer.soTimeout=3000;peer.connect(bound.peer,3000);assertEquals(0,reply(active).code)
                f.rules.update(RuleEngine.Snapshot(userReject=RuleMatcher().apply { add("wait.example");add("active.example") }))
                assertEquals(2,reply(waiting).code);assertEquals(-1,active.getInputStream().read());assertEquals(-1,peer.getInputStream().read())
                f.request("healthy.example").use { healthy -> val other=reply(healthy);assertEquals(0,other.code)
                    Socket().use { s -> s.connect(other.peer,3000);assertEquals(0,reply(healthy).code);s.getOutputStream().write(42);assertEquals(42,healthy.getInputStream().read()) }
                }
            }
        } } }
    }

    @Test(timeout=15000) fun boundedWaitAndSharedQuotaReleaseListeners() {
        Fixture(capacity=1,waitMillis=180).use { f -> f.request().use { first ->
            assertEquals(0,reply(first).code)
            f.request().use { assertEquals(1,reply(it).code) }
            assertEquals(6,reply(first).code)
            assertTrue(ProxyTestKit.await { f.closes.get()==1 && f.session.activeLeases==0 })
            f.request().use { assertEquals(0,reply(it).code); f.session.invalidate("network-changed");assertEquals(-1,it.getInputStream().read()) }
        } }
    }

    @Test(timeout=15000) fun cancelledSetupCannotPublishALateListener() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        Fixture(beforeOpen={entered.countDown();check(release.await(5,TimeUnit.SECONDS))}).use { f ->
            try { f.request().use { client ->
                assertTrue(entered.await(3,TimeUnit.SECONDS));f.session.invalidate("credentials-changed")
                assertEquals(-1,client.getInputStream().read());assertEquals(1,f.session.activeLeases)
                release.countDown();assertTrue(ProxyTestKit.await { f.opens.get()==1 && f.closes.get()==1 && f.session.activeLeases==0 })
            } } finally { release.countDown() }
        }
    }
}
