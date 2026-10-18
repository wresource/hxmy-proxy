package com.mzstd.hxmyproxy

import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.log.FileLog
import com.mzstd.hxmyproxy.testsupport.VerificationEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

class LoggingPreferenceInstrumentedTest {
    @Test fun preferenceAppliesWhileProxyIsStopped() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, VerificationEntryPoint::class.java)
        val original = entry.settings().settings.first()
        assertFalse("test requires stopped proxy", entry.repository().isRunning())
        try {
            entry.settings().update { it.copy(logEnabled = false) }
            val disabled = withTimeoutOrNull(1500) { while (FileLog.enabled) delay(10); true } ?: false
            assertTrue("persisted log switch must apply without starting sharing", disabled)
            val before = FileLog.snapshot()
            FileLog.crash("controlled-disabled", IllegalStateException("must not be saved"))
            assertEquals(before, FileLog.snapshot())
            entry.settings().update { it.copy(logEnabled = true) }
            assertTrue(withTimeoutOrNull(1500) { while (!FileLog.enabled) delay(10); true } ?: false)
            FileLog.i("log-switch-test", "re-enabled")
            assertTrue(FileLog.snapshot().contains("log-switch-test: re-enabled"))
        } finally { entry.settings().update { original } }
    }

    /** 外部驱动随后杀进程并重启，检查session.start不会在禁用日志时落盘。 */
    @Test fun persistDisabledForColdStartVerification() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, VerificationEntryPoint::class.java)
        entry.settings().update { it.copy(logEnabled = false) }
    }

    @Test fun restoreEnabledAfterColdStartVerification() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, VerificationEntryPoint::class.java)
        entry.settings().update { it.copy(logEnabled = true) }
    }
}
