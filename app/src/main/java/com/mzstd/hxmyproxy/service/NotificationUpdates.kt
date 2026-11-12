package com.mzstd.hxmyproxy.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Display updates only. Callers and [scope] use the same serial dispatcher (Main in the service).
 * Lifecycle decisions never wait here. One trailing update keeps the latest value under a
 * continuous burst, without a debounce that could postpone a busy connection indefinitely.
 */
internal class NotificationUpdates<T : Any>(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val publish: (T) -> Unit,
    private val intervalMillis: Long = 1_000,
) {
    private var active = false
    private var generation = 0L
    private var displayed: T? = null
    private var publishedAt = 0L
    private var latest: T? = null
    private var pending: Job? = null

    /** startForeground has already synchronously published this value. */
    fun foregroundPublished(value: T) {
        stop()
        active = true
        displayed = value
        publishedAt = nowMillis()
    }

    fun offer(value: T, important: Boolean = false) {
        if (!active) return
        if (value == displayed) {
            discardPending()
            return
        }
        latest = value
        val wait = (intervalMillis - (nowMillis() - publishedAt)).coerceIn(0, intervalMillis)
        if (important || wait == 0L) {
            publishNow(value)
        } else if (pending == null) {
            val owner = generation
            pending = scope.launch(start = CoroutineStart.LAZY) {
                delay(wait)
                if (!active || generation != owner) return@launch
                pending = null
                latest?.takeIf { it != displayed }?.let(::publishNow)
            }.also { it.start() }
        }
    }

    fun stop() {
        active = false
        generation++
        discardPending()
        displayed = null
    }

    private fun discardPending() {
        pending?.cancel()
        pending = null
        latest = null
    }

    private fun publishNow(value: T) {
        discardPending()
        displayed = value
        publishedAt = nowMillis()
        publish(value)
    }
}
