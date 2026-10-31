package com.mzstd.hxmyproxy.core.proxy

import com.mzstd.hxmyproxy.core.util.concurrentSnapshot
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** One bounded-work selector for TCP capsules and actual unconnected UDP sockets.
 * UDP loss under pressure is counted; queues never grow without bound and packets are not replayed. */
internal class UdpCapsuleReactor : Closeable {
    private val selector = Selector.open()
    private val tasks = ConcurrentLinkedQueue<() -> Unit>()
    private val flows = ConcurrentHashMap.newKeySet<Flow>()
    private val pendingInput = ArrayDeque<Flow>() // selector-thread only
    private val queuedBytes = AtomicInteger()
    private val stopped = AtomicBoolean()
    @Volatile private var terminated = false
    private val receiveBuffer = ByteBuffer.allocate(65_536)
    private val thread = Thread(::loop, "hxmy-udp-relay").apply { isDaemon = true }
    init {
        try { thread.start() } catch (failure: Throwable) { selector.close(); throw failure }
    }
    val activeFlows: Int get() = flows.size
    val bufferedBytes: Int get() = queuedBytes.get()
    val isRunning: Boolean get() = !stopped.get() && !terminated && thread.isAlive

    suspend fun relay(
        client: SocketChannel,
        endpoint: DatagramEndpoint,
        lease: ConnectUdpSession.Lease,
        idleMs: Long,
        host: String,
        trace: RequestTrace?,
        policyValid: () -> Boolean,
        onTraffic: (Long, Long) -> Unit,
        onFirstReply: () -> Unit = {},
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        val flow = Flow(client, endpoint, lease, idleMs, host, trace, policyValid, onTraffic, onFirstReply, continuation)
        continuation.invokeOnCancellation { flow.close(lease.reason ?: "cancelled") }
        enqueue {
            if (stopped.get() || !continuation.isActive || !lease.isCurrent) flow.close(lease.reason ?: "stopped")
            else try { flow.register() } catch (failure: Throwable) { flow.close("register-error", "register", failure) }
        }
    }

    private fun enqueue(action: () -> Unit) {
        tasks.add(action); runCatching { selector.wakeup() }
        if (terminated || !thread.isAlive) drainTasks()
    }
    private fun drainTasks() {
        var action = tasks.poll()
        while (action != null) { runCatching { action() }; action = tasks.poll() }
    }
    private fun reserve(bytes: Int): Boolean {
        while (true) {
            val current = queuedBytes.get()
            if (bytes > GLOBAL_QUEUE_BYTES - current) return false
            if (queuedBytes.compareAndSet(current, current + bytes)) return true
        }
    }
    private fun release(bytes: Int) { queuedBytes.addAndGet(-bytes) }

    private fun loop() {
        var failure: Throwable? = null
        try {
            bumpToForegroundPriority()
            var lastSweep = System.nanoTime()
            while (!stopped.get()) {
                drainTasks()
                repeat(minOf(pendingInput.size, MAX_PENDING_TURNS)) {
                    pendingInput.removeFirst().processBuffered()
                }
                if (stopped.get()) break
                if (pendingInput.isEmpty()) selector.select(100) else selector.selectNow()
                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext() && !stopped.get()) {
                    val key = keys.next(); keys.remove()
                    if (!key.isValid) continue
                    val attachment = key.attachment() as Attachment
                    attachment.flow.selected(key, attachment.udp)
                }
                val now = System.nanoTime()
                if (now - lastSweep >= 100_000_000L) {
                    concurrentSnapshot(flows).forEach { it.sweep(now) }
                    lastSweep = now
                }
            }
        } catch (error: Throwable) {
            failure = error
            runCatching { com.mzstd.hxmyproxy.core.log.Ev.e(com.mzstd.hxmyproxy.core.log.LogCat.RELAY, "udp.worker.died", error) }
        }
        finally {
            stopped.set(true)
            concurrentSnapshot(flows).forEach { it.close(if (failure == null) "stopped" else "worker-error", "selector", failure) }
            runCatching { selector.close() }
            terminated = true
            drainTasks()
            pendingInput.clear()
        }
    }

    override fun close() {
        stopped.set(true)
        // Close I/O synchronously even if the selector continuation has not yet been scheduled.
        concurrentSnapshot(flows).forEach { it.close("stopped") }
        runCatching { selector.wakeup() }
        if (Thread.currentThread() !== thread) runCatching { thread.join(1000) }
    }

    private data class Attachment(val flow: Flow, val udp: Boolean)
    private inner class Flow(
        private val client: SocketChannel,
        private val endpoint: DatagramEndpoint,
        private val lease: ConnectUdpSession.Lease,
        private val idleMs: Long,
        private val host: String,
        private val trace: RequestTrace?,
        private val policyValid: () -> Boolean,
        private val onTraffic: (Long, Long) -> Unit,
        private val onFirstReply: () -> Unit,
        private val continuation: CancellableContinuation<Unit>,
    ) {
        private val lock = Any()
        private var closed = false
        private var tcpKey: SelectionKey? = null
        private var udpKey: SelectionKey? = null
        private val input = ByteBuffer.allocate(8192)
        private val decoder = UdpCapsuleDecoder()
        private val downstream = ArrayDeque<UdpCapsuleEncoder.Frame>()
        private val upstream = ArrayDeque<ByteArray>()
        private var downstreamBytes = 0
        private var upstreamBytes = 0
        private var bufferedScheduled = false
        private var lastActivity = System.nanoTime()
        private var up = 0L; private var down = 0L
        private var packetsUp = 0L; private var packetsDown = 0L; private var drops = 0L
        private var replied = false
        private var operation = "register"
        private var side: String? = null

        fun register() = synchronized(lock) {
            if (closed || stopped.get() || !lease.isCurrent || !continuation.isActive) { close(lease.reason ?: "stopped"); return@synchronized }
            client.configureBlocking(false)
            endpoint.channel.configureBlocking(false)
            tcpKey = client.register(selector, SelectionKey.OP_READ, Attachment(this, false))
            udpKey = endpoint.channel.register(selector, SelectionKey.OP_READ, Attachment(this, true))
            flows.add(this)
        }

        private fun valid(): Boolean {
            if (closed) return false
            if (stopped.get() || !lease.isCurrent || !continuation.isActive) { close(lease.reason ?: "cancelled"); return false }
            operation = "policy"; side = null
            if (!policyValid()) { close("rule-changed"); return false }
            return true
        }

        fun selected(key: SelectionKey, udp: Boolean) = synchronized(lock) {
            try {
                if (!valid()) return@synchronized
                if (udp) {
                    if (key.isReadable) receive()
                    if (!closed && key.isValid && key.isWritable) flushUpstream()
                } else {
                    if (key.isReadable) readCapsules()
                    if (!closed && key.isValid && key.isWritable) flushDownstream()
                }
                if (!closed) interests()
            } catch (failure: Throwable) { fail(failure) }
        }

        private fun fail(failure: Throwable) {
            when {
                !lease.isCurrent || !continuation.isActive || stopped.get() -> close(lease.reason ?: "cancelled")
                failure is CapsuleProtocolException -> close("protocol-error", operation, failure, side)
                operation == "accounting" || operation == "policy" -> close("relay-error", operation, failure)
                else -> close("io-error", operation, failure, side)
            }
        }

        private fun readCapsules() {
            operation = "read"; side = "client"
            if (bufferedScheduled) return
            val n = client.read(input)
            if (n < 0) {
                input.flip(); decoder.consume(input, emit = ::sendOrQueue, drop = { drops++ }); input.compact()
                decoder.finish(); close("eof"); return
            }
            if (n > 0) consumeBuffered()
        }

        private fun consumeBuffered() {
            operation = "capsule-decode"; side = "client"
            input.flip()
            decoder.consume(input, MAX_DATAGRAMS_PER_TURN, ::sendOrQueue) { drops++ }
            val remains = input.hasRemaining()
            input.compact()
            if (remains && !bufferedScheduled && !closed) { bufferedScheduled = true; pendingInput.addLast(this) }
        }

        fun processBuffered() = synchronized(lock) {
            bufferedScheduled = false
            try { if (valid()) { consumeBuffered(); if (!closed) interests() } }
            catch (failure: Throwable) { fail(failure) }
        }

        private fun sendOrQueue(payload: ByteArray) {
            if (!valid()) return
            lastActivity = System.nanoTime()
            if (upstream.isEmpty()) {
                operation = "write"; side = "upstream"
                when (endpoint.send(payload)) {
                    DatagramSendResult.SENT -> { sent(payload.size); return }
                    DatagramSendResult.TOO_LARGE -> { drops++; return }
                    DatagramSendResult.WOULD_BLOCK -> Unit
                }
            }
            if (upstream.size >= MAX_QUEUE_PACKETS || payload.size > FLOW_QUEUE_BYTES - upstreamBytes || !reserve(payload.size)) { drops++; return }
            try { upstream.addLast(payload); upstreamBytes += payload.size }
            catch (failure: Throwable) { release(payload.size); throw failure }
        }

        private fun sent(size: Int) {
            up += size; packetsUp++
            operation = "accounting"; side = null
            onTraffic(size.toLong(), 0)
        }

        private fun flushUpstream() {
            repeat(MAX_DATAGRAMS_PER_TURN) {
                if (upstream.isEmpty() || !valid()) return
                val payload = upstream.peekFirst() ?: return
                operation = "write"; side = "upstream"
                val result = endpoint.send(payload)
                if (result == DatagramSendResult.WOULD_BLOCK) return
                upstream.removeFirst(); upstreamBytes -= payload.size; release(payload.size)
                if (result == DatagramSendResult.SENT) sent(payload.size) else drops++
            }
        }

        private fun receive() {
            repeat(MAX_DATAGRAMS_PER_TURN) {
                if (!valid()) return
                receiveBuffer.clear(); operation = "read"; side = "upstream"
                val sender = endpoint.channel.receive(receiveBuffer) as? InetSocketAddress ?: return
                if (sender != endpoint.peer) { drops++; return@repeat }
                lastActivity = System.nanoTime()
                val size = receiveBuffer.position()
                if (size > ConnectUdpProtocol.MAX_PAYLOAD) { drops++; return@repeat }
                if (!replied) { replied = true; operation = "accounting"; side = null; onFirstReply() }
                receiveBuffer.flip()
                val payload = ByteArray(size); receiveBuffer.get(payload)
                val frame = UdpCapsuleEncoder.datagram(payload)
                val bytes = frame.bytes.capacity()
                if (downstream.size >= MAX_QUEUE_PACKETS || bytes > FLOW_QUEUE_BYTES - downstreamBytes || !reserve(bytes)) { drops++; return@repeat }
                try { downstream.addLast(frame); downstreamBytes += bytes }
                catch (failure: Throwable) { release(bytes); throw failure }
                flushDownstream()
            }
        }

        private fun flushDownstream() {
            var written = 0
            while (downstream.isNotEmpty() && written < MAX_TCP_BYTES_PER_TURN && valid()) {
                val frame = downstream.peekFirst() ?: return
                val before = frame.bytes.position()
                operation = "write"; side = "client"
                val n = client.write(frame.bytes)
                if (n == 0) return
                written += n; lastActivity = System.nanoTime()
                val payloadBytes = (frame.bytes.position() - frame.payloadOffset).coerceAtLeast(0) - (before - frame.payloadOffset).coerceAtLeast(0)
                if (payloadBytes > 0) {
                    down += payloadBytes
                    operation = "accounting"; side = null
                    onTraffic(0, payloadBytes.toLong())
                }
                if (!frame.bytes.hasRemaining()) {
                    downstream.removeFirst(); downstreamBytes -= frame.bytes.capacity(); release(frame.bytes.capacity()); packetsDown++
                }
            }
        }

        private fun interests() {
            tcpKey?.takeIf { it.isValid }?.interestOps((if (bufferedScheduled) 0 else SelectionKey.OP_READ) or (if (downstream.isEmpty()) 0 else SelectionKey.OP_WRITE))
            udpKey?.takeIf { it.isValid }?.interestOps(SelectionKey.OP_READ or (if (upstream.isEmpty()) 0 else SelectionKey.OP_WRITE))
        }

        fun sweep(now: Long) = synchronized(lock) {
            try {
                if (!valid()) return@synchronized
                if (!client.isOpen || !endpoint.channel.isOpen) close("peer-closed")
                else if (idleMs > 0 && now - lastActivity >= idleMs * 1_000_000L) close("idle")
            } catch (failure: Throwable) { fail(failure) }
        }

        /** Same lock as nonblocking I/O+accounting: stop cannot reset stats before a prior send finishes. */
        fun close(reason: String, op: String? = null, error: Throwable? = null, errorSide: String? = null) = synchronized(lock) {
            if (closed) return@synchronized
            closed = true
            runCatching { tcpKey?.cancel() }; runCatching { udpKey?.cancel() }
            runCatching { client.close() }; runCatching { endpoint.close() }
            downstream.forEach { release(it.bytes.capacity()) }; downstream.clear(); downstreamBytes = 0
            upstream.forEach { release(it.size) }; upstream.clear(); upstreamBytes = 0
            try { runCatching { trace?.udpClosed(host, reason, op, errorSide, error, up, down, packetsUp, packetsDown, drops) } }
            finally { flows.remove(this); if (continuation.isActive) continuation.resume(Unit); runCatching { selector.wakeup() } }
        }
    }

    companion object {
        private const val MAX_DATAGRAMS_PER_TURN = 16
        private const val MAX_PENDING_TURNS = 64
        private const val MAX_TCP_BYTES_PER_TURN = 64 * 1024
        private const val MAX_QUEUE_PACKETS = 4
        private const val FLOW_QUEUE_BYTES = 256 * 1024
        private const val GLOBAL_QUEUE_BYTES = 8 * 1024 * 1024
    }
}
