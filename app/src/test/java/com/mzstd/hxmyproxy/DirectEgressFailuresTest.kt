package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.proxy.DirectEgressFailures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 直连失败计数（[DirectEgressFailures]）。
 *
 * 它守的是防护页那张「N 个设为直连的域名连不上」的卡片是否可信。两种错法都很难自查：
 * - 该清没清 → 早已恢复的域名一直挂在 UI 上，用户改回「走代理」反而是被误导；
 * - 该报没报 → 又回到 fail-closed 静默的老样子，用户只知道"这个 app 有时候卡"。
 */
class DirectEgressFailuresTest {

    @Before fun setUp() = DirectEgressFailures.reset()
    @After fun tearDown() = DirectEgressFailures.reset()

    @Test fun `未达阈值不报——偶发抖动不该惊动用户`() {
        repeat(DirectEgressFailures.MIN_FAILS_TO_REPORT - 1) {
            DirectEgressFailures.recordFailure("a.test", "远程连接超时")
        }
        assertTrue(DirectEgressFailures.snapshot().isEmpty())
    }

    @Test fun `达到阈值才报，并带上次数与原因`() {
        repeat(DirectEgressFailures.MIN_FAILS_TO_REPORT) {
            DirectEgressFailures.recordFailure("a.test", "远程连接超时")
        }
        val e = DirectEgressFailures.snapshot().single()
        assertEquals("a.test", e.host)
        assertEquals(DirectEgressFailures.MIN_FAILS_TO_REPORT, e.fails)
        assertEquals("远程连接超时", e.lastError)
    }

    @Test fun `一次成功即清零，而不是递减`() {
        // 计数语义是**连续**失败：连上了就说明当前是通的，不该还留着历史失败次数
        // ——否则一个偶尔抽风的域名会永远挂在提示里。
        repeat(10) { DirectEgressFailures.recordFailure("a.test", "远程连接超时") }
        DirectEgressFailures.recordSuccess("a.test")
        assertTrue(DirectEgressFailures.snapshot().isEmpty())

        // 清零后重新计数，仍需重新达到阈值才报
        DirectEgressFailures.recordFailure("a.test", "远程连接超时")
        assertTrue(DirectEgressFailures.snapshot().isEmpty())
    }

    @Test fun `forget 让条目立刻消失——用户改回走代理后不必等下次成功`() {
        repeat(5) { DirectEgressFailures.recordFailure("a.test", "远程连接超时") }
        assertEquals(1, DirectEgressFailures.snapshot().size)
        DirectEgressFailures.forget("a.test")
        assertTrue(DirectEgressFailures.snapshot().isEmpty())
    }

    @Test fun `多个 host 独立计数且按失败次数降序`() {
        repeat(9) { DirectEgressFailures.recordFailure("many.test", "远程连接超时") }
        repeat(4) { DirectEgressFailures.recordFailure("few.test", "DNS 解析失败") }
        val s = DirectEgressFailures.snapshot()
        assertEquals(listOf("many.test", "few.test"), s.map { it.host })
        assertEquals("DNS 解析失败", s[1].lastError)
        // 一个 host 恢复不影响另一个
        DirectEgressFailures.recordSuccess("many.test")
        assertEquals(listOf("few.test"), DirectEgressFailures.snapshot().map { it.host })
    }

    @Test fun `最近一次的错误原因会覆盖旧的`() {
        repeat(3) { DirectEgressFailures.recordFailure("a.test", "DNS 解析失败") }
        DirectEgressFailures.recordFailure("a.test", "远程连接超时")
        assertEquals("远程连接超时", DirectEgressFailures.snapshot().single().lastError)
    }

    @Test fun `host 数量有上限，超限淘汰最久未更新的`() {
        // 防止被大量一次性域名撑爆内存。淘汰按 lastAtMs，所以显式喂时间戳而不是靠真实时钟。
        for (i in 1..64) {
            repeat(3) { DirectEgressFailures.recordFailure("h$i.test", "远程连接超时", nowMs = i.toLong()) }
        }
        assertEquals(64, DirectEgressFailures.snapshot().size)
        // 第 65 个进来时，最旧的 h1 应被挤掉
        repeat(3) { DirectEgressFailures.recordFailure("new.test", "远程连接超时", nowMs = 999L) }
        val hosts = DirectEgressFailures.snapshot().map { it.host }
        assertTrue("新条目应在", "new.test" in hosts)
        assertTrue("最旧的应被淘汰", "h1.test" !in hosts)
        assertTrue("总数不超上限", hosts.size <= 64)
    }

    @Test fun `空 host 不记录——避免脏数据进 UI`() {
        repeat(5) { DirectEgressFailures.recordFailure("", "远程连接超时") }
        assertTrue(DirectEgressFailures.snapshot().isEmpty())
    }

    @Test fun `reset 清空全部——会话边界用`() {
        repeat(5) { DirectEgressFailures.recordFailure("a.test", "远程连接超时") }
        repeat(5) { DirectEgressFailures.recordFailure("b.test", "远程连接超时") }
        DirectEgressFailures.reset()
        assertTrue(DirectEgressFailures.snapshot().isEmpty())
    }
}
