package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.*
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.ui.monitor.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
class ActivitySnapshotSourceTest {
    private val unknown = ActivityQuery(route = ActivityRoute.UNKNOWN)
    private class Fixture(capacity: Int = 256) {
        val store = RequestActivityStore(capacity).apply { setEnabled(true) }
        val accounting = TrafficAccounting()
        val input = MutableStateFlow(ActivityInput(ShareState(running = true, sessionStartedAtMs = 10), true))
        val frozen = MutableStateFlow<ActivitySnapshot?>(null)
        val ticks = MutableStateFlow(0)
        val frames = mutableListOf<ActivitySnapshot>()
        var reads = 0
        val source = activitySnapshots(input, frozen, { reads++; store.snapshotState() }, ticks.map { Unit })
        fun visit(id: Int, host: String = "known.example", action: String = "PROXY"): Pair<RequestActivityStore.Token?, TrafficAccounting.ConnTracker> {
            val token = store.open(id, "HTTP")
            store.rule(token, host, action, "TEST")
            store.connected(token, "192.0.2.1")
            val tracker = accounting.openConnection(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), ProxyProtocol.HTTP)
            tracker.bindHost(host, action == "DIRECT")
            return token to tracker
        }
        fun publish() { input.value = input.value.copy(share = input.value.share.copy(topDomains = accounting.snapshot().topDomains)) }
    }

    @Test fun independentlySampledKnownRequestsNeverBecomeUnknown() {
        val f = Fixture()
        val first = f.visit(1)
        val oldRecords = f.store.snapshot()
        val second = f.visit(2)
        val currentCounters = f.accounting.snapshot().topDomains
        val mixedNewCounters = ActivitySnapshot(domains = currentCounters, requests = oldRecords)
        assertTrue(domainActivities(mixedNewCounters, unknown).isEmpty())
        f.store.closed(second.first, "eof", 10, 20); second.second.close()
        val mixedOldCounters = ActivitySnapshot(domains = currentCounters, requests = f.store.snapshot())
        assertTrue(domainActivities(mixedOldCounters, unknown).isEmpty())
        assertEquals(setOf("PROXY"), mixedOldCounters.requests.map { it.action }.toSet())
        assertEquals(1, domainActivities(mixedOldCounters).size)
        first.second.close()
    }

    @Test fun finishedRequestOnAReusableSocketKeepsItsKnownRoute() {
        val f = Fixture()
        val (token, connection) = f.visit(1)
        f.store.closed(token, "http-keepalive", 1, 200)
        val snapshot = ActivitySnapshot(domains = f.accounting.snapshot().topDomains, requests = f.store.snapshot())
        assertEquals(1L, snapshot.domains.single().connections)
        assertEquals(RequestOutcome.RELAYED, snapshot.requests.single().outcome)
        assertTrue(domainActivities(snapshot, unknown).isEmpty())
        assertEquals(1, domainActivities(snapshot, ActivityQuery(filter = ActivityFilter.ENDED, route = ActivityRoute.PROXY)).size)
        connection.close()
    }

    @Test fun firstDomainUsesAlreadyRecordedRouteBeforeTheNextRequestPoll() = runTest {
        val f = Fixture()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.source.toList(f.frames) }
        runCurrent()
        val initialReads = f.reads
        val (_, connection) = f.visit(1)
        f.publish() // No timer tick: the previous independent request sample would still be empty.
        runCurrent()
        assertTrue(f.reads > initialReads)
        assertEquals("PROXY", f.frames.last().requests.single().action)
        assertTrue(f.frames.all { domainActivities(it, unknown).isEmpty() })
        assertEquals(1, domainActivities(f.frames.last(), ActivityQuery(route = ActivityRoute.PROXY)).size)
        connection.close()
    }

    @Test fun recordOnlyRefreshDoesNotReuseThePreviousSampleOrInferACountGap() = runTest {
        val f = Fixture()
        val (token, connection) = f.visit(1)
        f.publish()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.source.toList(f.frames) }
        runCurrent()
        f.store.closed(token, "http-keepalive", 10, 20)
        f.ticks.value++; runCurrent()
        assertEquals(RequestOutcome.RELAYED, f.frames.last().requests.single().outcome)
        assertEquals(1L, f.frames.last().domains.single().connections)
        assertTrue(f.frames.all { domainActivities(it, unknown).isEmpty() })
        connection.close()
    }

    @Test fun trueEvictionAndDisabledRecordingRemainVisibleAsMissingEvidence() = runTest {
        val f = Fixture(capacity = 1)
        val older = f.visit(1, "older.example")
        f.visit(2, "newer.example")
        f.publish()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.source.toList(f.frames) }
        runCurrent()
        assertEquals("older.example", domainActivities(f.frames.last(), unknown).single().host)
        f.store.setEnabled(false)
        f.input.value = f.input.value.copy(recording = false)
        runCurrent()
        assertEquals(2, domainActivities(f.frames.last(), unknown).size)
        assertTrue(domainActivities(f.frames.last(), ActivityQuery(route = ActivityRoute.PROXY)).isEmpty())
        assertTrue(f.frames.last().requests.isEmpty())
        older.second.close()
    }

    @Test fun pauseKeepsOneSnapshotButActualStopClearsTheSession() = runTest {
        val f = Fixture(); f.visit(1); f.publish()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.source.toList(f.frames) }
        runCurrent()
        val before = f.frames.last()
        f.frozen.value = before.copy(paused = true); runCurrent()
        f.visit(2, "later.example"); f.publish(); f.ticks.value++; runCurrent()
        assertTrue(f.frames.last().paused)
        assertEquals(before.requests, f.frames.last().requests)
        f.input.value = f.input.value.copy(share = ShareState())
        runCurrent()
        assertFalse(f.frames.last().paused)
        assertTrue(f.frames.last().requests.isEmpty())
    }

    @Test fun anEmptyStoppedViewCanStillBePausedAndResumed() = runTest {
        val f = Fixture()
        f.input.value = ActivityInput(ShareState(), true)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.source.toList(f.frames) }
        runCurrent()
        f.frozen.value = f.frames.last().copy(paused = true); runCurrent()
        assertTrue(f.frames.last().paused)
        assertTrue(f.frames.last().requests.isEmpty())
        f.frozen.value = null; runCurrent()
        assertFalse(f.frames.last().paused)
    }

    @Test fun loggingPreferenceAndRecorderGenerationCannotExposePausedPrivateRecords() = runTest {
        val f = Fixture(); f.visit(1); f.publish()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.source.toList(f.frames) }
        runCurrent()
        f.frozen.value = f.frames.last().copy(paused = true); runCurrent()
        // Settings arrive first; the recorder has not reacted yet.
        f.input.value = f.input.value.copy(recording = false); runCurrent()
        assertFalse(f.frames.last().paused); assertTrue(f.frames.last().requests.isEmpty())
        f.store.setEnabled(false); f.ticks.value++; runCurrent()
        f.store.setEnabled(true); f.input.value = f.input.value.copy(recording = true); runCurrent()
        assertFalse(f.frames.last().paused); assertTrue(f.frames.last().requests.isEmpty())
        assertEquals(1, domainActivities(f.frames.last(), unknown).size)
    }

    @Test fun newSessionAndResetDoNotReapplyAFrozenOldSession() = runTest {
        val f = Fixture(); f.visit(1); f.publish()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.source.toList(f.frames) }
        runCurrent()
        f.frozen.value = f.frames.last().copy(paused = true); runCurrent()
        f.store.reset(); f.accounting.reset(); f.visit(2, "new-session.example")
        f.input.value = f.input.value.copy(share = f.input.value.share.copy(sessionStartedAtMs = 20,
            topDomains = f.accounting.snapshot().topDomains)); runCurrent()
        assertFalse(f.frames.last().paused)
        assertEquals(20L, f.frames.last().session)
        assertEquals("new-session.example", f.frames.last().requests.single().host)
    }

    @Test fun periodicRefreshIsLiveWhileSubscribedAndStopsAfterCancellation() = runTest {
        val f = Fixture(); val (token, _) = f.visit(1); f.publish()
        val source = activitySnapshots(f.input, f.frozen, { f.reads++; f.store.snapshotState() }, clock = { currentTime })
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.toList(f.frames) }
        runCurrent(); f.store.closed(token, "http-keepalive", 1, 2)
        advanceTimeBy(1000); runCurrent()
        assertEquals(RequestOutcome.RELAYED, f.frames.last().requests.single().outcome)
        assertTrue(domainActivities(f.frames.last(), unknown).isEmpty())
        collector.cancelAndJoin()
        val stoppedAt = f.reads; advanceTimeBy(5000); runCurrent()
        assertEquals(stoppedAt, f.reads)
    }
}
