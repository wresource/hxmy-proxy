package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.log.FileLog
import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.network.SystemDnsLookup
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.rules.RuleEngine
import com.mzstd.hxmyproxy.core.security.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.nio.file.Files

class RequestFailureIntegrationTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resources = Resources()
    private val registry = ConnectionRegistry()
    private val log = Files.createTempDirectory("request-failure").toFile()
    private var broken = true
    private val connector = testConnector(object : EgressGuard {
        override fun isAllowed(addr: InetAddress): Boolean {
            if (broken) throw NullPointerException("injected candidate failure")
            return true
        }
    }, systemDns = SystemDnsLookup { _, _ -> listOf(ProxyTestKit.loopback) })

    @Before fun setup() {
        FileLog.enabled = true; FileLog.init(log)
        RequestActivity.store.setEnabled(true); RequestActivity.store.reset()
    }
    @After fun cleanup() {
        resources.close(); scope.cancel(); connector.clearDnsCache()
        RequestActivity.store.setEnabled(false); FileLog.clear(); FileLog.enabled = true; log.deleteRecursively()
    }
    private fun http(nio: Boolean = false): HttpProxyServer {
        val reactor = if (nio) NioRelayReactor(workerCount = 1).also { it.start(); resources.onClose { it.stop() } } else null
        return HttpProxyServer(Dispatchers.IO, AllowAllAccessController, registry, connector, RelayEngine(),
            { NoAuthAuthenticator }, { ConnectionLimits() }, Dispatchers.IO, ruleEngine = RuleEngine(),
            nioReactor = reactor, useNioRelay = nio).also { server -> server.start(scope, 0, ProxyTestKit.loopback); resources.onClose { server.stop() } }
    }
    private fun checkFailure(host: String) {
        assertTrue(ProxyTestKit.await { registry.activeGlobal == 0 })
        val row = RequestActivity.store.snapshot().single { it.host == host }
        assertEquals(RequestOutcome.FAILED, row.outcome)
        assertEquals("internal-error", row.stage)
        val events = FileLog.snapshot().substringAfter("===== FULL LOG =====").lines().filter { "evt=req.failed " in it && "id=${row.requestId} " in it }
        assertEquals(events.toString(), 1, events.size)
        assertTrue(events.single(), " E/" in events.single())
        assertTrue(FileLog.snapshot().contains("NullPointerException"))
    }

    @Test(timeout = 15000) fun connectProgrammingErrorCompletesOriginalRequestAndListenerSurvives() {
        val server = http()
        ProxyTestKit.client(ProxyTestKit.awaitPort(server)).use { client ->
            val head = ProxyTestKit.sendAndReadHead(client, "CONNECT broken.example:443 HTTP/1.1\r\n\r\n")
            assertEquals(500, head.status); ProxyTestKit.readBody(client.getInputStream(), head)
            assertTrue(ProxyTestKit.closedByPeer(client))
        }
        checkFailure("broken.example")
        broken = false
        val echo = resources.keep(ProxyTestKit.startEcho())
        ProxyTestKit.client(ProxyTestKit.awaitPort(server)).use { client ->
            assertEquals(200, ProxyTestKit.sendAndReadHead(client, "CONNECT healthy.example:${echo.localPort} HTTP/1.1\r\n\r\n").status)
            ProxyTestKit.write(client, "ok"); assertEquals("ok", String(ProxyTestKit.readN(client.getInputStream(), 2)))
        }
    }
    @Test(timeout = 15000) fun nioConnectProgrammingErrorIsNotMistakenForBindingFallback() {
        val server = http(true)
        ProxyTestKit.client(ProxyTestKit.awaitPort(server)).use {
            assertEquals(500, ProxyTestKit.sendAndReadHead(it, "CONNECT nio.example:443 HTTP/1.1\r\n\r\n").status)
        }
        checkFailure("nio.example")
    }
    @Test(timeout = 15000) fun plainHttpProgrammingErrorCompletesOriginalRequest() {
        val server = http()
        ProxyTestKit.client(ProxyTestKit.awaitPort(server)).use {
            assertEquals(500, ProxyTestKit.sendAndReadHead(it, "GET http://plain.example/ HTTP/1.1\r\nHost: plain.example\r\n\r\n").status)
        }
        checkFailure("plain.example")
    }
    @Test(timeout = 15000) fun socksProgrammingErrorReturnsGeneralFailureAndReleasesConnection() {
        val server = Socks5ProxyServer(Dispatchers.IO, AllowAllAccessController, registry, connector, RelayEngine(),
            { NoAuthAuthenticator }, { ConnectionLimits() }, Dispatchers.IO, ruleEngine = RuleEngine())
        server.start(scope, 0, ProxyTestKit.loopback)
        resources.onClose { server.stop() }
        ProxyTestKit.client(ProxyTestKit.awaitPort(server)).use { client ->
            ProxyTestKit.write(client, byteArrayOf(5, 1, 0)); assertArrayEquals(byteArrayOf(5, 0), ProxyTestKit.readN(client.getInputStream(), 2))
            val host = "socks.example".toByteArray()
            ProxyTestKit.write(client, byteArrayOf(5, 1, 0, 3, host.size.toByte()) + host + byteArrayOf(1, -69))
            val reply = ProxyTestKit.readN(client.getInputStream(), 10)
            assertEquals(1, reply[1].toInt()); assertTrue(ProxyTestKit.closedByPeer(client))
        }
        checkFailure("socks.example")
    }
}
