package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.rules.DomainSuffixSet
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleEngine
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
                userDirect = DomainSuffixSet().apply { addSuffix("ads.allow.com") },  // 用户放行
                reject = DomainSuffixSet().apply { addSuffix("ads.allow.com"); addSuffix("ad.net") },
                direct = DomainSuffixSet().apply { addSuffix("cn.example") },
                proxy = DomainSuffixSet().apply { addSuffix("google.com") },
            ),
        )
        assertEquals(RuleAction.DIRECT, e.decide("x.ads.allow.com"))  // 用户白名单覆盖广告
        assertEquals(RuleAction.REJECT, e.decide("track.ad.net"))    // 广告 REJECT
        assertEquals(RuleAction.DIRECT, e.decide("www.cn.example"))  // 直连大类
        assertEquals(RuleAction.PROXY, e.decide("mail.google.com"))  // 代理大类
        assertEquals(RuleAction.PROXY, e.decide("unknown.org"))      // 兜底代理
    }
}
