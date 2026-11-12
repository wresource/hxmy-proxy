package com.mzstd.hxmyproxy.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationUpdatesTest {
    @Test fun identicalDisplayDoesNotBuildOrPostAnotherNotification() = runTest {
        val posted = mutableListOf<String>()
        val updates = NotificationUpdates(this, { testScheduler.currentTime }, posted::add)
        updates.foregroundPublished("running")
        repeat(500) { updates.offer("running") }
        advanceTimeBy(5_000); runCurrent()
        assertTrue(posted.isEmpty())
    }

    @Test fun sustainedChangesPublishAtBoundedIntervalsWithoutStarvingTheLastValue() = runTest {
        val posted = mutableListOf<Pair<Long, Int>>()
        val updates = NotificationUpdates(this, { testScheduler.currentTime }, { value: Int ->
            posted += testScheduler.currentTime to value
        })
        updates.foregroundPublished(-1)
        repeat(25) { value ->
            updates.offer(value); runCurrent(); advanceTimeBy(100); runCurrent()
        }
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(1_000L to 9, 2_000L to 19, 3_000L to 24), posted)
    }

    @Test fun returningToTheDisplayedValueCancelsAnObsoleteTrailingRefresh() = runTest {
        val posted = mutableListOf<String>()
        val updates = NotificationUpdates(this, { testScheduler.currentTime }, posted::add)
        updates.foregroundPublished("zero")
        updates.offer("busy"); runCurrent(); advanceTimeBy(200)
        updates.offer("zero"); advanceTimeBy(2_000); runCurrent()
        assertTrue(posted.isEmpty())
    }

    @Test fun importantStateAndLanguageChangesDoNotWaitBehindTrafficRefreshes() = runTest {
        val posted = mutableListOf<Pair<Long, String>>()
        val updates = NotificationUpdates(this, { testScheduler.currentTime }, { value: String ->
            posted += testScheduler.currentTime to value
        })
        updates.foregroundPublished("English / 0")
        updates.offer("English / 20"); runCurrent(); advanceTimeBy(100)
        updates.offer("Chinese / 20", important = true)
        assertEquals(listOf(100L to "Chinese / 20"), posted)
        updates.offer("Chinese / 21"); runCurrent(); advanceTimeBy(900); runCurrent()
        assertEquals(1, posted.size)
        advanceTimeBy(100); runCurrent()
        assertEquals(1_100L to "Chinese / 21", posted.last())
    }

    @Test fun stopAndNewForegroundGenerationDiscardOldQueuedContent() = runTest {
        val posted = mutableListOf<String>()
        val updates = NotificationUpdates(this, { testScheduler.currentTime }, posted::add)
        updates.foregroundPublished("old-start")
        updates.offer("old-traffic"); runCurrent(); advanceTimeBy(200)
        updates.stop(); updates.offer("after-stop", important = true)
        updates.foregroundPublished("new-start")
        updates.offer("new-running", important = true)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(listOf("new-running"), posted)
    }

    @Test fun repeatedForegroundPublicationReplacesPendingUpdateWithoutExtraNotify() = runTest {
        val posted = mutableListOf<String>()
        val updates = NotificationUpdates(this, { testScheduler.currentTime }, posted::add)
        updates.foregroundPublished("starting")
        updates.offer("old-running"); runCurrent()
        updates.foregroundPublished("starting-again")
        advanceTimeBy(2_000); runCurrent()
        assertTrue(posted.isEmpty())
        updates.offer("running")
        assertEquals(listOf("running"), posted)
    }
}
