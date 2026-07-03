package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.ui.parseLogEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 错误日志按「条目」解析：多行堆栈合为一条、不被逐行拆散；配合 reload 的 asReversed 实现最近在前。 */
class LogEntryParseTest {

    private val sample = """
        07-03 09:00:00.100 I/hxmyproxy: started
        07-03 09:00:01.200 E/hxmyproxy: boom
        java.lang.RuntimeException: boom
        	at com.example.A.f(A.kt:10)
        	at com.example.B.g(B.kt:20)
        07-03 09:00:02.300 W/hxmyproxy: retrying
    """.trimIndent()

    @Test
    fun groupsMultilineStackIntoOneEntry() {
        val entries = parseLogEntries(sample)
        // 3 条条目（不是 6 行 → 6 条）。
        assertEquals(3, entries.size)
        // 带异常的那条：message 只含首行，detail 含完整堆栈两帧。
        val err = entries[1]
        assertEquals("E", err.level)
        assertEquals("boom", err.message)
        assertTrue(err.hasMore)
        assertTrue(err.detail!!.contains("RuntimeException: boom"))
        assertTrue(err.detail!!.contains("A.kt:10"))
        assertTrue(err.detail!!.contains("B.kt:20"))
    }

    @Test
    fun plainEntryHasNoDetail() {
        val entries = parseLogEntries(sample)
        assertEquals("started", entries[0].message)
        assertNull(entries[0].detail)
        assertEquals(false, entries[0].hasMore)
    }

    @Test
    fun fileOrderIsOldestFirstSoReversedGivesRecentFirst() {
        val entries = parseLogEntries(sample)
        // 解析保持文件顺序（旧→新）：首条最旧、末条最新；UI 层 asReversed() 得最近在前。
        assertEquals("started", entries.first().message)
        assertEquals("retrying", entries.last().message)
        assertEquals(listOf("started", "boom", "retrying"), entries.asReversed().reversed().map { it.message })
    }

    @Test
    fun emptyAndBlankInputYieldNoEntries() {
        assertTrue(parseLogEntries("").isEmpty())
        assertTrue(parseLogEntries("\n\n  \n").isEmpty())
    }
}
