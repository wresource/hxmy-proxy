package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.proxy.ConnectionRegistry
import com.mzstd.hxmyproxy.core.proxy.PacServer
import com.mzstd.hxmyproxy.core.proxy.ProxyError
import com.mzstd.hxmyproxy.core.security.AllowAllAccessController
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class ListenerIsolationTest {
    @Test fun explicitAddressCannotClaimAnAlreadyOwnedEndpoint() = runBlocking {
        ServerSocket(0, 2, ProxyTestKit.loopback).use { competing ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val server = PacServer(Dispatchers.IO, AllowAllAccessController, ConnectionRegistry()) { "ours" }
            try {
                server.start(scope, competing.localPort, ProxyTestKit.loopback)
                assertTrue(ProxyTestKit.await { server.bindError.value != null })
                assertEquals(ProxyError.PortInUse, server.bindError.value)
                assertNull("must not advertise another listener's endpoint", server.boundPort.value)
            } finally { server.stop(); scope.cancel(); withTimeout(5000) { scope.coroutineContext[Job]!!.join() } }
        }
    }

    @Test fun explicitIpv6StillWorksWithoutDisablingIpv6ForTheTestJvm() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = PacServer(Dispatchers.IO, AllowAllAccessController, ConnectionRegistry()) { "ipv6-pac" }
        try {
            val address = InetAddress.getByName("::1")
            server.start(scope, 0, address)
            Socket().use { socket ->
                socket.soTimeout = 3000
                socket.connect(InetSocketAddress(address, ProxyTestKit.awaitPort(server)), 3000)
                val head = ProxyTestKit.sendAndReadHead(socket, "GET /proxy.pac HTTP/1.1\r\nHost: test\r\n\r\n")
                assertEquals(200, head.status)
                assertEquals("ipv6-pac", String(ProxyTestKit.readBody(socket.getInputStream(), head)))
            }
        } finally { server.stop(); scope.cancel(); withTimeout(5000) { scope.coroutineContext[Job]!!.join() } }
    }
}
