package com.mzstd.hxmyproxy.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RuleMatcher]：规则文本进引擎的**唯一入口**（分派 IP/域名、解析作用域前缀、算具体度）。
 *
 * ⚠️ 关于 IP 分支：本文件**只**断言域名分支。JVM 单测里 `android.net.InetAddresses` 是 stub，
 * 配合 `isReturnDefaultValues=true`，`isNumericAddress()` 恒返回 false —— 于是 IP 字面量
 * 会被当成域名走进字典树，任何「IP 规则是否生效」的断言在这里都是假的
 * （本项目栽过：expected `192.168.1.1` 实际 `.192.168.1.1`）。IP/CIDR 由设备侧
 * `RuleRepositoryTest.ipAndCidrRecognizedOnDevice` 覆盖。
 * 反过来说，正因为它恒 false，下面这些**纯域名**用例的行为是确定的、可信的。
 *
 * 守护的语义：
 * - 空规则 / 只有前缀的规则必须被丢弃 —— 这类残缺输入若被收下，就是一条「匹配一切」的口子；
 * - 手滑写法（`*apple.com` 漏点）只能变成**死规则**，绝不能变成过度匹配；
 * - [RuleMatcher.matchSpecificity] 未命中必须是 **-1 而不是 0**：RuleEngine 用 `>= 0` 判「有没有命中」，
 *   返回 0 会让**没写过任何规则**的域名被判成用户放行/拦截命中，全表语义崩塌。
 */
class RuleMatcherTest {

    private fun matcherOf(vararg rules: String) = RuleMatcher().apply { rules.forEach { add(it) } }

    /**
     * 残缺输入不得进表。若 `bare.isEmpty()` 这道闸没了，一条 `=` 或 `*.` 会落成空域名节点，
     * 表现是「用户什么都没写却整片域名被拦/被放行」——最坏的一类静默故障。
     */
    @Test
    fun `空规则与只有前缀的规则被丢弃`() {
        val m = matcherOf("", "   ", "=", "*.", "= ", " *. ", "\t")
        assertEquals("残缺规则不该进表", 0, m.size)
        listOf("anything.com", "a.b.c", "", "   ").forEach {
            assertFalse("空表不该命中 $it", m.matches(it))
            assertEquals(-1, m.matchSpecificity(it))
        }
    }

    /**
     * 手滑写法只会变成**死规则**，不会过度匹配。方向很重要：规则系统宁可某条不生效
     * （用户会发现「没生效」并去检查），也不能悄悄多拦一片（用户只会觉得网络时好时坏）。
     */
    @Test
    fun `星号后漏点只会变成死规则而不是过度匹配`() {
        val m = matcherOf("*apple.com")
        assertFalse(m.matches("apple.com"))
        assertFalse(m.matches("x.apple.com"))
        assertFalse(m.matches("apple.com.x"))
        assertEquals(-1, m.matchSpecificity("x.apple.com"))
        // 双等号同理：多剥出来的 "=" 留在域名里，永不命中
        assertFalse(matcherOf("==a.com").matches("a.com"))
    }

    /** 三档前缀在 add 时被分派到对应档位（用具体度侧面确认，与已有 matches 用例互补）。 */
    @Test
    fun `三种前缀写法分派到对应档位`() {
        assertTrue(matcherOf("apple.com").matches("x.y.apple.com"))       // 全层级：任意深度
        assertTrue(matcherOf("*.apple.com").matches("x.apple.com"))
        assertFalse(matcherOf("*.apple.com").matches("x.y.apple.com"))    // 单级：只多一层
        assertTrue(matcherOf("=apple.com").matches("apple.com"))
        assertFalse(matcherOf("=apple.com").matches("x.apple.com"))       // 精确：仅自身
    }

    /** 未命中是 -1；命中一定 > 0（锚定至少 1 级）。RuleEngine 的 `>= 0` 判定就靠这条分界。 */
    @Test
    fun `未命中返回负一而不是零`() {
        val m = matcherOf("apple.com")
        assertEquals(-1, m.matchSpecificity("other.org"))
        assertEquals(-1, m.matchSpecificity(""))
        assertTrue(m.matchSpecificity("x.apple.com") > 0)
    }

    /** 混档时取最深锚定：精确的 3 级压过单级的 2 级（同一张表内部取 max）。 */
    @Test
    fun `混档时具体度取最深的锚定`() {
        val m = matcherOf("*.apple.com", "=xxx.apple.com")
        assertEquals(3, m.matchSpecificity("xxx.apple.com"))   // 两条都命中，取深的
        assertEquals(2, m.matchSpecificity("yyy.apple.com"))   // 只命中 *.apple.com
        assertEquals(-1, m.matchSpecificity("y.y.apple.com"))  // 单级到不了二级，精确也不匹配
    }

    /**
     * **现状锁定**：同锚定深度下，档位不参与裁决。
     *
     * [RuleScope.specificity]（精确 2 > 单级 1 > 全层级 0）在全仓无人调用，
     * [RuleMatcher.matchSpecificity] 只返回锚定标签数。于是 `=a.com` 与 `a.com` 对 host `a.com`
     * 具体度完全相同 —— 落到 RuleEngine 里就走 `userDirectNewer` 平手裁决，
     * 而不是 RuleScope 文档承诺的「标签数相同再比档位」。
     *
     * 这条断言不是说现状正确，而是**把差异钉在明处**：谁去把档位接进裁决，这里会红，
     * 顺着注释就能找到 RuleScope 的文档与 RuleEngine 的平手分支，三处一起改。
     */
    @Test
    fun `同锚定深度下档位不参与裁决`() {
        val exact = matcherOf("=a.com")
        val suffix = matcherOf("a.com")
        val single = matcherOf("*.a.com")
        assertEquals(exact.matchSpecificity("a.com"), suffix.matchSpecificity("a.com"))
        assertEquals(single.matchSpecificity("a.com"), suffix.matchSpecificity("a.com"))
        assertEquals(2, exact.matchSpecificity("a.com"))
    }

    /** size 与 DomainSuffixSet 同口径：同档去重、不同档各计一条（规则组预览/日志的显示条数）。 */
    @Test
    fun `size 同档去重 不同档各计一条`() {
        val m = RuleMatcher()
        m.add("apple.com"); m.add("  APPLE.COM.  ")
        assertEquals(1, m.size)
        m.add("*.apple.com"); m.add("=apple.com")
        assertEquals(3, m.size)
    }

    /** 规则文本侧的大小写与空白同样归一（用户从网页复制粘贴常带这些）。 */
    @Test
    fun `规则文本的大小写与首尾空白不影响匹配`() {
        val m = matcherOf("  Apple.COM.  ", " = Example.COM ")
        assertTrue(m.matches("x.apple.com"))
        assertTrue(m.matches("EXAMPLE.com"))
        assertFalse(m.matches("x.example.com"))   // 精确档不因归一而放宽
    }
}
