package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.log.initialLoggingPreference
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test

class LoggingPreferenceTest {
    @Test fun persistedChoiceIsAvailableBeforeStartupEvents() {
        assertFalse(initialLoggingPreference(flowOf(false)))
        assertTrue(initialLoggingPreference(flowOf(true)))
    }
    @Test(timeout = 3000) fun unavailableOrBrokenStorageDoesNotEnableLoggingOrHangStartup() {
        assertFalse(initialLoggingPreference(flow { awaitCancellation() }, timeoutMs = 20))
        assertFalse(initialLoggingPreference(flow { throw java.io.IOException("unreadable settings") }))
    }
}
