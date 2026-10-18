package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.proxy.RelayStallStats
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class RelayStallConcurrencyTest {
    @Test(timeout = 15000) fun snapshotsCannotSplitOneClosedTunnelAcrossWindows() {
        RelayStallStats.reset()
        val done = AtomicBoolean(false)
        val writer = thread {
            try { repeat(200000) { RelayStallStats.record(1000, 500, 250) } }
            finally { done.set(true) }
        }
        var observed = 0L
        fun collect() {
            RelayStallStats.snapshotAndReset()?.let {
                observed += it.tunnels
                assertEquals(50, it.inPct)
                assertEquals(25, it.outPct)
                assertEquals(75, it.maxPct)
            }
        }
        try { while (!done.get()) collect(); writer.join(); collect(); assertEquals(200000L, observed) }
        finally { writer.join(); RelayStallStats.reset() }
    }
}
