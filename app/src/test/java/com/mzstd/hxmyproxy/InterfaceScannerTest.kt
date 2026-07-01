package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.network.InterfaceScanner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 验证「换 WiFi（IP 变、接口名不变）后选中仍保持」——修复「换 WiFi 中断需重启」的核心：
 * id 含 IP，换网后 id 变，若只按完整 id 比对选中会丢失；改为「接口名」比对后应容忍 IP 变化。
 * 用本机真实接口跑（纯 java.net，无需真机）；本机无可用 IPv4 接口时自动跳过。
 */
class InterfaceScannerTest {

    private val scanner = InterfaceScanner()

    @Test fun selectionSurvivesIpChangeViaInterfaceName() {
        val ifaces = scanner.scan(emptySet())
        assumeTrue("本机无可用 IPv4 接口，跳过", ifaces.isNotEmpty())
        val one = ifaces.first()
        // 模拟：用户之前选了该接口，但那时 IP 不同 → id 含旧/过期 IP，接口名相同
        val staleId = "${one.name}/203.0.113.99"   // TEST-NET-3，不可能是真实本机 IP
        val rescanned = scanner.scan(setOf(staleId))
        val same = rescanned.first { it.name == one.name && it.address == one.address }
        assertTrue("接口名匹配应让换 IP 后仍选中（修换 WiFi 中断）", same.isSelected)
    }

    @Test fun exactIdStillMatches() {
        val ifaces = scanner.scan(emptySet())
        assumeTrue("本机无可用 IPv4 接口，跳过", ifaces.isNotEmpty())
        val one = ifaces.first()
        val rescanned = scanner.scan(setOf(one.id))
        assertTrue("精确 id 仍应匹配", rescanned.first { it.id == one.id }.isSelected)
    }

    @Test fun unrelatedInterfaceNameNotSelected() {
        val ifaces = scanner.scan(emptySet())
        assumeTrue("本机无可用 IPv4 接口，跳过", ifaces.isNotEmpty())
        val rescanned = scanner.scan(setOf("nonexistent-iface-xyz/203.0.113.99"))
        assertFalse("不相关接口名不应被选中", rescanned.any { it.isSelected })
    }
}
