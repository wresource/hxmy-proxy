package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.DomainTraffic
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.core.proxy.RequestActivityRecord
import com.mzstd.hxmyproxy.core.proxy.RequestActivityStore
import com.mzstd.hxmyproxy.core.proxy.RequestOutcome
import com.mzstd.hxmyproxy.core.proxy.TrafficAccounting
import com.mzstd.hxmyproxy.ui.monitor.*
import org.junit.Assert.*
import org.junit.Test

class ActivityFilteringTest {
    private fun request(id: Int, action: String?, outcome: RequestOutcome, host: String = "mixed.example",
        protocol: String = "HTTP") = RequestActivityRecord("1:$id", id, protocol, host, id.toLong(), id.toLong(),
        action, "TEST", outcome)
    private fun select(s: ActivitySnapshot, state: ActivityFilter = ActivityFilter.ALL,
        route: ActivityRoute = ActivityRoute.ALL) = domainActivities(s, ActivityQuery(filter = state, route = route))

    @Test fun everyRecordedOutcomeHasAStatusAndUnknownIsNotAnInventedSuccess() {
        val records = listOf(
            request(1, "DIRECT", RequestOutcome.PENDING, "pending.example"),
            request(2, "PROXY", RequestOutcome.CONNECTED, "connected.example"),
            request(3, "DIRECT", RequestOutcome.RELAYED, "transferred.example"),
            request(4, "PROXY", RequestOutcome.CLOSED, "zero.example"),
            request(5, "DIRECT", RequestOutcome.FAILED, "failed.example"),
            request(6, "REJECT", RequestOutcome.BLOCKED, "blocked.example"),
        )
        val s = ActivitySnapshot(domains = listOf(DomainTraffic("missing.example", ProxyProtocol.HTTP)), requests = records)
        assertEquals(setOf("pending.example", "connected.example"), select(s, ActivityFilter.ACTIVE).map { it.host }.toSet())
        assertEquals(setOf("transferred.example", "zero.example"), select(s, ActivityFilter.ENDED).map { it.host }.toSet())
        assertEquals("failed.example", select(s, ActivityFilter.FAILED).single().host)
        assertEquals("blocked.example", select(s, ActivityFilter.BLOCKED).single().host)
        assertEquals("missing.example", select(s, ActivityFilter.UNKNOWN).single().host)
        val categorized = ActivityFilter.entries.filter { it != ActivityFilter.ALL }.flatMap { select(s, it) }
        assertEquals(7, categorized.size)
        assertEquals(7, categorized.map { it.key }.distinct().size)
    }

    @Test fun routeAndStatusMustBelongToTheSameRequest() {
        val direct = request(1, "DIRECT", RequestOutcome.RELAYED)
        val proxy = request(2, "PROXY", RequestOutcome.FAILED)
        val s = ActivitySnapshot(requests = listOf(proxy, direct), domains = listOf(
            DomainTraffic("mixed.example", ProxyProtocol.HTTP, uploadBytes = 123, downloadBytes = 456)))
        assertTrue(select(s, ActivityFilter.FAILED, ActivityRoute.DIRECT).isEmpty())
        assertTrue(select(s, ActivityFilter.ENDED, ActivityRoute.PROXY).isEmpty())
        val oldMatch = select(s, ActivityFilter.ENDED, ActivityRoute.DIRECT).single()
        assertEquals(direct, oldMatch.displayedRequest)
        assertEquals(proxy, oldMatch.latest)
        assertEquals(listOf(proxy, direct), oldMatch.requests)
        assertEquals(579L, oldMatch.bytes)
        assertEquals(proxy, select(s, ActivityFilter.FAILED, ActivityRoute.PROXY).single().displayedRequest)
    }

    @Test fun repeatedVisitsAndMixedRoutesNeverDuplicateTheSameDomainAndProtocol() {
        val records = (1..100).map { request(it, if (it % 2 == 0) "DIRECT" else "PROXY",
            if (it % 3 == 0) RequestOutcome.FAILED else RequestOutcome.RELAYED) }
        val traffic = DomainTraffic("mixed.example", ProxyProtocol.HTTP, 12, 34, 0, 99)
        val s = ActivitySnapshot(domains = listOf(traffic), requests = records)
        ActivityFilter.entries.forEach { status -> ActivityRoute.entries.forEach { route ->
            val rows = select(s, status, route)
            assertTrue("$status/$route split one host into duplicate rows", rows.size <= 1)
            rows.forEach { assertEquals(traffic, it.traffic); assertEquals(100, it.requests.size); assertEquals(46L, it.bytes) }
        } }
        assertEquals(1, select(s).size)
        assertEquals(1, select(s, route = ActivityRoute.DIRECT).size)
        assertEquals(1, select(s, route = ActivityRoute.PROXY).size)
    }

    @Test fun aLiveCounterCannotBorrowTheRouteOfAnOlderFailedRequest() {
        val old = request(1, "DIRECT", RequestOutcome.FAILED)
        val s = ActivitySnapshot(domains = listOf(DomainTraffic("mixed.example", ProxyProtocol.HTTP, connections = 1)), requests = listOf(old))
        assertTrue(select(s, ActivityFilter.ACTIVE, ActivityRoute.DIRECT).isEmpty())
        // The count can be a stale sample or a retained keep-alive socket, not a new unknown visit.
        assertTrue(select(s, ActivityFilter.ACTIVE, ActivityRoute.UNKNOWN).isEmpty())
        assertEquals(old, select(s, ActivityFilter.FAILED, ActivityRoute.DIRECT).single().displayedRequest)
    }

    @Test fun unrecordedCountersAreUnknownAndZeroBytesDoNotProveAnEndedVisit() {
        val s = ActivitySnapshot(domains = listOf(
            DomainTraffic("active.example", ProxyProtocol.HTTP, connections = 2),
            DomainTraffic("idle.example", ProxyProtocol.HTTP, downloadBytes = 500)))
        assertEquals(2, select(s, route = ActivityRoute.UNKNOWN).size)
        assertEquals("active.example", select(s, ActivityFilter.ACTIVE, ActivityRoute.UNKNOWN).single().host)
        assertEquals("idle.example", select(s, ActivityFilter.UNKNOWN, ActivityRoute.UNKNOWN).single().host)
        assertTrue(select(s, ActivityFilter.ENDED).isEmpty())
        assertTrue(select(s, route = ActivityRoute.PROXY).isEmpty())
        assertTrue(select(s, route = ActivityRoute.DIRECT).isEmpty())
        assertTrue(select(s, route = ActivityRoute.BLOCKED).isEmpty())
    }

    @Test fun knownStatusWithMissingOrUnrecognizedRouteStaysUnknown() {
        val s = ActivitySnapshot(requests = listOf(
            request(1, null, RequestOutcome.FAILED, "missing.example"),
            request(2, "FUTURE_ACTION", RequestOutcome.CONNECTED, "future.example")))
        assertEquals(2, select(s, route = ActivityRoute.UNKNOWN).size)
        assertEquals("missing.example", select(s, ActivityFilter.FAILED, ActivityRoute.UNKNOWN).single().host)
        assertEquals("future.example", select(s, ActivityFilter.ACTIVE, ActivityRoute.UNKNOWN).single().host)
        assertTrue(select(s, route = ActivityRoute.PROXY).isEmpty())
    }

    @Test fun sortingOnlyChangesOrderAndSearchStillCombinesWithBothFilters() {
        val s = ActivitySnapshot(domains = listOf(DomainTraffic("b.example", ProxyProtocol.HTTP, downloadBytes = 3_000_000_000L)),
            requests = listOf(request(1, "DIRECT", RequestOutcome.FAILED, "a.example"), request(2, "DIRECT", RequestOutcome.FAILED, "b.example")))
        val query = ActivityQuery(filter = ActivityFilter.FAILED, route = ActivityRoute.DIRECT)
        DomainSort.entries.forEach { assertEquals(setOf("a.example", "b.example"), domainActivities(s, query.copy(sort = it)).map { it.host }.toSet()) }
        assertEquals(listOf("b.example", "a.example"), domainActivities(s, query.copy(sort = DomainSort.TRAFFIC)).map { it.host })
        assertEquals("a.example", domainActivities(s, query.copy(text = "https://a.example/path?q=secret")).single().host)
        assertNull(domainActivities(s, query).first { it.host == "a.example" }.bytes)
    }

    @Test fun protocolsAndOverflowRemainSeparateWhileVisitsWithinEachProtocolMerge() {
        val s = ActivitySnapshot(domains = listOf(
            DomainTraffic("mixed.example", ProxyProtocol.HTTP), DomainTraffic("mixed.example", ProxyProtocol.SOCKS5),
            DomainTraffic(TrafficAccounting.OTHERS, ProxyProtocol.HTTP), DomainTraffic(TrafficAccounting.OTHERS, ProxyProtocol.SOCKS5)),
            requests = listOf(request(1, "DIRECT", RequestOutcome.RELAYED), request(2, "DIRECT", RequestOutcome.RELAYED),
                request(3, "PROXY", RequestOutcome.FAILED, protocol = "SOCKS5")))
        assertEquals(4, select(s).map { it.key }.distinct().size)
        assertEquals(ProxyProtocol.HTTP, select(s, route = ActivityRoute.DIRECT).single().protocol)
        assertEquals(ProxyProtocol.SOCKS5, select(s, route = ActivityRoute.PROXY).single().protocol)
        assertEquals(2, select(s, route = ActivityRoute.UNKNOWN).count { it.aggregate })
    }

    @Test fun actualStoreFailureRemainsFailedEvenAfterBytesWereTransferred() {
        val store = RequestActivityStore().apply { setEnabled(true) }
        val token = store.open(1, "HTTP")
        store.rule(token, "mixed.example", "DIRECT", "USER_ALLOW")
        store.connected(token, "192.0.2.1")
        store.failed(token, "relay", "connection reset")
        store.closed(token, "error", 100, 500)
        val s = ActivitySnapshot(requests = store.snapshot())
        assertEquals(1, select(s, ActivityFilter.FAILED, ActivityRoute.DIRECT).size)
        assertTrue(select(s, ActivityFilter.ENDED).isEmpty())
    }

    @Test fun evictionAndRecordingOffCannotFabricateDefaultProxyRoutes() {
        val store = RequestActivityStore(capacity = 1).apply { setEnabled(true) }
        store.rule(store.open(1, "HTTP"), "older.example", "DIRECT", "USER_ALLOW")
        store.rule(store.open(2, "HTTP"), "newer.example", "PROXY", "DEFAULT")
        val counters = listOf(DomainTraffic("older.example", ProxyProtocol.HTTP, connections = 1), DomainTraffic("newer.example", ProxyProtocol.HTTP))
        assertEquals("older.example", select(ActivitySnapshot(domains = counters, requests = store.snapshot()), route = ActivityRoute.UNKNOWN).single().host)
        store.setEnabled(false)
        val disabled = ActivitySnapshot(domains = counters, requests = store.snapshot())
        assertTrue(select(disabled, route = ActivityRoute.PROXY).isEmpty())
        assertEquals(2, select(disabled, route = ActivityRoute.UNKNOWN).size)
    }
}
