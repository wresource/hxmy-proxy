package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.security.DefaultEgressGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * [DefaultEgressGuard] 守护的是**反 SSRF 出口护栏**：代理替调用方发起连接，
 * 所以「目标地址」完全由外部输入决定——护栏漏一个网段，等于把手机所在局域网
 * 和手机自身开放给任何能连上代理的人。
 *
 * 错了会表现成什么：
 * - loopback 漏放行 → 外部通过代理 CONNECT 127.0.0.1:任意端口，打到本机其它 app 的
 *   本地监听端口（含 adb forward 出来的调试端口），用户完全无感。
 * - 链路本地漏放行 → 169.254.169.254 这类云元数据地址可达，是最经典的 SSRF 提权路径。
 * - 通配地址漏放行 → 0.0.0.0 在多数内核语义里等价于「本机」，是绕过 loopback 封禁的后门。
 * - 私网边界算错 → 要么用户开了「禁私网」却仍能扫内网，要么没开却连不上自家 NAS，
 *   两种都只在特定网段上出现，日志里看不出异常。
 *
 * 因此下面的用例集中在**分类边界**（网段的第一个/最后一个地址、刚好落在段外的邻居）
 * 与**开关无关性**（哪些禁止项无论开关怎么拨都必须禁止）。
 */
class EgressGuardTest {

    private fun addr(s: String) = InetAddress.getByName(s)

    @Test fun blocksLoopback() {
        assertFalse(DefaultEgressGuard().isAllowed(addr("127.0.0.1")))
    }

    @Test fun blocksLinkLocal() {
        assertFalse(DefaultEgressGuard().isAllowed(addr("169.254.10.10")))
    }

    @Test fun blocksMulticast() {
        assertFalse(DefaultEgressGuard().isAllowed(addr("224.0.0.251")))
    }

    @Test fun allowsPublicByDefault() {
        assertTrue(DefaultEgressGuard().isAllowed(addr("8.8.8.8")))
    }

    @Test fun allowsPrivateByDefault() {
        val g = DefaultEgressGuard()
        assertTrue(g.isAllowed(addr("192.168.1.5")))
        assertTrue(g.isAllowed(addr("10.0.0.5")))
        assertTrue(g.isAllowed(addr("172.16.5.5")))
        assertTrue(g.isAllowed(addr("100.64.0.1"))) // CGNAT treated as private
    }

    @Test fun blocksPrivateWhenConfigured() {
        val g = DefaultEgressGuard(blockPrivateLan = true)
        assertFalse(g.isAllowed(addr("192.168.1.5")))
        assertFalse(g.isAllowed(addr("10.0.0.5")))
        assertTrue(g.isAllowed(addr("8.8.8.8"))) // public still allowed
    }

    @Test fun blocksIpv6LoopbackAndLinkLocal() {
        val g = DefaultEgressGuard()
        assertFalse(g.isAllowed(addr("::1")))
        assertFalse(g.isAllowed(addr("fe80::1")))
    }

    // ---------------- 以下为补充用例：分类边界与开关无关性 ----------------

    /**
     * 通配地址 0.0.0.0 / :: 必须禁。它不是「无效地址」——connect 到 0.0.0.0 在 Linux 上
     * 等价于连本机，是绕开 loopback 封禁最省事的写法。原实现有这条分支但此前无用例覆盖。
     */
    @Test fun `通配地址等价于本机——v4 与 v6 都必须禁`() {
        val g = DefaultEgressGuard()
        assertFalse("0.0.0.0 未被拦截，等于留了一条打本机的后门", g.isAllowed(addr("0.0.0.0")))
        assertFalse(":: 未被拦截", g.isAllowed(addr("::")))
    }

    /**
     * loopback 是整个 127 段而不只是 127.0.0.1。攻击者写 127.1.2.3 一样能打到本机监听端口，
     * 若实现退化成字符串比对或只判 127.0.0.1，这条会红。
     */
    @Test fun `整个 127 段都算 loopback 而不只是 127 点 0 点 0 点 1`() {
        val g = DefaultEgressGuard()
        assertFalse(g.isAllowed(addr("127.1.2.3")))
        assertFalse(g.isAllowed(addr("127.255.255.254")))
    }

    /**
     * IPv4 映射写法 ::ffff:127.0.0.1 是绕过 loopback 封禁的经典手法：
     * 看起来是 IPv6 字面量，解析后仍指向本机。JDK 会把它归一成 Inet4Address，
     * 于是护栏天然覆盖——本用例把这个「天然」钉成契约，防止将来有人自己写解析绕开它。
     */
    @Test fun `IPv4 映射写法不能绕过 loopback 与私网封禁`() {
        val g = DefaultEgressGuard()
        assertFalse("::ffff:127.0.0.1 绕过了 loopback 封禁", g.isAllowed(addr("::ffff:127.0.0.1")))
        val blocking = DefaultEgressGuard(blockPrivateLan = true)
        assertFalse("::ffff:192.168.1.10 绕过了禁私网", blocking.isAllowed(addr("::ffff:192.168.1.10")))
    }

    /** 云元数据地址：SSRF 里危害最大的单个目标，必须被链路本地规则挡住。 */
    @Test fun `云元数据地址 169 点 254 点 169 点 254 被挡`() {
        assertFalse(DefaultEgressGuard().isAllowed(addr("169.254.169.254")))
    }

    /** IPv6 组播（ff00::/8）与 v4 组播同等对待；此前只覆盖了 v4 的 224 段。 */
    @Test fun `IPv6 组播同样被禁`() {
        val g = DefaultEgressGuard()
        assertFalse(g.isAllowed(addr("ff02::1")))
        assertFalse(g.isAllowed(addr("ff05::c")))
    }

    /**
     * loopback、链路本地、通配、组播是**无条件禁止**项：它们与 blockPrivateLan 开关无关。
     * 这是本模块最核心的一条语义——如果哪天有人把 isPrivate 判断挪到最前面，
     * 或者把这几条塞进 `if (blockPrivateLan)` 里，用户「关掉禁私网」就会顺手打开本机后门。
     */
    @Test fun `无条件禁止项与 blockPrivateLan 开关无关`() {
        val alwaysBlocked = listOf("127.0.0.1", "::1", "169.254.1.1", "fe80::1", "0.0.0.0", "::", "224.0.0.251", "ff02::1")
        for (flag in listOf(false, true)) {
            val g = DefaultEgressGuard(blockPrivateLan = flag)
            for (s in alwaysBlocked) {
                assertFalse("blockPrivateLan=$flag 时 $s 被放行了", g.isAllowed(addr(s)))
            }
        }
    }

    /**
     * RFC1918 的 172.16/12 段边界：段外的 172.15.255.255 / 172.32.0.0 是**公网**，
     * 误判成私网会导致用户开了「禁私网」后连不上这些公网地址，且完全不知道为什么。
     */
    @Test fun `172 点 16 段的私网边界前后各差一个地址`() {
        val g = DefaultEgressGuard(blockPrivateLan = true)
        assertTrue("172.15.255.255 是公网，不该被禁私网误伤", g.isAllowed(addr("172.15.255.255")))
        assertFalse(g.isAllowed(addr("172.16.0.0")))
        assertFalse(g.isAllowed(addr("172.31.255.255")))
        assertTrue("172.32.0.0 是公网，不该被禁私网误伤", g.isAllowed(addr("172.32.0.0")))
    }

    /**
     * CGNAT 100.64/10 是手写的字节判断（o0==100 && o1 in 64..127），最容易写错边界。
     * 100.63.x 与 100.128.x 是公网，100.64.0.0 与 100.127.255.255 是段内首尾。
     */
    @Test fun `CGNAT 100 点 64 段的首尾与段外邻居`() {
        val g = DefaultEgressGuard(blockPrivateLan = true)
        assertTrue("100.63.255.255 在 CGNAT 段外", g.isAllowed(addr("100.63.255.255")))
        assertFalse(g.isAllowed(addr("100.64.0.0")))
        assertFalse(g.isAllowed(addr("100.127.255.255")))
        assertTrue("100.128.0.0 在 CGNAT 段外", g.isAllowed(addr("100.128.0.0")))
        // 只判第二字节不判第一字节的写法会在这里露馅：99.64.0.1 与 101.64.0.1 都是公网。
        assertTrue(g.isAllowed(addr("99.64.0.1")))
        assertTrue(g.isAllowed(addr("101.64.0.1")))
    }

    /**
     * IPv6 ULA fc00::/7 走的是掩码判断 `(b0 and 0xFE) == 0xFC`，同样是手写位运算。
     * fc00:: 与 fd..:: 在段内；fbff:: 与 fe00:: 在段外且都不是链路本地，必须放行。
     */
    @Test fun `IPv6 ULA fc00 段算私网且段外邻居不受影响`() {
        val open = DefaultEgressGuard()
        val blocking = DefaultEgressGuard(blockPrivateLan = true)
        for (s in listOf("fc00::1", "fd12:3456::1", "fdff:ffff::1")) {
            assertTrue("$s 默认应放行", open.isAllowed(addr(s)))
            assertFalse("$s 属 ULA，开了禁私网应被挡", blocking.isAllowed(addr(s)))
        }
        // 段外：fbff:: 与 fe00:: 既不是 ULA 也不是链路本地（fe00 的第二字节不满足 fe80::/10）
        for (s in listOf("fbff::1", "fe00::1")) {
            assertTrue("$s 在 ULA 段外，禁私网不该误伤", blocking.isAllowed(addr(s)))
        }
    }

    /** IPv6 站点本地 fec0::/10 由 isSiteLocalAddress 覆盖，同样归入私网口径。 */
    @Test fun `IPv6 站点本地 fec0 段归入私网`() {
        assertTrue(DefaultEgressGuard().isAllowed(addr("fec0::1")))
        assertFalse(DefaultEgressGuard(blockPrivateLan = true).isAllowed(addr("fec0::1")))
    }

    /**
     * blockPrivateLan 是 @Volatile var，设置页改动会热更新到**同一个** guard 实例上，
     * 不重建对象。若哪天被改成构造期快照（比如挪进 val 或缓存判定结果），
     * 用户在设置里拨开关就会「要重启代理才生效」——本用例把热生效钉死。
     */
    @Test fun `运行期翻转开关立即改变私网裁决`() {
        val g = DefaultEgressGuard()
        val lan = addr("192.168.1.5")
        assertTrue(g.isAllowed(lan))
        g.blockPrivateLan = true
        assertFalse("翻开关后未立即生效", g.isAllowed(lan))
        g.blockPrivateLan = false
        assertTrue("翻回来后未恢复", g.isAllowed(lan))
        assertTrue("公网目标全程不受开关影响", g.isAllowed(addr("8.8.8.8")))
    }

    /** 公网 IPv6 默认放行——否则开了 IPv6 的网络会整体不可用。 */
    @Test fun `公网 IPv6 默认放行且不受禁私网影响`() {
        val dns = addr("2001:4860:4860::8888")
        assertTrue(DefaultEgressGuard().isAllowed(dns))
        assertTrue(DefaultEgressGuard(blockPrivateLan = true).isAllowed(dns))
    }
}
