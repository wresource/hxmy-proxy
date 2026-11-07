package com.mzstd.hxmyproxy.core.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import java.io.IOException
import java.io.OutputStream

/** Parsed-request containment, retaining its original trace and the response commit point.
 * Authentication/handshake stays outside. A second response must never enter an established
 * CONNECT/UDP/SOCKS carrier (nor a partially written ordinary HTTP response). */
internal suspend inline fun <T> requestBoundary(
    trace: RequestTrace, host: String, output: OutputStream,
    onFailure: (OutputStream?, Throwable) -> T,
    block: (OutputStream) -> T,
): T {
    val response = RequestOutput(output)
    val owner = currentCoroutineContext()[Job]
    try {
        return block(response)
    } catch (cancel: CancellationException) {
        trace.closeIfOpen(host, "cancelled")
        throw cancel
    } catch (error: Throwable) {
        if (error is IOException) {
            if (owner?.isCancelled == true) trace.closeIfOpen(host, "cancelled")
            else trace.failIfOpen(host, "request-io", error)
            throw error // Existing network/peer-close containment and classification remain.
        }
        if (error is ProxyException) trace.failed(host, error.stage ?: "request", error.error)
        else trace.internalError(host, error)
        return onFailure(response.takeIf { !it.started && owner?.isCancelled != true }, error)
    } finally {
        trace.closeIfOpen(host, "request-ended")
    }
}

internal class RequestOutput(private val delegate: OutputStream) : OutputStream() {
    var started = false
        private set
    override fun write(value: Int) { started = true; delegate.write(value) }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        // Mark before the write: an IOException can occur after a partial response.
        if (length > 0) started = true
        delegate.write(bytes, offset, length)
    }
    override fun write(bytes: ByteArray) = write(bytes, 0, bytes.size)
    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}
