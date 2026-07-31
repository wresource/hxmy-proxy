package com.mzstd.hxmyproxy.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * 准入 fail-closed 语义：允许集为空 → 拒绝全部；配置后仅放行选定接口地址上的连接。
 * 防回归：曾经空集=放行全部，导致「一个网段都没开却全网段可连」。
 */
class SubnetAccessControllerTest {

    private val ifaceA: InetAddress = InetAddress.getByName("192.168.1.10")
    private val ifaceB: InetAddress = InetAddress.getByName("10.0.0.5")
    private val client: InetAddress = InetAddress.getByName("192.168.1.99")

    @Test
    fun emptyAllowSetRefusesAll() {
        val c = SubnetAccessController()
        assertFalse(c.admit(ifaceA, client))
        assertFalse(c.admit(ifaceB, client))
    }

    @Test
    fun admitsOnlySelectedInterfaceAddress() {
        val c = SubnetAccessController()
        c.update(setOf(ifaceA))
        assertTrue(c.admit(ifaceA, client))
        assertFalse(c.admit(ifaceB, client))
    }

    @Test
    fun shrinkingToEmptyRefusesAgain() {
        val c = SubnetAccessController()
        c.update(setOf(ifaceA, ifaceB))
        assertTrue(c.admit(ifaceB, client))
        c.update(emptySet())
        assertFalse(c.admit(ifaceA, client))
        assertFalse(c.admit(ifaceB, client))
    }

    /**
     * loopback 不豁免：1.18.1 开发中曾为自探短路放行 127.0.0.1，review 推翻——「本机进程」
     * 包含全部第三方 app 与 adb forward，等于免认证暴露面，且打破「不开=全拒」与 UI 警示的
     * 一一对应。自探不需要放行（connect 在内核 backlog 层即成功）。此用例把语义钉死防再犯。
     */
    @Test
    fun loopbackIsNotExempt() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val c = SubnetAccessController()
        assertFalse(c.admit(loopback, loopback))          // 空集：连 loopback 一起拒
        c.update(setOf(ifaceA))
        assertFalse(c.admit(loopback, loopback))          // 非空集：loopback 仍不在允许集内
    }

    // ---------------- 以下为补充用例：判定口径与状态转换 ----------------

    /**
     * 判定必须按**地址值**而非对象同一性。真实路径上传进来的是 `Socket.getLocalAddress()`
     * 新解析出来的对象，与 update 时存进集合的那个绝不是同一个实例；
     * 若哪天把 Set 换成 IdentityHashMap 之类，线上表现是「明明开了网段却谁都连不上」，
     * 而单测里如果复用同一个对象是发现不了的——所以这里刻意另建一个等值对象。
     */
    @Test fun `按地址值判定而非对象同一性`() {
        val c = SubnetAccessController()
        c.update(setOf(InetAddress.getByName("192.168.1.10")))
        val freshlyParsed = InetAddress.getByName("192.168.1.10")
        assertTrue("等值但不同实例的地址被拒了", c.admit(freshlyParsed, client))
    }

    /**
     * 带主机名的 InetAddress 与不带主机名的等值对象必须互认。
     * accept 出来的地址通常没有主机名，而扫描接口时构造的可能带；
     * 若比较把 hostname 也算进去，会出现「某些网络下全拒」的偶发故障。
     */
    @Test fun `地址是否携带主机名不影响判定`() {
        val bytes = byteArrayOf(192.toByte(), 168.toByte(), 1, 10)
        val withHost = InetAddress.getByAddress("some-host", bytes)
        val withoutHost = InetAddress.getByName("192.168.1.10")

        val c = SubnetAccessController()
        c.update(setOf(withoutHost))
        assertTrue(c.admit(withHost, client))

        c.update(setOf(withHost))
        assertTrue(c.admit(withoutHost, client))
    }

    /**
     * update 是**整体替换**，不是并集累加。用户取消勾选一个网段后它必须立刻连不上；
     * 若实现写成 addAll，取消勾选将永远不生效——UI 显示已关、实际仍可代理，
     * 正是 1.8.7 拍板的「开关状态与连通性一一对应」被打破的样子。
     */
    @Test fun `update 是整体替换而不是并集累加`() {
        val c = SubnetAccessController()
        c.update(setOf(ifaceA))
        assertTrue(c.admit(ifaceA, client))
        c.update(setOf(ifaceB))
        assertFalse("换了选中集合后旧网段仍被放行，说明是累加而非替换", c.admit(ifaceA, client))
        assertTrue(c.admit(ifaceB, client))
    }

    /**
     * 远端地址不参与裁决——它可伪造，这正是类注释里「按本地接口地址归属」的理由。
     * 若哪天有人「顺手」加上按 remote 判断的分支，等于把安全决策建在攻击者可控的输入上。
     */
    @Test fun `远端地址不参与裁决——它可伪造`() {
        val c = SubnetAccessController()
        c.update(setOf(ifaceA))
        val remotes = listOf("192.168.1.99", "8.8.8.8", "127.0.0.1", "10.9.9.9", "::1")
        for (r in remotes) {
            val remote = InetAddress.getByName(r)
            assertTrue("本地接口在允许集内，远端 $r 不该影响放行", c.admit(ifaceA, remote))
            assertFalse("本地接口不在允许集内，远端 $r 不该换来放行", c.admit(ifaceB, remote))
        }
    }

    /**
     * 匹配的是接口自己的地址，**不是它所在的网段**。同网段的邻居地址不放行。
     * 类名带 Subnet 容易诱导人把它"修"成前缀匹配——那会让准入从「本机哪块网卡收的包」
     * 退化成「来自哪个网段」，而后者恰恰是可伪造的那一类信息。
     */
    @Test fun `同网段的其它地址不放行——匹配接口地址而非网段`() {
        val c = SubnetAccessController()
        c.update(setOf(ifaceA))                                  // 192.168.1.10
        assertFalse(c.admit(InetAddress.getByName("192.168.1.11"), client))
        assertFalse(c.admit(InetAddress.getByName("192.168.1.1"), client))
    }

    /** IPv6 接口地址同样受支持，且不会与 IPv4 混淆。 */
    @Test fun `IPv6 接口地址同样按值匹配`() {
        val v6 = InetAddress.getByName("2001:db8::1")
        val c = SubnetAccessController()
        c.update(setOf(v6))
        assertTrue(c.admit(InetAddress.getByName("2001:db8::1"), client))
        assertFalse(c.admit(InetAddress.getByName("2001:db8::2"), client))
        assertFalse("IPv4 不该命中 IPv6 条目", c.admit(ifaceA, client))
    }

    /**
     * 双栈监听时 accept 到的本地地址可能是 IPv4 映射写法。JDK 会把它归一成 Inet4Address，
     * 因此与允许集里的 IPv4 条目等价——若不等价，双栈环境下会整片连不上。
     */
    @Test fun `IPv4 映射写法与 IPv4 条目等价`() {
        val c = SubnetAccessController()
        c.update(setOf(ifaceA))                                  // 192.168.1.10
        assertTrue(c.admit(InetAddress.getByName("::ffff:192.168.1.10"), client))
    }
}
