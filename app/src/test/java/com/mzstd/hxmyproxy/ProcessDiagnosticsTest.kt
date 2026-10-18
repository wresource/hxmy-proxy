package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.log.FileLog
import com.mzstd.hxmyproxy.core.log.ProcessDiagnostics
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ProcessDiagnosticsTest {
    private val session = ProcessDiagnostics.Session(123, 10001, "test.process", 1000, 200, 183, "session-a")

    @Test fun crashKeepsOriginalCauseIdentityAndDelegatesToAndroidHandler() {
        val dir = Files.createTempDirectory("process-evidence").toFile()
        val error = IllegalArgumentException("controlled failure")
        var delegated: Throwable? = null
        try {
            FileLog.enabled = true; FileLog.init(dir)
            val handler = ProcessDiagnostics.handler({ _, e -> delegated = e }, session)
            handler.uncaughtException(Thread.currentThread(), error)
            val text = dir.resolve("key.log").readText()
            assertTrue(text.contains("pid=123 uid=10001 process=test.process"))
            assertTrue(text.contains("sid=session-a"))
            assertTrue(text.contains("fingerprint="))
            assertTrue(text.contains("IllegalArgumentException: controlled failure"))
            assertSame(error, delegated)
        } finally { FileLog.clear(); dir.deleteRecursively(); FileLog.enabled = true }
    }

    @Test fun loggingFailureDoesNotReplaceOrBlockOriginalException() {
        val error = IllegalStateException("original")
        var delegated: Throwable? = null
        val handler = ProcessDiagnostics.handler({ _, e -> delegated = e }, session) { _, _, _ ->
            throw OutOfMemoryError("logger failed")
        }
        handler.uncaughtException(Thread.currentThread(), error)
        assertSame(error, delegated)
    }

    @Test fun disabledDiagnosticsStillDelegateWithoutWritingCrash() {
        val dir = Files.createTempDirectory("disabled-process-evidence").toFile()
        var delegated = false
        try {
            FileLog.init(dir); FileLog.enabled = false
            ProcessDiagnostics.handler({ _, _ -> delegated = true }, session)
                .uncaughtException(Thread.currentThread(), IllegalStateException("private"))
            assertEquals("", FileLog.snapshot())
            assertTrue(delegated)
        } finally { FileLog.enabled = true; FileLog.clear(); dir.deleteRecursively() }
    }

    @Test fun fingerprintGroupsSameStackWithDifferentMessagesAndDistinguishesDifferentCauses() {
        fun failure(message: String, method: String) = IllegalArgumentException(message).apply {
            stackTrace = arrayOf(StackTraceElement("Screen", method, "Screen.kt", 123))
        }
        assertEquals(ProcessDiagnostics.fingerprint(failure("one.example", "render")),
            ProcessDiagnostics.fingerprint(failure("two.example", "render")))
        assertNotEquals(ProcessDiagnostics.fingerprint(failure("one.example", "render")),
            ProcessDiagnostics.fingerprint(failure("one.example", "save")))
    }
}
