package com.mzstd.hxmyproxy.ui.monitor

import com.mzstd.hxmyproxy.core.model.ClientSession
import com.mzstd.hxmyproxy.core.model.DomainTraffic
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.core.proxy.RequestActivityRecord
import com.mzstd.hxmyproxy.core.proxy.RequestOutcome
import com.mzstd.hxmyproxy.core.proxy.TrafficAccounting
import com.mzstd.hxmyproxy.core.rules.RuleInput

const val DOMAIN_PREVIEW_LIMIT = 5
const val CLIENT_PREVIEW_LIMIT = 4
enum class DomainSort { ACTIVE, RECENT, TRAFFIC }
enum class ActivityFilter { ALL, ACTIVE, BLOCKED, FAILED, RELAYED }
data class ActivityQuery(val text: String = "", val sort: DomainSort = DomainSort.ACTIVE, val filter: ActivityFilter = ActivityFilter.ALL)

data class ActivitySnapshot(
    val session: Long = 0,
    val evidenceGeneration: Long = 0,
    val clients: List<ClientSession> = emptyList(),
    val domains: List<DomainTraffic> = emptyList(),
    val requests: List<RequestActivityRecord> = emptyList(),
    val totalBytes: Long = 0,
    val downRate: Long = 0,
    val upRate: Long = 0,
    val capturedAt: Long = 0,
    val paused: Boolean = false,
)

data class DomainActivity(
    val host: String,
    val protocol: ProxyProtocol,
    val traffic: DomainTraffic?,
    val requests: List<RequestActivityRecord>,
) {
    val key: String get() = "${protocol.name}:$host"
    val aggregate: Boolean get() = host == TrafficAccounting.OTHERS
    val bytes: Long? get() = traffic?.let { it.uploadBytes + it.downloadBytes }
    val active: Long get() = traffic?.connections ?: 0
    val latest: RequestActivityRecord? get() = requests.firstOrNull()
    val lastSeen: Long get() = maxOf(traffic?.lastSeenAtEpochMs ?: 0, latest?.startedAt ?: 0)
}

/** Join only host×protocol. No device attribution and no invented cumulative bytes for aged-out hosts. */
fun domainActivities(snapshot: ActivitySnapshot, query: ActivityQuery = ActivityQuery()): List<DomainActivity> {
    val traffic = snapshot.domains.associateBy { "${it.protocol.name}:${it.host}" }
    val records = snapshot.requests.filter { it.protocol == "HTTP" || it.protocol == "SOCKS5" }
        .groupBy { "${it.protocol}:${it.host}" }
    val needle = RuleInput.parse(query.text)?.host ?: query.text.trim().lowercase()
    val result = (traffic.keys + records.keys).map { key ->
        val d = traffic[key]
        // An older tunnel closing late must not hide a newer failed request.
        val r = records[key].orEmpty().sortedWith(compareByDescending<RequestActivityRecord> { it.startedAt }.thenByDescending { it.requestId })
        DomainActivity(d?.host ?: r.first().host, d?.protocol ?: ProxyProtocol.valueOf(r.first().protocol), d, r)
    }.filter { row ->
        (needle.isEmpty() || row.host.lowercase().contains(needle)) && when (query.filter) {
            ActivityFilter.ALL -> true
            ActivityFilter.ACTIVE -> row.active > 0
            ActivityFilter.BLOCKED -> row.latest?.outcome == RequestOutcome.BLOCKED
            ActivityFilter.FAILED -> row.latest?.outcome == RequestOutcome.FAILED
            ActivityFilter.RELAYED -> row.latest?.outcome == RequestOutcome.RELAYED
        }
    }
    val order = when (query.sort) {
        DomainSort.ACTIVE -> compareByDescending<DomainActivity> { it.active > 0 }.thenByDescending { it.lastSeen }
        DomainSort.RECENT -> compareByDescending<DomainActivity> { it.lastSeen }
        // Keep the key Long? throughout. A generic Comparable selector boxes `?: -1` as
        // Integer, which crashes against a real Long. Descending nullable ordering puts
        // unknown counters last without inventing zero traffic or dropping request evidence.
        DomainSort.TRAFFIC -> compareByDescending<DomainActivity> { it.bytes }
    }.thenBy { it.key }
    return result.sortedWith(order)
}
