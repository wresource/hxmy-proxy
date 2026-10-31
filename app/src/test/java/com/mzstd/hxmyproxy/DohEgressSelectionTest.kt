package com.mzstd.hxmyproxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import com.mzstd.hxmyproxy.core.model.DohEgressChoice
import com.mzstd.hxmyproxy.core.network.SystemDnsLookup
import com.mzstd.hxmyproxy.core.network.UnderlyingNetworkProvider
import com.mzstd.hxmyproxy.core.proxy.OutboundConnector
import com.mzstd.hxmyproxy.core.security.EgressGuard
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.*
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Real provider selection/callback logic; intercept default URL transport before any socket. */
class DohEgressSelectionTest {
    private val allowed = object : EgressGuard { override fun isAllowed(addr: InetAddress) = true }
    @Suppress("UNCHECKED_CAST")
    private suspend fun invoke(c: OutboundConnector, method: String, vararg arguments: Any?): Any? =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            c.javaClass.declaredMethods.single { it.name == method }.apply { isAccessible = true }
                .invoke(c, *arguments, continuation)
        }

    private class Fixture : AutoCloseable {
        val defaultAttempts = AtomicInteger()
        val networkAttempts = AtomicInteger()
        val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
        private val previousSelector = ProxySelector.getDefault()
        val network = mockk<Network> {
            every { networkHandle } returns 9801L
        }
        val provider: UnderlyingNetworkProvider
        init {
            ProxySelector.setDefault(object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> {
                    defaultAttempts.incrementAndGet()
                    throw IllegalStateException("controlled default transport; no sockets opened")
                }
                override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) = Unit
            })
            mockkConstructor(NetworkRequest.Builder::class)
            val builder = mockk<NetworkRequest.Builder>()
            every { builder.addCapability(any()) } returns builder
            every { builder.removeCapability(any()) } returns builder
            every { builder.addTransportType(any()) } returns builder
            every { builder.build() } returns mockk<NetworkRequest>()
            every { anyConstructed<NetworkRequest.Builder>().addCapability(any()) } returns builder
            val cm = mockk<ConnectivityManager>(relaxed = true) {
                every { registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>()) } answers {
                    callbacks.add(secondArg()); Unit
                }
            }
            val context = mockk<Context>(relaxed = true) {
                every { getSystemService(ConnectivityManager::class.java) } returns cm
                every { getSystemService(android.telephony.TelephonyManager::class.java) } returns null
            }
            provider = UnderlyingNetworkProvider(context).also { it.start() }
            every { network.openConnection(any()) } answers {
                networkAttempts.incrementAndGet()
                throw IOException("controlled bound-network failure")
            }
        }
        override fun close() {
            try {
                val workers = OutboundConnector::class.java.getDeclaredField("DOH_WORKERS").apply { isAccessible = true }.get(null)
                val pool = workers.javaClass.getDeclaredField("pool").apply { isAccessible = true }.get(workers) as ThreadPoolExecutor
                assertTrue("DoH workers must stop before restoring the process URL selector", ProxyTestKit.await(3000) {
                    pool.activeCount == 0 && pool.queue.isEmpty()
                })
            } finally {
                provider.stop()
                ProxySelector.setDefault(previousSelector)
                unmockkConstructor(NetworkRequest.Builder::class)
            }
        }
    }

    @Test(timeout = 15000) fun missingSelectedPhysicalNetworkDoesNotBecomeSystemDefault() = runBlocking {
        Fixture().use { f ->
            val c = testConnector(allowed, underlyingNetworkProvider = f.provider)
            try {
                val started = System.nanoTime()
                for (choice in DohEgressChoice.entries.filter { it != DohEgressChoice.DEFAULT }) {
                    f.provider.setDohEgressChoice(choice)
                    assertNull(f.provider.dohNetwork())
                    invoke(c, "resolveDohWithinBudget", "missing-egress.example")
                }
                assertEquals("A missing selected network must not send DoH over the default/VPN route", 0, f.defaultAttempts.get())
                assertTrue("The initial callback window must not restart for each failed lookup", (System.nanoTime() - started) / 1_000_000 < 2500)
            } finally { c.cancelPendingDns() }
        }
    }

    @Test(timeout = 12000) fun explicitSystemDefaultStillUsesDefaultTransport() = runBlocking {
        Fixture().use { f ->
            f.provider.setDohEgressChoice(DohEgressChoice.DEFAULT)
            val c = testConnector(allowed, underlyingNetworkProvider = f.provider)
            try {
                invoke(c, "resolveDohWithinBudget", "default-egress.example")
                assertTrue("Negative control must actually enter the default URL transport", f.defaultAttempts.get() > 0)
                assertEquals(0, f.networkAttempts.get())
            } finally { c.cancelPendingDns() }
        }
    }

    @Test(timeout = 12000) fun connectorWithoutNetworkProviderKeepsItsDefaultTransport() = runBlocking {
        Fixture().use { f ->
            val c = testConnector(allowed)
            try {
                invoke(c, "resolveDohWithinBudget", "no-provider.example")
                assertTrue(f.defaultAttempts.get() > 0)
                assertEquals(0, f.networkAttempts.get())
            } finally { c.cancelPendingDns() }
        }
    }

    @Test(timeout = 12000) fun healthyPrimaryDoesNotNeedTheUnavailableBackupNetwork() = runBlocking {
        Fixture().use { f ->
            f.provider.setDohEgressChoice(DohEgressChoice.CELLULAR)
            val good = listOf(InetAddress.getByName("192.0.2.76"))
            val c = testConnector(allowed, underlyingNetworkProvider = f.provider,
                systemDns = SystemDnsLookup { _, _ -> good })
            try {
                assertEquals(good, invoke(c, "resolve", "healthy-primary.example", null, null))
                assertEquals(0, f.defaultAttempts.get())
                assertEquals(0, f.networkAttempts.get())
            } finally { c.cancelPendingDns() }
        }
    }

    @Test(timeout = 12000) fun initialCallbackGapWaitsForTheChosenPhysicalNetwork() = runBlocking {
        Fixture().use { f ->
            f.provider.setDohEgressChoice(DohEgressChoice.WIFI)
            // Begin a fresh callback registration window immediately before the query.
            f.provider.stop(); f.callbacks.clear(); f.provider.start()
            val c = testConnector(allowed, underlyingNetworkProvider = f.provider)
            try {
                val query = async(start = CoroutineStart.UNDISPATCHED) {
                    invoke(c, "resolveDohWithinBudget", "initial-callback.example")
                }
                assertFalse("Pending initial callbacks do not yet prove the selected network is absent", query.isCompleted)
                f.callbacks[0].onAvailable(f.network)
                query.await()
                assertTrue(f.networkAttempts.get() > 0)
                assertEquals(0, f.defaultAttempts.get())
            } finally { c.cancelPendingDns() }
        }
    }

    @Test(timeout = 12000) fun cancellationDuringInitialCallbackWaitDoesNotStartLateDohWork() = runBlocking {
        Fixture().use { f ->
            f.provider.setDohEgressChoice(DohEgressChoice.WIFI)
            f.provider.stop(); f.callbacks.clear(); f.provider.start()
            val c = testConnector(allowed, underlyingNetworkProvider = f.provider)
            try {
                val query = async(start = CoroutineStart.UNDISPATCHED) {
                    invoke(c, "resolveDohWithinBudget", "cancelled-initial-callback.example")
                }
                assertFalse(query.isCompleted)
                query.cancelAndJoin()
                f.callbacks[0].onAvailable(f.network)
                assertTrue(query.isCancelled)
                assertEquals(0, f.networkAttempts.get())
                assertEquals(0, f.defaultAttempts.get())
                invoke(c, "resolveDohWithinBudget", "next-query-after-cancel.example")
                assertTrue(f.networkAttempts.get() > 0)
                assertEquals(0, f.defaultAttempts.get())
            } finally { c.cancelPendingDns() }
        }
    }

    @Test(timeout = 12000) fun boundNetworkFailureDoesNotFallbackAndCallbackRecoveryIsUsable() = runBlocking {
        Fixture().use { f ->
            f.provider.setDohEgressChoice(DohEgressChoice.WIFI)
            val c = testConnector(allowed, underlyingNetworkProvider = f.provider)
            try {
                f.callbacks[0].onAvailable(f.network)
                invoke(c, "resolveDohWithinBudget", "bound-egress.example")
                val before = f.networkAttempts.get()
                assertTrue(before > 0)
                f.callbacks[0].onLost(f.network)
                invoke(c, "resolveDohWithinBudget", "lost-egress.example")
                assertEquals(before, f.networkAttempts.get())
                assertEquals(0, f.defaultAttempts.get())
                f.callbacks[0].onAvailable(f.network)
                invoke(c, "resolveDohWithinBudget", "recovered-egress.example")
                assertTrue(f.networkAttempts.get() > before)
                assertEquals(0, f.defaultAttempts.get())
            } finally { c.cancelPendingDns() }
        }
    }

    @Test(timeout = 12000) fun unavailableDohDoesNotDiscardExistingSystemDnsRescue() = runBlocking {
        Fixture().use { f ->
            f.callbacks[0].onAvailable(f.network)
            f.provider.setDohEgressChoice(DohEgressChoice.CELLULAR)
            val good = listOf(InetAddress.getByName("192.0.2.75"))
            val c = testConnector(allowed, underlyingNetworkProvider = f.provider,
                systemDns = SystemDnsLookup { net, _ -> if (net == f.network) good else emptyList() })
            try {
                assertEquals(good, invoke(c, "resolve", "system-rescue.example", null, null))
                assertEquals(0, f.defaultAttempts.get())
                assertEquals(0, f.networkAttempts.get())
            } finally { c.cancelPendingDns() }
        }
    }
}
