package com.mzstd.hxmyproxy.core.proxy

import java.util.concurrent.atomic.AtomicReference

/** Observations do not control forwarding. In particular, directional EOF is NOT termination.
 * Cancellation may arrive off the selector thread; first actual terminal cause wins atomically. */
internal class RelayCloseEvidence {
    data class Cause(val reason: String, val operation: String? = null, val side: String? = null,
        val errorType: String? = null, val errno: Int? = null) {
        val failed: Boolean get() = errorType != null
    }
    private val terminal = AtomicReference<Cause?>()
    private val eof = AtomicReference<String?>()
    private val cleanupError = AtomicReference<String?>()
    fun observedEof(side: String) { eof.compareAndSet(null, side) }
    fun terminate(reason: String, operation: String? = null, side: String? = null, error: Throwable? = null) {
        var current = error
        var errno: Int? = null
        repeat(4) { // bounded cause traversal, never retain exception or its stack/payload
            if (current is android.system.ErrnoException) errno = (current as android.system.ErrnoException).errno
            current = current?.cause
        }
        terminal.compareAndSet(null, Cause(reason, operation, side, error?.javaClass?.simpleName?.take(80), errno))
    }
    fun cleanupFailure(operation: String, side: String, error: Throwable) {
        cleanupError.compareAndSet(null, "$operation:$side:${error.javaClass.simpleName.take(80)}")
    }
    val firstEof: String? get() = eof.get()
    val cleanup: String? get() = cleanupError.get()
    fun cause(): Cause = terminal.get() ?: Cause("closed")
}
