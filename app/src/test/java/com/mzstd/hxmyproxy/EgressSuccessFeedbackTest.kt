package com.mzstd.hxmyproxy

import android.net.Network
import com.mzstd.hxmyproxy.core.network.EgressHealth
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class EgressSuccessFeedbackTest {
    private fun net(id: Long) = mockk<Network>().also { every { it.networkHandle } returns id }

    @Test fun inFlightSuccessRestoresSidelineButOldFailureCannotImmediatelySidelineAgain() = runBlocking {
        var now = 100_000L
        val h = EgressHealth(probe = { false }, nowMs = { now }); val n = net(11)
        val outstanding = h.beginAttempt(n)
        h.confirmOrSideline(n); assertTrue(h.isSidelined(n))
        h.recordSuccess(outstanding); assertFalse(h.isSidelined(n)); assertNull(h.retryAfterSeconds(n))
        now += EgressHealth.PROBE_COOLDOWN_MS + 1
        repeat(8) { assertFalse(h.recordFailure(n, "old$it.example", outstanding)) }
        h.confirmOrSideline(n, outstanding); assertFalse(h.isSidelined(n))
        // Fresh evidence still detects a real subsequent outage.
        repeat(3) { assertFalse(h.recordFailure(n, "new$it.example")) }
        assertTrue(h.recordFailure(n, "new3.example")); h.confirmOrSideline(n)
        assertTrue(h.isSidelined(n))
    }

    @Test fun oldGenerationOrAnotherNetworkSuccessCannotRestoreTheNewVpnState() = runBlocking {
        val h = EgressHealth(probe = { false }, nowMs = { 100_000L })
        val vpn = net(21); val physical = net(22)
        val old = h.beginAttempt(vpn); h.reset(); h.confirmOrSideline(vpn)
        h.recordSuccess(old); assertTrue(h.isSidelined(vpn))
        h.recordSuccess(h.beginAttempt(physical)); assertTrue(h.isSidelined(vpn))
        h.recordSuccess(h.beginAttempt(vpn)); assertFalse(h.isSidelined(vpn))
    }

    @Test fun successDuringARecheckCannotBeOverwrittenByItsLateFailure() = runBlocking {
        var now = 100_000L; var probes = 0
        val began = CompletableDeferred<Unit>(); val result = CompletableDeferred<Boolean>()
        val h = EgressHealth(probe = { if (++probes == 1) false else { began.complete(Unit); result.await() } }, nowMs = { now })
        val n = net(31); val outstanding = h.beginAttempt(n)
        h.confirmOrSideline(n); now += EgressHealth.RECHECK_MS
        val job = launch { h.confirmOrSideline(n) }
        try {
            began.await(); h.recordSuccess(outstanding)
            assertFalse(h.isSidelined(n))
            result.complete(false); job.join(); assertFalse(h.isSidelined(n))
        } finally { result.complete(false); job.cancelAndJoin() }
    }

    @Test fun fixedProbeSetRetainsOldTargetsAndCoversIpv6WithoutDns() {
        val hosts = EgressHealth.PROBE_TARGETS.map { it.first }
        assertTrue(hosts.containsAll(listOf("223.5.5.5", "120.53.53.53", "101.226.4.6", "1.1.1.1", "8.8.8.8")))
        assertEquals(2, hosts.count { ':' in it })
        assertTrue(EgressHealth.PROBE_TARGETS.all { it.second == 443 })
        hosts.forEach { assertNotNull(java.net.InetAddress.getByName(it)) }
        assertTrue(hosts.size <= 7)
    }
}
