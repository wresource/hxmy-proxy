package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.proxy.ConnectUdpSession
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class SessionPolicyBoundaryTest {
    @Test fun policySnapshotRejectsStaleAndStoppedReads() {
        ConnectUdpSession().use { session ->
            val old = session.currentEpoch
            var allowed = false
            assertEquals(old to false, session.policySnapshot(old) { it to allowed })
            session.changePolicy("local-targets") { allowed = true }
            assertNull(session.policySnapshot(old) { fail("stale callback must not run"); true })
            assertEquals((old + 1) to true, session.policySnapshot(session.currentEpoch) { it to allowed })
            session.close()
            assertNull(session.policySnapshot(null) { fail("stopped callback must not run"); true })
        }
    }

    @Test(timeout=10000) fun capabilityReadCannotMixAnOldRevisionWithANewPolicy() {
        ConnectUdpSession().use { session ->
            val executor = Executors.newFixedThreadPool(2)
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            var allowed = false
            try {
                val update = executor.submit { session.changePolicy("local-targets") {
                    allowed = true; entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
                } }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val read = executor.submit<Pair<Long, Boolean>?> { session.policySnapshot(null) { it to allowed } }
                assertThrows(TimeoutException::class.java) { read.get(100, TimeUnit.MILLISECONDS) }
                release.countDown(); update.get(2, TimeUnit.SECONDS)
                assertEquals(1L to true, read.get(2, TimeUnit.SECONDS))
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }
    @Test(timeout=10000) fun epochAndMutablePolicyArePublishedTogether() {
        ConnectUdpSession().use { session ->
            val executor=Executors.newFixedThreadPool(2)
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            var enabled=false
            try {
                val update=executor.submit { session.changePolicy("authentication") { entered.countDown();check(release.await(3,TimeUnit.SECONDS));enabled=true } }
                assertTrue(entered.await(2,TimeUnit.SECONDS))
                val capture=executor.submit<Pair<Long,Boolean>> { session.currentEpoch to enabled }
                assertThrows(TimeoutException::class.java) { capture.get(100,TimeUnit.MILLISECONDS) }
                release.countDown();update.get(2,TimeUnit.SECONDS)
                assertEquals(1L to true,capture.get(2,TimeUnit.SECONDS))
            } finally { release.countDown();executor.shutdownNow() }
        }
    }

    @Test fun failedPolicyUpdateStillRevokesOldWorkWithoutReleasingItsQuotaEarly() {
        ConnectUdpSession(1).use { session ->
            val epoch=session.currentEpoch
            var closed=false
            val lease=session.acquire(epoch) { closed=true }!!
            assertThrows(IllegalStateException::class.java) { session.changePolicy("failed-change") { error("injected failure") } }
            assertTrue(closed);assertFalse(lease.isCurrent);assertEquals(1,session.activeLeases)
            assertNull(session.acquire(epoch) {})
            lease.close();assertEquals(0,session.activeLeases)
            session.acquire(session.currentEpoch) {}!!.close()
        }
    }
}
