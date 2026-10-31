package com.mzstd.hxmyproxy.core.proxy

import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Sharing/listener lifetime, not a global process singleton. Pending setup counts toward quota. */
class ConnectUdpSession(private val maxFlows: Int = MAX_FLOWS, enableSocks: Boolean = false) : Closeable {
    val id: String = UUID.randomUUID().toString()
    internal val reactor = UdpCapsuleReactor()
    internal val socksReactor: SocksUdpReactor? = try {
        if (enableSocks) SocksUdpReactor() else null
    } catch (failure: Throwable) { reactor.close(); throw failure }
    private val lock = Any()
    @Volatile private var stopped = false
    @Volatile private var epoch = 0L
    private val leases = LinkedHashSet<Lease>()
    val isActive: Boolean get() = !stopped && reactor.isRunning
    internal val isStopped: Boolean get() = stopped
    val activeLeases: Int get() = synchronized(lock) { leases.size }
    /** Capture before authentication: an older handshake cannot enter a later policy epoch. */
    internal val currentEpoch: Long get() = synchronized(lock) { epoch }

    /** Serialize a small, non-blocking policy read with changePolicy; never write sockets here. */
    internal fun <T : Any> policySnapshot(expectedEpoch: Long?, read: (Long) -> T): T? = synchronized(lock) {
        if (!isActive || (expectedEpoch != null && epoch != expectedEpoch)) null else read(epoch)
    }

    /** Publish mutable auth/egress settings and the new epoch together. Capturing an epoch
     * must not see the new generation while authentication still contains the old values.
     * Revocation callbacks run outside the lock to preserve the reactor/session lock order. */
    fun changePolicy(reason: String, change: () -> Unit) {
        var failure: Throwable? = null
        val victims = synchronized(lock) {
            try { change() } catch (error: Throwable) { failure = error }
            if (stopped) emptyList() else { epoch++; leases.toList() }
        }
        victims.forEach { it.invalidate(reason) }
        failure?.let { throw it }
    }

    fun acquire(expectedEpoch: Long? = null, onInvalidated: (String) -> Unit): Lease? = synchronized(lock) {
        if (!isActive || (expectedEpoch != null && epoch != expectedEpoch) || leases.size >= maxFlows) return@synchronized null
        Lease(epoch, onInvalidated).also { leases.add(it) }
    }

    /** Invalidating never releases capacity before actual setup/relay finally closes its lease. */
    fun invalidate(reason: String) = invalidate(reason, false)
    private fun invalidate(reason: String, stop: Boolean) {
        val victims = synchronized(lock) {
            if (stopped) return
            if (stop) stopped = true
            epoch++
            leases.toList()
        }
        victims.forEach { it.invalidate(reason) }
    }

    override fun close() {
        invalidate("stopped", true)
        socksReactor?.close()
        reactor.close()
    }

    inner class Lease internal constructor(private val acquiredEpoch: Long, private val onInvalidated: (String) -> Unit) : Closeable {
        private val closed = AtomicBoolean()
        private val invalidated = AtomicBoolean()
        @Volatile var reason: String? = null
            private set
        val isCurrent: Boolean get() = !closed.get() && isActive && epoch == acquiredEpoch
        internal fun invalidate(reason: String) {
            if (!invalidated.compareAndSet(false, true)) return
            this.reason = reason
            runCatching { onInvalidated(reason) }
        }
        override fun close() {
            if (closed.compareAndSet(false, true)) synchronized(lock) { leases.remove(this) }
        }
    }

    companion object {
        const val MAX_FLOWS = 64
        // Each UDP endpoint owns one duplicate fd in addition to normal client+upstream.
        // A shared selector needs a few descriptors too. Reserve these before applying TCP caps.
        const val EXTRA_FD_RESERVE = MAX_FLOWS + 8
    }
}
