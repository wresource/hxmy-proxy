package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.network.SystemDnsLookup
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.rules.RuleEngine
import com.mzstd.hxmyproxy.core.rules.RuleMatcher
import com.mzstd.hxmyproxy.core.security.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Real HTTP framing, with only DNS's platform boundary controlled; no external network. */
class HttpFailureMetadataTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val servers = mutableListOf<HttpProxyServer>()
    private val connectors = mutableListOf<OutboundConnector>()
    private val reactors = mutableListOf<NioRelayReactor>()
    private val guard = object : EgressGuard { override fun isAllowed(addr: InetAddress) = true }

    private fun fixture(name: String, head: HttpHead, body: ByteArray) {
        val directory = File("build/test-results/http-failure-metadata").apply { mkdirs() }
        val header = head.statusLine + "\r\n" +
            head.headers.joinToString("") { (key, value) -> "$key: $value\r\n" } + "\r\n"
        File(directory, "$name.http").writeBytes(header.toByteArray(Charsets.ISO_8859_1) + body)
    }

    @After fun close() {
        servers.forEach { it.stop() }; scope.cancel()
        connectors.forEach { it.cancelPendingDns() }; reactors.forEach { it.stop() }
    }

    private fun proxy(
        failure: ProxyException? = null, nio: Boolean = false,
        rules: RuleEngine? = null, auth: Authenticator = NoAuthAuthenticator,
    ): HttpProxyServer {
        val connector = OutboundConnector(guard, systemDns = SystemDnsLookup { _, _ ->
            throw failure ?: error("unexpected DNS query")
        }).apply { backupDnsEnabled = false }
        connectors += connector
        val reactor = if (nio) NioRelayReactor(1, 100).apply { start(); reactors += this } else null
        return HttpProxyServer(Dispatchers.IO, AllowAllAccessController, ConnectionRegistry(32, 32),
            connector, RelayEngine(), { auth }, { ConnectionLimits() }, Dispatchers.IO,
            useNioRelay = nio, nioReactor = reactor, ruleEngine = rules)
            .also { servers += it; it.start(scope, 0, ProxyTestKit.loopback) }
    }

    private fun exchange(server: HttpProxyServer, method: String = "CONNECT", target: String = "failure.example:443"):
        Pair<HttpHead, ByteArray> = ProxyTestKit.client(ProxyTestKit.awaitPort(server)).use { socket ->
        val requestTarget = if (method == "CONNECT") target else "http://$target/"
        socket.getOutputStream().write("$method $requestTarget HTTP/1.1\r\nHost: $target\r\nConnection: close\r\n\r\n".toByteArray())
        val input = socket.getInputStream(); val head = ProxyTestKit.readHead(input)
        val body = if (method == "HEAD") {
            assertEquals("HEAD must end without response bytes", -1, input.read()); byteArrayOf()
        } else ProxyTestKit.readBody(input, head)
        head to body
    }

    @Test(timeout = 20_000) fun knownErrorsKeepStatusBodyAndRetrySemanticsAcrossConnectModes() {
        val errors = listOf(
            ProxyException(ProxyError.DnsFailure, "dns-no-answer"),
            ProxyException(ProxyError.RemoteTimeout, "connect-phase-timeout"),
            ProxyException(ProxyError.DnsBusy, "dns-shared-busy"),
            ProxyException(ProxyError.ResolvedLocalNetworkBlocked, "dns-local-address"),
            ProxyException(ProxyError.EgressSidelined, "egress-down", retryAfterSeconds = 0),
        )
        for (nio in listOf(false, true)) for (failure in errors) {
            val (head, body) = exchange(proxy(failure, nio))
            assertEquals(failure.error.httpStatus, head.status)
            assertEquals(failure.error.code, head.value("X-Hxmyproxy-Error"))
            assertEquals(failure.stage, head.value("X-Hxmyproxy-Stage"))
            assertEquals(1, head.headers.count { it.first.equals("X-Hxmyproxy-Error", true) })
            assertEquals(1, head.headers.count { it.first.equals("X-Hxmyproxy-Stage", true) })
            assertTrue(String(body).contains("cause: ${failure.error.label} (${failure.error.code})"))
            assertEquals(if (failure.error === ProxyError.EgressSidelined) "1" else null, head.value("Retry-After"))
            assertEquals(body.size, head.contentLength)
            fixture("connect-${if (nio) "nio" else "socket"}-${failure.error.code}", head, body)
        }
    }

    @Test(timeout = 15_000) fun plainHttpAndHeadExposeTheSameKnownCauseWithoutAddingHeadBody() {
        val server = proxy(ProxyException(ProxyError.DnsBusy, "dns-shared-busy"))
        for (method in listOf("GET", "HEAD")) {
            val (head, body) = exchange(server, method)
            assertEquals(503, head.status)
            assertEquals("dns-busy", head.value("X-Hxmyproxy-Error"))
            assertEquals("dns-shared-busy", head.value("X-Hxmyproxy-Stage"))
            assertFalse(head.has("Retry-After"))
            if (method == "HEAD") { assertTrue(body.isEmpty()); assertEquals(0, head.contentLength) }
            fixture("${method.lowercase()}-dns-busy", head, body)
        }
    }

    @Test(timeout = 15_000) fun invalidOrMissingStageIsOmittedWithoutLosingTheKnownErrorCode() {
        for (stage in listOf(null, "", "UPPER", "a".repeat(65), "dns\r\nInjected: yes", "non-ascii-\u4e2d")) {
            val (head, _) = exchange(proxy(ProxyException(ProxyError.DnsFailure, stage)))
            assertEquals("dns", head.value("X-Hxmyproxy-Error"))
            assertFalse("invalid stage must be omitted, not cleaned or truncated", head.has("X-Hxmyproxy-Stage"))
            assertFalse(head.has("Injected"))
        }
        val (boundary, _) = exchange(proxy(ProxyException(ProxyError.DnsFailure, "a".repeat(64))))
        assertEquals("a".repeat(64), boundary.value("X-Hxmyproxy-Stage"))
    }

    @Test(timeout = 15_000) fun authenticationAndRuleRejectionDoNotInventAnUpstreamFailure() {
        val auth = proxy(auth = SingleCredentialAuthenticator("user", "password", true))
        val rules = RuleEngine().apply { update(RuleEngine.Snapshot(userReject = RuleMatcher().apply { add("failure.example") })) }
        for (server in listOf(auth, proxy(rules = rules))) {
            val (head, body) = exchange(server)
            assertTrue(head.status in listOf(403, 407))
            assertFalse(head.has("X-Hxmyproxy-Error")); assertFalse(head.has("X-Hxmyproxy-Stage"))
            if (head.status == 403) assertTrue(head.has("X-Proxy-Rule"))
            else assertTrue(head.has("Proxy-Authenticate"))
            fixture("no-metadata-${head.status}", head, body)
        }
    }

    @Test(timeout = 15_000) fun successfulConnectPreservesImmediateServerBytesAndHasNoFailureHeaders() {
        ServerSocket(0, 10, ProxyTestKit.loopback).use { upstream ->
            val greeting = "server-first\r\n".toByteArray()
            val sender = thread(isDaemon = true) {
                upstream.accept().use { socket ->
                    socket.getOutputStream().write(greeting); socket.getOutputStream().flush()
                    socket.shutdownOutput()
                    while (socket.getInputStream().read() >= 0) { }
                }
            }
            val server = proxy(nio = true)
            ProxyTestKit.client(ProxyTestKit.awaitPort(server)).use { client ->
                client.getOutputStream().write("CONNECT 127.0.0.1:${upstream.localPort} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                val input = client.getInputStream(); val head = ProxyTestKit.readHead(input)
                assertEquals(200, head.status)
                assertFalse(head.has("X-Hxmyproxy-Error")); assertFalse(head.has("X-Hxmyproxy-Stage"))
                assertArrayEquals(greeting, ProxyTestKit.readN(input, greeting.size))
                fixture("connect-200-server-first", head, greeting)
                client.shutdownOutput()
            }
            sender.join(2_000); assertFalse(sender.isAlive)
        }
    }
}
