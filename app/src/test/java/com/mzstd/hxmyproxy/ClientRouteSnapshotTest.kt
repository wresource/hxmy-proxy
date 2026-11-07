package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.rules.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue

class ClientRouteSnapshotTest {
    @Test fun numericAliasesAndOtherClientsHaveOneGlobalRevisionButIndependentModes() {
        val engine = RuleEngine()
        engine.updateDirectClients(setOf("::ffff:192.0.2.10", "2001:db8::10"))
        val v4 = engine.clientRouteFor(InetAddress.getByName("::ffff:192.0.2.10"))
        val v6 = engine.clientRouteFor(InetAddress.getByName("2001:0db8:0:0:0:0:0:10"))
        val other = engine.clientRouteFor(InetAddress.getByName("192.0.2.11"))
        assertEquals("192.0.2.10", v4.sourceIp); assertTrue(v4.direct); assertTrue(v6.direct)
        assertEquals(ClientAddress.parse("2001:db8::10"), v6.sourceIp)
        assertFalse(other.direct); assertEquals(v4.revision, other.revision)
        engine.updateDirectClients(setOf("192.0.2.10", "2001:db8::10", "192.0.2.12"))
        val after = engine.clientRouteFor(InetAddress.getByName("192.0.2.10"))
        assertTrue(after.direct); assertTrue(after.revision > v4.revision)
    }

    @Test(timeout = 10000) fun concurrentReadsNeverPairAnOldModeWithANewRevision() {
        val engine = RuleEngine(); val peer = InetAddress.getByName("192.0.2.10")
        val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<String>()
        try {
            val writer = pool.submit {
                start.await()
                repeat(5000) { engine.updateDirectClients(setOf("192.0.2.10")); engine.updateDirectClients(emptySet()) }
            }
            val reader = pool.submit {
                start.await()
                repeat(20000) {
                    val snapshot = engine.clientRouteFor(peer)
                    if (snapshot.direct != (snapshot.revision % 2L == 1L)) errors.add(snapshot.toString())
                }
            }
            start.countDown(); writer.get(5, TimeUnit.SECONDS); reader.get(5, TimeUnit.SECONDS)
            assertTrue(errors.toString(), errors.isEmpty())
            assertEquals(10000L, engine.clientRouteFor(peer).revision)
        } finally { pool.shutdownNow() }
    }
}
