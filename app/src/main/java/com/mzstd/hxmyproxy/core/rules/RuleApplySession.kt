package com.mzstd.hxmyproxy.core.rules

import com.mzstd.hxmyproxy.core.model.ProxySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * One serial worker per sharing session. The supplied gate is the service lifecycle lock:
 * publishing a snapshot and accepting/stopping a session cannot cross each other.
 * Blocking builds may finish after cancellation; their commit and acknowledgement are fenced.
 */
internal class RuleApplySession(
    private val scope: CoroutineScope,
    private val gate: Any,
    private val validSession: () -> Boolean,
    initialSettings: ProxySettings,
    initial: RuleBuildResult.Ready,
    private val build: (ProxySettings, () -> Boolean, ((() -> Unit) -> Boolean)) -> RuleBuildResult,
    private val onState: (RuleApplicationState) -> Unit,
    private val onUnsafeFailure: () -> Unit,
    private val retryDelaysMs: List<Long> = listOf(1_000, 3_000),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class Request(val id: Long, val settings: ProxySettings, val inputs: RuleInputs)
    private val inputLock = Any()
    private val requests = Channel<Request>(Channel.CONFLATED)
    @Volatile private var closed = false
    @Volatile private var latest = Request(ids.incrementAndGet(), initialSettings, RuleInputs.of(initialSettings))
    private var status = RuleApplicationState(RuleApplyPhase.APPLIED, latest.id, initial.version, initial.counts)

    init {
        synchronized(gate) { if (active()) onState(status) }
        scope.launch(dispatcher) { work() }
    }

    /** Caller is an IO collector. A theme/name/status-only edit must neither reload nor reset retries. */
    fun submit(settings: ProxySettings) = synchronized(inputLock) {
        val inputs = RuleInputs.of(settings)
        val unchanged = try { inputs == latest.inputs }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { false } // Let the builder report a collection read failure.
        if (unchanged) return@synchronized
        synchronized(gate) {
            if (active()) enqueue(Request(ids.incrementAndGet(), settings, inputs))
        }
    }

    /** No settings captured by the UI: retry exactly the latest accepted request. */
    fun retry() = synchronized(gate) {
        if (active() && status.phase == RuleApplyPhase.FAILED) enqueue(latest.copy(id = ids.incrementAndGet()))
    }

    fun close() = synchronized(gate) { closed = true; requests.close() }
    private fun active() = !closed && scope.isActive && validSession()
    private fun current(r: Request) = active() && latest === r
    private fun emit(value: RuleApplicationState) { status = value; onState(value) }
    private fun enqueue(r: Request) {
        latest = r
        emit(status.copy(phase = RuleApplyPhase.APPLYING, requestId = r.id, failure = null,
            failureDetail = null, attempt = 0, retryScheduled = false, stoppedForFailure = false))
        requests.trySend(r)
    }

    private suspend fun work() {
        var request = requests.receiveCatching().getOrNull() ?: return
        var attempt = 0
        while (scope.isActive && !closed) {
            val result = build(request.settings, { current(request) }) { commit ->
                synchronized(gate) {
                    if (!current(request)) false else { commit(); true }
                }
            }
            var retryDelay: Long? = null
            synchronized(gate) {
                if (current(request)) when (result) {
                    is RuleBuildResult.Ready -> emit(RuleApplicationState(RuleApplyPhase.APPLIED,
                        request.id, result.version, result.counts))
                    is RuleBuildResult.Failed -> {
                        // PROXY-only overrides do not provide old DIRECT/REJECT protection either.
                        val mustStop = result.previousCounts.protection == 0 && result.requestedProtection
                        retryDelay = if (!mustStop && result.retryable) retryDelaysMs.getOrNull(attempt) else null
                        emit(RuleApplicationState(RuleApplyPhase.FAILED, request.id, result.previousVersion,
                            result.previousCounts, result.reason, result.detail, attempt,
                            retryScheduled = retryDelay != null, stoppedForFailure = mustStop))
                        if (mustStop) onUnsafeFailure()
                    }
                    RuleBuildResult.Superseded -> Unit
                }
            }
            if (!active()) return
            val next = if (retryDelay != null) {
                withTimeoutOrNull(retryDelay!!) { requests.receiveCatching().getOrNull() }
            } else requests.receiveCatching().getOrNull()
            if (next != null) { request = next; attempt = 0 }
            else if (retryDelay != null && current(request)) {
                attempt++
                synchronized(gate) {
                    if (current(request)) emit(status.copy(attempt = attempt, retryScheduled = false))
                }
            } else return
        }
    }

    private companion object { val ids = AtomicLong() }
}
