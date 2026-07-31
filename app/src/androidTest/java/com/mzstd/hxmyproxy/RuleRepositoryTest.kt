package com.mzstd.hxmyproxy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.model.RuleEntry
import com.mzstd.hxmyproxy.core.rules.IpCidrSet
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleEngine
import com.mzstd.hxmyproxy.data.repository.RuleRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机/模拟器上验证规则装载端到端：从真实 assets(gzip OISD 清单)装载 → 后缀树 → decide 判定。
 * 纯逻辑、不碰 UI,锁屏也能跑(am instrument)。
 */
@RunWith(AndroidJUnit4::class)
class RuleRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun oisdAdsLoadedAndRejected() {
        val engine = RuleEngine()
        RuleRepository(context, engine).rebuild(
            ProxySettings(enabledRuleGroups = setOf("ads-oisd-small")),
        )
        // 已知在 OISD small 表内的广告域名 → REJECT(后缀含子域)
        assertEquals(RuleAction.REJECT, engine.decide("googlesyndication.com"))
        assertEquals(RuleAction.REJECT, engine.decide("pagead2.googlesyndication.com"))
        // 普通域名 → 兜底 PROXY
        assertEquals(RuleAction.PROXY, engine.decide("example.com"))
        assertEquals(RuleAction.PROXY, engine.decide("wikipedia.org"))
    }

    @Test fun userAllowlistOverridesAds() {
        val engine = RuleEngine()
        RuleRepository(context, engine).rebuild(
            ProxySettings(
                enabledRuleGroups = setOf("ads-oisd-small"),
                userDirectRules = listOf(RuleEntry("googlesyndication.com")),
            ),
        )
        // 用户白名单优先级最高,覆盖广告表(防误杀)→ DIRECT
        assertEquals(RuleAction.DIRECT, engine.decide("pagead2.googlesyndication.com"))
    }

    @Test fun disabledGroupNoReject() {
        val engine = RuleEngine()
        RuleRepository(context, engine).rebuild(ProxySettings())  // 无启用组
        assertEquals(RuleAction.PROXY, engine.decide("googlesyndication.com"))
    }

    @Test fun appGroupLoadsAsDirect() {
        val engine = RuleEngine()
        RuleRepository(context, engine).rebuild(
            ProxySettings(enabledRuleGroups = setOf("app-neteasemusic")),
        )
        // App 服务组 → DIRECT(命中域名走出口分流、绕过共享 VPN)
        assertEquals(RuleAction.DIRECT, engine.decide("music.163.com"))
        assertEquals(RuleAction.DIRECT, engine.decide("api.iplay.163.com"))
        assertEquals(RuleAction.PROXY, engine.decide("example.com"))
    }

    @Test fun userRuleSetDirectAndReject() {
        val engine = RuleEngine()
        RuleRepository(context, engine).rebuild(
            ProxySettings(
                userRuleSets = listOf(
                    com.mzstd.hxmyproxy.core.rules.UserRuleSet("1", "direct", RuleAction.DIRECT, listOf("example.com")),
                    com.mzstd.hxmyproxy.core.rules.UserRuleSet("2", "reject", RuleAction.REJECT, listOf("badsite.test")),
                    com.mzstd.hxmyproxy.core.rules.UserRuleSet("3", "off", RuleAction.DIRECT, listOf("disabled.test"), enabled = false),
                ),
            ),
        )
        assertEquals(RuleAction.DIRECT, engine.decide("a.example.com"))  // 泛域名后缀匹配
        assertEquals(RuleAction.REJECT, engine.decide("badsite.test"))
        assertEquals(RuleAction.PROXY, engine.decide("disabled.test"))   // 禁用集不生效
    }

    @Test fun builtinOverrideReplacesAsset() {
        val engine = RuleEngine()
        RuleRepository(context, engine).rebuild(
            ProxySettings(
                enabledRuleGroups = setOf("app-neteasemusic"),
                ruleSetOverrides = mapOf("app-neteasemusic" to listOf("custom.example")),
            ),
        )
        assertEquals(RuleAction.DIRECT, engine.decide("custom.example"))  // 覆盖版生效
        assertEquals(RuleAction.PROXY, engine.decide("music.163.com"))    // 原 assets 被覆盖、不再生效
    }

    /**
     * 记忆化早退：**无关字段变了不该重建，规则字段变了必须重建**。
     *
     * 后一半才是要害。漏掉任何一个规则输入字段，症状是「改了规则却不生效」——
     * UI 上开关一切正常，只有用户某天发现拦截/放行没按他改的来，属于最难自查的一类。
     * 所以这里逐个字段改一次、断言判定确实跟着变，而不是只测「改主题不重建」那半边。
     *
     * 用 decide 的结果间接观察是否重建过：跳过时引擎快照不变，判定自然也不变。
     */
    @Test fun rebuildSkipsUnrelatedChangesButNotRuleChanges() {
        val engine = RuleEngine()
        val repo = RuleRepository(context, engine)
        val base = ProxySettings(userRejectRules = listOf(RuleEntry("blocked.test")))
        repo.rebuild(base)
        assertEquals(RuleAction.REJECT, engine.decide("blocked.test"))
        val v0 = engine.version.value

        // ① 与规则无关的字段：不应重建（version 不动）——这正是「改主题花 1.1 秒」的那条路。
        repo.rebuild(base.copy(themeMode = com.mzstd.hxmyproxy.core.model.ThemeMode.DARK))
        repo.rebuild(base.copy(language = com.mzstd.hxmyproxy.core.model.AppLanguage.ENGLISH))
        repo.rebuild(base.copy(httpPort = 9090, hiddenTabs = setOf("monitor")))
        assertEquals("无关字段变更不应触发重建", v0, engine.version.value)

        // ② 九个规则输入字段：每一个变了都必须重建。
        var v = v0
        fun mustRebuild(label: String, s: ProxySettings) {
            repo.rebuild(s)
            assertTrue("$label 变了却没重建——规则会静默停在旧版本", engine.version.value > v)
            v = engine.version.value
        }
        mustRebuild("userRejectRules", base.copy(userRejectRules = listOf(RuleEntry("other.test"))))
        mustRebuild("userDirectRules", base.copy(userDirectRules = listOf(RuleEntry("allow.test"))))
        mustRebuild("userRejectEnabled", base.copy(userRejectEnabled = false))
        mustRebuild("userDirectEnabled", base.copy(userDirectEnabled = false))
        mustRebuild("enabledRuleGroups", base.copy(enabledRuleGroups = setOf("app-neteasemusic")))
        mustRebuild("rejectedGroups", base.copy(
            enabledRuleGroups = setOf("app-neteasemusic"), rejectedGroups = setOf("app-neteasemusic"),
        ))
        mustRebuild("ruleSetOverrides", base.copy(
            enabledRuleGroups = setOf("app-neteasemusic"),
            ruleSetOverrides = mapOf("app-neteasemusic" to listOf("ovr.test")),
        ))
        mustRebuild("hostOverrides", base.copy(hostOverrides = mapOf("h.test" to RuleAction.DIRECT)))
        mustRebuild("userRuleSets", base.copy(userRuleSets = listOf(
            com.mzstd.hxmyproxy.core.rules.UserRuleSet(id = "s1", name = "s", action = RuleAction.REJECT,
                domains = listOf("set.test")),
        )))
    }

    /**
     * 只改 addedAt / enabled 也必须重建 —— 这两项不进判定表却影响判定结果：
     * addedAt 决定同锚定深度平手时谁赢，enabled 决定条目是否装载。
     * 若 memo 只比对规则的字符串值，这两类变更会被吞掉。
     */
    @Test fun rebuildDetectsAddedAtAndEnabledChanges() {
        val engine = RuleEngine()
        val repo = RuleRepository(context, engine)
        val s = ProxySettings(userRejectRules = listOf(RuleEntry("x.test", addedAt = 1L)))
        repo.rebuild(s)
        val v0 = engine.version.value

        repo.rebuild(s.copy(userRejectRules = listOf(RuleEntry("x.test", addedAt = 2L))))
        assertTrue("addedAt 变了必须重建", engine.version.value > v0)
        val v1 = engine.version.value

        repo.rebuild(s.copy(userRejectRules = listOf(RuleEntry("x.test", addedAt = 2L, enabled = false))))
        assertTrue("enabled 变了必须重建", engine.version.value > v1)
    }

    /**
     * IP / CIDR 的识别**只能在设备上验**：它走 `android.net.InetAddresses.isNumericAddress`，
     * 而 JVM 单测里 android.jar 是 stub、配合 isReturnDefaultValues=true 会静默返回 false。
     * 后果不是报错而是**判定翻转**——IP 被当成域名，规则页点一个 IP 会写成 `*.192.168.1.1`
     * 这种永远匹配不上的死规则（MainViewModelTest 里实测到过，因此那条用例挪到了这里）。
     */
    @Test fun ipAndCidrRecognizedOnDevice() {
        assertTrue(IpCidrSet.looksLikeIpOrCidr("192.168.1.1"))
        assertTrue(IpCidrSet.looksLikeIpOrCidr("10.0.0.0/8"))
        assertTrue(IpCidrSet.looksLikeIpOrCidr("2001:db8::1"))
        assertFalse(IpCidrSet.looksLikeIpOrCidr("example.com"))
        assertFalse(IpCidrSet.looksLikeIpOrCidr("*.example.com"))
    }
}
