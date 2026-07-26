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
}
