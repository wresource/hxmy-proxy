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

class ActivityPresentationTest {
    @Test fun realAccountingKeepsProtocolsAndOverflowIdentitiesSeparate() {
        val a = TrafficAccounting(maxDomains = 2)
        val ip = InetAddress.getByName("192.0.2.1")
        listOf(ProxyProtocol.HTTP, ProxyProtocol.SOCKS5).forEach { p ->
            a.openConnection(ip, p).apply { bindHost("same.example"); add(10, 20) }
        }
        listOf(ProxyProtocol.HTTP, ProxyProtocol.SOCKS5).forEach { p -> a.openConnection(ip, p).apply { bindHost("overflow.example"); add(5, 6) } }
        val rows = domainActivities(ActivitySnapshot(domains = a.snapshot().topDomains))
        assertEquals(4, rows.size); assertEquals(4, rows.map { it.key }.distinct().size)
        assertEquals(2, rows.count { it.aggregate })
        assertEquals(82L, rows.sumOf { it.bytes ?: 0 })
    }

    @Test fun missingTrafficDoesNotInventCumulativeBytesFromOneConnection() {
        val r = RequestActivityRecord("1:1", 1, "HTTP", "expired.example", 1, 2, "PROXY", "DEFAULT", RequestOutcome.RELAYED,
            uploadBytes = 10, downloadBytes = 20)
        val row = domainActivities(ActivitySnapshot(requests = listOf(r))).single()
        assertNull(row.bytes); assertEquals(20L, row.requests.single().downloadBytes)
    }

    @Test fun latestFailureAndCumulativeTrafficHaveDifferentMeaning() {
        val d = DomainTraffic("apple.com", ProxyProtocol.HTTP, 20, 900, 0, 5)
        val r = RequestActivityRecord("1:1", 1, "HTTP", "apple.com", 6, 7, "DIRECT", "USER_ALLOW", RequestOutcome.FAILED)
        val snapshot = ActivitySnapshot(domains = listOf(d), requests = listOf(r))
        val row = domainActivities(snapshot, ActivityQuery(filter = ActivityFilter.FAILED)).single()
        assertEquals(920L, row.bytes); assertEquals(RequestOutcome.FAILED, row.latest?.outcome)
        assertTrue(domainActivities(snapshot, ActivityQuery(filter = ActivityFilter.ACTIVE)).isEmpty())
    }

    @Test fun filteringAndSortingRetainTheCompleteSourceList() {
        val ds = (0..11).map { DomainTraffic("$it.example.org", ProxyProtocol.HTTP, 0, it.toLong() * 1000, if (it == 3) 1 else 0, it.toLong()) }
        val snapshot = ActivitySnapshot(domains = ds)
        val rows = domainActivities(snapshot)
        assertEquals("3.example.org", rows.first().host)
        assertEquals(12, rows.size)
        assertEquals("11.example.org", domainActivities(snapshot, ActivityQuery(sort = DomainSort.TRAFFIC)).first().host)
        assertEquals(listOf("11.example.org"), domainActivities(snapshot, ActivityQuery(text = "https://11.example.org/path?q=private")).map { it.host })
    }

    @Test fun currentRulesCannotFabricatePastBlockEvents() {
        val d = DomainTraffic("unknown.example", ProxyProtocol.HTTP)
        val snapshot = ActivitySnapshot(domains = listOf(d))
        assertTrue(domainActivities(snapshot, ActivityQuery(filter = ActivityFilter.BLOCKED)).isEmpty())
        assertEquals(1, domainActivities(snapshot).size)
    }
    @Test fun oldTunnelClosingCannotHideANewerFailedRequest() {
        val old = RequestActivityRecord("1:1", 1, "HTTP", "example.org", 10, 100, "PROXY", "DEFAULT", RequestOutcome.RELAYED)
        val latest = RequestActivityRecord("1:2", 2, "HTTP", "example.org", 80, 90, "PROXY", "DEFAULT", RequestOutcome.FAILED)
        val rows = domainActivities(ActivitySnapshot(requests = listOf(old, latest)), ActivityQuery(filter = ActivityFilter.FAILED))
        assertEquals(1, rows.size); assertEquals(2, rows.single().latest?.requestId); assertEquals(80L, rows.single().lastSeen)
    }
}
