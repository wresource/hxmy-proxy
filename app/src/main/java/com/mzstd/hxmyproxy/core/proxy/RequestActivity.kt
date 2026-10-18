package com.mzstd.hxmyproxy.core.proxy

/** Bounded, volatile UI evidence. Existing durable RequestTrace logs remain authoritative. */
enum class RequestOutcome { PENDING, CONNECTED, RELAYED, BLOCKED, FAILED, CLOSED }
data class RequestActivitySnapshot(val generation: Long, val enabled: Boolean, val records: List<RequestActivityRecord>)

data class RequestActivityRecord(
    val key: String,
    val requestId: Int,
    val protocol: String,
    val host: String,
    val startedAt: Long,
    val updatedAt: Long,
    val action: String?,
    val source: String?,
    val outcome: RequestOutcome,
    val stage: String? = null,
    val error: String? = null,
    val errorCode: String? = null,
    val peer: String? = null,
    val dnsSource: String? = null,
    val uploadBytes: Long? = null,
    val downloadBytes: Long? = null,
)

class RequestActivityStore(private val capacity: Int = 256, private val clock: () -> Long = System::currentTimeMillis) {
    init { require(capacity > 0) }
    data class Token internal constructor(val generation: Long, val requestId: Int, val protocol: String, val startedAt: Long) {
        val key: String get() = "$generation:$requestId"
    }
    private val lock = Any()
    private var generation = 0L
    private var enabled = false
    private val rows = LinkedHashMap<String, RequestActivityRecord>()

    fun setEnabled(value: Boolean) = synchronized(lock) {
        if (enabled != value) { generation++; rows.clear(); enabled = value }
    }
    fun reset() = synchronized(lock) { generation++; rows.clear() }
    fun open(id: Int, protocol: String): Token? = synchronized(lock) {
        if (enabled) Token(generation, id, protocol, clock()) else null
    }
    fun rule(token: Token?, host: String, action: String?, source: String?) = synchronized(lock) {
        if (token == null || !enabled || token.generation != generation || host.isBlank() || host == "?" || host.length > 253) return@synchronized
        rows[token.key] = RequestActivityRecord(token.key, token.requestId, token.protocol, host.lowercase(), token.startedAt,
            clock(), action, source, if (action == "REJECT") RequestOutcome.BLOCKED else RequestOutcome.PENDING)
        while (rows.size > capacity) rows.remove(rows.keys.first())
    }
    private fun change(token: Token?, transform: (RequestActivityRecord) -> RequestActivityRecord) = synchronized(lock) {
        if (token == null || !enabled || token.generation != generation) return@synchronized
        val current = rows[token.key] ?: return@synchronized // Never resurrect an evicted request with incomplete evidence.
        rows[token.key] = transform(current).copy(updatedAt = clock())
    }
    fun dns(token: Token?, source: String) = change(token) { it.copy(dnsSource = source.take(40)) }
    fun connected(token: Token?, peer: String?) = change(token) {
        if (it.outcome in terminalErrors) it else it.copy(outcome = RequestOutcome.CONNECTED, peer = peer?.take(80))
    }
    fun failed(token: Token?, stage: String, error: String?, code: String? = null) = change(token) {
        it.copy(outcome = if (it.outcome == RequestOutcome.BLOCKED) RequestOutcome.BLOCKED else RequestOutcome.FAILED,
            stage = stage.take(80), error = error?.take(240), errorCode = code?.take(40))
    }
    fun closed(token: Token?, reason: String, up: Long, down: Long) = change(token) {
        it.copy(outcome = when {
            it.outcome in terminalErrors -> it.outcome
            up > 0 || down > 0 -> RequestOutcome.RELAYED
            else -> RequestOutcome.CLOSED
        }, stage = it.stage ?: reason.take(80), uploadBytes = up.coerceAtLeast(0), downloadBytes = down.coerceAtLeast(0))
    }
    fun snapshot(): List<RequestActivityRecord> = synchronized(lock) {
        rows.values.sortedWith(compareByDescending<RequestActivityRecord> { it.updatedAt }.thenByDescending { it.requestId })
    }
    fun snapshotState(): RequestActivitySnapshot = synchronized(lock) {
        RequestActivitySnapshot(generation, enabled, snapshot())
    }
    private companion object { val terminalErrors = setOf(RequestOutcome.FAILED, RequestOutcome.BLOCKED) }
}

object RequestActivity {
    val store = RequestActivityStore()
}
