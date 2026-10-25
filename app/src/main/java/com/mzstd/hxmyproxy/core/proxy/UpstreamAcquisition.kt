package com.mzstd.hxmyproxy.core.proxy

import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable

/** One monotonic budget across NIO binding fallback and DNS recovery; not relay lifetime. */
internal class ConnectDeadline(timeoutMs: Long = ProxyTuning.CONNECT_PHASE_TIMEOUT_MS) {
    private val endNs = System.nanoTime() + timeoutMs * 1_000_000L
    fun remainingMs(): Long = ((endNs - System.nanoTime()) / 1_000_000L).coerceAtLeast(0)
}

/** Close a socket whose value is discarded by timeout/cancellation while leaving the timeout scope. */
internal suspend fun <T : Closeable> acquireUpstream(timeoutMs: Long, connect: suspend () -> T): T? {
    var acquired: T? = null
    try {
        val result = withTimeoutOrNull(timeoutMs) { connect().also { acquired = it } }
        if (result != null) acquired = null // caller now owns it and immediately enters use/finally
        return result
    } finally {
        runCatching { acquired?.close() }
    }
}
