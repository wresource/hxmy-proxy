package com.mzstd.hxmyproxy

import android.net.Network
import com.mzstd.hxmyproxy.core.network.EgressHealth
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出口网健康判定。**核心是「按域名去重」**——判据必须能区分
 * 「某个站点坏了」和「整张网坏了」，否则一个客户端自己重试几次就能把好网误判掉。
 *
 * 阈值来自 0806 日志的实测形态：
 * 正常期每分钟不同失败域名从未超过 1；故障期（08-06 02:00–02:40）是 4–6 个、
 * 40 分钟内涉及 13 个互不相干的域名。取 4 落在两段之间且靠近故障侧。
 */
class EgressHealthTest {

    private var now = 1_000_000L
    private fun net(id: Long) = mockk<Network>().also { every { it.networkHandle } returns id }

    /**
     * **同一域名反复失败不得触发判定** —— 这是最容易踩的坑：
     * 日志里 api.anthropic.com 单域名就失败了 7 次，而当时其他域名都是通的。
     * 不去重的话这 7 次就会把一张好网摘掉。
     */
    @Test fun `同一域名反复失败不触发`() {
        val h = EgressHealth(probe = { true }, nowMs = { now })
        val n = net(1)
        repeat(10) { assertFalse("同一域名第 $it 次不该触发", h.recordFailure(n, "api.anthropic.com")) }
    }

    /** 达到 4 个不同域名才触发。前 3 个都不该动。 */
    @Test fun `四个不同域名才触发`() {
        val h = EgressHealth(probe = { true }, nowMs = { now })
        val n = net(2)
        assertFalse(h.recordFailure(n, "a.com"))
        assertFalse(h.recordFailure(n, "b.com"))
        assertFalse(h.recordFailure(n, "c.com"))
        assertTrue("第 4 个不同域名应触发探测", h.recordFailure(n, "d.com"))
    }

    /** 窗口外的旧失败不计入——否则一整天零星攒够 4 个也会误触发。 */
    @Test fun `窗口外的旧失败不计入`() {
        val h = EgressHealth(probe = { true }, nowMs = { now })
        val n = net(3)
        h.recordFailure(n, "a.com"); h.recordFailure(n, "b.com"); h.recordFailure(n, "c.com")
        now += EgressHealth.WINDOW_MS + 1                     // 窗口滑过去
        assertFalse("旧的三个已过期，这个是新窗口的第一个", h.recordFailure(n, "d.com"))
    }

    /** 探测通过 = 不是我们的网的问题（比如某个 CDN 大面积故障）→ 绝不能摘。 */
    @Test fun `探测通过则不摘网`() = runBlocking {
        val h = EgressHealth(probe = { true }, nowMs = { now })
        val n = net(4)
        listOf("a.com", "b.com", "c.com", "d.com").forEach { h.recordFailure(n, it) }
        h.confirmOrSideline(n)
        assertFalse("探测通过说明网是好的", h.isSidelined(n))
    }

    /** 探测失败 → 摘掉；[EgressHealth.RECHECK_MS] 后应进入复检而不是永久摘着。 */
    @Test fun `探测失败则摘网且到点复检`() = runBlocking {
        var probeOk = false
        val h = EgressHealth(probe = { probeOk }, nowMs = { now })
        val n = net(5)
        listOf("a.com", "b.com", "c.com", "d.com").forEach { h.recordFailure(n, it) }
        h.confirmOrSideline(n)
        assertTrue("探测失败应摘掉", h.isSidelined(n))
        assertFalse("还没到复检时刻", h.needsRecheck(n))

        now += EgressHealth.RECHECK_MS + 1
        assertTrue("到点应复检", h.needsRecheck(n))
        assertFalse("到点后不再算摘掉状态，交由复检决定", h.isSidelined(n))

        probeOk = true                                        // 网恢复了
        h.confirmOrSideline(n)
        assertFalse("探测通过应立即放回", h.isSidelined(n))
    }

    /** 不同网络各算各的——摘掉 VPN 不该连累 Wi-Fi。 */
    @Test fun `不同网络互不影响`() = runBlocking {
        val h = EgressHealth(probe = { false }, nowMs = { now })
        val a = net(6); val b = net(7)
        listOf("a.com", "b.com", "c.com", "d.com").forEach { h.recordFailure(a, it) }
        h.confirmOrSideline(a)
        assertTrue(h.isSidelined(a))
        assertFalse("另一张网不该被连累", h.isSidelined(b))
    }

    /** 切网后清账：旧结论不能带到新网络上。 */
    @Test fun `reset 后清账`() = runBlocking {
        val h = EgressHealth(probe = { false }, nowMs = { now })
        val n = net(8)
        listOf("a.com", "b.com", "c.com", "d.com").forEach { h.recordFailure(n, it) }
        h.confirmOrSideline(n)
        assertTrue(h.isSidelined(n))
        h.reset()
        assertFalse("切网后应清账", h.isSidelined(n))
    }

    /** 探测冷却：阈值持续命中时不该反复探测（探测自己也要建连）。 */
    @Test fun `探测有冷却`() = runBlocking {
        var probes = 0
        val h = EgressHealth(probe = { probes++; true }, nowMs = { now })
        val n = net(9)
        listOf("a.com", "b.com", "c.com", "d.com").forEach { h.recordFailure(n, it) }
        h.confirmOrSideline(n)
        assertEquals(1, probes)
        // 冷却期内再攒够也不该返回 true
        assertFalse("冷却期内不该再触发", h.recordFailure(n, "e.com"))
    }

    /**
     * **探测端口必须是 443,不能退回 53。**
     *
     * 这条守的是 0815 现场的教训。当时三个探测目标全是 53,理由「DNS 服务器可用性最高」
     * 听上去很合理——但它测的不是业务走的那条路。换 WiFi 后 VPN 进入「53 通、443 不通」
     * 的半死状态,于是:
     *   · 业务侧 100+ 条 443 建连失败,Chrome 一路 ERR_TUNNEL_CONNECTION_FAILED
     *   · 探测侧 53 通 ⇒ 判定「网是好的」⇒ 不摘 ⇒ 拿坏句柄硬撞了 12 分钟
     *   · 心跳里 probe=ok/ok 全程绿着
     * 为区分「站点坏」与「整张网坏」而建的机制,因为探错端口而站到了错误的一边。
     *
     * 443 是 CONNECT 隧道的实际承载端口,探它才代表业务可用性。
     * 若有人为了「更快/更稳」把它改回 53,这条会立刻变红。
     */
    @Test fun `探测端口必须覆盖业务实际使用的 443`() {
        val ports = EgressHealth.PROBE_TARGETS.map { it.second }.toSet()
        assertEquals("探测端口必须是 443(业务承载端口),53 通不代表 443 通", setOf(443), ports)
        // 跨厂商仍是硬要求:单一厂商自己故障时会把好网误判成坏网。
        assertTrue("探测目标至少三个且跨厂商", EgressHealth.PROBE_TARGETS.size >= 3)
        // **必须同时覆盖国内与国外**:DIRECT 走物理网(0816 实测国外 443 超时),
        // PROXY 走 VPN(国外目标才有判据意义)。只留一侧就会在另一侧误判。
        val ips = EgressHealth.PROBE_TARGETS.map { it.first }
        assertTrue(
            "必须保留国内目标,否则物理网出口会被误判为坏网(国外 443 在该链路上不可达)",
            ips.any { it.startsWith("223.") || it.startsWith("120.") || it.startsWith("101.") },
        )
        assertTrue(
            "必须保留国外目标,否则 VPN 出口缺少判据",
            ips.any { it == "1.1.1.1" || it == "8.8.8.8" },
        )
        assertEquals(
            "目标 IP 不得重复(重复等于减少了厂商多样性)",
            EgressHealth.PROBE_TARGETS.size,
            EgressHealth.PROBE_TARGETS.map { it.first }.toSet().size,
        )
    }
}
