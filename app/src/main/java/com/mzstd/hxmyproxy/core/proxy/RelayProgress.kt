package com.mzstd.hxmyproxy.core.proxy

/** Diagnostics only. No timer, socket ownership, rate limiting, or connection decisions. */
internal class RelayProgressBudget(private val capacity: Int = 32, private val windowNs: Long = 60_000_000_000L) {
    private var started: Long? = null
    private var used = 0
    @Synchronized fun acquire(now: Long): Boolean {
        if (started == null || now - started!! >= windowNs) { started = now; used = 0 }
        if (used >= capacity) return false
        used++
        return true
    }
}

private val processProgressBudget = RelayProgressBudget()

internal data class RelayProgressSample(
    val windowMs: Long, val upDelta: Long, val downDelta: Long,
    val upTotal: Long, val downTotal: Long, val activity: RelayIdleSnapshot,
)

/** At most one sample per 30s per substantial flow, and 32 per minute across the process.
 * Small idle keep-alives produce no extra logs. Missing samples never imply no traffic. */
internal class RelayProgressMonitor(
    startedNs: Long = System.nanoTime(),
    private val budget: RelayProgressBudget = processProgressBudget,
    private val intervalNs: Long = 30_000_000_000L,
    private val minimumBytes: Long = 256 * 1024,
) {
    private var checkpointNs = startedNs
    private var up = 0L
    private var down = 0L

    fun poll(now: Long, sent: Long, received: Long, enabled: Boolean,
             activity: () -> RelayIdleSnapshot): RelayProgressSample? {
        if (now - checkpointNs < intervalNs) return null
        if (!enabled) {
            // Logging may be enabled mid-flow: do not label an earlier unobserved interval as current.
            checkpointNs = now; up = sent; down = received
            return null
        }
        if (sent + received < minimumBytes || !budget.acquire(now)) return null
        val sample = RelayProgressSample((now - checkpointNs) / 1_000_000,
            (sent - up).coerceAtLeast(0), (received - down).coerceAtLeast(0), sent, received, activity())
        checkpointNs = now; up = sent; down = received
        return sample
    }
}
