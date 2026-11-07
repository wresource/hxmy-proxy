package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.log.FileLog
import com.mzstd.hxmyproxy.core.proxy.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files

class RequestBoundaryTest {
    private val logs = Files.createTempDirectory("request-boundary").toFile()
    @Before fun setup() { RequestActivity.store.setEnabled(true); RequestActivity.store.reset(); FileLog.enabled = true; FileLog.init(logs) }
    @After fun cleanup() { RequestActivity.store.setEnabled(false); FileLog.clear(); FileLog.enabled = true; logs.deleteRecursively() }
    private fun trace() = RequestTrace.open("HTTP", 123).also { it.rule("test.example", "PROXY", "DEFAULT") }
    private fun fullLog() = FileLog.snapshot().substringAfter("===== FULL LOG =====")

    @Test fun exceptionsAfterAnyResponseBytesNeverWriteAnotherResponse() = runBlocking {
        for (prefix in listOf("HTTP/1.1 200 Connection established\r\n\r\n", "HTTP/1.1 101 Switching Protocols\r\n\r\n", "\u0005\u0000", "HTTP/1.1")) {
            RequestActivity.store.reset()
            val output = ByteArrayOutputStream(); val t = trace()
            requestBoundary(t, "test.example", output, onFailure = { writable, _ ->
                assertNull("already committed: $prefix", writable); writable?.write("ERROR".toByteArray())
            }) { writable ->
                writable.write(prefix.toByteArray()); throw NullPointerException("after commit")
            }
            assertEquals(prefix, output.toString())
            assertEquals(RequestOutcome.FAILED, RequestActivity.store.snapshot().single().outcome)
        }
    }

    @Test fun cancellationClosesPendingWithoutAnInternalErrorOrErrorResponse() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val t = trace(); var responded = false
        val job = launch {
            requestBoundary(t, "test.example", ByteArrayOutputStream(), onFailure = { _, _ -> responded = true }) {
                entered.complete(Unit); awaitCancellation()
            }
        }
        entered.await(); job.cancelAndJoin()
        assertFalse(responded); assertEquals(RequestOutcome.CLOSED, RequestActivity.store.snapshot().single().outcome)
        assertEquals("cancelled", RequestActivity.store.snapshot().single().stage)
        assertFalse(fullLog().contains(" E/"))
    }

    @Test fun ordinaryIoFailureRemainsWarningAndNeverBecomesInternalError() = runBlocking {
        val t = trace()
        val error = runCatching {
            requestBoundary(t, "test.example", ByteArrayOutputStream(), onFailure = { _, _ -> fail("unexpected response") }) {
                throw IOException("peer disconnected")
            }
        }.exceptionOrNull()
        assertTrue(error is IOException); assertEquals(RequestOutcome.FAILED, RequestActivity.store.snapshot().single().outcome)
        assertFalse(fullLog().contains(" E/")); assertTrue(fullLog().contains(" W/EGRESS: evt=req.failed"))
    }

    @Test fun loggingDisabledStillCompletesActivityAndOldGenerationIsNotResurrected() = runBlocking {
        FileLog.enabled = false
        val t = trace()
        requestBoundary(t, "test.example", ByteArrayOutputStream(), onFailure = { _, _ -> Unit }) { throw IllegalStateException() }
        assertEquals(RequestOutcome.FAILED, RequestActivity.store.snapshot().single().outcome)
        RequestActivity.store.reset(); t.internalError("test.example", IllegalStateException("late"))
        assertTrue(RequestActivity.store.snapshot().isEmpty()); assertFalse(fullLog().contains("evt=req.failed"))
    }

    @Test fun firstFailureAndRealByteTotalsSurviveCleanupError() {
        val t = trace(); t.failed("test.example", "connect-strict", ProxyError.RemoteTimeout)
        t.internalError("test.example", IllegalStateException("cleanup")); t.tunnelClosed("test.example", "done", 12, 34)
        val row = RequestActivity.store.snapshot().single()
        assertEquals("connect-strict", row.stage); assertEquals("timeout", row.errorCode)
        assertEquals(12L, row.uploadBytes); assertEquals(34L, row.downloadBytes)
        assertEquals(1, fullLog().lines().count { "evt=req.failed " in it })
        assertTrue(fullLog().contains(" E/EGRESS: evt=req.internalError"))
    }

    @Test fun failedResponseWriteDoesNotCauseASecondAttempt() = runBlocking {
        var writes = 0
        val output = object : OutputStream() { override fun write(value: Int) { writes++; throw IOException("partial write") } }
        val t = trace()
        val failure = runCatching {
            requestBoundary(t, "test.example", output, onFailure = { _, _ -> fail("must not retry output") }) {
                it.write(5)
            }
        }.exceptionOrNull()
        assertTrue(failure is IOException); assertEquals(1, writes)
        assertEquals(RequestOutcome.FAILED, RequestActivity.store.snapshot().single().outcome)
    }
}
