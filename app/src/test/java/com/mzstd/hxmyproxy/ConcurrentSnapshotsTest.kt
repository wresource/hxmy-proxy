package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.util.concurrentSnapshot
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class ConcurrentSnapshotsTest {
    /** Exactly the legal size/iterator mismatch from the real CHM shutdown failure. */
    private fun changedView(reportedSize: Int, visible: List<Int>) = object : java.util.AbstractCollection<Int>() {
        override val size: Int get() = reportedSize
        override fun iterator(): MutableIterator<Int> = visible.toMutableList().iterator()
    }

    @Test fun removalAfterSingletonSizeReadCannotCrashCleanup() {
        val view = changedView(1, emptyList())
        assertThrows(NoSuchElementException::class.java) { view.toList() }
        assertEquals(emptyList<Int>(), concurrentSnapshot(view))
    }

    @Test fun anIteratorEntryIsNotDiscardedBecauseAnEarlierSizeWasZero() {
        assertEquals(listOf(42), concurrentSnapshot(changedView(0, listOf(42))))
        assertEquals(listOf(1, 2), concurrentSnapshot(changedView(1, listOf(1, 2))))
    }

    @Test(timeout = 10000) fun realConcurrentSetRemovalKeepsSnapshotsIndependent() {
        val values = ConcurrentHashMap.newKeySet<Int>()
        val running = AtomicBoolean(true)
        val writer = thread { while (running.get()) { values.add(1); values.remove(1) } }
        try {
            repeat(10000) { assertTrue(concurrentSnapshot(values).all { it == 1 }) }
            val stable = concurrentSnapshot(listOf(3,4)); values.clear()
            assertEquals(listOf(3,4),stable)
        } finally { running.set(false); writer.join(2000); assertFalse(writer.isAlive) }
    }
}
