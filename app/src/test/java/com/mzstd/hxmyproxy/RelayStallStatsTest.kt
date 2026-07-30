package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.proxy.RelayStallStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 背压归因的聚合口径。这里锁的是「同一份 stall 时长怎么变成能读的结论」——
 * 方向别搞反(down=入口段、up=出口段)、均值别被短连接稀释、窗口取走即清零。
 */
class RelayStallStatsTest {

    private val ms = 1_000_000L   // 1ms 的纳秒数

    @Before
    fun setUp() = RelayStallStats.reset()

    @Test
    fun `无样本时返回 null(心跳据此省略字段)`() {
        assertNull(RelayStallStats.snapshotAndReset())
    }

    @Test
    fun `方向不能搞反：写客户端堵算入口段`() {
        // 存活 1000ms，其中写客户端堵了 700ms、写上游没堵
        RelayStallStats.record(liveNanos = 1000 * ms, stallInNanos = 700 * ms, stallOutNanos = 0)
        val s = RelayStallStats.snapshotAndReset()!!
        assertEquals(70, s.inPct)   // 入口段 70%
        assertEquals(0, s.outPct)
    }

    @Test
    fun `两段可以同时堵(设备整体过载)`() {
        RelayStallStats.record(liveNanos = 1000 * ms, stallInNanos = 600 * ms, stallOutNanos = 500 * ms)
        val s = RelayStallStats.snapshotAndReset()!!
        assertEquals(60, s.inPct)
        assertEquals(50, s.outPct)
        // 两段占比之和可以超过 100%：同一时刻两个方向可以都在等写入，不是错误
        assertEquals(100, s.maxPct)  // 单条的合计占比封顶 100
    }

    @Test
    fun `时长加权：长隧道不被大量短隧道稀释掉极值`() {
        // 100 条 1ms 的短连接完全不堵 + 1 条 1000ms 的隧道全程堵
        repeat(100) { RelayStallStats.record(1 * ms, 0, 0) }
        RelayStallStats.record(1000 * ms, 900 * ms, 0)
        val s = RelayStallStats.snapshotAndReset()!!
        assertEquals(101, s.tunnels)
        // 均值被稀释：900/(100+1000) ≈ 81%
        assertEquals(81, s.inPct)
        // 但极值把「有一条隧道几乎全程卡住」这件事保住了 —— 这正是 maxStall 存在的理由
        assertEquals(90, s.maxPct)
    }

    @Test
    fun `窗口取走即清零`() {
        RelayStallStats.record(100 * ms, 50 * ms, 0)
        assertEquals(50, RelayStallStats.snapshotAndReset()!!.inPct)
        assertNull(RelayStallStats.snapshotAndReset())   // 第二次没有新样本
    }

    @Test
    fun `零时长样本被忽略(不制造除零与假占比)`() {
        RelayStallStats.record(liveNanos = 0, stallInNanos = 5 * ms, stallOutNanos = 0)
        assertNull(RelayStallStats.snapshotAndReset())
    }
}
