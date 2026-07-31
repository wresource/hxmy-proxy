package com.mzstd.hxmyproxy.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RuleScope]：规则文本 ↔ (作用域, 裸域名) 的**唯一**翻译层。纯 Kotlin，无 android.* 依赖，
 * JVM 断言可信。
 *
 * 守护的三条语义，以及错了会表现成什么（全都是**不报错的**故障）：
 *
 * ① **无前缀 = 全层级**是历史契约。若哪天 [RuleScope.parse] 把裸域名当成精确档，
 *    内置 6.6 万条（广告表 + 65 个 App 组，全是裸域名写法）会一夜之间只匹配根域，
 *    `ad.qq.com` 这类深层子域集体漏网 —— 用户看到的是「广告拦截突然不管用了」，
 *    而规则页显示一切正常、开关都开着。
 *
 * ② **format 是 parse 的逆**。存进 ProxySettings.userDirectRules 的是 format 后的文本，
 *    读回来再 parse。一旦不互逆，「改作用域档位」（本质就是改前缀）会写出一条自己都读不回来
 *    的规则：列表里看着是 `*.a.com`，引擎里却装载成别的档，用户改了档位却毫无变化。
 *
 * ③ **裸域名与档位无关**。MainViewModel.sameHost 拿 `parse().second` 做 block/allow 互斥比较，
 *    `a.com` / `*.a.com` / `=a.com` 必须归一到同一个 bare。否则「设直连」移不掉拦截表里
 *    前缀写法不同的同名条目，两条并存后要靠具体度裁决 —— 用户明明点了直连，域名却仍被拦。
 */
class RuleScopeTest {

    /** ① 历史契约：不带前缀＝全层级。丢了它，6.6 万条内置数据集体降级为只匹配根域。 */
    @Test
    fun `无前缀一律解析为全层级`() {
        listOf("apple.com", "ad.qq.com", "a.b.c.example.com", "xn--fiqs8s").forEach { text ->
            val (scope, bare) = RuleScope.parse(text)
            assertEquals("「$text」必须按全层级解析", RuleScope.SUFFIX, scope)
            assertEquals(text, bare)   // 裸域名原样保留，不吞字符
        }
    }

    /** 三个前缀各自映射到对应档位；顺带覆盖「前缀本身」与 parse 的自洽（见下一条）。 */
    @Test
    fun `星点前缀是单级 等号前缀是精确`() {
        assertEquals(RuleScope.SINGLE to "apple.com", RuleScope.parse("*.apple.com"))
        assertEquals(RuleScope.EXACT to "apple.com", RuleScope.parse("=apple.com"))
    }

    /**
     * [RuleScope.prefix] 与 [RuleScope.parse] 必须自洽 —— 这条是「加档位时的护栏」：
     * 日后若新增第四档只加了 enum 常量、忘了在 parse 里加分支，新档写出的规则会被当成
     * 全层级装载（前缀连着域名一起进字典树 → 死规则或过度匹配），这里立刻变红。
     */
    @Test
    fun `每一档的前缀都能被自己解析回来`() {
        RuleScope.values().forEach { scope ->
            val text = RuleScope.format(scope, "apple.com")
            val (parsed, bare) = RuleScope.parse(text)
            assertEquals("档位 $scope 的前缀「${scope.prefix}」parse 不回来", scope, parsed)
            assertEquals(scope.toString(), "apple.com", bare)
        }
    }

    /** ② 双向互逆：(档位,裸域名) → 文本 → (档位,裸域名)，以及 文本 → 解析 → 文本。 */
    @Test
    fun `parse 与 format 对每一档都互逆`() {
        listOf("apple.com", "a.b.example.com").forEach { bare ->
            RuleScope.values().forEach { scope ->
                val text = RuleScope.format(scope, bare)
                assertEquals(scope to bare, RuleScope.parse(text))
                // 反向：从文本出发绕一圈也要回到同一串文本
                val (s2, b2) = RuleScope.parse(text)
                assertEquals(text, RuleScope.format(s2, b2))
            }
        }
    }

    /** ③ 互斥比较的地基：三种写法的裸域名必须相等，否则「设直连」清不掉拦截表里的同名条目。 */
    @Test
    fun `三种写法归一到同一个裸域名`() {
        val bares = listOf("apple.com", "*.apple.com", "=apple.com").map { RuleScope.parse(it).second }
        assertEquals(listOf("apple.com", "apple.com", "apple.com"), bares)
    }

    /**
     * 只有前缀、没有域名时裸域名为空 —— 调用方（[RuleMatcher.add] / MainViewModel.normalizeRule）
     * 据此丢弃。这条是**防止一条 `=` 变成匹配一切的口子**：若 parse 在这里返回个非空占位，
     * 空规则会被当成合法域名塞进字典树。
     */
    @Test
    fun `只有前缀没有域名时裸域名为空`() {
        listOf("=", "*.", "", "   ", " = ", " *. ").forEach { text ->
            assertTrue("「$text」应解析出空裸域名，实际「${RuleScope.parse(text).second}」",
                RuleScope.parse(text).second.isEmpty())
        }
    }

    /** 前缀与域名之间、整串首尾的空白都被吃掉；用户手打或粘贴带空格不会变成死规则。 */
    @Test
    fun `前缀与域名之间的空白被吃掉`() {
        assertEquals(RuleScope.EXACT to "apple.com", RuleScope.parse("= apple.com"))
        assertEquals(RuleScope.SINGLE to "apple.com", RuleScope.parse("*.  apple.com"))
        assertEquals(RuleScope.EXACT to "apple.com", RuleScope.parse("   =apple.com   "))
        assertEquals(RuleScope.SUFFIX to "apple.com", RuleScope.parse("  apple.com  "))
    }

    /**
     * parse **不**改变大小写：归一化只发生在匹配层（[DomainSuffixSet.normalize] 负责小写/去尾点）。
     * 写清楚职责边界，免得有人在 parse 里再补一次 lowercase —— 那会让规则列表把用户输入的
     * 原文改掉，而互斥比较又依赖 bare 逐字相等。
     */
    @Test
    fun `parse 不改变大小写`() {
        assertEquals(RuleScope.EXACT to "Apple.COM", RuleScope.parse("=Apple.COM"))
        assertEquals(RuleScope.SUFFIX to "Apple.COM", RuleScope.parse("Apple.COM"))
    }

    /**
     * 只剥**一层**前缀：`==a.com` / `=*.a.com` / `*apple.com`（漏了点）这些手滑写法不会被
     * 「聪明地」猜出意图。方向是对的 —— 剩下的怪字符会让规则落成永不命中的死规则（见
     * [RuleMatcherTest]），而不是过度匹配去误杀一片。宁可不生效，也不能误伤。
     */
    @Test
    fun `多余或残缺的前缀不会被二次解读`() {
        assertEquals(RuleScope.EXACT to "=a.com", RuleScope.parse("==a.com"))
        assertEquals(RuleScope.EXACT to "*.a.com", RuleScope.parse("=*.a.com"))
        // 星号后面漏了点：不是单级档，整串被当成域名
        assertEquals(RuleScope.SUFFIX to "*apple.com", RuleScope.parse("*apple.com"))
        assertEquals(RuleScope.SUFFIX to "*", RuleScope.parse("*"))
    }

    /**
     * 三档只有 prefix 一个维度，**没有权重字段**。
     *
     * 1.24.4 删掉了 `specificity`（精确 2 > 单级 1 > 全层级 0）：它从未被任何代码调用，
     * 且与产品模型冲突 —— 档位定的是作用范围（管多深），不是优先级。裁决只看锚定深度，
     * 锚定同一域名的几条一律平手，由「谁是用户最近的意图」决胜。
     * 详见 [RuleScope] 头部说明与 RuleMatcherTest 的「同锚定深度下档位不参与裁决」。
     *
     * 这条断言守的是「档位不应重新长出权重概念」：谁若再加一个排序字段，
     * 应当先回答「用户加的精确规则是否就该压过他后来加的宽泛规则」。
     */
    @Test
    fun `三档只有前缀这一个维度`() {
        assertEquals("", RuleScope.SUFFIX.prefix)
        assertEquals("*.", RuleScope.SINGLE.prefix)
        assertEquals("=", RuleScope.EXACT.prefix)
        assertEquals(3, RuleScope.entries.size)
    }
}
