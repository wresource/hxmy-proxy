package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.security.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 保留旧两类偶发失败的真实协议路径，交错并发施压，不加重试或放宽协议断言。 */
class PacAndAuthLoadTest {
    @Test(timeout = 30000) fun pacQueriesAndBearerRejectionRemainCorrectUnderConcurrentLoad() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val registry = ConnectionRegistry(128, 128)
        val guard = object : EgressGuard { override fun isAllowed(addr: InetAddress) = true }
        val pac = PacServer(Dispatchers.IO, AllowAllAccessController, registry) { "pac-content" }
        val http = HttpProxyServer(Dispatchers.IO, AllowAllAccessController, registry,
            testConnector(guard), RelayEngine(), { SingleCredentialAuthenticator("u", "p", true) },
            { ConnectionLimits() }, Dispatchers.IO)
        val pool = Executors.newFixedThreadPool(16)
        try {
            pac.start(scope, 0, ProxyTestKit.loopback); http.start(scope, 0, ProxyTestKit.loopback)
            val pacPort = ProxyTestKit.awaitPort(pac); val proxyPort = ProxyTestKit.awaitPort(http)
            val jobs = (0 until 500).map { i -> pool.submit {
                if (i % 2 == 0) {
                    ProxyTestKit.client(pacPort).use { socket ->
                        val head = ProxyTestKit.sendAndReadHead(socket, "GET /proxy.pac?t=$i HTTP/1.1\r\nHost: x\r\n\r\n")
                        assertEquals(200, head.status)
                        assertEquals("application/x-ns-proxy-autoconfig", head.value("Content-Type"))
                        assertEquals("pac-content", String(ProxyTestKit.readBody(socket.getInputStream(), head)))
                    }
                } else {
                    ProxyTestKit.client(proxyPort).use { socket ->
                        val head = ProxyTestKit.sendAndReadHead(socket,
                            "CONNECT example.invalid:443 HTTP/1.1\r\nProxy-Authorization: Bearer tok\r\n\r\n")
                        assertEquals(407, head.status)
                        assertTrue(head.value("Proxy-Authenticate")!!.startsWith("Basic"))
                        ProxyTestKit.readBody(socket.getInputStream(), head)
                    }
                }
            } }
            jobs.forEach { it.get(20, TimeUnit.SECONDS) }
            assertTrue(ProxyTestKit.await { registry.activeGlobal == 0 })
        } finally {
            pac.stop(); http.stop(); scope.cancel(); pool.shutdownNow()
            withTimeout(5000) { scope.coroutineContext[Job]!!.join() }
        }
    }
}
