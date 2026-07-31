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
