package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.security.*
import com.mzstd.hxmyproxy.ui.monitor.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

class UnknownKeepAliveTest {
    @Test(timeout = 20000) fun realHttpRequestsOnOneClientSocketNeverInventAnUnknownRoute() = runBlocking {
        val accounting = TrafficAccounting()
        val registry = ConnectionRegistry()
        val store = RequestActivity.store
        val wasEnabled = store.snapshotState().enabled
        store.reset(); store.setEnabled(true)
        val origin = ServerSocket(0, 16, ProxyTestKit.loopback).apply { soTimeout = 5000 }
        val failures = ConcurrentLinkedQueue<Throwable>()
        val payload = ByteArray(1024) { (it * 31).toByte() }
        val worker = thread(isDaemon = true, name = "unknown-keepalive-origin") {
            try {
                repeat(2) {
                    origin.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream()
                        check(ProxyTestKit.readLine(input).startsWith("GET /"))
                        while (ProxyTestKit.readLine(input).isNotEmpty()) Unit
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(payload); flush()
                        }
                    }
                }
            } catch (t: Throwable) { if (!origin.isClosed) failures.add(t) }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val guard = object : EgressGuard { override fun isAllowed(addr: InetAddress) = true }
        val server = HttpProxyServer(Dispatchers.IO, AllowAllAccessController, registry, testConnector(guard),
            RelayEngine(), { NoAuthAuthenticator }, { ConnectionLimits() }, Dispatchers.IO, accounting = accounting)
        try {
            server.start(scope, 0, ProxyTestKit.loopback)
            ProxyTestKit.client(ProxyTestKit.awaitPort(server)).use { client ->
                repeat(2) { index ->
                    ProxyTestKit.write(client, "GET http://127.0.0.1:${origin.localPort}/$index HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
                    val head = ProxyTestKit.readHead(client.getInputStream())
                    assertEquals(200, head.status)
                    assertArrayEquals(payload, ProxyTestKit.readBody(client.getInputStream(), head))
                    assertTrue(ProxyTestKit.await { store.snapshot().count { it.outcome == RequestOutcome.RELAYED } == index + 1 })
                    val frame = ActivitySnapshot(domains = accounting.snapshot().topDomains, requests = store.snapshot())
                    assertEquals(1L, frame.domains.single().connections)
                    assertEquals(1, registry.activeGlobal)
                    assertTrue(frame.requests.all { it.action == "PROXY" && it.outcome == RequestOutcome.RELAYED })
                    assertTrue(domainActivities(frame, ActivityQuery(route = ActivityRoute.UNKNOWN)).isEmpty())
                    val rows = domainActivities(frame, ActivityQuery(route = ActivityRoute.PROXY, filter = ActivityFilter.ENDED))
                    assertEquals(1, rows.size)
                    assertEquals(index + 1, rows.single().requests.size)
                }
            }
            assertTrue(ProxyTestKit.await { registry.activeGlobal == 0 })
            assertTrue(failures.toString(), failures.isEmpty())
        } finally {
            server.stop(); scope.cancel(); origin.close(); worker.join(6000)
            withTimeout(5000) { scope.coroutineContext[Job]!!.join() }
            store.setEnabled(wasEnabled); store.reset()
        }
    }
}
