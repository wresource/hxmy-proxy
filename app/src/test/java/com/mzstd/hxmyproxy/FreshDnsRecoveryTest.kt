package com.mzstd.hxmyproxy

import android.net.Network
import com.mzstd.hxmyproxy.core.model.EgressNetworkChoice
import com.mzstd.hxmyproxy.core.network.*
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.security.EgressGuard
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

class FreshDnsRecoveryTest {
    private val bad = InetAddress.getByName("192.0.2.20")
    private val good = InetAddress.getByName("192.0.2.10")
    private inner class Fixture {
        val ordinary = AtomicInteger(); val fresh = AtomicInteger()
        var initial = listOf(bad)
        var next = listOf(good)
        var refresh: suspend () -> Unit = {}
        var failure: Exception = SocketTimeoutException("controlled unreachable address")
        val firstEntry = AtomicReference<Any?>()
        val net = mockk<Network> { every { networkHandle } returns 781L }
        val factory = mockk<SocketFactory>()
        val provider = mockk<UnderlyingNetworkProvider>(relaxed = true) {
            every { egressSelection() } returns UnderlyingNetworkProvider.EgressSelection(EgressNetworkChoice.VPN, net)
        }
        init {
            every { net.socketFactory } returns factory
            every { factory.createSocket() } answers { object : Socket() {
                private var peer: InetAddress? = null
                override fun connect(endpoint: SocketAddress?, timeout: Int) {
                    firstEntry.compareAndSet(null, cacheEntry())
                    val address = (endpoint as InetSocketAddress).address
                    if (address != good) throw failure
                    peer = address
                }
                override fun getInetAddress() = peer
            } }
        }
        val connector by lazy {
            OutboundConnector(object : EgressGuard { override fun isAllowed(addr: InetAddress) = true },
                underlyingNetworkProvider = provider, systemDns = object : SystemDnsLookup {
                    override suspend fun resolve(network: Network?, host: String): List<InetAddress> {
                        ordinary.incrementAndGet(); return initial
                    }
                    override suspend fun resolveFresh(network: Network?, host: String): List<InetAddress> {
                        assertSame(net, network); fresh.incrementAndGet(); refresh(); return next
                    }
                }).apply { backupDnsEnabled = false }
        }
        fun cacheEntry(): Any? = (connector.javaClass.getDeclaredField("dnsCache").apply { isAccessible = true }
            .get(connector) as Map<*, *>).values.singleOrNull()
    }

    @Test(timeout = 10000) fun failedPrimaryUsesFreshBoundaryAndThenReusesWorkingAppCache() = runBlocking {
        val f = Fixture()
        try {
            repeat(3) { f.connector.connect("fresh.example", 443).use { assertEquals(good, it.inetAddress) } }
            assertEquals(1, f.ordinary.get()); assertEquals(1, f.fresh.get())
        } finally { f.connector.cancelPendingDns() }
    }

    @Test(timeout = 10000) fun identicalReorderedAnswerDoesNotRenewCacheAndNegativeResultHasCooldown() = runBlocking {
        val f = Fixture(); val second = InetAddress.getByName("192.0.2.21")
        f.initial = listOf(bad, second); f.next = listOf(second, bad)
        try {
            repeat(3) { assertTrue(runCatching { f.connector.connect("same.example", 443) }.isFailure) }
            assertSame("no new timestamp, source or lease", f.firstEntry.get(), f.cacheEntry())
            assertEquals(1, f.ordinary.get()); assertEquals(1, f.fresh.get())
        } finally { f.connector.cancelPendingDns() }
    }

    @Test(timeout = 10000) fun concurrentFailuresShareFreshLookupAndCancellingOneWaiterKeepsOthers() = runBlocking {
        val f = Fixture(); val began = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.refresh = { began.complete(Unit); release.await() }
        val owner = async { f.connector.connect("shared-fresh.example", 443) }
        try {
            began.await()
            val followers = List(5) { async { f.connector.connect("shared-fresh.example", 443) } }
            delay(50); owner.cancelAndJoin(); release.complete(Unit)
            followers.awaitAll().forEach { it.use { assertEquals(good, it.inetAddress) } }
            assertEquals(1, f.fresh.get())
        } finally { release.complete(Unit); owner.cancelAndJoin(); f.connector.cancelPendingDns() }
    }

    @Test(timeout = 10000) fun servicePortRefusalNeverRequestsFreshDns() = runBlocking {
        val f = Fixture(); f.failure = ConnectException("controlled port refused")
        try {
            assertTrue(runCatching { f.connector.connect("refused-fresh.example", 443) }.isFailure)
            assertEquals(1, f.ordinary.get()); assertEquals(0, f.fresh.get())
        } finally { f.connector.cancelPendingDns() }
    }

    @Test(timeout = 10000) fun successfulPrimaryDoesNotBypassSystemDnsCache() = runBlocking {
        val f = Fixture(); f.initial = listOf(good)
        try {
            f.connector.connect("healthy-fresh.example", 443).close()
            assertEquals(1, f.ordinary.get()); assertEquals(0, f.fresh.get())
        } finally { f.connector.cancelPendingDns() }
    }
}
