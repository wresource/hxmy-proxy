package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.network.Subnets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * IPv4 子网几何（[Subnets]）。
 *
 * 这三个函数原本是 ProxyServerRepository 里的 private 方法，位运算边界**一行都没测过**。
 * 它们错了的表现都不是崩溃，而是静默走偏：
 * - subnetBroadcast 算错 → 存在通告（15s 一次的定向广播）发到错误地址，
 *   沿路转发表刷不到，「客户端连不上」的自愈手段静默失效；
 * - inSubnets 判错 → 失联检测的探测范围出错：把换网后的旧网段 IP 当成本网内目标去探
 *   （单播经默认路由外泄），或把真客户端排除在探测之外（该告警时不告警）。
 */
class SubnetsTest {

    private fun ip(s: String): InetAddress = InetAddress.getByName(s)

    // ==================== ipv4Int ====================

    @Test fun `IPv4 转整数按大端且高位不被符号污染`() {
        // 255 开头最容易暴露「Byte 有符号」的错误：不 and 255 的话会得到负数高位。
        assertEquals(0, Subnets.ipv4Int(ip("0.0.0.0")))
        assertEquals(-1, Subnets.ipv4Int(ip("255.255.255.255")))   // 0xFFFFFFFF
        assertEquals(0xC0A80101.toInt(), Subnets.ipv4Int(ip("192.168.1.1")))
        assertEquals(0x0A000001, Subnets.ipv4Int(ip("10.0.0.1")))
    }

    @Test fun `IPv6 返回 null 而不是截断成前四字节`() {
        assertNull(Subnets.ipv4Int(ip("2001:db8::1")))
        assertNull(Subnets.ipv4Int(ip("::1")))
    }

    // ==================== subnetBroadcast ====================

    @Test fun `常见前缀的定向广播地址`() {
        assertEquals(ip("192.168.1.255"), Subnets.subnetBroadcast(ip("192.168.1.100"), 24))
        assertEquals(ip("192.168.255.255"), Subnets.subnetBroadcast(ip("192.168.1.100"), 16))
        assertEquals(ip("10.255.255.255"), Subnets.subnetBroadcast(ip("10.1.2.3"), 8))
    }

    @Test fun `非整字节前缀也要算对`() {
        // /23 跨两个 C 段：192.168.0.x 与 192.168.1.x 同网，广播是 192.168.1.255。
        assertEquals(ip("192.168.1.255"), Subnets.subnetBroadcast(ip("192.168.0.5"), 23))
        // /25 只覆盖下半段。
        assertEquals(ip("192.168.1.127"), Subnets.subnetBroadcast(ip("192.168.1.1"), 25))
        assertEquals(ip("192.168.1.255"), Subnets.subnetBroadcast(ip("192.168.1.200"), 25))
    }

    @Test fun `31 位前缀仍有效——它是边界内的最后一档`() {
        // 点对点链路常用 /31。条件是 `prefix !in 1..31`，31 必须留在有效侧，
        // 写成 1..30 就会让这类接口拿不到通告目标（静默少发广播）。
        assertEquals(ip("192.168.1.1"), Subnets.subnetBroadcast(ip("192.168.1.0"), 31))
    }

    @Test fun `32 与 0 前缀返回 null——这是刻意保留的行为`() {
        // /32 没有广播地址；/0 会算出受限广播 255.255.255.255，不是「子网定向广播」的有效目标。
        // 若有人「顺手」把范围放宽成 0..32，存在通告就会开始往全网广播地址发包。
        assertNull(Subnets.subnetBroadcast(ip("192.168.1.1"), 32))
        assertNull(Subnets.subnetBroadcast(ip("192.168.1.1"), 0))
    }

    @Test fun `越界与负前缀返回 null`() {
        assertNull(Subnets.subnetBroadcast(ip("192.168.1.1"), -1))
        assertNull(Subnets.subnetBroadcast(ip("192.168.1.1"), 33))
    }

    @Test fun `IPv6 地址返回 null`() {
        assertNull(Subnets.subnetBroadcast(ip("2001:db8::1"), 64))
    }

    // ==================== inSubnets ====================

    /** 网段字面量 → (网络号字节, 前缀)。字节表示同时适用 v4 与 v6。 */
    private fun net(cidr: String): Pair<ByteArray, Int> {
        val (a, p) = cidr.split("/")
        return ip(a).address to p.toInt()
    }

    @Test fun `落在子网内与不在子网内`() {
        val subnets = listOf(net("192.168.1.0/24"))
        assertTrue(Subnets.inSubnets(ip("192.168.1.1"), subnets))
        assertTrue(Subnets.inSubnets(ip("192.168.1.254"), subnets))
        assertFalse(Subnets.inSubnets(ip("192.168.2.1"), subnets))
        assertFalse(Subnets.inSubnets(ip("10.0.0.1"), subnets))
    }

    @Test fun `多个子网任一命中即可`() {
        val subnets = listOf(net("192.168.1.0/24"), net("10.0.0.0/8"))
        assertTrue(Subnets.inSubnets(ip("10.5.6.7"), subnets))
        assertTrue(Subnets.inSubnets(ip("192.168.1.9"), subnets))
        assertFalse(Subnets.inSubnets(ip("172.16.0.1"), subnets))
    }

    @Test fun `空子网列表一律不匹配——没有入口就没有可探目标`() {
        assertFalse(Subnets.inSubnets(ip("192.168.1.1"), emptyList()))
    }

    @Test fun `前缀小于等于 0 时任何地址都算在网内——刻意保留的当前行为`() {
        // mask 为 0，比较恒成立。这是 entrySubnets 混入异常前缀时的既有行为；
        // 改掉会改变失联判定的探测范围，所以钉在这里，将来若要改必须是有意识的决定。
        val degenerate = listOf(ByteArray(4) to 0)
        assertTrue(Subnets.inSubnets(ip("8.8.8.8"), degenerate))
        assertTrue(Subnets.inSubnets(ip("192.168.1.1"), degenerate))
    }

    @Test fun `32 位前缀只匹配自身`() {
        val host = listOf(net("192.168.1.50/32"))
        assertTrue(Subnets.inSubnets(ip("192.168.1.50"), host))
        assertFalse(Subnets.inSubnets(ip("192.168.1.51"), host))
    }

    @Test fun `v6 地址不匹配 v4 子网——靠字节长度隔离两个地址族`() {
        assertFalse(Subnets.inSubnets(ip("2001:db8::1"), listOf(net("192.168.1.0/24"))))
    }

    // ==================== IPv6 ====================

    /**
     * 入站 IPv6 一直进不来的真正卡点是**网段表示法**：原先是 Pair<Int, Int>，
     * 32 位装不下 128 位地址，于是 v6 接口连准入集都进不去。
     * 换成字节数组后这一组才有意义。
     */
    @Test fun `v6 落在子网内与不在子网内`() {
        val subnets = listOf(net("2001:db8:1::/64"))
        assertTrue(Subnets.inSubnets(ip("2001:db8:1::1"), subnets))
        assertTrue(Subnets.inSubnets(ip("2001:db8:1::ffff"), subnets))
        assertFalse(Subnets.inSubnets(ip("2001:db8:2::1"), subnets))
    }

    @Test fun `v6 唯一地址前缀 128 只匹配自身`() {
        val host = listOf(net("fd00::50/128"))
        assertTrue(Subnets.inSubnets(ip("fd00::50"), host))
        assertFalse(Subnets.inSubnets(ip("fd00::51"), host))
    }

    @Test fun `v6 非 8 整除的前缀要按位比而不是按字节比`() {
        // /60 的最后一个字节只比高 4 位：2001:db8:0:10::/60 覆盖 ...:10:: 到 ...:1f::
        val subnets = listOf(net("2001:db8:0:10::/60"))
        assertTrue(Subnets.inSubnets(ip("2001:db8:0:10::1"), subnets))
        assertTrue(Subnets.inSubnets(ip("2001:db8:0:1f::1"), subnets))
        assertFalse(Subnets.inSubnets(ip("2001:db8:0:20::1"), subnets))
    }

    @Test fun `v4 地址不匹配 v6 子网——反方向也要隔离`() {
        assertFalse(Subnets.inSubnets(ip("192.168.1.1"), listOf(net("2001:db8::/32"))))
    }

    @Test fun `v4 与 v6 网段混在一起时各自命中各自的`() {
        val mixed = listOf(net("192.168.1.0/24"), net("fd00::/8"))
        assertTrue(Subnets.inSubnets(ip("192.168.1.5"), mixed))
        assertTrue(Subnets.inSubnets(ip("fd00::abcd"), mixed))
        assertFalse(Subnets.inSubnets(ip("10.0.0.1"), mixed))
        assertFalse(Subnets.inSubnets(ip("2001:db8::1"), mixed))
    }
}
