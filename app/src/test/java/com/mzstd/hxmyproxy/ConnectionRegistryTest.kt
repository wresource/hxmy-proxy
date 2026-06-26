package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.proxy.ConnectionRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class ConnectionRegistryTest {

    private fun ip(s: String) = InetAddress.getByName(s)

    @Test fun enforcesGlobalCap() {
        val r = ConnectionRegistry(maxGlobal = 2, maxPerClient = 10)
        val a = ip("1.2.3.4")
        assertTrue(r.tryAcquire(a))
        assertTrue(r.tryAcquire(a))
        assertFalse(r.tryAcquire(a))
        r.release(a)
        assertTrue(r.tryAcquire(a))
    }

    @Test fun enforcesPerClientCap() {
        val r = ConnectionRegistry(maxGlobal = 100, maxPerClient = 1)
        val a = ip("1.2.3.4")
        val b = ip("5.6.7.8")
        assertTrue(r.tryAcquire(a))
        assertFalse(r.tryAcquire(a))   // a at its cap
        assertTrue(r.tryAcquire(b))    // b independent
    }

    @Test fun releaseTracksGlobalCount() {
        val r = ConnectionRegistry(maxGlobal = 5, maxPerClient = 5)
        val a = ip("1.2.3.4")
        r.tryAcquire(a); r.tryAcquire(a)
        assertEquals(2, r.activeGlobal)
        r.release(a)
        assertEquals(1, r.activeGlobal)
        assertEquals(1, r.activeFor(a))
    }

    @Test fun rejectedAcquireDoesNotLeakGlobalSlot() {
        val r = ConnectionRegistry(maxGlobal = 100, maxPerClient = 1)
        val a = ip("1.2.3.4")
        assertTrue(r.tryAcquire(a))
        assertFalse(r.tryAcquire(a))   // per-client rejects; must roll back global
        assertEquals(1, r.activeGlobal)
    }

    @Test fun resetZeroesAllCounts() {
        val r = ConnectionRegistry(maxGlobal = 10, maxPerClient = 10)
        val a = ip("1.2.3.4")
        r.tryAcquire(a); r.tryAcquire(a)
        assertEquals(2, r.activeGlobal)
        r.reset()
        assertEquals(0, r.activeGlobal)
        assertEquals(0, r.activeFor(a))
        assertTrue(r.tryAcquire(a))    // 重置后可正常占用
    }

    @Test fun onChangeFiresWithLiveGaugeOnAcquireReleaseReset() {
        val seen = ArrayList<Int>()
        val r = ConnectionRegistry(maxGlobal = 10, maxPerClient = 10, onChange = { seen.add(it) })
        val a = ip("1.2.3.4")
        r.tryAcquire(a)   // -> 1
        r.tryAcquire(a)   // -> 2
        r.release(a)      // -> 1
        r.reset()         // -> 0
        assertEquals(listOf(1, 2, 1, 0), seen)
    }

    @Test fun rejectedAcquireDoesNotFireOnChange() {
        val seen = ArrayList<Int>()
        val r = ConnectionRegistry(maxGlobal = 1, maxPerClient = 1, onChange = { seen.add(it) })
        val a = ip("1.2.3.4")
        assertTrue(r.tryAcquire(a))    // -> 1
        assertFalse(r.tryAcquire(a))   // 被拒，不应再触发 onChange
        assertEquals(listOf(1), seen)
    }
}
