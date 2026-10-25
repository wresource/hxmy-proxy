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
import javax.net.SocketFactory

/** Environment boundary is fake DNS/socket I/O; cache, HE and routing are production code. */
class RescueDnsRecoveryTest {
    private val good = InetAddress.getByName("192.0.2.10")
    private val bad = InetAddress.getByName("192.0.2.20")
    private val allowed = object : EgressGuard { override fun isAllowed(addr: InetAddress) = true }

    private inner class Fixture {
        val primaryCalls = AtomicInteger()
        val sockets = AtomicInteger()
        var primaryRecovered = true
        var badFailure: Exception = SocketTimeoutException("controlled dial timeout")
        var goodDelayMs = 0L
        var badPortSucceeds: Int? = null
        var onBadDial: ((Int) -> Unit)? = null
        var onPrimaryRefresh: (suspend () -> Unit)? = null
        var rescueAnswers = listOf(bad)
        val physicalCalls = AtomicInteger()
        var physicalAfterFirst: List<InetAddress>? = null
        val net = mockk<Network> { every { networkHandle } returns 8801L }
        val physical = mockk<Network> { every { networkHandle } returns 8802L }
        val factory = mockk<SocketFactory>()
        val provider = mockk<UnderlyingNetworkProvider>(relaxed = true) {
            every { current() } returns physical
            every { egressSelection() } returns UnderlyingNetworkProvider.EgressSelection(EgressNetworkChoice.VPN, net)
        }
        init {
            every { net.socketFactory } returns factory
            every { factory.createSocket() } answers {
                sockets.incrementAndGet()
                object : Socket() {
                    private var peer: InetAddress? = null
                    override fun connect(endpoint: SocketAddress?, timeout: Int) {
                        val target = endpoint as InetSocketAddress
                        if (target.address == bad) {
                            onBadDial?.invoke(target.port)
                            if (target.port != badPortSucceeds) throw badFailure
                        }
                        peer = target.address
                    }
                    override fun getInetAddress(): InetAddress? = peer
                }
            }
        }
        val connector by lazy {
            testConnector(allowed, underlyingNetworkProvider = provider,
                systemDns = SystemDnsLookup { n, _ ->
                    if (n == net) {
                        val count = primaryCalls.incrementAndGet()
                        if (count == 1 || !primaryRecovered) emptyList()
                        else { onPrimaryRefresh?.invoke(); delay(goodDelayMs); listOf(good) }
                    } else if (n == physical) {
                        if (physicalCalls.incrementAndGet() > 1) physicalAfterFirst ?: rescueAnswers else rescueAnswers
                    } else emptyList()
                }).apply { backupDnsEnabled = false }
        }
    }

    @Test(timeout = 10000) fun failedRescueAllowsPrimaryRecoveryBeforeOldTtlExpires() = runBlocking {
        val f = Fixture()
        try {
            withTimeout(3000) { f.connector.connect("recovered.example", 443) }.use {
                assertEquals(good, it.inetAddress)
            }
            f.connector.connect("RECOVERED.example.", 443).close()
            assertEquals("cache of recovered primary is reused", 2, f.primaryCalls.get())
            assertEquals("one failed group plus two good sockets", 3, f.sockets.get())
        } finally { f.connector.cancelPendingDns() }
    }

    @Test(timeout = 10000) fun concurrentFailuresShareOnlyTheRecoveryLookup() = runBlocking {
        val f = Fixture().apply { goodDelayMs = 200 }
        try {
            val peers = List(12) { async { f.connector.connect("shared.example", 443) } }.awaitAll()
            try { assertTrue(peers.all { it.inetAddress == good }); assertEquals(12, peers.toSet().size) }
            finally { peers.forEach { it.close() } }
            assertEquals(2, f.primaryCalls.get())
        } finally { f.connector.cancelPendingDns() }
    }

    @Test(timeout = 10000) fun refusedServicePortDoesNotInvalidateHostDns() = runBlocking {
        val f = Fixture().apply { badFailure = ConnectException("controlled refused port") }
        try {
            repeat(3) {
                val failure = runCatching { f.connector.connect("refused.example", 443) }.exceptionOrNull()
                assertSame(ProxyError.ConnectionRefused, (failure as ProxyException).error)
            }
            assertEquals(1, f.primaryCalls.get())
        } finally { f.connector.cancelPendingDns() }
    }

    @Test(timeout = 10000) fun failedRefreshHasBoundedCooldownAndOriginalRescueRemainsUsable() = runBlocking {
        val f = Fixture().apply { primaryRecovered = false }
        try {
            repeat(6) { assertTrue(runCatching { f.connector.connect("outage.example", 443) }.isFailure) }
            assertEquals("one initial lookup plus one shared refresh during cooldown", 2, f.primaryCalls.get())
            f.badPortSucceeds = 80
            f.connector.connect("outage.example", 80).use { assertEquals(bad, it.inetAddress) }
            delay(1550)
            assertTrue(runCatching { f.connector.connect("outage.example", 443) }.isFailure)
            assertEquals("working rescue on another port must not be invalidated", 2, f.primaryCalls.get())
        } finally { f.connector.cancelPendingDns() }
    }

    @Test(timeout = 10000) fun successWhileRefreshIsPendingRetainsTheWorkingRescue() = runBlocking {
        val refreshing = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val f = Fixture().apply {
            badPortSucceeds = 80
            onPrimaryRefresh = { refreshing.complete(Unit); release.await() }
        }
        try {
            val failing = async { runCatching { f.connector.connect("race.example", 443) } }
            refreshing.await()
            f.connector.connect("race.example", 80).close()
            release.complete(Unit)
            assertTrue(failing.await().isFailure)
            f.connector.connect("race.example", 80).use { assertEquals(bad, it.inetAddress) }
            assertEquals(2, f.primaryCalls.get())
        } finally { release.complete(Unit); f.connector.cancelPendingDns() }
    }

    @Test(timeout = 10000) fun cancellingRecoveryOwnerDoesNotCancelAnotherWaiterOrPublishNegativeCooldown() = runBlocking {
        val refreshing = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val f = Fixture().apply { onPrimaryRefresh = { refreshing.complete(Unit); release.await() } }
        try {
            val owner = async { f.connector.connect("cancel.example", 443) }
            refreshing.await()
            val follower = async { f.connector.connect("cancel.example", 443) }
            withTimeout(2000) { while (f.sockets.get() < 2) delay(5) }
            // Give the independently completed dial group a turn to join the shared lookup.
            delay(30)
            owner.cancelAndJoin(); release.complete(Unit)
            follower.await().use { assertEquals(good, it.inetAddress) }
            assertEquals(2, f.primaryCalls.get())
        } finally { release.complete(Unit); f.connector.cancelPendingDns() }
    }

    @Test(timeout = 10000) fun oldRefreshCannotOverwriteANewNetworkGeneration() = runBlocking {
        val refreshing = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val f = Fixture().apply { onPrimaryRefresh = {
            if (primaryCalls.get() == 2) { refreshing.complete(Unit); release.await() }
        } }
        try {
            val old = async { runCatching { f.connector.connect("generation.example", 443) } }
            refreshing.await(); f.connector.clearDnsCache()
            f.connector.connect("generation.example", 443).use { assertEquals(good, it.inetAddress) }
            release.complete(Unit); assertTrue(old.await().isFailure)
            f.connector.connect("generation.example", 443).close()
            assertEquals(3, f.primaryCalls.get())
        } finally { release.complete(Unit); f.connector.cancelPendingDns() }
    }

    @Test(timeout = 10000) fun cancelledLastWaiterDoesNotDelayLaterPrimaryRecovery() = runBlocking {
        val refreshing = CompletableDeferred<Unit>()
        val f = Fixture().apply { onPrimaryRefresh = { refreshing.complete(Unit); awaitCancellation() } }
        try {
            val owner = async { f.connector.connect("last.example", 443) }
            refreshing.await(); owner.cancelAndJoin(); f.onPrimaryRefresh = null
            f.connector.connect("last.example", 443).use { assertEquals(good, it.inetAddress) }
            assertEquals(3, f.primaryCalls.get())
        } finally { f.connector.cancelPendingDns() }
    }

    @Test(timeout = 10000) fun healthyHappyEyeballsWinnerDoesNotTriggerDnsRecovery() = runBlocking {
        val f = Fixture().apply { rescueAnswers = listOf(bad, good) }
        try {
            f.connector.connect("working.example", 443).close()
            f.connector.connect("working.example", 443).close()
            assertEquals(1, f.primaryCalls.get())
        } finally { f.connector.cancelPendingDns() }
    }

    @Test(timeout = 10000) fun configuredDegradeUsesExistingFallbackBeforeSpendingTimeOnPrimaryRefresh() = runBlocking {
        val f = Fixture().apply { physicalAfterFirst = listOf(good); goodDelayMs = 1500 }
        every { f.provider.fallbackEgress(f.net) } returns f.physical
        every { f.physical.socketFactory } returns f.factory
        f.connector.egressFallback = com.mzstd.hxmyproxy.core.model.EgressFallback.DEGRADE
        try {
            withTimeout(1200) { f.connector.connect("degrade.example", 443) }.use { assertEquals(good, it.inetAddress) }
            assertEquals("no extra primary refresh ahead of configured fallback", 1, f.primaryCalls.get())
            verify(exactly = 1) { f.physical.socketFactory }
        } finally { f.connector.cancelPendingDns() }
    }

    @Test(timeout = 10000) fun sameNetworkDirectAndProxyKeepSeparateEffectiveDnsContexts() = runBlocking {
        val f = Fixture().apply { badPortSucceeds = 443 }
        try {
            f.connector.connect("contexts.example", 443).use { assertEquals(bad, it.inetAddress) }
            every { f.provider.current() } returns f.net
            f.connector.connect("contexts.example", 443, bypassVpn = true).use { assertEquals(good, it.inetAddress) }
            assertEquals(2, f.primaryCalls.get())
            f.connector.connect("contexts.example", 443).use { assertEquals(bad, it.inetAddress) }
        } finally { f.connector.cancelPendingDns() }
    }

    @Test(timeout = 10000) fun cacheHitsAndSuccessfulConnectionsDoNotRenewOriginalExpiry() = runBlocking {
        val f = Fixture().apply { badPortSucceeds = 443 }
        try {
            f.connector.connect("ttl.example", 443).close()
            // Controlled clock boundary: age the existing entry without waiting30 seconds.
            val field = f.connector.javaClass.getDeclaredField("dnsCache").apply { isAccessible = true }
            val cache = field.get(f.connector) as Map<*, *>
            val entry = cache.values.single()!!
            val timestamp = entry.javaClass.getDeclaredField("atMs").apply { isAccessible = true }
            val original = System.currentTimeMillis() - 10_000
            timestamp.setLong(entry, original)
            f.connector.connect("ttl.example", 443).use { assertEquals(bad, it.inetAddress) }
            assertEquals(original, timestamp.getLong(entry))
            assertEquals(1, f.primaryCalls.get())
            timestamp.setLong(entry, System.currentTimeMillis() - 30_001)
            f.connector.connect("ttl.example", 443).use { assertEquals(good, it.inetAddress) }
            assertEquals(2, f.primaryCalls.get())
        } finally { f.connector.cancelPendingDns() }
    }
}
