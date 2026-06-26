package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.model.PerformancePreset
import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceTest {

    @Test fun coercedClampsToRanges() {
        val c = ConnectionLimits(
            maxGlobalConnections = 99999,
            maxPerClientConnections = 0,
            relayParallelism = 1,
            relayBufferBytes = 1,
            idleTimeoutSeconds = 99999,
        ).coerced()
        assertEquals(1024, c.maxGlobalConnections)
        assertEquals(16, c.maxPerClientConnections)
        assertEquals(4, c.relayParallelism)
        assertEquals(8 * 1024, c.relayBufferBytes)
        assertEquals(1800, c.idleTimeoutSeconds)
    }

    @Test fun presetsProduceExpectedDefaults() {
        assertEquals(256, PerformancePreset.BALANCED.toLimits().maxGlobalConnections)
        assertEquals(64, PerformancePreset.BATTERY.toLimits().maxGlobalConnections)
        assertEquals(512, PerformancePreset.HIGH_THROUGHPUT.toLimits().maxGlobalConnections)
    }

    @Test fun matchRecognizesPresetsAndCustom() {
        assertEquals(PerformancePreset.BALANCED, PerformancePreset.match(PerformancePreset.BALANCED.toLimits()))
        assertEquals(PerformancePreset.BATTERY, PerformancePreset.match(PerformancePreset.BATTERY.toLimits()))
        assertEquals(PerformancePreset.CUSTOM, PerformancePreset.match(ConnectionLimits(maxGlobalConnections = 100)))
    }
}
