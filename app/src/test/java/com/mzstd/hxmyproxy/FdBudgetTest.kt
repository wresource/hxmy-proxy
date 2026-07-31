package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.proxy.FdBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * FD 预算（[FdBudget]）。
 *
 * 原本是 ProxyServerRepository 的 private 方法，读的是硬编码的 /proc/self/limits，
 * 因此「读不到 rlimit 时退回不钳制」这条兜底一行都没测过 —— 而它错了的后果很重：
 * 若读失败时返回 0 而不是 Int.MAX_VALUE，用户配的最大连接数会被直接钳成 0，
 * 表现为「开了共享但一个连接都建不起来」，且日志里只有一行看不懂的钳制告警。
 *
 * 抽出后 limitsFile 可注入，这些分支才第一次真正可测。
 */
class FdBudgetTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun limitsFileWith(line: String): File =
        tmp.newFile().apply {
            writeText(
                """
                Limit                     Soft Limit           Hard Limit           Units
                Max cpu time              unlimited            unlimited            seconds
                $line
                Max processes             12345                12345                processes
                """.trimIndent(),
            )
        }

    @Test fun `按 rlimit 反推：每连接两个 FD 且预留 256`() {
        // (32768 - 256) / 2 = 16256
        val b = FdBudget(limitsFileWith("Max open files            32768                32768                files"))
        assertEquals(16256, b.safeMaxGlobal())
        assertEquals(32768, b.rlimit)
    }

    @Test fun `读不到文件时退回不钳制而不是钳成 0`() {
        // 这是最危险的一条：返回 0 会让用户「一个连接都建不起来」。
        val b = FdBudget(File(tmp.root, "nonexistent"))
        assertEquals(Int.MAX_VALUE, b.safeMaxGlobal())
        assertEquals(0, b.rlimit)
    }

    @Test fun `文件里没有 Max open files 行时同样退回不钳制`() {
        val f = tmp.newFile().apply { writeText("Limit  Soft Limit  Hard Limit  Units\nMax cpu time  unlimited  unlimited  seconds") }
        assertEquals(Int.MAX_VALUE, FdBudget(f).safeMaxGlobal())
    }

    @Test fun `软上限是 unlimited 这类非数字时退回不钳制`() {
        val b = FdBudget(limitsFileWith("Max open files            unlimited            unlimited            files"))
        assertEquals(Int.MAX_VALUE, b.safeMaxGlobal())
    }

    @Test fun `极小 rlimit 也不会低于配置区间下界`() {
        // (300 - 256) / 2 = 22，低于 RANGE_GLOBAL 下界 → 必须抬到下界，
        // 否则等于把功能钳死（而这种设备上真正该做的是让用户自己降配置）。
        val b = FdBudget(limitsFileWith("Max open files            300                  300                  files"))
        assertEquals(ConnectionLimits.RANGE_GLOBAL.first, b.safeMaxGlobal())
    }

    @Test fun `rlimit 只读一次并缓存`() {
        val f = limitsFileWith("Max open files            8192                 8192                 files")
        val b = FdBudget(f)
        val first = b.safeMaxGlobal()
        // 读过之后把文件改掉：结果不应变化（rlimit 在进程生命周期内不变，缓存是有意的）。
        f.writeText("Max open files            99999                99999                files")
        assertEquals(first, b.safeMaxGlobal())
        assertEquals(8192, b.rlimit)
    }

    @Test fun `常量与实现口径一致`() {
        assertEquals(2, FdBudget.PER_CONN)
        assertEquals(256, FdBudget.RESERVED)
        assertTrue(ConnectionLimits.RANGE_GLOBAL.first > 0)
    }
}
