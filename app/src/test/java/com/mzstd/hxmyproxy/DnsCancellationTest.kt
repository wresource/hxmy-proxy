package com.mzstd.hxmyproxy

import android.net.Network
import com.mzstd.hxmyproxy.core.model.EgressNetworkChoice
import com.mzstd.hxmyproxy.core.network.SystemDnsLookup
import com.mzstd.hxmyproxy.core.network.UnderlyingNetworkProvider
import com.mzstd.hxmyproxy.core.proxy.ProxyError
import com.mzstd.hxmyproxy.core.proxy.ProxyException
import com.mzstd.hxmyproxy.core.security.EgressGuard
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

class DnsCancellationTest {
    private val allowed = object : EgressGuard { override fun isAllowed(addr: InetAddress) = true }
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test(timeout = 10000) fun callerDeadlineCancelsDnsAndReleasesSingleFlightAndCapacity() = runBlocking {
        var active = 0
        var calls = 0
        var slow = true
        val dns = SystemDnsLookup { _, _ ->
            calls++; active++
            try { if (slow) awaitCancellation() else listOf(loopback) }
            finally { active-- }
        }
        val connector = testConnector(allowed, systemDns = dns).apply { backupDnsEnabled = false }
        val start = System.nanoTime()
        assertNull(withTimeoutOrNull(300) { connector.connect("cancel.example", 9) })
        assertTrue("外层超时必须及时结束", (System.nanoTime() - start) / 1_000_000 < 1500)
        assertEquals(0, active)
        assertEquals(0, connector.dnsStats().inflight)
        slow = false
        ServerSocket(0, 1, loopback).use { server ->
            connector.connect("cancel.example", server.localPort).use { assertTrue(it.isConnected) }
        }
        assertEquals("取消的单飞结果不能污染下一次查询", 2, calls)
    }

    @Test(timeout = 10000) fun failedPrimaryStillUsesPhysicalRescueAndCancelsSlowDefault() = runBlocking {
        val egress = mockk<Network>()
        every { egress.networkHandle } returns 1L
        every { egress.socketFactory } returns SocketFactory.getDefault()
        val physical = mockk<Network>()
        every { physical.networkHandle } returns 2L
        val provider = mockk<UnderlyingNetworkProvider>()
        every { provider.egressSelection() } returns UnderlyingNetworkProvider.EgressSelection(EgressNetworkChoice.VPN, egress)
        every { provider.current() } returns physical
        val cancelled = AtomicInteger()
        val dns = SystemDnsLookup { net, _ ->
            if (net === egress) throw java.net.UnknownHostException("primary really failed")
            if (net === physical) listOf(loopback)
            else try { awaitCancellation() } finally { cancelled.incrementAndGet() }
        }
        val connector = testConnector(allowed, underlyingNetworkProvider = provider, systemDns = dns).apply { backupDnsEnabled = false }
        ServerSocket(0, 1, loopback).use { server ->
            val start = System.nanoTime()
            connector.connect("race.example", server.localPort).use { assertTrue(it.isConnected) }
            assertTrue("对冲不该等到慢路1.5秒超时", (System.nanoTime() - start) / 1_000_000 < 1400)
        }
        assertEquals(1, cancelled.get())
        assertEquals(0, connector.dnsStats().inflight)
    }

    @Test(timeout = 10000) fun saturatedDnsDoesNotSpawnUnlimitedRescueWork() = runBlocking {
        val entered = AtomicInteger()
        val allEntered = CompletableDeferred<Unit>()
        val dns = SystemDnsLookup { _, _ ->
            if (entered.incrementAndGet() == 12) allEntered.complete(Unit)
            awaitCancellation()
        }
        val connector = testConnector(allowed, systemDns = dns).apply { backupDnsEnabled = false }
        val work = List(12) { i -> launch { connector.connect("busy-$i.example", 9) } }
        try {
            allEntered.await()
            val error = runCatching { connector.connect("overflow.example", 9) }.exceptionOrNull()
            assertTrue(error is ProxyException)
            assertSame(ProxyError.DnsFailure, (error as ProxyException).error)
            assertEquals("满载后不得再起旁路系统查询", 12, entered.get())
            assertEquals(12, connector.dnsStats().inflight)
        } finally {
            work.forEach { it.cancelAndJoin() }
        }
        assertEquals(0, connector.dnsStats().inflight)
    }

    @Test(timeout = 10000) fun networkChangeDoesNotJoinOrCacheAnOldInFlightQuery() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val oldAnswer = CompletableDeferred<List<InetAddress>>()
        var queries = 0
        val connector = testConnector(allowed, systemDns = SystemDnsLookup { _, _ ->
            if (++queries == 1) { entered.complete(Unit); oldAnswer.await() } else listOf(loopback)
        })
        ServerSocket(0, 50, loopback).use { server ->
            val old = async { connector.connect("changing.example", server.localPort) }
            try {
                entered.await()
                connector.clearDnsCache()
                withTimeout(1000) { connector.connect("changing.example", server.localPort) }.close()
                oldAnswer.complete(listOf(loopback))
                old.await().close()
                connector.connect("changing.example", server.localPort).close()
                assertEquals("新网络不能等待旧查询；迟到旧答案也不能清掉新缓存", 2, queries)
            } finally {
                old.cancelAndJoin()
            }
        }
    }

    @Test(timeout = 10000) fun cancelledDnsOwnerDoesNotSilentlyCancelOtherClients() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var queries = 0
        val connector = testConnector(allowed, systemDns = SystemDnsLookup { _, _ ->
            queries++; entered.complete(Unit); delay(600); listOf(loopback)
        }).apply { backupDnsEnabled = false }
        ServerSocket(0, 2, loopback).use { server ->
            val owner = async { withTimeoutOrNull(300) { connector.connect("shared.example", server.localPort) } }
            entered.await()
            val waiter = async { connector.connect("shared.example", server.localPort) }
            assertNull(owner.await())
            waiter.await().use { assertTrue("remaining client must still complete", it.isConnected) }
        }
        assertEquals("one DNS query serves the surviving waiter", 1, queries)
        assertEquals(0, connector.dnsStats().inflight)
    }
}
