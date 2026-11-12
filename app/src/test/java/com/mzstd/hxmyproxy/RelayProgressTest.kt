package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.proxy.*
import org.junit.Assert.*
import org.junit.Test

class RelayProgressTest {
    private val second = 1_000_000_000L
    private val snapshot = RelayIdleSnapshot(120000, 1234, listOf(111L, 222L, 333L, 444L), 0, 65536)

    @Test fun samplesUseIntervalsRatherThanDilutingTrafficWithWholeConnectionLifetime() {
        val monitor = RelayProgressMonitor(0, RelayProgressBudget())
        assertNull(monitor.poll(29 * second, 1000, 1_000_000, true) { error("not yet due") })
        val first = monitor.poll(30 * second, 1000, 1_000_000, true) { snapshot }!!
        assertEquals(30000, first.windowMs); assertEquals(1_000_000, first.downDelta)
        val secondSample = monitor.poll(60 * second, 1200, 1_300_000, true) { snapshot }!!
        assertEquals(300_000, secondSample.downDelta); assertEquals(200, secondSample.upDelta)
        assertEquals(65536, secondSample.activity.pendingDown)
    }

    @Test fun idleSmallConnectionsAndDisabledLoggingDoNotEmitOrCollectSnapshots() {
        val monitor = RelayProgressMonitor(0, RelayProgressBudget())
        assertNull(monitor.poll(30 * second, 100, 1000, true) { error("small keep-alive") })
        assertNull(monitor.poll(60 * second, 100, 1_000_000, false) { error("logging off") })
        val sample = monitor.poll(90 * second, 120, 1_100_000, true) { snapshot }!!
        assertEquals(100_000, sample.downDelta); assertEquals(30000, sample.windowMs)
    }

    @Test fun processBudgetIsSharedAndSkippedSamplesDoNotLoseByteDeltas() {
        val budget = RelayProgressBudget(capacity = 1, windowNs = 60 * second)
        val first = RelayProgressMonitor(0, budget); val secondFlow = RelayProgressMonitor(0, budget)
        assertNotNull(first.poll(30 * second, 0, 1_000_000, true) { snapshot })
        assertNull(secondFlow.poll(30 * second, 0, 2_000_000, true) { error("global limit") })
        assertNull(secondFlow.poll(89 * second, 0, 3_000_000, true) { error("not replenished") })
        val sample = secondFlow.poll(90 * second, 0, 4_000_000, true) { snapshot }!!
        assertEquals(4_000_000, sample.downDelta); assertEquals(90000, sample.windowMs)
    }
}
