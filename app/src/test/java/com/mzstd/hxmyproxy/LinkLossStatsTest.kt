package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.LinkStats
import com.mzstd.hxmyproxy.core.network.LinkProbe
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

    // ==================== lossPct 的口径:在线设备的丢失率,不是「设备在不在」 ====================
    //
    // 0816 两台设备的对照暴露了这个误读:A 机 loss=0% 全程,B 机 p50=28%/max=56%,
    // 同一个 WiFi、同一套探测。差别不在链路而在被探对象——A 探一直在用的 Mac,
    // B 探的客户端反复离线(inboundSilenceSec 到过 371 秒)。
    // 若把离线设备的失败照单计入,一台睡着的设备就能把丢包率拉到接近 100%。

    @Test fun `离线设备不该把丢包率拉满`() {
        LinkProbe.reset()
        val dead = "192.168.1.188"
        // 前 3 次失败照常入窗:那是真实的链路信号,不该被吞掉。
        repeat(3) { LinkProbe.record(dead, null) }
        assertEquals("头 3 次失败必须计入", 100, LinkProbe.stats()!!.lossPct)
        assertEquals(3, LinkProbe.stats()!!.lossSamples)
        // 之后这台设备已被判定「不在」,继续失败不再累加样本。
        repeat(20) { LinkProbe.record(dead, null) }
        assertEquals("离线设备的后续探测不得入窗", 3, LinkProbe.stats()!!.lossSamples)
    }

    @Test fun `一台设备离线不该污染另一台在线设备的丢包率`() {
        LinkProbe.reset()
        val dead = "192.168.1.188"
        val live = "192.168.1.56"
        repeat(3) { LinkProbe.record(dead, null) }        // 判定离线,入窗 3 次
        repeat(30) { LinkProbe.record(dead, null) }       // 离线期间大量失败,不入窗
        repeat(17) { LinkProbe.record(live, 12L) }        // 在线设备一直正常
        val s = LinkProbe.stats()!!
        assertEquals("窗口应为 3 次失败 + 17 次成功", 20, s.lossSamples)
        assertEquals("丢包率 = 3/20,不应被离线设备拉高", 15, s.lossPct)
    }

    @Test fun `设备恢复后重新计入`() {
        LinkProbe.reset()
        val k = "192.168.1.188"
        repeat(3) { LinkProbe.record(k, null) }
        repeat(10) { LinkProbe.record(k, null) }          // 离线期,不入窗
        assertEquals(3, LinkProbe.stats()!!.lossSamples)
        LinkProbe.record(k, 20L)                          // 恢复
        assertEquals("恢复的那次要入窗", 4, LinkProbe.stats()!!.lossSamples)
        LinkProbe.record(k, null)                         // 恢复后再失败,是真实信号
        assertEquals("恢复后的失败必须重新计入", 5, LinkProbe.stats()!!.lossSamples)
    }
}
