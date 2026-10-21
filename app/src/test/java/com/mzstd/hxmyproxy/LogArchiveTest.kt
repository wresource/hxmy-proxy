package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.log.FileLog
import com.mzstd.hxmyproxy.core.log.LogArchive
import com.mzstd.hxmyproxy.core.log.ExitReason
import android.app.ApplicationExitInfo
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.util.TimeZone

class LogArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private val now = Instant.parse("2026-10-21T16:00:00Z").toEpochMilli()
    private val zone = TimeZone.getTimeZone("UTC")
    private val crashA = "10-21 15:49:02.704 E/crash: Uncaught pid=1 sid=one v=238\njava.lang.IllegalArgumentException: Constraints\n\tat first.frame(Source.kt:1)\n"
    private val crashB = "10-21 15:49:34.937 E/crash: Uncaught pid=2 sid=two v=238\njava.lang.IllegalArgumentException: Constraints\n\tat first.frame(Source.kt:1)\n"
    @Test fun errorsSurviveThousandsOfTrafficRecordsAndMirrorsAreRemoved() {
        val file = temp.newFile().apply { writeText(buildString {
            append("===== KEY EVENTS (independent rolling buffer) =====\n").append(crashA).append(crashB)
            append("===== FULL LOG =====\n").append(crashA).append(crashB)
            repeat(5000) { append("10-21 15:56:00.000 I/RELAY: evt=traffic id=$it\n") }
        }) }
        LogArchive.fromExport(file, now, zone).use { archive ->
            val errors = archive.query("ERROR")
            assertEquals(2, errors.total); assertEquals(2, errors.errors); assertEquals(5002, errors.all)
            assertTrue(archive.text(errors.entries.first()).text.contains("sid=two"))
            assertEquals(2, archive.query(search = "Constraints").total)
            assertEquals(2, archive.query(crashesOnly = true).total)
        }
    }
    @Test fun identicalEventsAreDeduplicatedOnlyAcrossTheTwoBuffers() {
        val line = "10-21 15:00:00.001 E/error: repeated\n"
        val file = temp.newFile().apply { writeText("===== KEY EVENTS (independent rolling buffer) =====\n" + line.repeat(2) + "===== FULL LOG =====\n" + line.repeat(2)) }
        LogArchive.fromExport(file, now, zone).use { archive ->
            val first = archive.query(limit = 1)
            val second = archive.query(before = first.next, limit = 1)
            assertEquals(2, first.total); assertTrue(first.hasMore); assertFalse(second.hasMore)
            assertNotEquals(first.entries.single().id, second.entries.single().id)
        }
    }
    @Test fun cursorPagesHaveNoOverlapAndYearBoundaryIsChronological() {
        val file = temp.newFile().apply { writeText(buildString {
            append("12-31 23:59:59.999 E/crash: previous year\n")
            repeat(121) { append("01-01 00:00:00.001 I/PROC: id=$it\n") }
        }) }
        LogArchive.fromExport(file, Instant.parse("2027-01-01T01:00:00Z").toEpochMilli(), zone).use { archive ->
            val rows = mutableListOf<LogArchive.Entry>(); var cursor: LogArchive.Cursor? = null
            do { val page = archive.query(before = cursor); assertTrue(page.entries.size <= 50); rows += page.entries; cursor = page.next; if (!page.hasMore) break } while (true)
            assertEquals(122, rows.size); assertEquals(122, rows.map { it.id }.distinct().size)
            assertEquals("previous year", rows.last().message)
        }
    }
    @Test fun longUnicodeDetailIsPagedWithoutLossAndExportInputIsReadOnly() {
        val raw = "10-21 15:00:00.001 E/crash: long\n" + ("stack 中文 🐾 ".repeat(9000))
        val file = temp.newFile().apply { writeText(raw) }
        LogArchive.fromExport(file, now, zone).use { archive ->
            val entry = archive.query("ERROR").entries.single()
            val joined = StringBuilder(); var offset = 0
            while (true) {
                val page = archive.text(entry, offset)
                assertTrue(page.text.length <= 8192)
                assertFalse(page.text.lastOrNull()?.isHighSurrogate() == true)
                joined.append(page.text); offset += page.text.length
                if (!page.hasMore) break
            }
            assertEquals(raw, joined.toString())
        }
        assertTrue(file.exists()); assertEquals(raw, file.readText())
    }
    @Test fun frozenViewSurvivesLiveRotationAndClear() {
        val enabled = FileLog.enabled
        try {
            FileLog.enabled = true; FileLog.init(temp.newFolder(), maxBytes = 120)
            FileLog.e("test", "old evidence")
            LogArchive.capture(temp.newFolder()).use { archive ->
                FileLog.clear(); repeat(10) { FileLog.i("test", "new traffic $it") }
                val page = archive.query("ERROR")
                assertEquals(1, page.total); assertEquals("old evidence", page.entries.single().message)
            }
        } finally { FileLog.clear(); FileLog.enabled = enabled }
    }
    @Test fun systemExitSeveritySeparatesCrashesFromNormalUpdates() {
        assertEquals("E", ExitReason.exitLevel(ApplicationExitInfo.REASON_CRASH))
        assertEquals("E", ExitReason.exitLevel(ApplicationExitInfo.REASON_ANR))
        assertEquals("W", ExitReason.exitLevel(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("I", ExitReason.exitLevel(ApplicationExitInfo.REASON_PACKAGE_UPDATED))
        assertEquals("I", ExitReason.exitLevel(ApplicationExitInfo.REASON_USER_REQUESTED))
    }
}
