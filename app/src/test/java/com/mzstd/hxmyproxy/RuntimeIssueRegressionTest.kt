package com.mzstd.hxmyproxy

import android.net.Network
import com.mzstd.hxmyproxy.core.model.DohEgressChoice
import com.mzstd.hxmyproxy.core.model.EgressNetworkChoice
import com.mzstd.hxmyproxy.core.network.SystemDnsLookup
import com.mzstd.hxmyproxy.core.network.UnderlyingNetworkProvider
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.security.DefaultEgressGuard
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.*
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

/** Real connector paths; controlled DNS and bound socket factory never contact public servers. */
class RuntimeIssueRegressionTest {
    private val privateIp = InetAddress.getByName("10.0.0.1")
    private val publicIp = InetAddress.getByName("192.0.2.80")

    private inner class Fixture {
        val ordinary = AtomicInteger(); val fresh = AtomicInteger(); val sockets = AtomicInteger()
        val guard = DefaultEgressGuard(blockPrivateLan = true)
        var initial = listOf(privateIp); var replacement = listOf(publicIp)
        var onFresh: suspend () -> Unit = {}
        val net = mockk<Network> { every { networkHandle } returns 99111L }
        val provider = mockk<UnderlyingNetworkProvider>(relaxed = true) {
            every { egressSelection() } returns UnderlyingNetworkProvider.EgressSelection(EgressNetworkChoice.VPN, net)
            every { current() } returns net
        }
        init {
            val factory = mockk<SocketFactory>()
            every { net.socketFactory } returns factory
            every { factory.createSocket() } answers {
                sockets.incrementAndGet()
                object : Socket() {
                    private var peer: InetAddress? = null
                    override fun connect(endpoint: SocketAddress?, timeout: Int) {
                        val address = (endpoint as InetSocketAddress).address
                        check(guard.isAllowed(address)) { "Blocked destination reached socket connect" }
                        peer = address
                    }
                    override fun getInetAddress() = peer
                }
            }
        }
        val connector by lazy { OutboundConnector(guard, underlyingNetworkProvider = provider,
            systemDns = object : SystemDnsLookup {
                override suspend fun resolve(network: Network?, host: String): List<InetAddress> {
                    ordinary.incrementAndGet(); return initial
                }
                override suspend fun resolveFresh(network: Network?, host: String): List<InetAddress> {
                    assertSame("refresh stays on the request's selected network", net, network)
                    fresh.incrementAndGet(); onFresh(); return replacement
                }
            }).apply { backupDnsEnabled = false } }
    }

    @Test(timeout = 10000) fun publicDomainWithPrivateAnswerCanRecoverWithoutOpeningPrivateSocket() = runBlocking {
        for (direct in listOf(false, true)) {
            val f = Fixture()
            try {
                f.connector.connect("download.example", 443, bypassVpn = direct).use { assertEquals(publicIp, it.inetAddress) }
                f.connector.connect("download.example", 443, bypassVpn = direct).close()
                assertEquals(1, f.ordinary.get()); assertEquals(1, f.fresh.get()); assertEquals(2, f.sockets.get())
            } finally { f.connector.cancelPendingDns() }
        }
    }

    @Test(timeout = 10000) fun persistentPrivateAnswerHasAccurateErrorAndBoundedRefresh() = runBlocking {
        val f = Fixture(); f.replacement = listOf(privateIp)
        try {
            repeat(2) {
                val error = runCatching { f.connector.connect("public.example", 443) }.exceptionOrNull() as ProxyException
                assertEquals("dns-local-address", error.error.code)
                assertEquals(403, error.error.httpStatus)
                assertEquals(2, error.error.socksReply)
            }
            assertEquals(0, f.sockets.get()); assertEquals(1, f.fresh.get())
        } finally { f.connector.cancelPendingDns() }
    }

    @Test(timeout = 10000) fun explicitPrivateTargetsStillRefuseBeforeDnsAndOptInStillWorks() = runBlocking {
        val f = Fixture()
        try {
            for (host in listOf("10.0.0.1", "nas", "nas.local")) {
                val error = runCatching { f.connector.connect(host, 443) }.exceptionOrNull() as ProxyException
                assertSame(ProxyError.LocalNetworkBlocked, error.error)
            }
            assertEquals(0, f.ordinary.get()); assertEquals(0, f.fresh.get()); assertEquals(0, f.sockets.get())
            f.guard.blockPrivateLan = false
            f.connector.connect("internal.example", 443).use { assertEquals(privateIp, it.inetAddress) }
            assertEquals(0, f.fresh.get())
        } finally { f.connector.cancelPendingDns() }
    }

    @Test(timeout = 10000) fun mixedAnswersKeepTheUsableCandidateWithoutFreshQuery() = runBlocking {
        val f = Fixture(); f.initial = listOf(privateIp, publicIp)
        try {
            f.connector.connect("mixed.example", 443).use { assertEquals(publicIp, it.inetAddress) }
            assertEquals(0, f.fresh.get())
        } finally { f.connector.cancelPendingDns() }
    }

    @Test(timeout = 10000) fun directAndProxyNeverShareAddressEntriesEvenOnTheSameNetwork() = runBlocking {
        val f = Fixture(); f.initial = listOf(publicIp)
        try {
            f.connector.connect("routes.example", 443).close()
            f.connector.connect("routes.example", 443, bypassVpn = true).close()
            repeat(5) {
                f.connector.connect("routes.example", 443).close()
                f.connector.connect("routes.example", 443, bypassVpn = true).close()
            }
            assertEquals(2, f.ordinary.get()); assertEquals(2, f.connector.dnsStats().cached)
            f.connector.invalidateDnsPolicy()
            f.connector.connect("routes.example", 443).close()
            assertEquals(3, f.ordinary.get())
        } finally { f.connector.cancelPendingDns() }
    }

    @Test(timeout = 10000) fun anotherBlockedFreshAnswerDoesNotRenewTheBadCacheOrChangeStrictEgress() = runBlocking {
        val f = Fixture(); f.replacement = listOf(InetAddress.getByName("10.0.0.2"))
        try {
            repeat(3) {
                val error = runCatching { f.connector.connect("still-blocked.example", 443) }.exceptionOrNull() as ProxyException
                assertEquals("dns-local-address", error.error.code)
            }
            assertEquals(1, f.ordinary.get()); assertEquals(1, f.fresh.get()); assertEquals(0, f.sockets.get())
            verify(exactly = 0) { f.provider.fallbackEgress(any()) }
        } finally { f.connector.cancelPendingDns() }
    }

    @Test(timeout = 10000) fun blockedAnswerRecoveryIsSharedAndSurvivesOneCancelledWaiter() = runBlocking {
        val f = Fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.onFresh = { entered.complete(Unit); release.await() }
        val owner = async { f.connector.connect("shared-private.example", 443) }
        try {
            entered.await()
            val follower = async { f.connector.connect("shared-private.example", 443) }
            delay(50); owner.cancelAndJoin(); release.complete(Unit)
            follower.await().use { assertEquals(publicIp, it.inetAddress) }
            assertEquals(1, f.fresh.get()); assertEquals(1, f.sockets.get())
        } finally { release.complete(Unit); owner.cancelAndJoin(); f.connector.cancelPendingDns() }
    }

    @Test(timeout = 10000) fun localDnsBusyNeverSelectsAnAlternateNetworkEvenWithDegradeEnabled() = runBlocking {
        val f = Fixture(); f.initial = listOf(publicIp)
        f.connector.egressFallback = com.mzstd.hxmyproxy.core.model.EgressFallback.DEGRADE
        val slots = f.connector.javaClass.getDeclaredField("dnsSlots").apply { isAccessible = true }
            .get(f.connector) as java.util.concurrent.Semaphore
        slots.acquire(12)
        DirectEgressFailures.forget("busy-degrade.example")
        try {
            for (direct in listOf(false, true)) {
                val attempts: List<suspend () -> Unit> = listOf(
                    { f.connector.connect("busy-degrade.example", 443, direct).close() },
                    { f.connector.connectChannel("busy-degrade.example", 443, direct).close() },
                    { f.connector.prepareDatagram("busy-degrade.example", 443, direct).close() },
                )
                for (attempt in attempts) {
                    val error = runCatching { attempt() }.exceptionOrNull() as ProxyException
                    assertEquals("dns-busy", error.error.code)
                }
            }
            assertEquals(0, f.ordinary.get()); assertEquals(0, f.sockets.get())
            assertTrue(DirectEgressFailures.snapshot(1).none { it.host == "busy-degrade.example" })
            verify(exactly = 0) { f.provider.fallbackEgress(any()) }
        } finally { slots.release(12); f.connector.cancelPendingDns() }
        f.connector.connect("after-busy.example", 443).use { assertEquals(publicIp, it.inetAddress) }
        f.connector.cancelPendingDns()
    }

    @Test(timeout = 15000) fun busyPrimaryKeepsBoundedRescueAndReportsBusyWhenNoRouteAnswers() = runBlocking {
        val entered = AtomicInteger(); val allEntered = CompletableDeferred<Unit>()
        val dohStarts = AtomicInteger()
        val dohNet = mockk<Network> {
            every { networkHandle } returns 99112L
            every { openConnection(any()) } answers { dohStarts.incrementAndGet(); throw IOException("controlled DoH; no external IO") }
        }
        val provider = mockk<UnderlyingNetworkProvider>(relaxed = true) {
            every { egressSelection() } returns UnderlyingNetworkProvider.EgressSelection(EgressNetworkChoice.AUTO, null)
            every { dohSelection() } returns UnderlyingNetworkProvider.DohEgressSelection(DohEgressChoice.WIFI, dohNet)
        }
        val c = OutboundConnector(DefaultEgressGuard(), underlyingNetworkProvider = provider,
            systemDns = SystemDnsLookup { _, _ ->
                if (entered.incrementAndGet() == 12) allEntered.complete(Unit)
                awaitCancellation()
            })
        val work = List(12) { i -> async { runCatching { c.connect("held-$i.example", 443) } } }
        try {
            withTimeout(3000) { allEntered.await() }
            val error = runCatching { c.connect("overflow.example", 443) }.exceptionOrNull() as ProxyException
            println("DNS busy result=${error.error.code} backupQueries=${c.dnsStats().dohCalls} httpAttempts=${dohStarts.get()}")
            assertTrue("configured backup keeps a real chance", dohStarts.get() > 0)
            assertEquals("busy primary is not a negative DNS answer", "dns-busy", error.error.code)
            assertEquals(503, error.error.httpStatus)
            assertTrue("failed endpoints are bounded, not retried indefinitely", dohStarts.get() <= 4)
            assertEquals(12, entered.get())
        } finally {
            work.forEach { it.cancel() }; work.joinAll(); c.cancelPendingDns()
        }
        assertEquals(0, c.dnsStats().inflight)
    }
}
