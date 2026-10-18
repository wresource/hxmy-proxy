package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.proxy.RequestActivityStore
import com.mzstd.hxmyproxy.core.proxy.RequestOutcome
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class RequestActivityTest {
    @Test fun hostIdentityMatchesAccountingWithoutInventingTruncatedTargets() {
        val s = RequestActivityStore().apply { setEnabled(true) }
        s.rule(s.open(1, "HTTP"), "Example.COM", "PROXY", "DEFAULT")
        assertEquals("example.com", s.snapshot().single().host)
        s.rule(s.open(2, "HTTP"), "x".repeat(300), "PROXY", "DEFAULT")
        assertEquals(1, s.snapshot().size)
    }
    @Test fun disabledLoggingDoesNotRetainRequestDetails() {
        val s = RequestActivityStore()
        assertNull(s.open(1, "HTTP"))
        s.rule(null, "private.example", "PROXY", "DEFAULT")
        assertTrue(s.snapshot().isEmpty())
        s.setEnabled(true)
        val old = s.open(1, "HTTP")
        s.rule(old, "private.example", "PROXY", "DEFAULT")
        s.setEnabled(false)
        assertTrue(s.snapshot().isEmpty())
        s.setEnabled(true)
        s.connected(old, "192.0.2.1")
        assertTrue(s.snapshot().isEmpty())
    }

    @Test fun connectedIsNotReportedAsRelayedUntilBytesAreKnown() {
        val s = RequestActivityStore().apply { setEnabled(true) }
        val t = s.open(1, "HTTP")
        s.rule(t, "example.org", "PROXY", "DEFAULT")
        s.connected(t, "192.0.2.1")
        assertEquals(RequestOutcome.CONNECTED, s.snapshot().single().outcome)
        assertNull(s.snapshot().single().uploadBytes)
        s.closed(t, "eof", 12, 34)
        assertEquals(RequestOutcome.RELAYED, s.snapshot().single().outcome)
        assertEquals(34L, s.snapshot().single().downloadBytes)
    }

    @Test fun emptyCloseAndFailedCloseDoNotBecomeSuccessfulTraffic() {
        val s = RequestActivityStore().apply { setEnabled(true) }
        val a = s.open(1, "HTTP"); s.rule(a, "a.org", "PROXY", "DEFAULT"); s.closed(a, "eof", 0, 0)
        assertEquals(RequestOutcome.CLOSED, s.snapshot().first().outcome)
        val b = s.open(2, "SOCKS5"); s.rule(b, "b.org", "DIRECT", "USER_ALLOW")
        s.failed(b, "dns", "failure", "dns"); s.connected(b, "192.0.2.2"); s.closed(b, "error", 100, 200)
        val failed = s.snapshot().first { it.requestId == 2 }
        assertEquals(RequestOutcome.FAILED, failed.outcome)
        assertEquals("dns", failed.errorCode)
        assertEquals(100L, failed.uploadBytes)
        val c = s.open(3, "HTTP"); s.rule(c, "c.org", "REJECT", "USER_BLOCK")
        s.failed(c, "rule", "denied"); s.closed(c, "denied", 0, 0)
        assertEquals(RequestOutcome.BLOCKED, s.snapshot().first { it.requestId == 3 }.outcome)
    }

    @Test fun resetInvalidatesLateCallbacksEvenIfRequestIdsAreReused() {
        val s = RequestActivityStore().apply { setEnabled(true) }
        val old = s.open(1, "HTTP"); s.rule(old, "old.example", "DIRECT", "USER_ALLOW")
        s.reset()
        val fresh = s.open(1, "HTTP"); s.rule(fresh, "new.example", "PROXY", "DEFAULT")
        s.closed(old, "late", 1000, 2000)
        assertEquals("new.example", s.snapshot().single().host)
        assertNull(s.snapshot().single().downloadBytes)
    }

    @Test fun boundedEvictionCannotResurrectPartialOldRequests() {
        val s = RequestActivityStore(2).apply { setEnabled(true) }
        val old = s.open(1, "HTTP"); s.rule(old, "old.example", "PROXY", "DEFAULT")
        for (id in 2..3) s.rule(s.open(id, "HTTP"), "$id.example", "PROXY", "DEFAULT")
        s.closed(old, "late", 100, 200)
        assertEquals(setOf(2, 3), s.snapshot().map { it.requestId }.toSet())
    }

    @Test fun concurrentEventsKeepCompleteImmutableRowsAndBoundedStorage() {
        val clock = AtomicLong()
        val s = RequestActivityStore(64, clock::incrementAndGet).apply { setEnabled(true) }
        val pool = Executors.newFixedThreadPool(8)
        val finished = CountDownLatch(1000)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        try {
            repeat(1000) { id -> pool.execute {
                try { val token = s.open(id, "HTTP"); s.rule(token, "$id.example.org", "PROXY", "DEFAULT")
                    s.dns(token, "sys-egress"); s.connected(token, "192.0.2.1"); s.closed(token, "eof", id.toLong(), 50)
                    assertTrue(s.snapshotState().records.size <= 64)
                } catch (failure: Throwable) { failures.add(failure) } finally { finished.countDown() }
            } }
            assertTrue(finished.await(10, TimeUnit.SECONDS))
            assertTrue(failures.joinToString(), failures.isEmpty())
            val rows = s.snapshot(); assertEquals(64, rows.size); assertEquals(64, rows.map { it.key }.distinct().size)
            assertTrue(rows.all { it.host == "${it.requestId}.example.org" })
            assertTrue(rows.filter { it.outcome == RequestOutcome.RELAYED }.all { it.uploadBytes == it.requestId.toLong() && it.downloadBytes == 50L })
        } finally { pool.shutdownNow() }
    }
}
