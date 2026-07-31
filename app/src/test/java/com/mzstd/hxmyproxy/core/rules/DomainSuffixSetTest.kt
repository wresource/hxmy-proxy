package com.mzstd.hxmyproxy.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DomainSuffixSet]：每一次连接判定都要过的域名字典树。纯 Kotlin，无 android.* 依赖，JVM 断言可信。
 *
 * 为什么值得下重手测：这里出错的两种形态**都不会报错、不会进日志**，
 * - **误伤**：把 `notapple.com` 当成 `apple.com` 的子域（纯字符串 endsWith 就会这样），
 *   于是一条内置规则悄悄殃及一片无关域名。6.6 万条内置数据 × 每条都可能误伤 ⇒ 面积极大，
 *   而用户只会觉得「某个网站偶尔打不开」，永远查不到是规则干的。
 * - **漏放**：标签边界算错一位，规则加了却永不命中，用户觉得「我明明加了却没生效」。
 *
 * [DomainSuffixSet.matchDepth] 返回的**锚定标签数**还是 most-specific-wins 的唯一输入
 * （见 RuleEngine.decideDetailed）：它算错，用户的放行/拦截谁压过谁就跟着错。
 *
 * 已有覆盖（RuleEngineTest 的 suffixMatchesSelfAndSubdomains / exactMatchesOnlySelf /
 * suffixAndExactCoexist / emptyAndMalformed）只测了 matches 的基本形状，本文件补的是
 * **边界与深度记账**：标签边界防误伤、回落到较浅规则、size 计数、归一化口径。
 */
class DomainSuffixSetTest {

    private fun suffixOf(vararg d: String) = DomainSuffixSet().apply { d.forEach { addSuffix(it) } }

    /**
     * 后缀档必须按**标签边界**匹配，不是字符串 endsWith。
     * 这是本文件最重要的一条：写错了就是大面积误杀，且极难察觉。
     */
    @Test
    fun `后缀档按标签边界匹配 尾巴相同的无关域名不能误伤`() {
        val s = suffixOf("apple.com")
        // 命中：自身与任意深度子域
        assertTrue(s.matches("apple.com"))
        assertTrue(s.matches("xx.apple.com"))
        assertTrue(s.matches("a.b.c.d.apple.com"))
        // 不命中：只是字符串上以 "apple.com" 结尾 / 只是共享了后半截
        listOf(
            "notapple.com",     // endsWith("apple.com") 为真 —— 纯字符串实现会在这里误杀
            "myapple.com",
            "xapple.com",
            "apple.com.cn",     // 另一个注册域
            "apple.company",    // 顶级标签不同
            "com",              // 父域不能被子域规则命中
            "appl.com",
        ).forEach { assertFalse("apple.com 不应命中 $it", s.matches(it)) }
    }

    /** 规则比 host 深时不得反向命中：`a.example.com` 管不到 `example.com`，也管不到兄弟子域。 */
    @Test
    fun `子域规则不会反向命中父域或兄弟域`() {
        val s = suffixOf("a.example.com")
        assertTrue(s.matches("a.example.com"))
        assertTrue(s.matches("x.a.example.com"))
        assertFalse(s.matches("example.com"))
        assertFalse(s.matches("b.example.com"))
        assertFalse(s.matches("ba.example.com"))   // 标签边界：不是 "a" 这一级
    }

    /** 单级档只多容忍一层 —— 与全层级的唯一区别，也是用户点域名设规则时的默认档。 */
    @Test
    fun `单级档只多容忍一层子域`() {
        val s = DomainSuffixSet().apply { addSingle("apple.com") }
        assertTrue(s.matches("apple.com"))          // 含自身
        assertTrue(s.matches("xx.apple.com"))
        assertFalse(s.matches("xx.yy.apple.com"))   // 二级不含
        assertFalse(s.matches("a.b.c.apple.com"))
    }

    /** 精确档一层都不容忍。 */
    @Test
    fun `精确档只命中自身`() {
        val s = DomainSuffixSet().apply { addExact("apple.com") }
        assertTrue(s.matches("apple.com"))
        assertFalse(s.matches("xx.apple.com"))
        assertFalse(s.matches("com"))
    }

    /**
     * 同一域名挂多档时取**最宽**的那档（三个标记位落在同一个节点上，谁先满足谁命中）。
     * 规则页允许同一域名以不同写法存在，这里锁死「加了更宽的档不会被更窄的档反向收紧」。
     */
    @Test
    fun `同域多档共存时按最宽的那档命中`() {
        val wide = DomainSuffixSet().apply { addExact("apple.com"); addSingle("apple.com"); addSuffix("apple.com") }
        assertTrue(wide.matches("a.b.c.apple.com"))      // 有 suffix 档就能到任意深度
        assertEquals(3, wide.size)                        // 三个档各计一条

        val narrow = DomainSuffixSet().apply { addExact("apple.com"); addSingle("apple.com") }
        assertTrue(narrow.matches("apple.com"))
        assertTrue(narrow.matches("x.apple.com"))
        assertFalse("没有 suffix 档就不该到二级", narrow.matches("x.y.apple.com"))
    }

    /** 具体度 = **规则锚定域名**的标签数，取命中里最深的那条；未命中是 -1（不是 0）。 */
    @Test
    fun `matchDepth 返回最深命中规则的锚定标签数`() {
        val s = suffixOf("apple.com", "xxx.apple.com")
        assertEquals(3, s.matchDepth("xxx.apple.com"))     // 两条都命中，取更深的
        assertEquals(3, s.matchDepth("a.xxx.apple.com"))
        assertEquals(2, s.matchDepth("yyy.apple.com"))     // 只命中 apple.com
        assertEquals(2, s.matchDepth("apple.com"))
        assertEquals(-1, s.matchDepth("other.org"))
    }

    /**
     * **深度记的是规则的锚定长度，不是 host 的长度** —— 这点最容易写反。
     * 写反了会让「host 越长越具体」，于是同一条泛规则对深子域凭空获得更高具体度，
     * 把用户另一张表里真正更具体的规则压掉：明明加了 `xxx.a.com` 拦截，深层子域却被放行。
     */
    @Test
    fun `深度记的是规则锚定长度而非 host 长度`() {
        val s = suffixOf("apple.com")
        assertEquals(2, s.matchDepth("apple.com"))
        assertEquals(2, s.matchDepth("x.apple.com"))
        assertEquals(2, s.matchDepth("x.y.z.w.apple.com"))   // host 再深，锚定仍是 2

        val single = DomainSuffixSet().apply { addSingle("apple.com") }
        assertEquals(2, single.matchDepth("x.apple.com"))
        assertEquals(-1, single.matchDepth("x.y.apple.com"))

        val exact = DomainSuffixSet().apply { addExact("apple.com") }
        assertEquals(2, exact.matchDepth("apple.com"))
        assertEquals(-1, exact.matchDepth("x.apple.com"))
    }

    /**
     * 走到更深的节点却因档位不满足而没命中时，必须**回落**到之前找到的较浅命中，而不是返回 -1。
     *
     * matchDepth 内部沿标签反向下行，中途遇到缺失标签会 `return best`。若把 best 丢了，
     * 表现是：给某个子域单独加了一条**精确**规则后，它的**更深**子域反而不再被父域的全层级规则管
     * —— 加一条规则把另一条弄失效，排查时几乎不可能想到这里。
     */
    @Test
    fun `深层节点不命中时回落到较浅的那条规则`() {
        val s = DomainSuffixSet().apply { addSuffix("example.com"); addExact("a.example.com") }
        assertEquals(3, s.matchDepth("a.example.com"))       // 精确档在 3 级
        assertEquals(2, s.matchDepth("b.a.example.com"))     // 精确档不满足 → 回落到 example.com
        assertTrue(s.matches("b.a.example.com"))
        // 树里根本没有这条分支时同样回落
        assertEquals(2, s.matchDepth("q.w.e.example.com"))
    }

    /** 大小写、尾点、首尾空白在**规则侧与查询侧**都要归一，两侧口径必须一致。 */
    @Test
    fun `大小写 尾点 空白在两侧都归一`() {
        val s = suffixOf("  APPLE.COM.  ")               // 规则侧带噪声
        assertTrue(s.matches("apple.com"))
        assertTrue(s.matches("X.Apple.Com"))
        assertTrue(s.matches("apple.com."))              // 查询侧尾点（DNS 绝对域名写法）
        assertTrue(s.matches("  apple.com..  "))
        assertEquals(s.matchDepth("apple.com"), s.matchDepth("APPLE.COM."))
        assertEquals(1, s.size)                           // 噪声不产生第二条
    }

    /** 畸形输入既不入表也不命中；空集恒 false。 */
    @Test
    fun `空串与畸形域名既不入表也不命中`() {
        val s = DomainSuffixSet()
        listOf("", "   ", ".", "..", "a..b", ".a.com", "a.com..b").forEach { s.addSuffix(it) }
        assertEquals("畸形域名不该占用条数", 0, s.size)
        listOf("", "   ", ".", "a..b", "anything.com").forEach {
            assertFalse("空集不该命中 $it", s.matches(it))
            assertEquals(-1, s.matchDepth(it))
        }
    }

    /**
     * size 是「已装载条数」的口径：同档重复添加只算一次，不同档各算一次。
     * 它出现在日志与规则组预览里；虚高会让「装载了 N 条」这个排障锚点失真
     * （以为表已就绪，实际全是被 normalize 丢掉的垃圾行）。
     */
    @Test
    fun `size 同档去重 不同档各计一次`() {
        val s = DomainSuffixSet()
        s.addSuffix("apple.com"); s.addSuffix("apple.com"); s.addSuffix("APPLE.com.")
        assertEquals(1, s.size)
        s.addSingle("apple.com")
        assertEquals(2, s.size)
        s.addExact("apple.com")
        assertEquals(3, s.size)
        s.addSuffix("")           // 非法：不计数
        assertEquals(3, s.size)
    }

    /**
     * 单标签规则会吞掉整个顶级域 —— 能力真实存在，故意锁住它的爆炸半径。
     * 这正是 MainViewModel.normalizeRule 拒绝单段输入（"com"）的原因：
     * 用户随手加一条 `com`，这里会老老实实地把全网都算成命中。
     */
    @Test
    fun `单标签规则覆盖整个顶级域`() {
        val s = suffixOf("com")
        assertTrue(s.matches("a.b.com"))
        assertEquals(1, s.matchDepth("a.b.com"))   // 锚定只有 1 级 —— 具体度最低，任何真规则都能压过它
        assertFalse(s.matches("a.b.cn"))
    }

    /** normalize 的独立口径（装载与查询共用它，改了会同时影响两侧）。 */
    @Test
    fun `normalize 小写去尾点去空白 空标签视为非法`() {
        assertEquals(listOf("a", "b"), DomainSuffixSet.normalize("  A.B..  "))
        assertEquals(listOf("apple", "com"), DomainSuffixSet.normalize("Apple.COM."))
        assertEquals(listOf("com"), DomainSuffixSet.normalize("com"))
        listOf("", "   ", ".", "...", "a..b", ".a").forEach {
            assertNull("「$it」应判为非法", DomainSuffixSet.normalize(it))
        }
        // 末尾点是合法写法（DNS 绝对域名），只去尾点，不算空标签
        assertEquals(listOf("a", "b"), DomainSuffixSet.normalize("a.b."))
    }
}
