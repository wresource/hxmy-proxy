package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.network.SystemDnsLookup
import com.mzstd.hxmyproxy.core.proxy.ProxyError
import com.mzstd.hxmyproxy.core.proxy.ProxyException
import com.mzstd.hxmyproxy.core.security.EgressGuard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

class DnsAnswerIntegrityTest {
    private val local = InetAddress.getByName("127.0.0.1")
    private val allow = object : EgressGuard { override fun isAllowed(addr: InetAddress) = true }
    @Suppress("UNCHECKED_CAST")
    private fun raw(vararg items: Any?): List<InetAddress> = items.toList() as List<InetAddress>

    @Test fun candidateBoundaryDropsInvalidElementsWithoutReorderingValidAddresses() {
        val v4 = InetAddress.getByName("192.0.2.1")
        val v6 = InetAddress.getByName("2001:db8::1")
        val seen = mutableListOf<InetAddress>()
        val connector = testConnector(object : EgressGuard {
            override fun isAllowed(addr: InetAddress): Boolean { seen.add(addr); return true }
        })
        assertEquals(listOf(v6, v4, v6), connector.tcpCandidates(raw(null, v6, "bad", v4, v6)))
        assertEquals(listOf(v6, v4, v6), seen)
    }

    @Test(timeout = 10000) fun mixedAnswerConnectsAndCachedAnswerOwnsItsData() = runBlocking {
        val input = arrayListOf<Any?>(null, local, "bad")
        val calls = AtomicInteger()
        val connector = testConnector(allow, systemDns = SystemDnsLookup { _, _ ->
            calls.incrementAndGet()
            @Suppress("UNCHECKED_CAST") (input as List<InetAddress>)
        }).apply { backupDnsEnabled = false }
        try {
            ServerSocket(0, 10, local).use { server ->
                connector.connect("snapshot.example", server.localPort).close()
                input.clear() // The resolver retains and mutates its original collection.
                repeat(3) { connector.connect("snapshot.example", server.localPort).close() }
            }
            assertEquals("cache hits must reuse the accepted snapshot", 1, calls.get())
        } finally { connector.clearDnsCache() }
    }

    @Test(timeout = 10000) fun allInvalidFailsAsDnsAndDoesNotPublishOrHoldCapacity() = runBlocking {
        var valid = false
        var calls = 0
        val connector = testConnector(allow, systemDns = SystemDnsLookup { _, _ ->
            calls++; if (valid) listOf(local) else raw(null, "bad")
        }).apply { backupDnsEnabled = false }
        try {
            val error = runCatching { connector.connect("invalid-answer.example", 9) }.exceptionOrNull()
            assertTrue("Expected DNS failure, got $error", error is ProxyException)
            assertSame(ProxyError.DnsFailure, (error as ProxyException).error)
            assertEquals(0, connector.dnsStats().inflight)
            valid = true
            ServerSocket(0, 1, local).use { connector.connect("invalid-answer.example", it.localPort).close() }
            assertEquals(2, calls)
        } finally { connector.clearDnsCache() }
    }

    @Test(timeout = 10000) fun structurallyValidButForbiddenAddressStillUsesTheGuard() = runBlocking {
        val connector = testConnector(object : EgressGuard { override fun isAllowed(addr: InetAddress) = false },
            systemDns = SystemDnsLookup { _, _ -> raw(null, local) }).apply { backupDnsEnabled = false }
        try {
            val error = runCatching { connector.connect("denied.example", 9) }.exceptionOrNull()
            assertTrue("Expected access denial, got $error", error is ProxyException)
            assertSame(ProxyError.AccessDenied, (error as ProxyException).error)
        } finally { connector.clearDnsCache() }
    }
}
