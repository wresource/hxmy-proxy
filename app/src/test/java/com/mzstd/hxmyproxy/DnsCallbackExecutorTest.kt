package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.network.DnsAddressSnapshot
import com.mzstd.hxmyproxy.core.network.DnsCallbackExecutor
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class DnsCallbackExecutorTest {
    @Test(timeout = 10000) fun sameQueryQueuesWithoutOccupyingAnotherWorkerAndOtherQueriesStillRun() {
        val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val done = CountDownLatch(3); val order = CopyOnWriteArrayList<Int>()
        val failures = CopyOnWriteArrayList<Throwable>()
        val a = DnsCallbackExecutor(pool) { failures.add(it) }
        val b = DnsCallbackExecutor(pool) { failures.add(it) }
        try {
            a.execute { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); order.add(1); done.countDown() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            a.execute { order.add(2); done.countDown() }
            val other = CountDownLatch(1)
            b.execute { other.countDown(); done.countDown() }
            assertTrue("A's queued callback must not block B's worker", other.await(2, TimeUnit.SECONDS))
            assertTrue(order.isEmpty())
            release.countDown(); assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(1, 2), order); assertTrue(failures.toString(), failures.isEmpty())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test(timeout = 15000) fun concurrentSubmissionsKeepAllCallbacksAndNeverOverlapPerQuery() {
        val workers = Executors.newFixedThreadPool(2)
        val producers = Executors.newFixedThreadPool(4)
        val pending = CountDownLatch(8000); val active = AtomicInteger(); val overlaps = AtomicInteger()
        val failures = CopyOnWriteArrayList<Throwable>()
        val serial = DnsCallbackExecutor(workers) { failures.add(it) }
        try {
            val work = List(4) { producers.submit {
                repeat(2000) { serial.execute {
                    if (active.incrementAndGet() != 1) overlaps.incrementAndGet()
                    Thread.yield(); active.decrementAndGet(); pending.countDown()
                } }
            } }
            work.forEach { it.get(5, TimeUnit.SECONDS) }
            assertTrue(pending.await(5, TimeUnit.SECONDS)); assertEquals(0, overlaps.get())
            assertTrue(failures.toString(), failures.isEmpty())
        } finally { producers.shutdownNow(); workers.shutdownNow() }
    }

    @Test fun callbackFailureDoesNotStrandCleanupAndReentrantSubmissionIsSerialized() {
        val scheduled = ArrayDeque<Runnable>(); val failures = mutableListOf<Throwable>()
        val serial = DnsCallbackExecutor(Executor { scheduled.add(it) }) { failures.add(it) }
        val order = mutableListOf<Int>()
        serial.execute {
            serial.execute { order.add(3) }
            order.add(1); throw IllegalStateException("callback")
        }
        serial.execute { order.add(2) }
        assertEquals(1, scheduled.size); scheduled.removeFirst().run()
        assertEquals(listOf(1, 2, 3), order); assertEquals(1, failures.size)
        serial.execute { order.add(4) }; scheduled.removeFirst().run()
        assertEquals(listOf(1, 2, 3, 4), order)
    }

    @Test fun rejectedDispatchIsReportedOnceAndDoesNotExecuteWithoutAWorker() {
        var reports = 0; var ran = false
        val serial = DnsCallbackExecutor(Executor { throw RejectedExecutionException() }) { reports++; throw IllegalStateException("observer") }
        repeat(3) { serial.execute { ran = true } }
        assertEquals(1, reports); assertFalse(ran)
    }

    @Test fun snapshotIsImmutablePreservesDuplicatesAndReusesItsIdentity() {
        val a = InetAddress.getByName("192.0.2.1"); val b = InetAddress.getByName("2001:db8::1")
        val input = arrayListOf<Any?>(b, null, a, a, "bad")
        val value = DnsAddressSnapshot.capture(input, "test")
        input.clear()
        assertEquals(listOf(b, a, a), value)
        assertSame(value, DnsAddressSnapshot.capture(value, "cache"))
        val error = runCatching { @Suppress("UNCHECKED_CAST") (value as java.util.List<InetAddress>).set(0, a) }.exceptionOrNull()
        assertTrue(error.toString(), error is UnsupportedOperationException)
        assertEquals(listOf(b, a, a), value)
    }
}
