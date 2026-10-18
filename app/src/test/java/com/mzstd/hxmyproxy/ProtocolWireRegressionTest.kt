package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.security.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProtocolWireRegressionTest {
    @Test(timeout = 45000) fun badBasicAndPacStatusMatchCapturedWireBytesAcrossListenerGenerations() = runBlocking {
        val guard = object : EgressGuard { override fun isAllowed(addr: InetAddress) = true }
        val password = Base64.getEncoder().encodeToString("u:WRONG".toByteArray())
        val workers = Executors.newFixedThreadPool(8)
        try {
            repeat(20) { generation ->
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val registry = ConnectionRegistry(64, 64)
                val http = HttpProxyServer(Dispatchers.IO, AllowAllAccessController, registry,
                    testConnector(guard), RelayEngine(), { SingleCredentialAuthenticator("u", "p", true) },
                    { ConnectionLimits() }, Dispatchers.IO)
                val pac = PacServer(Dispatchers.IO, AllowAllAccessController, registry) { "pac" }
                try {
                    http.start(scope, 0, ProxyTestKit.loopback); pac.start(scope, 0, ProxyTestKit.loopback)
                    val httpPort = ProxyTestKit.awaitPort(http); val pacPort = ProxyTestKit.awaitPort(pac)
                    val tasks = (0 until 250).map { request -> workers.submit {
                        val auth = request % 2 == 0
                        val expected = if (auth) 407 else 200
                        ProxyTestKit.client(if (auth) httpPort else pacPort).use { socket ->
                            ProxyTestKit.write(socket, if (auth)
                                "CONNECT example.invalid:443 HTTP/1.1\r\nProxy-Authorization: Basic $password\r\n\r\n"
                            else "GET /proxy.pac?t=$request HTTP/1.1\r\nHost: test\r\n\r\n")
                            val captured = ByteArrayOutputStream()
                            val input = object : FilterInputStream(socket.getInputStream()) {
                                override fun read(): Int = super.read().also { if (it >= 0) captured.write(it) }
                            }
                            val head = ProxyTestKit.readHead(input)
                            val wire = captured.toString("ISO-8859-1")
                            val message = "generation=$generation request=$request parsed=${head.statusLine} wire=$wire"
                            assertTrue(message, wire.startsWith("HTTP/1.1 $expected "))
                            assertEquals(message, expected, head.status)
                            assertEquals(message, wire.substringBefore("\r\n"), head.statusLine)
                        }
                    } }
                    tasks.forEach { it.get(15, TimeUnit.SECONDS) }
                } finally {
                    http.stop(); pac.stop(); scope.cancel()
                    withTimeout(5000) { scope.coroutineContext[Job]!!.join() }
                }
                assertEquals(0, registry.activeGlobal)
            }
        } finally { workers.shutdownNow() }
    }
}
