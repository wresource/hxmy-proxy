package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.rules.DomainSuffixSet
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleEngine
import com.mzstd.hxmyproxy.core.rules.RuleMatcher
import com.mzstd.hxmyproxy.core.rules.RuleScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {

    @Test fun suffixMatchesSelfAndSubdomains() {
        val s = DomainSuffixSet().apply { addSuffix("example.com") }
        assertTrue(s.matches("example.com"))
        assertTrue(s.matches("a.example.com"))
        assertTrue(s.matches("a.b.example.com"))
        assertTrue(s.matches("EXAMPLE.com"))       // 大小写无关
        assertTrue(s.matches("example.com."))      // 尾点
        assertFalse(s.matches("notexample.com"))   // 不是子域
        assertFalse(s.matches("example.com.cn"))   // 不同后缀
        assertFalse(s.matches("com"))
    }

    @Test fun exactMatchesOnlySelf() {
        val s = DomainSuffixSet().apply { addExact("example.com") }
        assertTrue(s.matches("example.com"))
        assertFalse(s.matches("a.example.com"))    // 子域不命中
        assertFalse(s.matches("com"))
    }

    @Test fun suffixAndExactCoexist() {
        val s = DomainSuffixSet().apply { addExact("a.example.com"); addSuffix("example.com") }
        assertTrue(s.matches("a.example.com"))     // 两种都命中
        assertTrue(s.matches("z.example.com"))     // 后缀覆盖其它子域
    }

    @Test fun emptyAndMalformed() {
        val s = DomainSuffixSet().apply { addSuffix("example.com") }
        assertFalse(s.matches(""))
        assertFalse(s.matches("."))
        assertFalse(s.matches("a..b"))
        assertFalse(DomainSuffixSet().matches("example.com"))  // 空集恒 false
    }

    @Test fun decideDefaultsToProxy() {
        val e = RuleEngine()
        assertEquals(RuleAction.PROXY, e.decide("anything.example"))
        assertEquals(RuleAction.PROXY, e.decide("1.2.3.4"))    // IP 兜底走代理
    }

    @Test fun decidePriority() {
        val e = RuleEngine()
        e.update(
            RuleEngine.Snapshot(
                ovrReject = RuleMatcher().apply { add("ovr.reject.com") },              // per-host 覆盖：最高优先
                userDirect = RuleMatcher().apply { add("ads.allow.com"); add("ovr.reject.com") },  // 用户放行
                reject = RuleMatcher().apply { add("ads.allow.com"); add("ad.net") },
                direct = RuleMatcher().apply { add("cn.example") },
                proxy = RuleMatcher().apply { add("google.com") },
            ),
        )
        assertEquals(RuleAction.REJECT, e.decide("x.ovr.reject.com"))  // per-host 覆盖压过用户白名单（最高优先）
        assertEquals(RuleAction.DIRECT, e.decide("x.ads.allow.com"))  // 用户白名单覆盖广告
        assertEquals(RuleAction.REJECT, e.decide("track.ad.net"))    // 广告 REJECT
        assertEquals(RuleAction.DIRECT, e.decide("www.cn.example"))  // 直连大类
        assertEquals(RuleAction.PROXY, e.decide("mail.google.com"))  // 代理大类
        assertEquals(RuleAction.PROXY, e.decide("unknown.org"))      // 兜底代理
    }

    /**
     * 三档作用域(2026-07-28 用户拍板)：
     * `apple.com` 全层级 / `*.apple.com` 自身+恰好一级 / `=apple.com` 仅自身。
     * 无前缀维持全层级是硬约束——内置 6.6 万条全靠它。
     */
    @Test fun threeScopesDefineHowDeepARuleReaches() {
        val suffix = RuleMatcher().apply { add("apple.com") }
        assertTrue(suffix.matches("apple.com"))
        assertTrue(suffix.matches("xx.apple.com"))
        assertTrue(suffix.matches("xx.yy.apple.com"))       // 任意深度

        val single = RuleMatcher().apply { add("*.apple.com") }
        assertTrue(single.matches("apple.com"))             // 含自身
        assertTrue(single.matches("xx.apple.com"))
        assertFalse(single.matches("xx.yy.apple.com"))      // 二级不含 —— 与全层级的唯一区别

        val exact = RuleMatcher().apply { add("=apple.com") }
        assertTrue(exact.matches("apple.com"))
        assertFalse(exact.matches("xx.apple.com"))
        assertFalse(exact.matches("xx.yy.apple.com"))
        // 注：IP/CIDR 分支在本地单测里测不了——isReturnDefaultValues 让 android.net.InetAddresses
        // 的 isNumericAddress 恒返回 false，IP 规则会被误当域名。IP 匹配由设备侧验证。
    }

    /** 具体度 = 锚定标签数：越深越具体，供 most-specific-wins 用。 */
    @Test fun specificityIsAnchorDepth() {
        val m = RuleMatcher().apply { add("apple.com"); add("xxx.apple.com") }
        assertEquals(3, m.matchSpecificity("xxx.apple.com"))   // 命中更深的那条
        assertEquals(2, m.matchSpecificity("yyy.apple.com"))   // 只命中 apple.com
        assertEquals(-1, m.matchSpecificity("other.org"))
    }

    /**
     * most-specific-wins：用户放行/拦截同级，谁更具体谁赢，与添加先后无关。
     * 这正是用户举的例子：先加 `*.apple.com` 放行、后加 `xxx.apple.com` 拦截 → xxx 应被拦。
     */
    @Test fun userRulesResolveByMostSpecific() {
        val e = RuleEngine()
        e.update(
            RuleEngine.Snapshot(
                userDirect = RuleMatcher().apply { add("*.apple.com") },
                userReject = RuleMatcher().apply { add("xxx.apple.com") },
                userDirectNewer = true,   // 放行表更"新"，但具体度应压过它
            ),
        )
        assertEquals(RuleAction.REJECT, e.decide("xxx.apple.com"))   // 锚定 3 级 > 2 级
        assertEquals(RuleAction.DIRECT, e.decide("yyy.apple.com"))   // 只命中 *.apple.com
        assertEquals(RuleAction.DIRECT, e.decide("apple.com"))       // 单级含自身
        assertEquals(RuleAction.PROXY, e.decide("xx.yy.apple.com"))  // 单级不含二级 → 兜底

        // 反向：先具体后宽泛，具体的那条不被抹掉(B 方案相对纯时间序的价值所在)
        val e2 = RuleEngine()
        e2.update(
            RuleEngine.Snapshot(
                userDirect = RuleMatcher().apply { add("*.apple.com") },
                userReject = RuleMatcher().apply { add("secret.apple.com") },
                userDirectNewer = true,   // 放行是后加的
            ),
        )
        assertEquals(RuleAction.REJECT, e2.decide("secret.apple.com"))
    }

    /** 具体度完全相同（同锚定同档位）才按「最近添加的表」裁决。 */
    @Test fun equalSpecificityFallsBackToRecency() {
        val snapshot = { newer: Boolean ->
            RuleEngine.Snapshot(
                userDirect = RuleMatcher().apply { add("tie.com") },
                userReject = RuleMatcher().apply { add("tie.com") },
                userDirectNewer = newer,
            )
        }
        assertEquals(RuleAction.DIRECT, RuleEngine().apply { update(snapshot(true)) }.decide("tie.com"))
        assertEquals(RuleAction.REJECT, RuleEngine().apply { update(snapshot(false)) }.decide("tie.com"))
    }

    /** 作用域前缀往返：parse/format 互逆，且无前缀维持 SUFFIX（历史数据零迁移的前提）。 */
    @Test fun scopeParseAndFormatRoundTrip() {
        listOf(
            "apple.com" to RuleScope.SUFFIX,
            "*.apple.com" to RuleScope.SINGLE,
            "=apple.com" to RuleScope.EXACT,
        ).forEach { (text, expected) ->
            val (scope, bare) = RuleScope.parse(text)
            assertEquals(expected, scope)
            assertEquals("apple.com", bare)
            assertEquals(text, RuleScope.format(scope, bare))
        }
    }

    /** 用户规则整体仍低于防护页 per-host 覆盖、高于一切内置表。 */
    @Test fun userScopeRulesStillSitBetweenOverrideAndBuiltins() {
        val e = RuleEngine()
        e.update(
            RuleEngine.Snapshot(
                ovrReject = RuleMatcher().apply { add("=top.example") },
                userDirect = RuleMatcher().apply { add("top.example") },  // 更泛但层级更低
                reject = RuleMatcher().apply { add("ads.example") },
                direct = RuleMatcher().apply { add("cn.example") },
            ),
        )
        assertEquals(RuleAction.REJECT, e.decide("top.example"))     // per-host 覆盖最高
        assertEquals(RuleAction.DIRECT, e.decide("x.top.example"))   // 覆盖是精确档，子域回落用户放行
        assertEquals(RuleAction.REJECT, e.decide("t.ads.example"))   // 内置广告
        assertEquals(RuleAction.DIRECT, e.decide("w.cn.example"))
    }
}
