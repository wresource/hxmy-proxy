package com.mzstd.hxmyproxy.core.proxy

/** A connection is idle only when neither direction makes progress. The timeout claim
 * shares the progress lock, so a stale reader timeout cannot close a newly active flow. */
internal class RelayIdleClock(private val limitMs: Int) {
    private val lock = Any()
    private var last = System.nanoTime()
    private var expired = false
    private val activity = LongArray(4) { last }
    fun progress(direction: Int) = synchronized(lock) {
        if (!expired) { last = System.nanoTime(); activity[direction] = last }
    }
    fun remainingMs(): Long = synchronized(lock) {
        if (limitMs <= 0) Long.MAX_VALUE else
            (limitMs - (System.nanoTime() - last) / 1_000_000).coerceAtLeast(1)
    }
    fun claimTimeout(): Boolean = synchronized(lock) {
        if (limitMs <= 0 || System.nanoTime() - last < limitMs * 1_000_000L) false
        else { expired = true; true }
    }
    fun snapshot(): RelayIdleSnapshot = synchronized(lock) {
        val now = System.nanoTime()
        RelayIdleSnapshot(limitMs.toLong(), (now - last) / 1_000_000,
            activity.map { (now - it) / 1_000_000 })
    }
}

/** Positions: client read/write, upstream read/write; absent traffic ages from relay start. */
internal data class RelayIdleSnapshot(val limitMs: Long, val idleMs: Long, val directionAgesMs: List<Long>,
                                      val pendingUp: Int? = null, val pendingDown: Int? = null)
