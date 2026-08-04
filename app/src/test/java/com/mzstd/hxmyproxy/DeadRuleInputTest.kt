package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleEngine
import com.mzstd.hxmyproxy.core.rules.RuleMatcher
import com.mzstd.hxmyproxy.core.rules.RuleScope
import com.mzstd.hxmyproxy.core.rules.RuleSrc
import com.mzstd.hxmyproxy.data.repository.CredentialStore
import com.mzstd.hxmyproxy.data.repository.EndpointHistoryRepository
import com.mzstd.hxmyproxy.data.repository.ManualResetPhase
import com.mzstd.hxmyproxy.data.repository.ProxyServerRepository
import com.mzstd.hxmyproxy.data.repository.SettingsRepository
import com.mzstd.hxmyproxy.ui.MainViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「静默死规则」穷举：能通过 [MainViewModel] 的 normalizeRule 校验、存进规则表、显示在规则页，
 * 却**永远匹配不上任何 host** 的输入形态。
 *
 * 起因：用户把 `https://arxiv.org/` 填进「放行直连」，规则页看得见，访问 arxiv.org 却照旧走代理。
 *
 * ## 这里测的是真实代码，不是抄一份逻辑
 * - normalizeRule 是 private，但走公开入口 [MainViewModel.addUserDirectRule] 命中的就是它本身
 *   （唯一校验点，MainViewModel:215-219）；存进去的字符串从 settingsFlow 读回来，与用户在规则页
 *   看到的、以及 RuleRepository 喂给引擎的是同一个值（RuleRepository:89 `userDirect.add(it.value)` 原样透传）。
 * - 匹配侧用真实的 [RuleMatcher] / [RuleEngine]，不做任何模拟。
 * - 唯一一处「抄」的是规则页输入框的 submit 变换（RulesScreen.kt:465-476），见 [uiSubmit] 的逐行对照。
 *
 * ## 这些断言固化的是**当前（有 bug 的）行为**，不是期望行为
 * `assertDead(...)` 的每一条都是「存下来了、却永远不生效」——它们本该在入口就被拒。
 * 修好校验后，①②③④⑤⑩ 各组会从 [assertDead] 变成 [assertRejected]，届时按组改断言即可：
 * **这份清单就是修复的验收清单**。而 [assertAlive] 的几条是红线，修完必须还是绿的。
 *
 * ## JVM 单测的已知盲区（务必别在这里断言）
 * android.jar 是 stub + isReturnDefaultValues=true ⇒ `android.net.InetAddresses.isNumericAddress`
 * 恒返回 false，于是 IP/CIDR 会被当域名走后缀树。所以本文件**只对域名形态下结论**，
 * IP/IPv6/CIDR 的真实分派由仪器测试 RuleRepositoryTest.ipAndCidrRecognizedOnDevice 覆盖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeadRuleInputTest {

    private companion object {
        const val NBSP = " "     // 不换行空格：网页复制的常客
        const val ZWSP = "​"     // 零宽空格
        const val LRM = "‎"      // 从左至右标记
        const val CYR_A = "а"    // 西里尔小写 а，与 ASCII a 同形
        const val IDEO_DOT = "。" // 全角句号 。
    }

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var settingsFlow: MutableStateFlow<ProxySettings>
    private lateinit var vm: MainViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        settingsFlow = MutableStateFlow(ProxySettings())
        val settingsRepo = mockk<SettingsRepository>(relaxed = true)
        every { settingsRepo.settings } returns settingsFlow
        every { settingsRepo.onboardingCompleted } returns MutableStateFlow(true)
        every { settingsRepo.domainHistory } returns flowOf(emptySet())
        coEvery { settingsRepo.update(any()) } answers {
            val transform = firstArg<(ProxySettings) -> ProxySettings>()
            settingsFlow.value = transform(settingsFlow.value)
        }
        val proxyRepo = mockk<ProxyServerRepository>(relaxed = true)
        every { proxyRepo.state } returns MutableStateFlow(com.mzstd.hxmyproxy.core.model.ShareState())
        every { proxyRepo.resetState } returns MutableStateFlow(ManualResetPhase.IDLE)
        val historyRepo = mockk<EndpointHistoryRepository>(relaxed = true)
        every { historyRepo.history } returns MutableStateFlow(emptyList())
        val credStore = mockk<CredentialStore>(relaxed = true)
        every { credStore.credentials } returns flowOf(CredentialStore.Credentials())
        vm = MainViewModel(settingsRepo, proxyRepo, historyRepo, credStore, RuleEngine())
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private val target = "arxiv.org"

    /** 把输入喂给**真实** normalizeRule；返回存进规则表的字符串，null = 被拒（没存下）。 */
    private fun store(input: String): String? {
        settingsFlow.value = ProxySettings()
        vm.addUserDirectRule(input)
        return settingsFlow.value.userDirectRules.singleOrNull()?.value
    }

    /** 一条存下来的规则装进真实 RuleMatcher 后：(实际装载条数, 是否命中 host)。 */
    private fun probe(stored: String, host: String = target): Pair<Int, Boolean> {
        val m = RuleMatcher()
        m.add(stored)
        return m.size to m.matches(host)
    }

    /** 端到端：只放这一条 userDirect 规则时，引擎对 host 的判定。 */
    private fun decide(stored: String, host: String = target): Pair<RuleAction, RuleSrc> {
        val engine = RuleEngine()
        engine.update(RuleEngine.Snapshot(userDirect = RuleMatcher().apply { add(stored) }))
        val d = engine.decideDetailed(host)
        return d.action to d.src
    }

    private data class Row(val input: String, val stored: String?, val loaded: Int, val hit: Boolean)

    private val rows = mutableListOf<Row>()

    private fun visible(s: String) = s
        .replace(NBSP, "<NBSP>").replace(ZWSP, "<ZWSP>").replace(LRM, "<LRM>")
        .replace(CYR_A, "<а>").replace(IDEO_DOT, "<。>")

    /** 存下来了、却永远匹配不到目标 host —— 静默死规则。 */
    private fun assertDead(input: String, host: String = target) {
        val stored = store(input)
        assertTrue("『${visible(input)}』本应通过校验并存进规则表，实际被拒", stored != null)
        val (n, hit) = probe(stored!!, host)
        rows += Row(input, stored, n, hit)
        assertFalse("『${visible(input)}』存成『${visible(stored)}』后竟然命中了 $host —— 结论需重写", hit)
        assertEquals(
            "『${visible(input)}』存成『${visible(stored)}』，引擎应落到兜底 PROXY",
            RuleAction.PROXY to RuleSrc.DEFAULT, decide(stored, host),
        )
    }

    /** 被 normalizeRule 挡掉（没进规则表）—— 这是好的。 */
    private fun assertRejected(input: String) {
        val stored = store(input)
        rows += Row(input, null, 0, false)
        assertNull("『${visible(input)}』本应被拒，实际存成了『${stored?.let { visible(it) }}』", stored)
    }

    /** 存下来且能真正命中 —— 正常规则，修复时不能误伤。 */
    private fun assertAlive(input: String, host: String = target) {
        val stored = store(input)
        assertTrue("『${visible(input)}』被拒了", stored != null)
        val (n, hit) = probe(stored!!, host)
        rows += Row(input, stored, n, hit)
        assertTrue("『${visible(input)}』存成『${visible(stored)}』却匹配不到 $host", hit)
    }

    private fun dump(title: String) {
        println("\n===== $title （命中列针对 host=$target 除非另注） =====")
        println(String.format("%-32s | %-32s | %s | %s", "输入", "存进规则表/规则页显示", "装载", "命中"))
        rows.forEach {
            println(String.format(
                "%-32s | %-32s | %4s | %s",
                visible(it.input), it.stored?.let { s -> visible(s) } ?: "—— 被拒，没存下 ——",
                if (it.stored == null) "-" else it.loaded.toString(),
                if (it.stored == null) "-" else if (it.hit) "命中" else "✗ 永不命中",
            ))
        }
        rows.clear()
    }

    // ==================== ① URL 形态：核心怀疑对象 ====================

    @Test fun `URL 形态全部通过校验并原样存入，永不匹配`() {
        // 「必须含 '.' 或 ':'」那条把 ':' 当成了 IPv6 的信号，而 URL 的 scheme 冒号照样满足它。
        listOf(
            "https://arxiv.org/",
            "https://arxiv.org",
            "http://arxiv.org",
            "http://arxiv.org/",
            "https://arxiv.org/pdf/2606.20161",
            "//arxiv.org",
            "HTTPS://ARXIV.ORG/",
        ).forEach { assertDead(it) }
        assertDead("https://www.arxiv.org/", "www.arxiv.org")
        dump("① URL 形态")
    }

    @Test fun `带 query 的 URL 反而被拒——靠的是等号那条校验，纯属巧合`() {
        // '=' 本是作用域前缀符，这里顺手挡下了带 query 的 URL；换成不含 '=' 的 query 就照样进得去。
        assertRejected("https://arxiv.org/abs?id=1")
        assertDead("https://arxiv.org/abs?id")
        dump("① URL + query")
    }

    // ==================== ② host 之外的附加成分 ====================

    @Test fun `端口、路径、用户名、引号、逗号列表都能存下且永不匹配`() {
        listOf(
            "arxiv.org:443",             // host:port
            "arxiv.org:80/pdf",
            "arxiv.org/pdf",             // host + path
            "arxiv.org/",                // 只多一个斜杠
            "user@arxiv.org",            // userinfo
            "\"arxiv.org\"",             // 复制时带上的引号
            "'arxiv.org'",
            "<arxiv.org>",
            "(arxiv.org)",
            "arxiv.org,openreview.net",  // 一次粘一串
            "arxiv.org;",
            "arxiv.org|openreview.net",
            "arxiv.org#",
        ).forEach { assertDead(it) }
        dump("② 附加成分")
    }

    @Test fun `IP 带端口也命中不了裸 IP`() {
        // JVM 里 isNumericAddress 恒 false，"1.2.3.4:8080" 走域名树；设备上它同样不是数字地址，
        // 走的还是域名树，而 host="1.2.3.4" 走 IP 表（空）。两条路径不同、结论一致：永不命中。
        val stored = store("1.2.3.4:8080")
        assertEquals("1.2.3.4:8080", stored)
        val (n, hit) = probe(stored!!, "1.2.3.4")
        println("\n② IP:port  存值=$stored  装载=$n  matches(1.2.3.4)=$hit")
        assertFalse(hit)
    }

    // ==================== ③ 点的位置：有的连字典树都进不去 ====================

    @Test fun `前导点与连续点存得下但连字典树都装不进去`() {
        // DomainSuffixSet.normalize 见到空标签直接返回 null ⇒ walkCreate 返回 null ⇒ size 仍是 0。
        // 这类规则在 rules.rebuilt 日志里连计数都不加一，排障时最迷惑。
        listOf(".arxiv.org", "..arxiv.org", "arxiv..org", ".", "..", "*..arxiv.org").forEach { input ->
            val stored = store(input)
            assertTrue("『$input』本应存下", stored != null)
            val (n, hit) = probe(stored!!)
            rows += Row(input, stored, n, hit)
            assertEquals("『$input』本应连装载都失败（size=0）", 0, n)
            assertFalse(hit)
        }
        dump("③ 空标签：装载=0 表示连字典树都没进")
    }

    @Test fun `尾点是合法写法，必须仍然命中——修复别误伤`() {
        assertAlive("arxiv.org.")
        assertAlive("*.arxiv.org.")
        dump("③ 尾点（正常）")
    }

    // ==================== ④ 看不见的字符 ====================

    @Test fun `零宽字符能穿过空白校验，NBSP 被挡住`() {
        // Kotlin 的 Char.isWhitespace = Character.isWhitespace || Character.isSpaceChar：
        // NBSP(U+00A0) isSpaceChar=true 会被挡；ZWSP(U+200B)/LRM(U+200E) 是 Cf 类，两个判定都是 false。
        assertRejected("ar${NBSP}xiv.org")      // 内部 NBSP
        assertAlive("arxiv.org$NBSP")           // 尾部 NBSP：被 trim 吃掉，反而正常
        assertDead("${ZWSP}arxiv.org")
        assertDead("arxiv${ZWSP}.org")
        assertDead("arxiv.org$ZWSP")
        assertDead("${LRM}arxiv.org")
        dump("④ 不可见字符")
    }

    // ==================== ⑤ 同形字与非 ASCII 域名 ====================

    @Test fun `西里尔同形字与全角句号`() {
        assertDead("${CYR_A}rxiv.org")                    // 肉眼与 arxiv.org 无异
        assertRejected("arxiv${IDEO_DOT}org")             // 无 ASCII 点也无冒号 ⇒ 被首条校验挡下（好）
        assertDead("www${IDEO_DOT}arxiv.org", "www.arxiv.org")  // 串里另有真点，全角点就混进来了
        dump("⑤ 同形/全角")
    }

    @Test fun `Unicode 域名存的是原文，而 host 到达时是 punycode`() {
        // CONNECT/Host 头里到达的是 xn-- 形式；规则表存的是中文原文，两边永远对不上。
        val stored = store("中国.com")
        assertEquals("中国.com", stored)
        val (n, hit) = probe(stored!!, "xn--fiqs8s.com")
        rows += Row("中国.com", stored, n, hit)
        assertFalse("中文域名规则对 punycode host 命中了？结论要重写", hit)
        dump("⑤ IDN（命中列针对 host=xn--fiqs8s.com）")
    }

    // ==================== ⑥ 已被现有校验覆盖的形态：回归确认 ====================

    @Test fun `通配与前缀的各种手滑写法确实已被挡住`() {
        listOf(
            "*arxiv.org", "arxiv.*", "**.arxiv.org", "*.*.arxiv.org",
            "==arxiv.org", "=*.arxiv.org", "*.arxiv.*", "arxiv.org*", "arxiv=org.com",
        ).forEach { assertRejected(it) }
        dump("⑥ 通配手滑（已被覆盖）")
    }

    @Test fun `大小写与空白的既有处理`() {
        assertAlive("  ARXIV.Org  ")
        assertRejected("by wxs.qq.com")
        assertRejected("arxiv .org")
        assertRejected("arxiv\torg.com")
        assertAlive("arxiv.org\n")     // trim 吃掉换行 ⇒ 正常
        dump("⑥ 大小写/空白")
    }

    @Test fun `单段与空输入被拒`() {
        listOf("com", "arxiv", "", "   ", "*.", "=", "*.com").forEach { assertRejected(it) }
        dump("⑥ 单段/空")
    }

    // ==================== ⑦ 正常写法必须仍然活着（修复的红线）====================

    @Test fun `三档正常写法全部命中`() {
        assertAlive("arxiv.org")
        assertAlive("*.arxiv.org")
        assertAlive("=arxiv.org")
        assertAlive("*.arxiv.org", "www.arxiv.org")
        assertAlive("arxiv.org", "export.arxiv.org")
        dump("⑦ 正常写法")
    }

    @Test fun `IPv6 与 CIDR 仍能存进规则表（匹配侧只能在设备上验）`() {
        assertEquals("2001:db8::1", store("2001:db8::1"))
        assertEquals("10.0.0.0/8", store("10.0.0.0/8"))
        assertEquals("::1", store("::1"))
        assertEquals("2001:db8::/32", store("2001:db8::/32"))
        assertEquals("[2001:db8::1]", store("[2001:db8::1]"))   // 带方括号：能存，设备上是否可解析未验
        println("\n⑦ IPv6/CIDR 均已存下；匹配侧不在 JVM 断言（isNumericAddress 恒 false 会骗人）")
    }

    /**
     * 规则页输入框的 submit 变换 —— **逐行抄自** RulesScreen.kt:465-476。
     * 抄的原因：它是 @Composable 内部闭包，JVM 单测无法直接调用。抄写对照（左＝原文行号）：
     *   466  val bare = raw.trim().lowercase().removePrefix("*.").removePrefix("=")
     *   467  if (bare.isNotBlank()) {
     *   469  val isIp = bare.firstOrNull()?.isDigit() == true || bare.contains(':')
     *   471  isIp    -> onAdd(bare)
     *   472  SUFFIX  -> 弹确认框（「保留全层级」→ onAdd(bare)；「改用单级」→ onAdd("*."+bare)）
     *   473  else    -> onAdd(RuleScope.format(scope, bare))
     * 返回值＝真正传给 addUserDirectRule 的字符串（SUFFIX 档按「用户点了保留全层级」算）。
     */
    private fun uiSubmit(raw: String, scope: RuleScope = RuleScope.SINGLE): String? {
        val bare = raw.trim().lowercase().removePrefix("*.").removePrefix("=")
        if (!bare.isNotBlank()) return null
        val isIp = bare.firstOrNull()?.isDigit() == true || bare.contains(':')
        return when {
            isIp -> bare
            scope == RuleScope.SUFFIX -> bare
            else -> RuleScope.format(scope, bare)
        }
    }

    @Test fun `输入框把含冒号的串当成 IP，URL 因此绕过作用域档位与全层级确认框`() {
        // 默认档位是「单级」，正常域名会被加上 "*."；但 URL 含 ':' ⇒ 走 isIp 分支 ⇒
        // 原样提交、不加前缀、也不弹全层级确认框。用户看到的是「加成功了」。
        assertEquals("https://arxiv.org/", uiSubmit("https://arxiv.org/"))
        assertEquals("*.arxiv.org", uiSubmit("arxiv.org"))
        assertEquals("arxiv.org:443", uiSubmit("arxiv.org:443"))
        // 顺带一个不是死规则、但档位被静默改掉的形态：数字开头的域名也走 isIp 分支，
        // 于是变成「全层级」且不弹确认框——与用户选的「单级」不一致。
        assertEquals("1password.com", uiSubmit("1password.com"))
        assertEquals("*.example.com", uiSubmit("example.com"))
        println("\n⑧ UI submit：URL→原样提交（无前缀/无确认框）；1password.com→全层级（用户选的却是单级）")
    }

    // ==================== ⑩ 自建/内置规则集：这条路上校验更弱，甚至没有 ====================

    /** 逐行抄自 RuleSetEditScreen.kt:178-181（多行编辑器保存时的解析）。 */
    private fun parseDomains(text: String): List<String> =
        text.split("\n").map { it.trim().lowercase().removePrefix("*.") }
            .filter { it.isNotEmpty() && it[0] != '#' && it.contains('.') }
            .distinct()

    @Test fun `自建集的单条添加只查了一个点，normalizeRule 挡下的它全放行`() {
        // MainViewModel.addDomainToSet:357-363 只有 `d.contains('.')`：
        // 没有空白校验、没有 * 与 = 的校验，也不认识 URL。
        vm.addRuleSet("我的集", RuleAction.DIRECT)
        val id = settingsFlow.value.userRuleSets.single().id
        val bad = listOf(
            "https://arxiv.org/", "arxiv.org:443", "arxiv.org/pdf",
            "by wxs.qq.com",            // 快速表里被拒，这里能进
            "*apple.com",               // 快速表里被拒，这里能进
            "=*.arxiv.org",             // 快速表里被拒，这里能进
            ".arxiv.org",
        )
        bad.forEach { vm.addDomainToSet(id, it) }
        val stored = settingsFlow.value.userRuleSets.single().domains
        println("\n===== ⑩ 自建集 addDomainToSet =====")
        stored.forEach { d ->
            val (n, hit) = probe(d)
            println(String.format("  %-24s 装载=%d 命中 arxiv.org=%s", d, n, hit))
            assertFalse("『$d』竟然命中了 $target", hit)
        }
        // "=*.arxiv.org" 的 "*." 不在开头，removePrefix 不动它 ⇒ 原样进集合。
        assertEquals("这些在快速放行表里全被拒，在自建集里却一条不落地存了下来", bad.size, stored.size)
    }

    @Test fun `多行编辑器保存路径同样只查一个点`() {
        vm.addRuleSet("批量", RuleAction.DIRECT)
        val id = settingsFlow.value.userRuleSets.single().id
        val text = """
            # 我的直连清单
            https://arxiv.org/pdf/2606.20161
            arxiv.org:443
            by wxs.qq.com
            *.openreview.net
        """.trimIndent()
        vm.setRuleSetDomains(id, parseDomains(text))
        val stored = settingsFlow.value.userRuleSets.single().domains
        println("\n===== ⑩ 多行编辑器 parseDomains + setRuleSetDomains =====")
        stored.forEach { d ->
            val (n, hit) = probe(d)
            println(String.format("  %-34s 装载=%d 命中 arxiv.org=%s", d, n, hit))
        }
        assertEquals(
            listOf("https://arxiv.org/pdf/2606.20161", "arxiv.org:443", "by wxs.qq.com", "openreview.net"),
            stored,
        )
        // 前三条全是死规则；第四条被 removePrefix("*.") 悄悄从「单级」变成「全层级」。
        stored.take(3).forEach { assertFalse(probe(it).second) }
        assertTrue(probe("openreview.net", "a.b.openreview.net").second)
    }

    @Test fun `端到端复现用户现场：放行表里躺着 URL，arxiv 仍走代理`() {
        // 完整链路：输入框 → normalizeRule → 规则表 → RuleRepository 透传 → RuleEngine。
        val submitted = uiSubmit("https://arxiv.org/")!!
        val stored = store(submitted)
        assertEquals("规则页会原样显示这一条", "https://arxiv.org/", stored)
        val engine = RuleEngine()
        engine.update(RuleEngine.Snapshot(userDirect = RuleMatcher().apply { add(stored!!) }))
        println("\n⑨ 端到端：规则页显示『$stored』，userDirect 装载 ${RuleMatcher().apply { add(stored!!) }.size} 条")
        listOf("arxiv.org", "www.arxiv.org", "export.arxiv.org").forEach {
            val d = engine.decideDetailed(it)
            println("   decideDetailed($it) = ${d.action}/${d.src}")
            assertEquals(RuleAction.PROXY, d.action)
            assertEquals(RuleSrc.DEFAULT, d.src)
        }
        println("   ⇒ 用户看到的正是「设了放行却还走梯子」")
    }
}
