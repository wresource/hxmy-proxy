package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.model.RuleEntry
import com.mzstd.hxmyproxy.core.model.ShareState
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleEngine
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
 * [MainViewModel] 的规则写入语义。
 *
 * 为什么重点在这里：ViewModel 里这批方法本质是 `(ProxySettings) -> ProxySettings` 的纯变换，
 * 而它们管的都是**错了也看不出来**的东西——规则归一化写歪了会静默生成永不匹配的死规则；
 * 互斥漏了会让同一域名同时躺在 block 与 allow 两张表里；编辑时重置 addedAt 会悄悄改变
 * most-specific-wins 的平手裁决结果。这些在 UI 上全都毫无异样，只有用户某天发现
 * 「我明明加了却没生效」。
 *
 * 做法：把 SettingsRepository.update 接成一个内存里的 MutableStateFlow，
 * 于是每次调用后可以直接断言变换后的 ProxySettings —— 不碰 DataStore、不需要设备。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private lateinit var settingsFlow: MutableStateFlow<ProxySettings>
    private lateinit var vm: MainViewModel

    @Before
    fun setUp() {
        // viewModelScope 默认在 Dispatchers.Main；JVM 单测里没有主循环，必须换掉。
        // Unconfined：update{} 里的协程立即执行，断言不必再 advance。
        Dispatchers.setMain(UnconfinedTestDispatcher())
        settingsFlow = MutableStateFlow(ProxySettings())

        val settingsRepo = mockk<SettingsRepository>(relaxed = true)
        every { settingsRepo.settings } returns settingsFlow
        every { settingsRepo.onboardingCompleted } returns flowOf(true)
        every { settingsRepo.domainHistory } returns flowOf(emptySet())
        // 把真实的 transform 应用到内存状态上——这样测的是 ViewModel 的变换逻辑本身，
        // 而不是「有没有调用 repository」这种无信息量的断言。
        coEvery { settingsRepo.update(any()) } answers {
            val transform = firstArg<(ProxySettings) -> ProxySettings>()
            settingsFlow.value = transform(settingsFlow.value)
        }

        val proxyRepo = mockk<ProxyServerRepository>(relaxed = true)
        every { proxyRepo.state } returns MutableStateFlow(ShareState())
        every { proxyRepo.resetState } returns MutableStateFlow(ManualResetPhase.IDLE)

        val historyRepo = mockk<EndpointHistoryRepository>(relaxed = true)
        every { historyRepo.history } returns flowOf(emptyList())

        val credStore = mockk<CredentialStore>(relaxed = true)
        every { credStore.credentials } returns flowOf(CredentialStore.Credentials())

        vm = MainViewModel(settingsRepo, proxyRepo, historyRepo, credStore, RuleEngine())
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun s() = settingsFlow.value
    private fun rejects() = s().userRejectRules
    private fun directs() = s().userDirectRules
    private fun seed(v: ProxySettings) { settingsFlow.value = v }

    // ==================== 归一化：写法决定作用域，不能被吃掉 ====================

    @Test fun `裸域名按全层级存，不加任何前缀`() {
        vm.addUserRejectRule("ads.example.com")
        assertEquals("ads.example.com", rejects().single().value)
    }

    @Test fun `单级与精确前缀必须原样保留`() {
        vm.addUserRejectRule("*.ads.example.com")
        vm.addUserRejectRule("=exact.example.com")
        assertEquals(
            listOf("*.ads.example.com", "=exact.example.com"),
            rejects().map { it.value },
        )
    }

    @Test fun `大小写归一到小写`() {
        vm.addUserRejectRule("  ADS.Example.COM  ")
        assertEquals("ads.example.com", rejects().single().value)
    }

    @Test fun `单段整段 TLD 被拒绝——收下就是全网误杀`() {
        vm.addUserRejectRule("com")
        assertTrue(rejects().isEmpty())
    }

    @Test fun `含空白的输入被拒绝——它永远匹配不上任何 host`() {
        // 粘贴/输入法误入的 "by wxs.qq.com" 这类串曾能存进列表，成为静默的死规则：
        // 用户看得见自己加了，却永远不生效。
        vm.addUserRejectRule("by wxs.qq.com")
        assertTrue(rejects().isEmpty())
    }

    @Test fun `IPv4 CIDR 与 IPv6 都能收下`() {
        vm.addUserRejectRule("10.0.0.0/8")
        vm.addUserRejectRule("2001:db8::1")
        assertEquals(listOf("10.0.0.0/8", "2001:db8::1"), rejects().map { it.value })
    }

    @Test fun `重复添加同一条不产生第二份`() {
        vm.addUserRejectRule("ads.example.com")
        vm.addUserRejectRule("ads.example.com")
        assertEquals(1, rejects().size)
    }

    // ==================== 就地编辑：addedAt 是裁决依据，绝不能重置 ====================

    @Test fun `改写规则值保留 addedAt 与启停状态`() {
        seed(ProxySettings(userRejectRules = listOf(
            RuleEntry("ads.example.com", enabled = false, addedAt = 1_000L, disabledAt = 2_000L),
        )))
        vm.updateUserRejectRule("ads.example.com", "tracker.example.com")
        val e = rejects().single()
        assertEquals("tracker.example.com", e.value)
        // 这三项若被重置，most-specific-wins 的同档位平手裁决会静默改变，
        // 而 UI 上一切正常——正是本测试存在的理由。
        assertEquals(1_000L, e.addedAt)
        assertFalse(e.enabled)
        assertEquals(2_000L, e.disabledAt)
    }

    @Test fun `只改作用域档位同样保留 addedAt`() {
        seed(ProxySettings(userRejectRules = listOf(RuleEntry("ads.example.com", addedAt = 1_000L))))
        vm.updateUserRejectRule("ads.example.com", "*.ads.example.com")
        val e = rejects().single()
        assertEquals("*.ads.example.com", e.value)
        assertEquals(1_000L, e.addedAt)
    }

    @Test fun `改成与其它条目重复时不生效`() {
        seed(ProxySettings(userRejectRules = listOf(
            RuleEntry("a.example.com", addedAt = 1L),
            RuleEntry("b.example.com", addedAt = 2L),
        )))
        vm.updateUserRejectRule("a.example.com", "b.example.com")
        assertEquals(listOf("a.example.com", "b.example.com"), rejects().map { it.value })
    }

    @Test fun `改成自身值视为无操作而非重复`() {
        seed(ProxySettings(userRejectRules = listOf(RuleEntry("a.example.com", addedAt = 1L))))
        vm.updateUserRejectRule("a.example.com", "A.Example.com")   // 只是大小写
        assertEquals("a.example.com", rejects().single().value)
        assertEquals(1L, rejects().single().addedAt)
    }

    @Test fun `改成非法值时原条目不动`() {
        seed(ProxySettings(userRejectRules = listOf(RuleEntry("a.example.com", addedAt = 1L))))
        vm.updateUserRejectRule("a.example.com", "com")
        assertEquals("a.example.com", rejects().single().value)
    }

    @Test fun `白名单的就地编辑语义与拦截表对称`() {
        seed(ProxySettings(userDirectRules = listOf(RuleEntry("cdn.example.com", addedAt = 7L))))
        vm.updateUserDirectRule("cdn.example.com", "*.cdn.example.com")
        assertEquals("*.cdn.example.com", directs().single().value)
        assertEquals(7L, directs().single().addedAt)
    }

    // ==================== 互斥：同一域名不能同时在两张表 ====================

    @Test fun `设直连会把该域名从拦截表移除`() {
        seed(ProxySettings(userRejectRules = listOf(RuleEntry("x.example.com"))))
        vm.setDomainDirect("x.example.com")
        assertTrue(rejects().isEmpty())
        assertEquals(1, directs().size)
    }

    @Test fun `互斥比较按裸域名——不同作用域写法算同一条`() {
        // 否则「设直连」移不掉 block 里前缀不同的同名规则，两张表并存后
        // 还要靠具体度去裁决，等于把一个本可避免的冲突留给运行时。
        seed(ProxySettings(userRejectRules = listOf(RuleEntry("=x.example.com"))))
        vm.setDomainDirect("x.example.com")
        assertTrue("精确写法的同名规则也应被移除", rejects().isEmpty())
    }

    @Test fun `设拦截会把该域名从白名单移除`() {
        seed(ProxySettings(userDirectRules = listOf(RuleEntry("*.y.example.com"))))
        vm.setDomainReject("y.example.com")
        assertTrue(directs().isEmpty())
        assertEquals(1, rejects().size)
    }

    @Test fun `清除会把两张表里的该域名都移除`() {
        seed(ProxySettings(
            userDirectRules = listOf(RuleEntry("*.z.example.com")),
            userRejectRules = listOf(RuleEntry("=z.example.com")),
        ))
        vm.clearDomainRule("z.example.com")
        assertTrue(directs().isEmpty())
        assertTrue(rejects().isEmpty())
    }

    @Test fun `点域名设规则写成单级而非全层级`() {
        // 列表里点到的是实际访问过的具体域名，用户意图是「这个域名」，
        // 不是它底下的整棵子树。
        vm.setDomainReject("api.example.com")
        assertEquals("*.api.example.com", rejects().single().value)
    }

    // 「点 IP 设规则不加作用域前缀」这条**不能在 JVM 单测里验**：
    // 判定走 IpCidrSet.looksLikeIpOrCidr → android.net.InetAddresses.isNumericAddress，
    // 而 JVM 单测里 android.jar 是 stub，配合 isReturnDefaultValues=true 会**静默返回 false**，
    // 于是 IP 被当成域名、被加上 "*." 前缀（实测 expected:<192.168.1.1> but was:<*.192.168.1.1>）。
    // 在这里断言就等于把 stub 的假行为固化成"期望"。真实判定由仪器测试
    // RuleRepositoryTest.ipAndCidrRecognizedOnDevice 覆盖。

    // ==================== 启停：停用不等于切到反面 ====================

    @Test fun `停用记下停用时间且不删除条目`() {
        seed(ProxySettings(userRejectRules = listOf(RuleEntry("a.example.com", addedAt = 5L))))
        vm.toggleUserRejectRule("a.example.com")
        val e = rejects().single()
        assertFalse(e.enabled)
        assertTrue("停用时间应被记下（用于停用项排序）", e.disabledAt > 0)
        assertEquals(5L, e.addedAt)
    }

    @Test fun `重新启用不清空历史停用时间`() {
        seed(ProxySettings(userRejectRules = listOf(
            RuleEntry("a.example.com", enabled = false, disabledAt = 999L),
        )))
        vm.toggleUserRejectRule("a.example.com")
        assertTrue(rejects().single().enabled)
        assertEquals(999L, rejects().single().disabledAt)
    }

    // ==================== 规则集与 host 覆盖 ====================

    @Test fun `自建规则集可增删与切换动作`() {
        vm.addRuleSet("我的集", RuleAction.REJECT)
        val id = s().userRuleSets.single().id
        assertEquals(RuleAction.REJECT, s().userRuleSets.single().action)

        vm.setRuleSetAction(id, RuleAction.DIRECT)
        assertEquals(RuleAction.DIRECT, s().userRuleSets.single().action)

        vm.addDomainToSet(id, "A.Example.com")
        assertEquals(listOf("a.example.com"), s().userRuleSets.single().domains)
        vm.addDomainToSet(id, "a.example.com")          // 重复
        vm.addDomainToSet(id, "nodot")                  // 非法
        assertEquals(1, s().userRuleSets.single().domains.size)

        vm.deleteRuleSet(id)
        assertTrue(s().userRuleSets.isEmpty())
    }

    @Test fun `host 覆盖归一化到小写且忽略空输入`() {
        vm.setHostOverride("  API.Example.COM ", RuleAction.DIRECT)
        assertEquals(RuleAction.DIRECT, s().hostOverrides["api.example.com"])
        vm.setHostOverride("   ", RuleAction.REJECT)
        assertEquals(1, s().hostOverrides.size)
        vm.clearHostOverride("API.example.com")
        assertTrue(s().hostOverrides.isEmpty())
    }

    @Test fun `内置组覆盖可设置与恢复默认`() {
        vm.setGroupOverride("app-tencent", listOf("qq.com"))
        assertEquals(listOf("qq.com"), s().ruleSetOverrides["app-tencent"])
        vm.clearGroupOverride("app-tencent")
        assertNull(s().ruleSetOverrides["app-tencent"])
    }

    // ==================== 端口：越界值必须被钳住 ====================

    @Test fun `端口钳制在 1024 到 65535`() {
        vm.setHttpPort(80)
        assertEquals(1024, s().httpPort)
        vm.setSocksPort(70000)
        assertEquals(65535, s().socksPort)
        vm.setPacPort(8899)
        assertEquals(8899, s().pacPort)
    }
}
