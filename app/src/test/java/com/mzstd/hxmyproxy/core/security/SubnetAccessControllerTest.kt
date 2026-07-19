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
}
