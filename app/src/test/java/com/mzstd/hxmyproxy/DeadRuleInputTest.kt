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

/** Regression coverage replacing the old defect-oracle assertions.
 * Real MainViewModel writes and real RuleMatcher/RuleEngine are used.
 * ICU/IDN and numeric Android matching are checked in RuleInputDeviceTest.
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

    @Test fun webAddressesSaveOnlyHostAndMatch() {
        listOf("https://arxiv.org/", "https://arxiv.org", "http://arxiv.org/",
            "https://arxiv.org/pdf/2606.20161", "//arxiv.org", "HTTPS://ARXIV.ORG/",
            "https://arxiv.org/abs?id=1", "https://arxiv.org/abs?id", "arxiv.org:443",
            "arxiv.org:80/pdf", "arxiv.org/pdf", "arxiv.org/", "arxiv.org#").forEach {
            assertEquals("arxiv.org", store(it)); assertAlive(it)
        }
        assertAlive("https://www.arxiv.org/", "www.arxiv.org")
    }

    @Test fun ambiguousAndMalformedValuesAreRejected() {
        listOf("user@arxiv.org", "https://user@arxiv.org", "\"arxiv.org\"", "'arxiv.org'",
            "<arxiv.org>", "(arxiv.org)", "arxiv.org,openreview.net", "arxiv.org;",
            "arxiv.org|openreview.net", ".arxiv.org", "..arxiv.org", "arxiv..org",
            ".", "..", "*..arxiv.org", "arxiv.org..", "arxiv.org:65536", "arxiv.org:-1",
            "https:///arxiv.org", "https://", "ftp://arxiv.org").forEach(::assertRejected)
    }

    @Test fun invisibleCharactersAreRejectedAndEdgeWhitespaceIsPreserved() {
        listOf("ar${NBSP}xiv.org", "${ZWSP}arxiv.org", "arxiv${ZWSP}.org",
            "arxiv.org$ZWSP", "${LRM}arxiv.org").forEach(::assertRejected)
        listOf("arxiv.org$NBSP", "  ARXIV.Org  ", "arxiv.org\n", "arxiv.org.", "*.arxiv.org.").forEach { assertAlive(it) }
    }

    @Test fun malformedScopeAndSingleLabelStayRejected() {
        listOf("*arxiv.org", "arxiv.*", "**.arxiv.org", "*.*.arxiv.org", "==arxiv.org",
            "=*.arxiv.org", "*.arxiv.*", "arxiv.org*", "arxiv=org.com", "by wxs.qq.com",
            "arxiv .org", "arxiv\torg.com", "com", "arxiv", "", "   ", "*.", "=", "*.com").forEach(::assertRejected)
    }

    @Test fun scopesKeepOriginalMatchingBoundaries() {
        assertAlive("arxiv.org"); assertAlive("*.arxiv.org"); assertAlive("=arxiv.org")
        assertAlive("*.arxiv.org", "www.arxiv.org")
        assertFalse(probe(store("*.arxiv.org")!!, "deep.www.arxiv.org").second)
        assertFalse(probe(store("=arxiv.org")!!, "www.arxiv.org").second)
        assertAlive("arxiv.org", "deep.www.arxiv.org")
    }

    @Test fun numericAddressesAndCidrValidateWithoutDns() {
        val values = mapOf("1.2.3.4:8080" to "1.2.3.4", "2001:db8::1" to "2001:db8::1",
            "10.0.0.0/8" to "10.0.0.0/8", "::1" to "::1", "2001:db8::/32" to "2001:db8::/32",
            "[2001:db8::1]" to "2001:db8::1", "[2001:db8::1]:443" to "2001:db8::1")
        values.forEach { (input, expected) -> assertEquals(input, expected, store(input)) }
        listOf("999.1.2.3", "10.0.0.1/33", "2001:db8::/129", "1.2.3.4/no", "1.2.3", "1::2::3", "=1.2.3.4",
            "010.0.0.1", "http://010.0.0.1/").forEach(::assertRejected)
    }

    @Test fun actualUiParserKeepsDigitLeadingDomainInSelectedScope() {
        val parsed = com.mzstd.hxmyproxy.core.rules.RuleInput.forScope("https://1password.com/login", RuleScope.SINGLE)!!
        assertFalse(parsed.isIp)
        assertEquals("*.1password.com", parsed.value)
        assertEquals(RuleScope.SUFFIX, com.mzstd.hxmyproxy.core.rules.RuleInput.forScope("https://arxiv.org", RuleScope.SUFFIX)!!.scope)
    }

    @Test fun namedSetInputKeepsScopeAndRejectsPartialSave() {
        val result = com.mzstd.hxmyproxy.core.rules.RuleInput.parseLines("*.arxiv.org\n2001:db8::/32\nnot a domain.com")
        assertEquals(listOf("*.arxiv.org", "2001:db8::/32"), result.rules)
        assertEquals(listOf(3), result.invalidLines)
    }

    @Test fun pastedUrlFlowsThroughViewModelIntoEffectiveRule() {
        val saved = store("https://arxiv.org/pdf/2606.20161")!!
        assertEquals(RuleAction.DIRECT to RuleSrc.USER_ALLOW, decide(saved))
        settingsFlow.value = ProxySettings()
        vm.addUserRejectRule("https://arxiv.org/abs?id=1")
        val entry = settingsFlow.value.userRejectRules.single()
        val engine = RuleEngine().apply { update(RuleEngine.Snapshot(userReject=RuleMatcher().apply { add(entry.value) })) }
        assertEquals(RuleAction.REJECT, engine.decide("arxiv.org"))
    }

    @Test fun quickActionRepairsLegacyUrlWithoutWideningScopeOrDuplicatingKeys() {
        settingsFlow.value = ProxySettings(userDirectRules = listOf(
            com.mzstd.hxmyproxy.core.model.RuleEntry("https://arxiv.org/pdf/1", enabled = false),
            com.mzstd.hxmyproxy.core.model.RuleEntry("*.arxiv.org")))
        vm.setDomainDirect("arxiv.org")
        val value = settingsFlow.value.userDirectRules.single()
        assertEquals("*.arxiv.org", value.value); assertTrue(value.enabled)
        assertTrue(probe(value.value).second)
        assertFalse(probe(value.value, "deep.www.arxiv.org").second)
        vm.clearDomainRule("ARXIV.ORG.")
        assertTrue(settingsFlow.value.userDirectRules.isEmpty())
    }
}
