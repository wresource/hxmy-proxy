package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.LinkStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 丢包率的**口径**（[LinkStats]）。
 *
 * 这条指标存在的理由是：时延窗口只收成功样本，失败的探测根本不进去，于是
 * 「p50 很漂亮但一半的包丢了」在时延数字上完全看不出来 —— 8-01 真机日志正是这个形状
 * （p50 <20ms 占 34.8%，而自愈突发实测每 10 发只回 3~5 发）。
 *
 * 真实的窗口累积逻辑在 LinkProbe 内部（要真发探测包，JVM 里测不了），这里守的是
 * **阈值口径与展示契约**：什么数字该显示成什么颜色、样本不足时不能误报。
 */
class LinkLossStatsTest {

    @Test fun `阈值分档：绿 黄 红`() {
        // UI 按这两个常量分色（见 DashboardScreen 的丢包格与 LinkLossBanner）。
        assertTrue("黄线要低于红线", LinkStats.LOSS_WARN_PCT < LinkStats.LOSS_BAD_PCT)
        // 5% 是 TCP 开始明显重传、体感变卡的经验值；20% 是双跳放大的典型区间。
        assertEquals(5, LinkStats.LOSS_WARN_PCT)
        assertEquals(20, LinkStats.LOSS_BAD_PCT)
    }

    @Test fun `丢包率与时延是两个独立维度——不能互相推断`() {
        // 这正是 8-01 日志的形状：时延好看，丢包严重。若把两者耦合成一个"健康度"，
        // 这种情况就会被平均掉，用户永远看不到真正的病因。
        val good延迟坏丢包 = LinkStats(p50Ms = 12, p95Ms = 30, samples = 20, lossPct = 55, lossSamples = 20)
        assertTrue("时延在绿区", good延迟坏丢包.p50Ms < LinkStats.GOOD_MS)
        assertTrue("丢包在红区", good延迟坏丢包.lossPct >= LinkStats.LOSS_BAD_PCT)
    }

    @Test fun `样本数为 0 时不该被当成 0 丢包`() {
        // lossSamples=0 表示「还没探过」，UI 必须据此显示占位而不是绿色的 0%
        // ——否则刚启动的那几秒会给用户一个"链路完美"的假象。
        val 无样本 = LinkStats()
        assertEquals(0, 无样本.lossSamples)
        assertEquals(0, 无样本.lossPct)
    }

    @Test fun `全丢的链路仍要能报出丢包率——此时时延样本为空`() {
        // 全丢时 window 是空的（没有成功样本），若 stats() 按 window 判空返回 null，
        // 最该告警的情况反而什么都不显示。这里钉住这个契约。
        val 全丢 = LinkStats(p50Ms = 0, p95Ms = 0, samples = 0, lossPct = 100, lossSamples = 12)
        assertEquals(0, 全丢.samples)
        assertTrue("有丢包样本", 全丢.lossSamples > 0)
        assertEquals(100, 全丢.lossPct)
    }
}
