package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.proxy.ConnectionRegistry
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConnectionRegistryTest {
    private val a = InetAddress.getByName("127.0.0.1")
    private val b = InetAddress.getByName("127.0.0.2")

    @Test fun enforcesGlobalCap() {
        val r = ConnectionRegistry(2, 10)
        val first = requireNotNull(r.tryAcquire(a))
        val second = requireNotNull(r.tryAcquire(b))
        assertNull(r.tryAcquire(a))
        first.close()
        requireNotNull(r.tryAcquire(a)).close()
        second.close()
        assertEquals(0, r.activeGlobal)
    }

    @Test fun enforcesPerClientCap() {
        val r = ConnectionRegistry(100, 1)
        requireNotNull(r.tryAcquire(a)).use {
            assertNull(r.tryAcquire(a))
            requireNotNull(r.tryAcquire(b)).close()
        }
    }

    @Test fun releaseTracksGlobalCount() {
        val r = ConnectionRegistry(5, 5)
        val one = requireNotNull(r.tryAcquire(a))
        val two = requireNotNull(r.tryAcquire(a))
        assertEquals(2, r.activeGlobal)
        one.close()
        assertEquals(1, r.activeGlobal)
        assertEquals(1, r.activeFor(a))
        two.close()
        assertEquals(0, r.activeFor(a))
    }

    @Test fun rejectedAcquireDoesNotLeakGlobalSlot() {
        val r = ConnectionRegistry(100, 1)
        requireNotNull(r.tryAcquire(a)).use {
            repeat(10) { assertNull(r.tryAcquire(a)) }
            assertEquals(1, r.activeGlobal)
        }
    }

    @Test fun resetZeroesAllCountsAndOldReleaseCannotConsumeNewLease() {
        val seen = mutableListOf<Int>()
        val r = ConnectionRegistry(1, 1, seen::add)
        val old = requireNotNull(r.tryAcquire(a))
        r.reset()
        assertEquals(0, r.activeGlobal)
        assertEquals(0, r.activeFor(a))
        val current = requireNotNull(r.tryAcquire(a))
        old.close()
        assertEquals(1, r.activeGlobal)
        assertEquals(1, r.activeFor(a))
        assertNull(r.tryAcquire(a))
        assertEquals(listOf(1, 0, 1), seen)
        current.close()
        assertEquals(0, r.activeGlobal)
    }

    @Test fun onChangeFiresWithLiveGaugeOnAcquireReleaseReset() {
        val seen = mutableListOf<Int>()
        val r = ConnectionRegistry(10, 10, seen::add)
        val one = requireNotNull(r.tryAcquire(a))
        val two = requireNotNull(r.tryAcquire(a))
        one.close()
        r.reset()
        two.close()
        assertEquals(listOf(1, 2, 1, 0), seen)
    }

    @Test fun rejectedAcquireDoesNotFireOnChange() {
        val seen = mutableListOf<Int>()
        val r = ConnectionRegistry(1, 1, seen::add)
        val one = requireNotNull(r.tryAcquire(a))
        assertNull(r.tryAcquire(a))
        assertEquals(listOf(1), seen)
        one.close()
    }

    @Test fun duplicateReleaseDoesNotConsumeAnotherConnection() {
        val r = ConnectionRegistry(2, 2)
        val one = requireNotNull(r.tryAcquire(a))
        val two = requireNotNull(r.tryAcquire(a))
        one.close(); one.close()
        assertEquals(1, r.activeGlobal)
        assertEquals(1, r.activeFor(a))
        two.close()
    }

    @Test fun loweringLimitsPreservesLiveConnectionsAndRejectsUntilBelowCap() {
        val r = ConnectionRegistry(4, 4)
        val one = requireNotNull(r.tryAcquire(a))
        val two = requireNotNull(r.tryAcquire(a))
        r.maxGlobal = 1; r.maxPerClient = 1
        assertEquals(2, r.activeGlobal)
        assertNull(r.tryAcquire(b))
        one.close()
        assertNull(r.tryAcquire(a))
        two.close()
        requireNotNull(r.tryAcquire(b)).close()
    }

    @Test fun observerFailureCannotLeakTheAcquiredLease() {
        val r = ConnectionRegistry(1, 1) { error("observer failed") }
        requireNotNull(r.tryAcquire(a)).close()
        assertEquals(0, r.activeGlobal)
        requireNotNull(r.tryAcquire(a)).close()
    }

    @Test(timeout = 15000) fun concurrentLastReleaseAndAcquireKeepLiveCounterAndCap() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(1000) {
                val r = ConnectionRegistry(10, 1)
                val old = requireNotNull(r.tryAcquire(a))
                val gate = CyclicBarrier(2)
                val release = pool.submit { gate.await(3, TimeUnit.SECONDS); old.close() }
                val acquire = pool.submit<ConnectionRegistry.Lease?> {
                    gate.await(3, TimeUnit.SECONDS); r.tryAcquire(a)
                }
                release.get(3, TimeUnit.SECONDS)
                val current = acquire.get(3, TimeUnit.SECONDS) ?: requireNotNull(r.tryAcquire(a))
                assertEquals(1, r.activeFor(a))
                assertEquals(1, r.activeGlobal)
                assertNull("No second connection at cap=1", r.tryAcquire(a))
                current.close()
                assertEquals(0, r.activeGlobal)
            }
        } finally { pool.shutdownNow() }
    }
}
