package com.mzstd.hxmyproxy

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.mzstd.hxmyproxy.core.log.ExitReason
import com.mzstd.hxmyproxy.core.log.FileLog
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

class ExitIdentityTest {
    private val dir = Files.createTempDirectory("exit-identity").toFile()
    private var seen: Set<String>? = null
    private var cursor = 0L
    private var records = emptyList<ApplicationExitInfo>()
    private val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").let {
        it.isAccessible = true; it.get(null) as sun.misc.Unsafe
    }
    private val field = Build.VERSION::class.java.getDeclaredField("SDK_INT")
    private val base = unsafe.staticFieldBase(field)
    private val offset = unsafe.staticFieldOffset(field)
    private var oldSdk = 0
    private lateinit var ctx: Context

    @Before fun setup() {
        oldSdk = unsafe.getInt(base, offset); unsafe.putInt(base, offset, 37)
        FileLog.enabled = true; FileLog.init(dir)
        val edit = mockk<SharedPreferences.Editor>()
        every { edit.putLong("lastSeenTs", any()) } answers { cursor = secondArg(); edit }
        every { edit.putStringSet("seenRecordsV2", any()) } answers { seen = secondArg<Set<String>>().toSet(); edit }
        every { edit.apply() } just Runs
        val prefs = mockk<SharedPreferences> {
            every { getLong("lastSeenTs", 0L) } answers { cursor }
            every { getStringSet("seenRecordsV2", null) } answers { seen }
            every { edit() } returns edit
        }
        val am = mockk<ActivityManager> {
            every { getHistoricalProcessExitReasons(null, 0, 8) } answers { records }
        }
        ctx = mockk {
            every { getSystemService(ActivityManager::class.java) } returns am
            every { getSharedPreferences("exit_reason", 0) } returns prefs
        }
    }

    @After fun cleanup() {
        unsafe.putInt(base, offset, oldSdk)
        FileLog.clear(); dir.deleteRecursively(); FileLog.enabled = true
    }

    private fun info(at: Long, processId: Int, importanceValue: Int = 100) = mockk<ApplicationExitInfo>(relaxed = true) {
        every { timestamp } returns at
        every { pid } returns processId
        every { realUid } returns 10001
        every { packageUid } returns 10001
        every { processName } returns "com.example.proxy"
        every { reason } returns ApplicationExitInfo.REASON_CRASH
        every { importance } returns importanceValue
        every { description } returns "crash"
        every { processStateSummary } returns "sid=old-session v=155 sh=1".toByteArray()
    }
    private fun loggedRecords() = dir.resolve("key.log").takeIf { it.exists() }?.readLines()
        .orEmpty().filter { "evt=proc.lastExit record=" in it }

    @Test fun upgradingTimestampCursorReplaysHistoricalRecordsWithIdentityOnlyOnce() {
        cursor = 9999
        records = listOf(info(100, 11), info(200, 12))
        ExitReason.reportLastExit(ctx)
        assertEquals(2, loggedRecords().size)
        assertTrue(loggedRecords().all { "replay=identity-upgrade" in it && "process=com.example.proxy" in it })
        assertTrue(loggedRecords().any { "pid=11" in it && "uid=10001" in it })
        assertEquals(9999, cursor)
        ExitReason.reportLastExit(ctx)
        assertEquals(2, loggedRecords().size)
    }

    @Test fun sameTimestampLateRecordIsNotLostAndSamePidEventsAreNotCollapsed() {
        records = listOf(info(100, 11))
        ExitReason.reportLastExit(ctx)
        records = listOf(info(101, 11, 125), info(100, 12), info(100, 11))
        ExitReason.reportLastExit(ctx)
        assertEquals(3, loggedRecords().size)
        assertEquals(3, seen!!.size)
        ExitReason.reportLastExit(ctx)
        assertEquals(3, loggedRecords().size)
    }

    @Test fun disabledLogDoesNotConsumeExitEvidence() {
        records = listOf(info(100, 11))
        FileLog.enabled = false
        ExitReason.reportLastExit(ctx)
        assertNull(seen)
        assertTrue(loggedRecords().isEmpty())
        FileLog.enabled = true
        ExitReason.reportLastExit(ctx)
        assertEquals(1, loggedRecords().size)
    }

    @Test fun failedKeyWriteDoesNotConsumeIdentityOrTimestamp() {
        records = listOf(info(100, 11))
        val broken = dir.resolve("broken").apply { mkdirs() }
        broken.resolve("key.log").mkdirs()
        FileLog.init(broken)
        ExitReason.reportLastExit(ctx)
        assertNull(seen)
        assertEquals(0L, cursor)
        FileLog.init(dir)
        ExitReason.reportLastExit(ctx)
        assertEquals(1, loggedRecords().size)
    }
}
