package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.rules.DomainSuffixSet
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleDecision
import com.mzstd.hxmyproxy.core.rules.RuleEngine
import com.mzstd.hxmyproxy.core.rules.RuleMatcher
import com.mzstd.hxmyproxy.core.rules.RuleScope
import com.mzstd.hxmyproxy.core.rules.RuleSrc
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

    // ─────────────────────────────────────────────────────────────────────────
    // 以下为补充用例（合成数据，与 BuiltinRuleAssetsTest 的真实 assets 用例互补）：
    // 补的是原来没人管的分支 —— ovrProxy 档、救济表的「跳过而非放行」、单边命中、
    // BUILTIN_PROXY/DEFAULT 两种来源的区分、空 host、热替换与版本号。
    // 这些分支一旦坏掉都不会崩，只会让判定悄悄变成另一个值。
    // ─────────────────────────────────────────────────────────────────────────

    private fun m(vararg rules: String) = RuleMatcher().apply { rules.forEach { add(it) } }

    /**
     * per-host 覆盖的**第三档**（手动指定走代理）必须真的生效，且排在直连/拦截之后。
     * 防护页上「指定走代理」是三态里唯一没有旧用例覆盖的一档：它若失效，用户点了没反应，
     * 而域名会安静地落回内置表判定 —— 界面上仍显示「已指定」。
     */
    @Test fun `覆盖表的代理档生效且排在直连与拦截之后`() {
        val e = RuleEngine()
        e.update(
            RuleEngine.Snapshot(
                ovrProxy = m("p.example"),
                reject = m("p.example"),          // 内置广告表同样命中，覆盖必须压过它
            ),
        )
        assertEquals(RuleAction.PROXY, e.decide("x.p.example"))
        assertEquals(RuleSrc.OVERRIDE, e.decideDetailed("x.p.example").src)

        // 三态短路顺序：direct > reject > proxy（同一 host 落在多张覆盖表里时）
        e.update(RuleEngine.Snapshot(ovrDirect = m("h.example"), ovrReject = m("h.example"), ovrProxy = m("h.example")))
        assertEquals(RuleAction.DIRECT, e.decide("h.example"))
        e.update(RuleEngine.Snapshot(ovrReject = m("h.example"), ovrProxy = m("h.example")))
        assertEquals(RuleAction.REJECT, e.decide("h.example"))
    }

    /**
     * 救济表只是「**跳过**广告表」，不等于「放行为直连」。
     *
     * 这条差别很容易在重构时被抹平（顺手写成命中救济表就 return DIRECT）。抹平后，
     * 救济表里的域名会绕过内置直连组直接判 DIRECT —— 而 DIRECT 意味着代理出站绑物理网络、
     * 绕开共享 VPN，egress=VPN 时还会 fail-closed 断开。用户表现是「加进救济表的域名反而连不上」。
     */
    @Test fun `救济表只是跳过广告表而不是放行为直连`() {
        val e = RuleEngine()
        // 只被救济、下游没有任何直连组接住 → 兜底 PROXY/DEFAULT（不是 DIRECT）
        e.update(RuleEngine.Snapshot(adsAllow = m("img.example"), reject = m("img.example")))
        assertEquals(RuleAction.PROXY, e.decide("img.example"))
        assertEquals(RuleSrc.DEFAULT, e.decideDetailed("img.example").src)

        // 下游有直连组时才落到 DIRECT，且来源是内置 App 组
        e.update(RuleEngine.Snapshot(adsAllow = m("img.example"), reject = m("img.example"), direct = m("example")))
        assertEquals(RuleAction.DIRECT, e.decide("img.example"))
        assertEquals(RuleSrc.BUILTIN_APP, e.decideDetailed("img.example").src)

        // 没被救济的仍旧被广告表拦下
        e.update(RuleEngine.Snapshot(adsAllow = m("img.example"), reject = m("ad.example"), direct = m("example")))
        assertEquals(RuleAction.REJECT, e.decide("x.ad.example"))
        assertEquals(RuleSrc.BUILTIN_ADS, e.decideDetailed("x.ad.example").src)
    }

    /**
     * 广告表优先于内置直连组 —— 救济表存在的**根本原因**就在这条顺序上。
     * 若哪天有人「顺手」把 direct 提到 reject 之前，救济表整套机制就成了摆设（且测试全绿）。
     */
    @Test fun `内置广告表优先于内置直连组`() {
        val e = RuleEngine()
        e.update(RuleEngine.Snapshot(reject = m("a.example"), direct = m("a.example")))
        assertEquals(RuleAction.REJECT, e.decide("a.example"))
        assertEquals(RuleSrc.BUILTIN_ADS, e.decideDetailed("a.example").src)
    }

    /**
     * 用户两张表只有一张命中时，直接采用该边，`userDirectNewer` 不得干预 ——
     * 它只是**平手**裁决用的。若被误用成通用优先级，「放行表更新」会让拦截表里
     * 唯一命中的规则彻底失效：用户加的拦截规则明明在列表里，却从不生效。
     */
    @Test fun `用户表单边命中时不受新旧标志影响`() {
        listOf(true, false).forEach { newer ->
            val onlyBlock = RuleEngine().apply {
                update(RuleEngine.Snapshot(userReject = m("b.example"), userDirectNewer = newer))
            }
            assertEquals("newer=$newer", RuleAction.REJECT, onlyBlock.decide("x.b.example"))
            assertEquals(RuleSrc.USER_BLOCK, onlyBlock.decideDetailed("x.b.example").src)

            val onlyAllow = RuleEngine().apply {
                update(RuleEngine.Snapshot(userDirect = m("a.example"), userDirectNewer = newer))
            }
            assertEquals("newer=$newer", RuleAction.DIRECT, onlyAllow.decide("x.a.example"))
            assertEquals(RuleSrc.USER_ALLOW, onlyAllow.decideDetailed("x.a.example").src)
        }
    }

    /**
     * PROXY 有两种来源，排障时必须分得清：命中内置代理组，还是没人管的兜底。
     * 「为什么这个域名走了代理」只有来源能回答；两者混为一谈，用户关掉某个组后
     * 看到判定没变，就无从判断是组没生效还是本来就该兜底。
     */
    @Test fun `内置代理组与兜底都判代理但来源不同`() {
        val e = RuleEngine()
        e.update(RuleEngine.Snapshot(proxy = m("g.example")))
        assertEquals(RuleDecision(RuleAction.PROXY, RuleSrc.BUILTIN_PROXY), e.decideDetailed("mail.g.example"))
        assertEquals(RuleDecision(RuleAction.PROXY, RuleSrc.DEFAULT), e.decideDetailed("unknown.example"))
    }

    /**
     * 空 / 空白 host 必须安全落到兜底，既不崩也不被判成命中。
     * CONNECT 行解析异常、URL 缺 Host 头时这里真的会收到空串；若空串能命中某张表
     * （历史上空串规则进过字典树就会这样），会变成「一批异常请求被静默拦掉」。
     */
    @Test fun `空 host 落到兜底而不是被误判命中`() {
        val e = RuleEngine()
        e.update(
            RuleEngine.Snapshot(
                ovrDirect = m("a.example"), userReject = m("b.example"),
                reject = m("c.example"), direct = m("d.example"), proxy = m("e.example"),
            ),
        )
        listOf("", "   ", ".").forEach {
            assertEquals("host=「$it」", RuleAction.PROXY, e.decide(it))
            assertEquals("host=「$it」", RuleSrc.DEFAULT, e.decideDetailed(it).src)
        }
    }

    /**
     * 热替换：[RuleEngine.update] 后判定立刻按新表走，且 version 自增供 UI 重判。
     * version 不动的话，规则页/监控页的判定列会停在旧结论 —— 用户改了规则却看不到变化，
     * 只能靠重启应用；而引擎其实早就换了表，两边显示还不一致。
     */
    @Test fun `热替换后判定立即生效且版本号自增`() {
        val e = RuleEngine()
        assertEquals(0, e.version.value)
        assertEquals(RuleAction.PROXY, e.decide("x.example"))

        e.update(RuleEngine.Snapshot(userReject = m("x.example")))
        assertEquals(1, e.version.value)
        assertEquals(RuleAction.REJECT, e.decide("x.example"))

        e.update(RuleEngine.Snapshot())          // 清空（如用户关掉所有组）
        assertEquals(2, e.version.value)
        assertEquals("清空后必须回到兜底，不能留着上一份快照", RuleAction.PROXY, e.decide("x.example"))
    }
}
