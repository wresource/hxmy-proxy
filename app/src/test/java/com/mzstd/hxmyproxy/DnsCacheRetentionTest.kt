package com.mzstd.hxmyproxy

import android.net.Network
import com.mzstd.hxmyproxy.core.network.SystemDnsLookup
import com.mzstd.hxmyproxy.core.proxy.OutboundConnector
import com.mzstd.hxmyproxy.core.security.EgressGuard
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Exercise the production connector cache; only the network lookup and entry ages are controlled. */
class DnsCacheRetentionTest {
    private val address = InetAddress.getByName("192.0.2.80")
    private inner class Fixture(val onLookup: suspend (String) -> Unit = {}) {
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val c = testConnector(object : EgressGuard { override fun isAllowed(addr: InetAddress) = true },
            systemDns = SystemDnsLookup { _, host -> calls.computeIfAbsent(host) { AtomicInteger() }.incrementAndGet(); onLookup(host); listOf(address) })
            .apply { backupDnsEnabled = false }
        @Suppress("UNCHECKED_CAST")
        suspend fun resolve(host: String): List<InetAddress> = suspendCoroutineUninterceptedOrReturn { cont ->
            c.javaClass.declaredMethods.single { it.name == "resolve" }.apply { isAccessible = true }
                .invoke(c, host, null, null, cont)
        }
        private fun cache() = c.javaClass.getDeclaredField("dnsCache").apply { isAccessible = true }.get(c) as Map<*, *>
        private fun entry(host: String) = cache().entries.single {
            val key = it.key!!
            key.javaClass.getDeclaredField("host").apply { isAccessible = true }.get(key) == host
        }.value!!
        fun writtenAt(host: String): Long = entry(host).let {
            it.javaClass.getDeclaredField("atMs").apply { isAccessible = true }.getLong(it)
        }
        fun age(host: String, ageMs: Long) {
            val e = entry(host)
            e.javaClass.getDeclaredField("atMs").apply { isAccessible = true }.setLong(e, System.currentTimeMillis() - ageMs)
        }
        fun count(host: String) = calls[host]?.get() ?: 0
        suspend fun fill() { resolve("hot.example"); repeat(511) { resolve("cold-$it.example") }; assertEquals(512, c.dnsStats().cached) }
        fun waiters(): Int {
            val lock = c.javaClass.getDeclaredField("dnsLock").apply { isAccessible = true }.get(c)
            return synchronized(lock) {
                val flights = c.javaClass.getDeclaredField("inflightDns").apply { isAccessible = true }.get(c) as Map<*, *>
                flights.values.sumOf { flight -> flight!!.javaClass.getDeclaredField("waiters").apply { isAccessible = true }.getInt(flight) }
            }
        }
    }

    @Test(timeout = 15000) fun addingOneEntryKeepsTheRecentHotAnswerAndEvictsOnlyTheLruEntry() = runBlocking {
        val f = Fixture()
        try {
            f.fill(); f.resolve("hot.example")
            val stamp = f.writtenAt("hot.example")
            f.resolve("new.example"); f.resolve("hot.example")
            println("capacity=${f.c.dnsStats().cached} hotQueries=${f.count("hot.example")}")
            assertEquals("a capacity boundary must not flush a valid hot answer", 1, f.count("hot.example"))
            assertEquals(512, f.c.dnsStats().cached)
            assertEquals("LRU access never renews TTL", stamp, f.writtenAt("hot.example"))
            f.resolve("cold-0.example")
            assertEquals("only least recently used entry needs re-resolution", 2, f.count("cold-0.example"))
            assertEquals(512, f.c.dnsStats().cached)
        } finally { f.c.cancelPendingDns() }
    }

    @Test(timeout = 15000) fun expiredEntriesAreRemovedBeforeAnyValidAnswerIsEvicted() = runBlocking {
        val f = Fixture()
        try {
            f.fill()
            repeat(256) { f.age("cold-$it.example", 31000) }
            f.resolve("new.example"); f.resolve("hot.example")
            assertEquals(1, f.count("hot.example"))
            assertEquals("256 expired entries removed, one new entry added", 257, f.c.dnsStats().cached)
            f.resolve("cold-400.example"); assertEquals(1, f.count("cold-400.example"))
        } finally { f.c.cancelPendingDns() }
    }

    @Test(timeout = 10000) fun cacheHitsDoNotExtendThirtySecondLifetimeAndNetworkChangeStillInvalidates() = runBlocking {
        val f = Fixture()
        try {
            f.resolve("ttl.example"); f.age("ttl.example", 25000)
            val stamp = f.writtenAt("ttl.example")
            repeat(20) { f.resolve("ttl.example") }
            assertEquals(1, f.count("ttl.example")); assertEquals(stamp, f.writtenAt("ttl.example"))
            f.age("ttl.example", 31000); f.resolve("ttl.example"); assertEquals(2, f.count("ttl.example"))
            f.c.clearDnsCache(); f.resolve("ttl.example"); assertEquals(3, f.count("ttl.example"))
        } finally { f.c.cancelPendingDns() }
    }

    @Test(timeout = 15000) fun concurrentColdLookupsStayBoundedWithoutFlushingAnActiveHotEntry() = runBlocking {
        val f = Fixture()
        try {
            f.fill(); f.resolve("hot.example")
            repeat(32) { batch ->
                List(8) { slot -> async(Dispatchers.Default) { f.resolve("batch-$batch-$slot.example") } }
                    .awaitAll().forEach { assertEquals(listOf(address), it) }
                f.resolve("hot.example")
                assertEquals(512, f.c.dnsStats().cached)
            }
            assertEquals(1, f.count("hot.example"))
            assertEquals(256L, f.c.dnsStats().cacheEvicted)
            assertEquals(0, f.c.dnsStats().inflight)
        } finally { f.c.cancelPendingDns() }
    }

    @Test(timeout = 10000) fun twentyCallersShareOneLookupAndOneCancelledCallerDoesNotDiscardTheAnswer() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val f = Fixture { entered.complete(Unit); release.await() }
        val owner = async { f.resolve("shared.example") }
        var others = emptyList<Deferred<List<InetAddress>>>()
        try {
            entered.await()
            others = List(19) { async(Dispatchers.Default) { f.resolve("shared.example") } }
            withTimeout(2000) { while (f.waiters() != 20) delay(1) }
            owner.cancelAndJoin(); release.complete(Unit)
            others.awaitAll().forEach { assertEquals(listOf(address), it) }
            repeat(20) { f.resolve("shared.example") }
            assertEquals(1, f.count("shared.example")); assertEquals(1, f.c.dnsStats().cached)
            assertEquals(0, f.c.dnsStats().inflight)
        } finally { release.complete(Unit); owner.cancel(); others.forEach { it.cancel() }; f.c.cancelPendingDns() }
    }
}
