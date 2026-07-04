package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.rules.DomainSuffixSet
import com.mzstd.hxmyproxy.core.rules.RuleMatcher
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * 出厂内置规则数据的真实路由验证（纯 JVM，直接读 shipped assets 灌进真实
 * [DomainSuffixSet] / [RuleEngine]）——不依赖设备/模拟器，证明发布数据经真实
 * 引擎代码正确分流。锁屏时这就是比 UI 截图更硬的证据。
 */
class BuiltinRuleAssetsTest {

    private val assetsDir = File("src/main/assets/rules")

    private fun load(name: String): DomainSuffixSet {
        val set = DomainSuffixSet()
        File(assetsDir, name).forEachLine { line ->
            val d = line.trim()
            if (d.isNotEmpty() && d[0] != '#') set.addSuffix(d)
        }
        return set
    }

    /** 每个 app-*.txt 都存在、非空、且每行都是合法域名（addSuffix 接受）。 */
    @Test
    fun allAppAssetsPresentNonEmptyAndValid() {
        val files = assetsDir.listFiles { f -> f.name.startsWith("app-") && f.name.endsWith(".txt") }
        requireNotNull(files) { "assets/rules 目录不存在" }
        assertTrue("app-* 规则文件应 >= 60 个，实际 ${files.size}", files.size >= 60)
        var totalDomains = 0
        val domainRx = Regex("^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,}$")
        for (f in files) {
            val lines = f.readLines().map { it.trim() }.filter { it.isNotEmpty() && it[0] != '#' }
            assertTrue("${f.name} 不应为空", lines.isNotEmpty())
            for (d in lines) {
                assertTrue("${f.name} 含非法域名: $d", domainRx.matches(d))
            }
            totalDomains += lines.size
        }
        assertTrue("内置域名总量应 >= 5000，实际 $totalDomains", totalDomains >= 5000)
    }

    /** 后缀匹配语义：列表里的域名自身及其任意子域都命中，无关域名不命中。 */
    @Test
    fun suffixMatchingRoutesKnownDomainsAndSubdomains() {
        val cases = mapOf(
            "app-tencent.txt" to listOf("qq.com", "mail.qq.com", "wx2.qq.com", "im.qq.com"),
            "app-icbc.txt" to listOf("icbc.com.cn", "mybank.icbc.com.cn"),
            "app-alipay.txt" to listOf("alipay.com", "mobilecodec.alipay.com"),
            "app-neteasemusic.txt" to listOf("music.163.com"),
            "app-mihoyo.txt" to listOf("mihoyo.com", "api-takumi.mihoyo.com"),
            "app-bilibili.txt" to listOf("bilibili.com"),
        )
        for ((file, hosts) in cases) {
            val set = load(file)
            for (h in hosts) {
                assertTrue("$file 应命中 $h（自身或子域）", set.matches(h))
            }
        }
    }

    /** 无关域名绝不被内置组误命中（防误杀回归）。 */
    @Test
    fun unrelatedDomainsDoNotMatch() {
        val tencent = load("app-tencent.txt")
        listOf("google.com", "example.com", "cloudflare.com", "github.com").forEach {
            assertFalse("tencent 组不应误命中 $it", tencent.matches(it))
        }
    }

    /** 端到端：把内置直连组灌进 RuleEngine，decide() 对命中域名返回 DIRECT、其余兜底 PROXY。 */
    @Test
    fun ruleEngineDecidesDirectForBuiltinDirectGroups() {
        val direct = RuleMatcher()
        // 模拟 RuleRepository：把多个已启用直连组合并进 direct 集
        listOf("app-tencent.txt", "app-icbc.txt", "app-mihoyo.txt").forEach { f ->
            File(assetsDir, f).forEachLine { line ->
                val d = line.trim(); if (d.isNotEmpty() && d[0] != '#') direct.add(d)
            }
        }
        val engine = RuleEngine()
        engine.update(RuleEngine.Snapshot(direct = direct))

        assertEquals(RuleAction.DIRECT, engine.decide("mail.qq.com"))
        assertEquals(RuleAction.DIRECT, engine.decide("mybank.icbc.com.cn"))
        assertEquals(RuleAction.DIRECT, engine.decide("api-takumi.mihoyo.com"))
        // 未启用的组 / 无关域名 → 兜底 PROXY
        assertEquals(RuleAction.PROXY, engine.decide("google.com"))
        assertEquals(RuleAction.PROXY, engine.decide("192.168.1.1"))
    }
}
