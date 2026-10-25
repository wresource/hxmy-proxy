package com.mzstd.hxmyproxy.core.proxy

import java.io.Closeable
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicBoolean

/** Android SocketAdaptor's timed input and Channels output both hold blockingLock across I/O.
 * A waiting reader therefore prevents the reverse pump from writing on that same socket.
 * Use independent public channel readiness waits only for channel-backed fallback sockets.
 * Ordinary network.socketFactory sockets retain their native streams and timeout behavior.
 * Owns selectors, never the socket; the enclosing relay retains close/half-close ownership. */
internal class DuplexSocketStreams(socket: Socket, private val idleMillis: Int) : Closeable {
    private val channel: SocketChannel? = socket.channel
    private var reads: Selector? = null
    private var writes: Selector? = null
    private val closed = AtomicBoolean()
    val input: InputStream
    val output: OutputStream

    init {
        val ch = channel
        if (ch == null) {
            input = socket.getInputStream()
            output = socket.getOutputStream()
        } else {
            try {
                // Handshake has completed. No other owner may use SocketAdaptor streams now.
                ch.configureBlocking(false)
                reads = Selector.open()
                ch.register(reads, SelectionKey.OP_READ)
                writes = Selector.open()
                ch.register(writes, SelectionKey.OP_WRITE)
                input = object : InputStream() {
                    override fun read(): Int {
                        val byte = ByteArray(1)
                        return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
                    }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        val buffer = ByteBuffer.wrap(bytes, offset, length)
                        if (length == 0) return 0
                        val deadline = if (idleMillis > 0) System.nanoTime() + idleMillis * 1_000_000L else null
                        while (true) {
                            val n = ch.read(buffer)
                            if (n != 0) return n
                            awaitReady(reads!!, deadline)
                        }
                    }
                }
                output = object : OutputStream() {
                    override fun write(byte: Int) { write(byteArrayOf(byte.toByte())) }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        val buffer = ByteBuffer.wrap(bytes, offset, length)
                        while (buffer.hasRemaining()) {
                            if (ch.write(buffer) == 0) awaitReady(writes!!, null)
                        }
                    }
                }
            } catch (e: Throwable) {
                close()
                throw e
            }
        }
    }

    private fun awaitReady(selector: Selector, deadline: Long?) {
        while (true) {
            if (closed.get() || channel?.isOpen != true) throw ClosedChannelException()
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Relay wait interrupted")
            val remaining = deadline?.let { it - System.nanoTime() }
            if (remaining != null && remaining <= 0) throw SocketTimeoutException("Relay read idle")
            // Periodic open check also covers an external channel close without selector wakeup.
            val waitMs = remaining?.let { ((it + 999_999) / 1_000_000).coerceAtMost(250) } ?: 250L
            val ready = selector.select(waitMs)
            selector.selectedKeys().clear()
            if (ready > 0) return
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { reads?.close() }
        runCatching { writes?.close() }
    }
}
