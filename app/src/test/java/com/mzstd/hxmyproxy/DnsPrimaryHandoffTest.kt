package com.mzstd.hxmyproxy

import android.net.Network
import com.mzstd.hxmyproxy.core.network.SystemDnsLookup
import com.mzstd.hxmyproxy.core.network.UnderlyingNetworkProvider
import com.mzstd.hxmyproxy.core.proxy.OutboundConnector
import com.mzstd.hxmyproxy.core.proxy.ProxyException
import com.mzstd.hxmyproxy.core.security.EgressGuard
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Semaphore
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

class DnsPrimaryHandoffTest {
    private val good = listOf(InetAddress.getByName("192.0.2.11"))
    private val rescue = listOf(InetAddress.getByName("192.0.2.22"))
    private val physical = mockk<Network> { every { networkHandle } returns 7711L }
    private fun connector(lookup: SystemDnsLookup) = testConnector(
        object : EgressGuard { override fun isAllowed(addr: InetAddress) = true },
        underlyingNetworkProvider = mockk<UnderlyingNetworkProvider> {
            every { current() } returns physical
            every { dohSelection() } returns UnderlyingNetworkProvider.DohEgressSelection(com.mzstd.hxmyproxy.core.model.DohEgressChoice.WIFI, physical)
        }, systemDns = lookup,
    ).apply { backupDnsEnabled = false }

    @Suppress("UNCHECKED_CAST")
    private suspend fun resolve(c: OutboundConnector, host: String = "handoff.example", network: Network? = null): List<InetAddress> =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            c.javaClass.declaredMethods.single { it.name == "resolve" }.apply { isAccessible = true }
                .invoke(c, host, network, null, continuation)
        }

    @Test(timeout = 10000) fun latePrimaryCanWinWhileAlreadyStartedRescueIsStillPending() = runBlocking {
        val releasePrimary = CompletableDeferred<Unit>()
        val rescueStarted = CompletableDeferred<Unit>()
        val releaseRescue = CompletableDeferred<Unit>()
        val c = connector(SystemDnsLookup { network, _ ->
            if (network == null) { releasePrimary.await(); good }
            else { rescueStarted.complete(Unit); releaseRescue.await(); rescue }
        })
        val request = async(Dispatchers.Default) { resolve(c) }
        try {
            withTimeout(3000) { rescueStarted.await() } // AUTO rescue must start at the soft handoff.
            releasePrimary.complete(Unit)
            delay(200)
            releaseRescue.complete(Unit)
            assertEquals(good, withTimeout(2000) { request.await() })
            assertEquals("only one answer is published and reused", good, resolve(c))
        } finally { releaseRescue.complete(Unit); request.cancelAndJoin(); c.cancelPendingDns() }
    }

    @Test(timeout = 10000) fun readyRescueDoesNotWaitForTheRemainingPrimaryBudget() = runBlocking {
        val never = CompletableDeferred<Unit>()
        val c = connector(SystemDnsLookup { network, _ -> if (network == null) { never.await(); good } else rescue })
        try {
            val started = System.nanoTime()
            assertEquals(rescue, withTimeout(2800) { resolve(c) })
            assertTrue("the original 1.5s handoff is preserved", (System.nanoTime() - started) / 1_000_000 >= 1400)
            assertEquals(0, c.dnsStats().inflight)
        } finally { c.cancelPendingDns() }
    }

    @Test(timeout = 10000) fun exhaustedRescueDoesNotExtendAFailedRequestToThePrimaryHardDeadline() = runBlocking {
        val never = CompletableDeferred<Unit>()
        val c = connector(SystemDnsLookup { network, _ -> if (network == null) { never.await(); good } else emptyList() })
        try {
            val result = withTimeout(2800) { runCatching { resolve(c) } }
            assertTrue(result.exceptionOrNull() is ProxyException)
            assertEquals(0, c.dnsStats().inflight)
        } finally { c.cancelPendingDns() }
    }

    @Test(timeout = 12000) fun fourConcurrentPrimaryQueriesKeepTheirSlotsUntilTheyActuallyStop() = runBlocking {
        val never = CompletableDeferred<Unit>()
        val primaryActive = AtomicInteger()
        val backupActive = AtomicInteger()
        val allBackupsStarted = CompletableDeferred<Unit>()
        val c = connector(SystemDnsLookup { network, _ ->
            val counter = if (network == null) primaryActive else backupActive
            if (counter.incrementAndGet() == 4 && network != null) allBackupsStarted.complete(Unit)
            try { never.await(); good } finally { counter.decrementAndGet() }
        })
        val requests = List(4) { i -> async(Dispatchers.Default) { resolve(c, "handoff-$i.example") } }
        try {
            withTimeout(3500) { allBackupsStarted.await() }
            assertEquals("soft handoff does not cancel a primary still able to win", 4, primaryActive.get())
            assertEquals(4, backupActive.get())
            assertEquals("actual primary and rescue queries both occupy the bounded slots", 8, c.dnsStats().inflight)
        } finally {
            requests.forEach { it.cancel() }
            withTimeout(1500) { requests.joinAll() }
            c.cancelPendingDns()
        }
        assertEquals(0, primaryActive.get())
        assertEquals(0, backupActive.get())
        assertEquals(0, c.dnsStats().inflight)
    }

    @Test(timeout = 12000) fun twelveSaturatedPrimariesStillMakeRoomForTheirWorkingRescue() = runBlocking {
        val never = CompletableDeferred<Unit>()
        val active = AtomicInteger(); val peak = AtomicInteger()
        val c = connector(SystemDnsLookup { network, _ ->
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try { if (network == null) { never.await(); good } else { delay(30); rescue } }
            finally { active.decrementAndGet() }
        })
        val requests = List(12) { i -> async(Dispatchers.Default) { resolve(c, "saturated-$i.example") } }
        try {
            val answers = withTimeout(3000) { requests.awaitAll() }
            answers.forEach { assertEquals(rescue, it) }
            assertTrue("never increase the existing actual DNS limit", peak.get() <= 12)
        } finally { requests.forEach { it.cancel() }; requests.joinAll(); c.cancelPendingDns() }
        assertEquals(0, active.get())
    }

    @Test(timeout = 12000) fun retainedPrimariesYieldToNewRequestsWithoutOvercommittingSlots() = runBlocking {
        val never = CompletableDeferred<Unit>()
        val releaseRescue = CompletableDeferred<Unit>(); val releaseNew = CompletableDeferred<Unit>()
        val rescuesStarted = CompletableDeferred<Unit>(); val newStarted = CompletableDeferred<Unit>()
        val rescueCount = AtomicInteger(); val newCount = AtomicInteger()
        val active = AtomicInteger(); val peak = AtomicInteger()
        val c = connector(SystemDnsLookup { network, host ->
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try {
                if (host.startsWith("new-")) {
                    if (newCount.incrementAndGet() == 8) newStarted.complete(Unit)
                    releaseNew.await(); good
                } else if (network == null) { never.await(); good }
                else { if (rescueCount.incrementAndGet() == 4) rescuesStarted.complete(Unit); releaseRescue.await(); rescue }
            } finally { active.decrementAndGet() }
        })
        val old = List(4) { i -> async(Dispatchers.Default) { resolve(c, "old-$i.example") } }
        var fresh = emptyList<Deferred<List<InetAddress>>>()
        try {
            withTimeout(3000) { rescuesStarted.await() }
            fresh = List(8) { i -> async(Dispatchers.Default) { resolve(c, "new-$i.example") } }
            withTimeout(1000) { newStarted.await() }
            releaseNew.complete(Unit); releaseRescue.complete(Unit)
            fresh.awaitAll().forEach { assertEquals(good, it) }
            old.awaitAll().forEach { assertEquals(rescue, it) }
            assertTrue(peak.get() <= 12)
        } finally {
            releaseNew.complete(Unit); releaseRescue.complete(Unit)
            (old + fresh).forEach { it.cancel() }; (old + fresh).joinAll(); c.cancelPendingDns()
        }
        assertEquals(0, active.get())
    }

    @Test(timeout = 10000) fun rescueReadyAtSixteenHundredMsStillWinsOverTwoSecondPrimary() = runBlocking {
        val c = connector(SystemDnsLookup { network, _ ->
            if (network == physical) { delay(2000); good } else { delay(1200); rescue }
        })
        try { assertEquals(rescue, withTimeout(2800) { resolve(c, "ready-rescue.example", physical) }) }
        finally { c.cancelPendingDns() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun readyRescueHandoffDoesNotWaitForAnotherPreemptionMutexOwner() = runTest {
        val never = CompletableDeferred<Unit>()
        val c = connector(SystemDnsLookup { network, _ -> if (network == physical) { never.await(); good } else rescue })
        // Control only coroutine scheduling and the capacity-maintenance lock. The real
        // 12-query admission bound is exercised separately with actual lookup invocations.
        val scopeField = c.javaClass.getDeclaredField("dnsScope").apply { isAccessible = true }
        (scopeField.get(c) as CoroutineScope).cancel()
        scopeField.set(c, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        val slots = c.javaClass.getDeclaredField("dnsSlots").apply { isAccessible = true }.get(c) as Semaphore
        val maintenance = c.javaClass.getDeclaredField("dnsPreemption").apply { isAccessible = true }.get(c) as Mutex
        val request = async { resolve(c, "mutex-handoff.example", physical) }
        var reserved = false
        try {
            runCurrent()
            advanceTimeBy(450); runCurrent() // Rescue is already available; primary is pending.
            assertEquals(1, c.dnsStats().inflight)
            reserved = slots.tryAcquire(11)
            assertTrue(reserved)
            maintenance.lock()
            advanceTimeBy(1050); runCurrent()
            assertTrue("the 1.5s acceptance gate must not wait for maintenance's extra 100ms", request.isCompleted)
            assertEquals(rescue, request.await())
        } finally {
            if (maintenance.isLocked) maintenance.unlock()
            if (reserved) slots.release(11)
            request.cancel(); runCurrent(); c.cancelPendingDns()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun cancellingAdmissionMaintenanceReturnsItsPermitWithoutPreemptingOtherRequests() = runTest {
        val never = CompletableDeferred<Unit>()
        val primaryActive = AtomicInteger()
        val c = connector(SystemDnsLookup { network, _ ->
            if (network == null) primaryActive.incrementAndGet()
            try { never.await(); good } finally { if (network == null) primaryActive.decrementAndGet() }
        })
        val scopeField = c.javaClass.getDeclaredField("dnsScope").apply { isAccessible = true }
        (scopeField.get(c) as CoroutineScope).cancel()
        scopeField.set(c, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        val slots = c.javaClass.getDeclaredField("dnsSlots").apply { isAccessible = true }.get(c) as Semaphore
        val maintenance = c.javaClass.getDeclaredField("dnsPreemption").apply { isAccessible = true }.get(c) as Mutex
        val old = List(4) { i -> async { resolve(c, "held-$i.example") } }
        var newcomer: Deferred<List<InetAddress>>? = null
        var reserved = false
        try {
            runCurrent(); advanceTimeBy(1500); runCurrent()
            assertEquals(8, c.dnsStats().inflight)
            reserved = slots.tryAcquire(3); assertTrue(reserved)
            maintenance.lock()
            newcomer = async { resolve(c, "cancelled-admission.example") }
            runCurrent()
            assertEquals("new admission owns the last permit while maintaining capacity", 12, c.dnsStats().inflight)
            newcomer.cancel(); runCurrent()
            assertTrue(newcomer.isCompleted)
            assertEquals("cancelled admission returned its own permit", 11, c.dnsStats().inflight)
            assertEquals("a cancelled mutex waiter must not preempt unrelated primaries", 4, primaryActive.get())
        } finally {
            if (maintenance.isLocked) maintenance.unlock()
            if (reserved) slots.release(3)
            newcomer?.cancel(); old.forEach { it.cancel() }; runCurrent(); c.cancelPendingDns()
        }
        assertEquals(0, primaryActive.get())
        assertEquals(0, c.dnsStats().inflight)
    }
}
