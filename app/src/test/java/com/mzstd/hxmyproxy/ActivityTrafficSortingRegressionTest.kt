package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.DomainTraffic
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.core.proxy.RequestActivityRecord
import com.mzstd.hxmyproxy.core.proxy.RequestOutcome
import com.mzstd.hxmyproxy.core.proxy.TrafficAccounting
import com.mzstd.hxmyproxy.ui.monitor.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class ActivityTrafficSortingRegressionTest {
    private fun record(host: String, id: Int = 1, protocol: String = "HTTP") =
        RequestActivityRecord("1:$id", id, protocol, host, 1, 2, "PROXY", "DEFAULT", RequestOutcome.FAILED,
            uploadBytes = 3, downloadBytes = 4)
    private val trafficOrder = ActivityQuery(sort = DomainSort.TRAFFIC)

    @Test fun mixedKnownUnknownAndZeroTrafficUsesOneNumericTypeAndKeepsBothProtocols() {
        val snapshot = ActivitySnapshot(domains = listOf(
            DomainTraffic("zero.example", ProxyProtocol.HTTP),
            DomainTraffic("large.example", ProxyProtocol.HTTP, downloadBytes = 3_000_000_000L),
        ), requests = listOf(record("unknown.example"), record("unknown.example", 2, "SOCKS5")))
        val rows = domainActivities(snapshot, trafficOrder)
        assertEquals(listOf("HTTP:large.example", "HTTP:zero.example", "HTTP:unknown.example", "SOCKS5:unknown.example"), rows.map { it.key })
        assertEquals(listOf(3_000_000_000L, 0L, null, null), rows.map { it.bytes })
        assertEquals(4L, rows.last().requests.single().downloadBytes)
    }

    @Test fun agedCountersCanDisappearWhileTheirRequestEvidenceRemains() {
        var now = 1L
        val accounting = TrafficAccounting(clock = { now })
        val client = InetAddress.getByName("192.0.2.5")
        accounting.openConnection(client, ProxyProtocol.HTTP).apply { bindHost("aged.example"); add(10, 20); close() }
        val requests = listOf(record("aged.example"))
        assertEquals(30L, domainActivities(ActivitySnapshot(domains = accounting.snapshot().topDomains, requests = requests), trafficOrder).single().bytes)
        now = 300_002
        accounting.openConnection(client, ProxyProtocol.HTTP).apply { bindHost("live.example"); add(1, 9) }
        accounting.ageOut(300_000)
        val rows = domainActivities(ActivitySnapshot(domains = accounting.snapshot().topDomains, requests = requests), trafficOrder)
        assertEquals(listOf("live.example", "aged.example"), rows.map { it.host })
        assertEquals(10L, rows.first().bytes)
        assertNull(rows.last().bytes)
        assertEquals(RequestOutcome.FAILED, rows.last().latest?.outcome)
    }

    @Test fun requestArrivingBeforeAccountingRefreshIsSafeAcrossSortsFiltersAndNextRefresh() {
        val known = DomainTraffic("known.example", ProxyProtocol.HTTP, downloadBytes = 50)
        val requests = listOf(record("new.example"), record("another.example", 2))
        val pending = ActivitySnapshot(domains = listOf(known), requests = requests)
        DomainSort.entries.forEach { sort -> ActivityFilter.entries.forEach { filter ->
            val rows = domainActivities(pending, ActivityQuery(sort = sort, filter = filter))
            assertEquals(rows.size, rows.map { it.key }.distinct().size)
        } }
        val first = domainActivities(pending, trafficOrder)
        assertEquals(listOf("known.example", "another.example", "new.example"), first.map { it.host })
        val refreshed = pending.copy(domains = listOf(known, DomainTraffic("new.example", ProxyProtocol.HTTP, downloadBytes = 80)))
        assertEquals(listOf("new.example", "known.example", "another.example"), domainActivities(refreshed, trafficOrder).map { it.host })
        assertEquals(2, domainActivities(pending.copy(domains = emptyList()), trafficOrder).size)
        assertTrue(domainActivities(ActivitySnapshot(), trafficOrder).isEmpty())
    }
}
