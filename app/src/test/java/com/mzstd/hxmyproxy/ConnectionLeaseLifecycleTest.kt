package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.core.proxy.ConnectionRegistry
import com.mzstd.hxmyproxy.core.proxy.TcpProxyServerBase
import com.mzstd.hxmyproxy.core.proxy.TrafficAccounting
import com.mzstd.hxmyproxy.core.security.AllowAllAccessController
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.nio.channels.SocketChannel
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

class ConnectionLeaseLifecycleTest {
    private class HoldingServer(
        registry: ConnectionRegistry,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        protocol: ProxyProtocol = ProxyProtocol.HTTP,
    ) : TcpProxyServerBase(protocol, dispatcher, AllowAllAccessController, registry) {
        val entered = LinkedBlockingQueue<Unit>()
        val finish = CompletableDeferred<Unit>()
        override suspend fun handle(client: SocketChannel, tracker: TrafficAccounting.ConnTracker?) {
            entered.add(Unit)
            withContext(NonCancellable) { finish.await() }
        }
    }

    @Test(timeout = 15000) fun oldListenerFinishingAfterRestartCannotReleaseNewConnection() = runBlocking {
        val registry = ConnectionRegistry(1, 1)
        val oldScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val old = HoldingServer(registry)
        val current = HoldingServer(registry, protocol = ProxyProtocol.SOCKS5)
        try {
            old.start(oldScope, 0, ProxyTestKit.loopback)
            Socket("127.0.0.1", ProxyTestKit.awaitPort(old)).use {
                assertNotNull(old.entered.poll(3, TimeUnit.SECONDS))
                old.stop(); oldScope.cancel(); registry.reset()
                current.start(newScope, 0, ProxyTestKit.loopback)
                Socket("127.0.0.1", ProxyTestKit.awaitPort(current)).use {
                    assertNotNull(current.entered.poll(3, TimeUnit.SECONDS))
                    old.finish.complete(Unit)
                    withTimeout(3000) { oldScope.coroutineContext[Job]!!.join() }
                    assertEquals(1, registry.activeGlobal)
                    Socket("127.0.0.1", current.boundPort.value!!).use { extra ->
                        extra.soTimeout = 3000
                        assertEquals("cap=1 rejects second live client", -1, extra.getInputStream().read())
                    }
                    assertEquals(1, registry.activeGlobal)
                }
            }
        } finally {
            old.finish.complete(Unit); current.finish.complete(Unit)
            old.stop(); current.stop(); oldScope.cancel(); newScope.cancel()
            withTimeout(3000) { oldScope.coroutineContext[Job]!!.join(); newScope.coroutineContext[Job]!!.join() }
        }
        assertEquals(0, registry.activeGlobal)
    }

    @Test(timeout = 15000) fun cancellationBeforeHandlerStartsClosesClientAndReleasesLease() = runBlocking {
        val pending = LinkedBlockingQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
        }
        val registry = ConnectionRegistry(1, 1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = HoldingServer(registry, dispatcher, ProxyProtocol.PAC)
        try {
            server.start(scope, 0, ProxyTestKit.loopback)
            Socket("127.0.0.1", ProxyTestKit.awaitPort(server)).use { client ->
                val scheduled = requireNotNull(pending.poll(3, TimeUnit.SECONDS))
                assertEquals(1, registry.activeGlobal)
                server.stop()
                scheduled.run()
                withTimeout(3000) { while (registry.activeGlobal != 0) delay(5) }
                assertTrue(server.entered.isEmpty())
                client.soTimeout = 3000
                assertEquals(-1, client.getInputStream().read())
            }
        } finally {
            server.finish.complete(Unit); server.stop(); scope.cancel()
            while (true) { val block = pending.poll() ?: break; block.run() }
            withTimeout(3000) { scope.coroutineContext[Job]!!.join() }
        }
    }
}
