package com.mzstd.hxmyproxy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleEngine
import com.mzstd.hxmyproxy.data.repository.RuleRepository
import org.junit.Assert.assertEquals
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
                userDirectRules = setOf("googlesyndication.com"),
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
}
