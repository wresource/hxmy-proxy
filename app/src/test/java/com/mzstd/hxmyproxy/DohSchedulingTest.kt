package com.mzstd.hxmyproxy

import android.net.Network
import com.mzstd.hxmyproxy.core.network.*
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.security.EgressGuard
import io.mockk.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

class DohSchedulingTest {
    private val allowed = object : EgressGuard { override fun isAllowed(addr: InetAddress) = true }
    @Suppress("UNCHECKED_CAST")
    private suspend fun resolve(c: OutboundConnector, host: String = "slow-doh.example"): List<InetAddress> = suspendCoroutineUninterceptedOrReturn { continuation ->
        val method = c.javaClass.declaredMethods.single { it.name == "resolve" }
        method.isAccessible = true
        method.invoke(c, host, null, null, continuation)
    }

    @Test(timeout = 12000) fun slowEarlyProvidersDoNotConsumeEveryLaterProviderChance() = runBlocking {
        val net = mockk<Network> { every { networkHandle } returns 9901L }
        val calls = ConcurrentLinkedQueue<String>()
        val running = AtomicInteger(); val peak = AtomicInteger()
        val provider = mockk<UnderlyingNetworkProvider> {
            every { current() } returns null
            every { dohNetwork() } returns net
        }
        every { net.openConnection(any()) } answers {
            val url = firstArg<URL>()
            calls.add(url.toString())
            mockk<HttpsURLConnection>(relaxed = true) {
                every { inputStream } answers {
                    val n = running.incrementAndGet(); peak.accumulateAndGet(n, ::maxOf)
                    try {
                        if (url.host == "8.8.8.8" || url.host == "1.1.1.1") {
                            Thread.sleep(1200)
                            throw SocketTimeoutException("controlled slow provider")
                        }
                        ByteArrayInputStream("{}".toByteArray())
                    } finally { running.decrementAndGet() }
                }
            }
        }
        mockkConstructor(JSONObject::class)
        val arr = mockk<JSONArray> {
            every { length() } returns 1
            every { getJSONObject(0) } returns mockk<JSONObject> {
                every { optInt("type") } returns 1
                every { getString("data") } returns "192.0.2.90"
            }
        }
        every { anyConstructed<JSONObject>().optJSONArray("Answer") } returns arr
        val c = testConnector(allowed, underlyingNetworkProvider = provider, systemDns = SystemDnsLookup { _, _ -> emptyList() })
        try {
            assertEquals(listOf(InetAddress.getByName("192.0.2.90")), resolve(c))
            assertTrue(calls.any { it.contains("223.5.5.5") })
            assertTrue(peak.get() <= 4)
        } finally { c.cancelPendingDns(); unmockkConstructor(JSONObject::class) }
    }

    /** Thread-local JSON is an explicit JVM Android-JSON boundary; actual scheduling/priority
     * and worker admission remain production. Each fake HTTP result has a different address. */
    private inner class Fixture(val response: (URL) -> Pair<Int, String>?) : AutoCloseable {
        val calls = ConcurrentLinkedQueue<String>()
        val active = AtomicInteger(); val peak = AtomicInteger()
        val json = ThreadLocal<JSONArray?>()
        val net = mockk<Network> { every { networkHandle } returns 9902L }
        val provider = mockk<UnderlyingNetworkProvider> {
            every { current() } returns null
            every { dohNetwork() } returns net
        }
        val c = testConnector(allowed, underlyingNetworkProvider = provider, systemDns = SystemDnsLookup { _, _ -> emptyList() })
        var connect: (URL) -> Unit = {}
        init {
            mockkConstructor(JSONObject::class)
            every { anyConstructed<JSONObject>().optJSONArray("Answer") } answers { json.get() }
            every { net.openConnection(any()) } answers {
                val url = firstArg<URL>()
                calls.add(url.toString())
                var counted = false
                mockk<HttpsURLConnection>(relaxed = true) {
                    every { connect() } answers {
                        counted = true
                        peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                        connect(url)
                    }
                    every { inputStream } answers {
                        val result = response(url)
                        json.set(result?.let { (type, address) -> mockk<JSONArray> {
                            every { length() } returns 1
                            every { getJSONObject(0) } returns mockk<JSONObject> {
                                every { optInt("type") } returns type
                                every { getString("data") } returns address
                            }
                        } })
                        ByteArrayInputStream("{}".toByteArray())
                    }
                    every { disconnect() } answers { if (counted) { counted = false; active.decrementAndGet() }; Unit }
                }
            }
        }
        override fun close() {
            c.cancelPendingDns()
            assertTrue("all actual HTTP workers finish", ProxyTestKit.await(3000) { active.get() == 0 })
            unmockkConstructor(JSONObject::class)
        }
    }

    @Test(timeout = 12000) fun slowValidPreferredProviderStillBeatsFasterDifferentBackup() = runBlocking {
        Fixture { url ->
            if (url.host == "8.8.8.8") { Thread.sleep(850); 1 to "192.0.2.1" }
            else 1 to "192.0.2.2"
        }.use { f ->
            f.connect = { url -> if (url.host == "8.8.8.8") Thread.sleep(850) }
            assertEquals(listOf(InetAddress.getByName("192.0.2.1")), resolve(f.c))
            assertTrue("lower-priority work really completed before preferred", f.calls.any { it.contains("1.1.1.1") })
            assertTrue(f.peak.get() <= 4)
        }
    }

    @Test(timeout = 12000) fun ipv6OnlyAnswerSurvivesAnEmptyAQuery() = runBlocking {
        Fixture { url -> if (url.query.endsWith("type=28")) 28 to "2001:db8::99" else null }.use { f ->
            assertEquals(listOf(InetAddress.getByName("2001:db8::99")), resolve(f.c))
            assertTrue(f.calls.any { it.contains("type=28") })
        }
    }

    @Test(timeout = 15000) fun cancelledUninterruptibleQueriesKeepActualWorkersBoundedAcrossHosts() = runBlocking {
        val release = CountDownLatch(1)
        Fixture { _ ->
            while (release.count != 0L) try { release.await() } catch (_: InterruptedException) { }
            1 to "192.0.2.7"
        }.use { f ->
            try {
                val requests = List(10) { i -> async {
                    withTimeoutOrNull(700) { runCatching { resolve(f.c, "busy$i.example") } }
                } }
                requests.awaitAll()
                assertEquals("cancelled native work still occupies all four workers", 4, f.active.get())
                assertEquals(4, f.peak.get())
                val tooSoon = withTimeoutOrNull(100) { runCatching { resolve(f.c, "healthy.example") } }
                assertNull(tooSoon)
                assertEquals(4, f.peak.get())
                release.countDown()
                assertTrue(ProxyTestKit.await(3000) { f.active.get() == 0 })
                assertEquals(listOf(InetAddress.getByName("192.0.2.7")), resolve(f.c, "healthy.example"))
                assertTrue(f.peak.get() <= 4)
            } finally { release.countDown() }
        }
    }

    @Test(timeout = 12000) fun readyBackupSurvivesOverallDeadlineAndLatePreferredAnswerCannotOverwriteIt() = runBlocking {
        val release = CountDownLatch(1)
        Fixture { url ->
            if (url.host == "8.8.8.8") {
                while (release.count != 0L) try { release.await() } catch (_: InterruptedException) { }
                1 to "192.0.2.1"
            } else 1 to "192.0.2.2"
        }.use { f ->
            try {
                val start = System.nanoTime()
                val answer = withTimeout(4000) { resolve(f.c) }
                val ms = (System.nanoTime() - start) / 1_000_000
                assertEquals(listOf(InetAddress.getByName("192.0.2.2")), answer)
                assertTrue("preferred had its budget; ready backup not discarded: $ms", ms in 2800..3800)
                assertEquals("cancelled native primary still owns its worker", 1, f.active.get())
                release.countDown()
                assertTrue(ProxyTestKit.await(2000) { f.active.get() == 0 })
                val before = f.calls.size
                assertEquals(answer, resolve(f.c))
                assertEquals("late primary cannot replace or invalidate the published answer", before, f.calls.size)
            } finally { release.countDown() }
        }
    }
}
