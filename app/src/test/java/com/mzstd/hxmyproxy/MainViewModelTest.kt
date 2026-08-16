package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.model.HistoryEndpoint
import com.mzstd.hxmyproxy.core.model.InterfaceStatus
import com.mzstd.hxmyproxy.core.model.InterfaceType
import com.mzstd.hxmyproxy.core.model.PerformancePreset
import com.mzstd.hxmyproxy.core.model.ProxyEntry
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.model.RuleEntry
import com.mzstd.hxmyproxy.core.model.ShareInterface
import com.mzstd.hxmyproxy.core.model.ShareState
import com.mzstd.hxmyproxy.core.model.visibleUnderIpv6Pref
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleCatalog
import com.mzstd.hxmyproxy.core.rules.RuleCategory
import com.mzstd.hxmyproxy.core.rules.RuleEngine
import com.mzstd.hxmyproxy.data.repository.CredentialStore
import com.mzstd.hxmyproxy.data.repository.EndpointHistoryRepository
import com.mzstd.hxmyproxy.data.repository.ManualResetPhase
import com.mzstd.hxmyproxy.data.repository.ProxyServerRepository
import com.mzstd.hxmyproxy.data.repository.SettingsRepository
import com.mzstd.hxmyproxy.ui.MainViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
import java.net.InetAddress

/**
 * [MainViewModel] 的设置写入语义与派生状态。
 *
 * 为什么值得测：ViewModel 里这批方法本质是 `(ProxySettings) -> ProxySettings` 的纯变换，
 * 而它们管的都是**错了也看不出来**的东西——规则归一化写歪了会静默生成永不匹配的死规则；
 * 互斥漏了会让同一域名同时躺在 block 与 allow 两张表里；编辑时重置 addedAt 会悄悄改变
 * most-specific-wins 的平手裁决结果；批量开关的差集写成清空会连坐别的分类。这些在 UI 上
 * 全都毫无异样，只有用户某天发现「我明明加了却没生效」。
 *
 * 做法：把 SettingsRepository.update 接成一个内存里的 MutableStateFlow，
 * 于是每次调用后可以直接断言变换后的 ProxySettings —— 不碰 DataStore、不需要设备。
 * 派生状态（历史入口可用性 / 引导显示与否）走 [observe] 订阅后再读。
 *
 * 覆盖分四块：规则写入（归一化/编辑/互斥/启停/规则集/端口）、历史入口可用性、
 * 性能预设与上限钳制、集合型开关（tab/接口/内置组/分类/总闸）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var settingsFlow: MutableStateFlow<ProxySettings>
    private lateinit var shareFlow: MutableStateFlow<ShareState>
    private lateinit var historyFlow: MutableStateFlow<List<HistoryEndpoint>>
    private lateinit var onboardingFlow: MutableStateFlow<Boolean>
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var vm: MainViewModel

    @Before
    fun setUp() {
        // viewModelScope 默认在 Dispatchers.Main；JVM 单测里没有主循环，必须换掉。
        // Unconfined：update{} 里的协程立即执行，断言不必再 advance。
        Dispatchers.setMain(mainDispatcher)
        settingsFlow = MutableStateFlow(ProxySettings())
        shareFlow = MutableStateFlow(ShareState())
        historyFlow = MutableStateFlow(emptyList<HistoryEndpoint>())
        onboardingFlow = MutableStateFlow(true)

        settingsRepo = mockk<SettingsRepository>(relaxed = true)
        every { settingsRepo.settings } returns settingsFlow
        every { settingsRepo.onboardingCompleted } returns onboardingFlow
        every { settingsRepo.domainHistory } returns flowOf(emptySet())
        // 把真实的 transform 应用到内存状态上——这样测的是 ViewModel 的变换逻辑本身，
        // 而不是「有没有调用 repository」这种无信息量的断言。
        coEvery { settingsRepo.update(any()) } answers {
            val transform = firstArg<(ProxySettings) -> ProxySettings>()
            settingsFlow.value = transform(settingsFlow.value)
        }
        // 引导完成标志同样接回内存，才能验证「看完就不再弹」而不是只验证「调了一下」。
        coEvery { settingsRepo.setOnboardingCompleted(any()) } answers { onboardingFlow.value = firstArg() }

        val proxyRepo = mockk<ProxyServerRepository>(relaxed = true)
        every { proxyRepo.state } returns shareFlow
        every { proxyRepo.resetState } returns MutableStateFlow(ManualResetPhase.IDLE)

        val historyRepo = mockk<EndpointHistoryRepository>(relaxed = true)
        every { historyRepo.history } returns historyFlow

        val credStore = mockk<CredentialStore>(relaxed = true)
        every { credStore.credentials } returns flowOf(CredentialStore.Credentials())

        vm = MainViewModel(settingsRepo, proxyRepo, historyRepo, credStore, RuleEngine())
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun s() = settingsFlow.value
    private fun rejects() = s().userRejectRules
    private fun directs() = s().userDirectRules
    private fun seed(v: ProxySettings) { settingsFlow.value = v }

    /**
     * 读一个 `stateIn(WhileSubscribed)` 暴露的状态。
     *
     * 这类流**没有订阅者时上游根本不跑**，直接读 `.value` 只会拿到 stateIn 的初始占位值——
     * 于是「历史入口可用性」这种派生状态怎么测都是空列表，测试会以「什么都没验」的方式假通过。
     * 这里订阅一次、抽干调度队列后取值再退订。
     */
    private fun <T> observe(flow: StateFlow<T>): T {
        val job = CoroutineScope(mainDispatcher).launch { flow.collect { } }
        mainDispatcher.scheduler.runCurrent()
        return flow.value.also { job.cancel() }
    }

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

    @Test fun `前缀手滑写法被拒绝——它们会变成永不匹配的死规则`() {
        // RuleScope.parse 只剥一层前缀，这三种手滑会把符号留在裸域名里：
        //   *apple.com（漏了点）→ SUFFIX + "*apple.com"
        //   ==a.com            → EXACT  + "=a.com"
        //   =*.a.com           → EXACT  + "*.a.com"
        // 从前它们能存进列表、显示在规则页、装进字典树，却永远匹配不到任何 host。
        vm.addUserRejectRule("*apple.com")
        vm.addUserRejectRule("==a.com")
        vm.addUserRejectRule("=*.a.com")
        assertTrue(rejects().isEmpty())
    }

    @Test fun `合法的前缀写法不受影响`() {
        // 上一条的校验不能误伤正常的三档写法（* 与 = 作为**前缀**是合法的，只是不能残留在裸域名里）。
        vm.addUserRejectRule("*.a.com")
        vm.addUserRejectRule("=b.com")
        vm.addUserRejectRule("c.com")
        assertEquals(listOf("*.a.com", "=b.com", "c.com"), rejects().map { it.value })
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

    // ==================== 历史入口可用性：标错了用户就点到一个连不上的地址 ====================
    //
    // 首页历史入口是「一键回到上次用的地址」。可用性标记错在两个方向都很难受：
    // 标成不可用 → 明明能连却被劝退；标成可用 → 点了连不上，用户第一反应是「这软件坏了」。
    // 判定只有两条：IP 仍是当前某个接口地址，且端口与当前**对应协议**的配置一致。

    private fun iface(ip: String) = ShareInterface(
        id = "wlan0/$ip", name = "wlan0", type = InterfaceType.WIFI,
        address = InetAddress.getByName(ip), prefixLength = 24,
        gatewayLike = false, isSelected = true, status = InterfaceStatus.UP,
    )

    private fun ep(protocol: ProxyProtocol, ip: String, port: Int) =
        HistoryEndpoint(protocol, ip, port, lastUsedMillis = 1L)

    /** 历史视图（经 uiState 观察，含可用性判定）。 */
    private fun historyViews() = observe(vm.uiState).history

    @Test fun `历史入口要 IP 与端口同时吻合才算可用`() {
        shareFlow.value = ShareState(interfaces = listOf(iface("192.168.1.34")))
        historyFlow.value = listOf(
            ep(ProxyProtocol.HTTP, "192.168.1.34", 8080),   // 接口在、端口是当前 http 端口
            ep(ProxyProtocol.HTTP, "10.0.0.7", 8080),       // 换了网，这个 IP 已经不是本机地址
            ep(ProxyProtocol.HTTP, "192.168.1.34", 9999),   // 地址还在，但端口早改了
        )
        assertEquals(listOf(true, false, false), historyViews().map { it.available })
        // 条目本身只是被套上可用性，顺序与内容都不该被加工（列表按最近使用排序）。
        assertEquals(historyFlow.value, historyViews().map { it.entry })
    }

    @Test fun `改了监听端口后旧历史入口立刻变不可用`() {
        shareFlow.value = ShareState(interfaces = listOf(iface("192.168.1.34")))
        historyFlow.value = listOf(ep(ProxyProtocol.HTTP, "192.168.1.34", 8080))
        assertTrue(historyViews().single().available)

        vm.setHttpPort(8081)
        // 若可用性只看 IP 不看端口，这条会继续显示成可用，用户点进去连的是一个没人监听的端口。
        assertFalse(historyViews().single().available)
    }

    @Test fun `接口消失后历史入口变不可用`() {
        historyFlow.value = listOf(ep(ProxyProtocol.HTTP, "192.168.1.34", 8080))
        shareFlow.value = ShareState(interfaces = listOf(iface("192.168.1.34")))
        assertTrue(historyViews().single().available)

        // 断网/切网后接口列表被重扫空——历史条目还在，但已经不指向任何本机地址。
        shareFlow.value = ShareState(interfaces = emptyList())
        assertFalse(historyViews().single().available)
    }

    @Test fun `每条历史各自比对自己协议的端口`() {
        // 默认 http=8080 / socks=1080 / pac=8899。前三条都写成「别的协议的端口」：
        // when 分支一旦接错线（比如 SOCKS5 去比 httpPort），错标的就是这三条。
        shareFlow.value = ShareState(interfaces = listOf(iface("192.168.1.34")))
        historyFlow.value = listOf(
            ep(ProxyProtocol.SOCKS5, "192.168.1.34", 8080),
            ep(ProxyProtocol.HTTP, "192.168.1.34", 1080),
            ep(ProxyProtocol.PAC, "192.168.1.34", 1080),
            ep(ProxyProtocol.SOCKS5, "192.168.1.34", 1080),
            ep(ProxyProtocol.HTTP, "192.168.1.34", 8080),
            ep(ProxyProtocol.PAC, "192.168.1.34", 8899),
        )
        assertEquals(
            listOf(false, false, false, true, true, true),
            historyViews().map { it.available },
        )
    }

    // ==================== 首次引导：只该在该弹的时候弹 ====================

    @Test fun `引导未完成时显示，走完后不再显示`() {
        onboardingFlow.value = false
        assertEquals(true, observe(vm.showOnboarding))
        vm.completeOnboarding()
        // 完成标志没被持久化的话，用户每次冷启动都要重看一遍引导。
        assertEquals(false, observe(vm.showOnboarding))
    }

    @Test fun `重新查看引导会再弹一次，且看完后不影响已完成标志`() {
        assertEquals(false, observe(vm.showOnboarding))   // 已完成
        vm.replayOnboarding()
        assertEquals(true, observe(vm.showOnboarding))
        vm.completeOnboarding()
        // 「重看」是一次性请求：不清掉就会永远卡在引导页出不来。
        assertEquals(false, observe(vm.showOnboarding))
        assertTrue(onboardingFlow.value)
    }

    // ==================== 性能预设：切档不能偷偷吞掉用户调好的参数 ====================

    @Test fun `切到非自定义档时上限跟随预设联动`() {
        seed(ProxySettings(
            preset = PerformancePreset.CUSTOM,
            limits = ConnectionLimits(maxGlobalConnections = 111, relayParallelism = 7),
        ))
        vm.setPreset(PerformancePreset.BATTERY)
        assertEquals(PerformancePreset.BATTERY, s().preset)
        // 只改档位标签、不改实际上限，就会出现「显示省电档、跑的还是高吞吐参数」的分裂状态。
        assertEquals(PerformancePreset.BATTERY.toLimits(), s().limits)

        vm.setPreset(PerformancePreset.HIGH_THROUGHPUT)
        assertEquals(PerformancePreset.HIGH_THROUGHPUT.toLimits(), s().limits)
    }

    @Test fun `切到自定义档保留用户已调好的上限`() {
        val mine = ConnectionLimits(maxGlobalConnections = 333, relayParallelism = 48)
        seed(ProxySettings(preset = PerformancePreset.BALANCED, limits = mine))
        vm.setPreset(PerformancePreset.CUSTOM)
        assertEquals(PerformancePreset.CUSTOM, s().preset)
        // CUSTOM.toLimits() 返回的是均衡档：这里若跟着联动，用户逐项调过的参数会在
        // 点一下「自定义」时被静默重置回默认，且界面上的滑块会一起跳回去。
        assertEquals(mine, s().limits)
    }

    @Test fun `自定义上限越界被钳到合法区间并切到自定义档`() {
        vm.setCustomLimits(ConnectionLimits(
            maxGlobalConnections = 10_000,   // 上溢：FD 会先于连接数耗尽（EMFILE）
            maxPerClientConnections = 1,     // 下溢：单浏览器就能把自己卡死
            relayParallelism = 1_000,
            relayBufferBytes = 1,
            idleTimeoutSeconds = 100_000,
            maxTrackedDomains = 0,
        ))
        val l = s().limits
        assertEquals(PerformancePreset.CUSTOM, s().preset)
        assertEquals(ConnectionLimits.RANGE_GLOBAL.last, l.maxGlobalConnections)
        assertEquals(ConnectionLimits.RANGE_PER_CLIENT.first, l.maxPerClientConnections)
        assertEquals(ConnectionLimits.RANGE_PARALLELISM.last, l.relayParallelism)
        assertEquals(ConnectionLimits.RANGE_BUFFER_BYTES.first, l.relayBufferBytes)
        assertEquals(ConnectionLimits.RANGE_IDLE_SECONDS.last, l.idleTimeoutSeconds)
        assertEquals(ConnectionLimits.RANGE_TRACKED_DOMAINS.first, l.maxTrackedDomains)
    }

    @Test fun `区间内的自定义上限原样保留`() {
        val ok = ConnectionLimits(128, 64, 16, 32 * 1024, 90, 512)
        vm.setCustomLimits(ok)
        assertEquals(ok, s().limits)
    }

    // ==================== 集合增删：漏了「减」的一半就是关不掉的开关 ====================

    @Test fun `隐藏与恢复顶层 tab 是集合增删且幂等`() {
        vm.setTabHidden("monitor", true)
        vm.setTabHidden("monitor", true)       // 重复隐藏不产生第二份
        vm.setTabHidden("rules", true)
        assertEquals(setOf("monitor", "rules"), s().hiddenTabs)

        vm.setTabHidden("monitor", false)
        assertEquals(setOf("rules"), s().hiddenTabs)
        vm.setTabHidden("home", false)         // 恢复一个本就没隐藏的，不该炸也不该改动集合
        assertEquals(setOf("rules"), s().hiddenTabs)
    }

    @Test fun `勾选与取消接口只动对应的 id`() {
        vm.toggleInterface("wlan0/192.168.1.34", true)
        vm.toggleInterface("ap0/192.168.43.1", true)
        assertEquals(setOf("wlan0/192.168.1.34", "ap0/192.168.43.1"), s().selectedInterfaceIds)

        vm.toggleInterface("wlan0/192.168.1.34", false)
        // 取消一个不能连坐其它接口：准入是 fail-closed 的，集合被清空 = 所有网段全被拒，
        // 表现为「客户端全部连不上」而界面上开关看着还开着。
        assertEquals(setOf("ap0/192.168.43.1"), s().selectedInterfaceIds)
    }

    @Test fun `内置组的启用与拦截归属是两个独立集合`() {
        vm.toggleRuleGroup("app-tencent", true)
        vm.setGroupRejected("app-tencent", true)
        // 「移到拦截行」只改这一组的动作，不等于把组关掉——若顺手从 enabledRuleGroups 拿掉，
        // 整组域名会回落成默认代理，用户看到的是「我明明把它拉到拦截行了，却照样能上」。
        assertEquals(setOf("app-tencent"), s().enabledRuleGroups)
        assertEquals(setOf("app-tencent"), s().rejectedGroups)

        vm.setGroupRejected("app-tencent", false)
        assertTrue(s().rejectedGroups.isEmpty())
        assertEquals(setOf("app-tencent"), s().enabledRuleGroups)

        vm.toggleRuleGroup("app-tencent", false)
        assertTrue(s().enabledRuleGroups.isEmpty())
    }

    @Test fun `按分类一键开关只影响该分类的组`() {
        val video = RuleCatalog.all.filter { it.category == RuleCategory.VIDEO }.map { it.id }.toSet()
        val social = RuleCatalog.all.filter { it.category == RuleCategory.SOCIAL }.map { it.id }.toSet()
        assertTrue("目录里得真有这两类组，否则这条测试什么都没验", video.size > 1 && social.size > 1)

        vm.setCategoryEnabled(RuleCategory.VIDEO, true)
        assertEquals(video, s().enabledRuleGroups)
        vm.setCategoryEnabled(RuleCategory.SOCIAL, true)
        assertEquals(video + social, s().enabledRuleGroups)

        vm.setCategoryEnabled(RuleCategory.VIDEO, false)
        // 关一个分类顺手把别的分类也关掉，是这类批量开关最容易写出的错（差集写成清空）；
        // 用户只会发现「我关了视频，社交那一排也全灭了」。
        assertEquals(social, s().enabledRuleGroups)
    }

    @Test fun `一键全开覆盖目录全集，一键全关只清目录内的 id`() {
        seed(ProxySettings(enabledRuleGroups = setOf("legacy-group-from-older-build")))
        vm.setAllBuiltinEnabled(true)
        assertTrue(RuleCatalog.all.map { it.id }.all { it in s().enabledRuleGroups })
        assertEquals(RuleCatalog.all.size + 1, s().enabledRuleGroups.size)

        vm.setAllBuiltinEnabled(false)
        // 全关是「按目录做差集」而不是「清空」：目录里没有的 id（降级安装/旧版本残留）保留，
        // 否则在旧版本上点一次全关，升回新版本时那些组的启用状态就凭空没了。
        assertEquals(setOf("legacy-group-from-older-build"), s().enabledRuleGroups)
    }

    // ==================== 总闸与单条：两者不能互相污染 ====================

    @Test fun `整组总闸只切开关，列表数据与单条启停状态原样保留`() {
        seed(ProxySettings(
            userDirectRules = listOf(RuleEntry("cdn.example.com", addedAt = 3L)),
            userRejectRules = listOf(RuleEntry("ads.example.com", enabled = false, addedAt = 4L)),
        ))
        vm.toggleUserDirectEnabled(false)
        assertFalse(s().userDirectEnabled)
        // 总闸若是靠「逐条 enabled=false」实现，再打开时用户原本手动停用的那几条会被一起点亮，
        // 数据无法还原——所以总闸必须是独立的一位，列表一个字节都不动。
        assertEquals(1, directs().size)
        assertTrue(directs().single().enabled)
        assertTrue("白名单总闸不该动拦截表总闸", s().userRejectEnabled)

        vm.toggleUserRejectEnabled(false)
        assertFalse(s().userRejectEnabled)
        assertEquals(1, rejects().size)
        assertFalse("原本就停用的单条不该被总闸改写", rejects().single().enabled)

        vm.toggleUserDirectEnabled(true)
        assertTrue(s().userDirectEnabled)
        assertFalse("重新打开白名单总闸不该顺手打开拦截表", s().userRejectEnabled)
    }

    @Test fun `白名单单条停用只动白名单那张表`() {
        seed(ProxySettings(
            userDirectRules = listOf(RuleEntry("a.example.com", addedAt = 1L)),
            userRejectRules = listOf(RuleEntry("a.example.com", addedAt = 2L)),
        ))
        vm.toggleUserDirectRule("a.example.com")
        assertFalse(directs().single().enabled)
        assertTrue(directs().single().disabledAt > 0)
        // 两张表各管各的启停。这对方法是对称复制出来的，写错表的后果是
        // 「点了放行那条的开关，实际灭掉的是拦截那条」——UI 上两个开关都会显示成错的。
        assertTrue(rejects().single().enabled)
    }

    // ==================== 删除白名单要留痕：「从历史添加」的唯一数据来源 ====================

    @Test fun `移除白名单会记入域名历史`() {
        seed(ProxySettings(userDirectRules = listOf(
            RuleEntry("cdn.example.com"), RuleEntry("*.img.example.com"),
        )))
        vm.removeUserDirectRule("cdn.example.com")
        assertEquals(listOf("*.img.example.com"), directs().map { it.value })
        // 不记的话，规则页「从历史添加」里永远看不到刚删掉的那条，误删只能靠手打原样敲回来。
        coVerify(exactly = 1) { settingsRepo.addDomainHistory(listOf("cdn.example.com")) }
    }

    @Test fun `移除拦截规则不写域名历史`() {
        seed(ProxySettings(userRejectRules = listOf(RuleEntry("ads.example.com"))))
        vm.removeUserRejectRule("ads.example.com")
        assertTrue(rejects().isEmpty())
        // 历史是给白名单「加回来」用的候选池；把拦截项也塞进去，用户从历史里挑一条放行
        // 就等于把自己刚拦掉的广告域名放了行。
        coVerify(exactly = 0) { settingsRepo.addDomainHistory(any()) }
    }

    @Test fun `白名单新增与拦截新增共用同一套归一化与去重`() {
        vm.addUserDirectRule("  *.CDN.Example.com  ")
        vm.addUserDirectRule("*.cdn.example.com")   // 归一化后重复
        vm.addUserDirectRule("com")                 // 单段整段 TLD：收下就是全网放行
        vm.addUserDirectRule("by wxs.qq.com")       // 含空白的死规则
        assertEquals(listOf("*.cdn.example.com"), directs().map { it.value })
    }

    // ==================== 自建规则集：批量操作必须认准目标集 ====================

    @Test fun `自建集的启停删域名与整表覆盖都只作用于目标集`() {
        vm.addRuleSet("A", RuleAction.DIRECT)
        vm.addRuleSet("B", RuleAction.REJECT)
        val a = s().userRuleSets[0].id
        val b = s().userRuleSets[1].id
        vm.addDomainToSet(a, "a1.example.com")
        vm.addDomainToSet(a, "a2.example.com")
        vm.addDomainToSet(b, "b1.example.com")

        vm.toggleRuleSet(a, false)
        assertFalse(s().userRuleSets.first { it.id == a }.enabled)
        // 少了 `if (s.id == id)` 这层判断，一次操作会横扫所有集——用户停用一个集，
        // 结果全部自建规则一起失效，而列表上只有一个开关是灭的。
        assertTrue(s().userRuleSets.first { it.id == b }.enabled)

        vm.removeDomainFromSet(a, "a1.example.com")
        assertEquals(listOf("a2.example.com"), s().userRuleSets.first { it.id == a }.domains)
        assertEquals(listOf("b1.example.com"), s().userRuleSets.first { it.id == b }.domains)

        vm.setRuleSetDomains(a, listOf("x.example.com", "y.example.com"))
        assertEquals(
            listOf("x.example.com", "y.example.com"),
            s().userRuleSets.first { it.id == a }.domains,
        )
        assertEquals(listOf("b1.example.com"), s().userRuleSets.first { it.id == b }.domains)
    }

    // ==================== 显示 IPv6：藏的是显示，不是功能 ====================
    //
    // 这个开关最容易被实现歪成「关掉 IPv6 支持」。真要那样，1.33.0 刚做的 v6 入站
    // 就被一个显示偏好废掉了，而且是静默的——v6 客户端连不上，日志里只有一条准入拒绝。
    // 所以这一节的每条断言都是在钉同一件事：过滤只发生在给人看的那一层。

    private fun iface6(ip: String) = ShareInterface(
        id = "wlan0/$ip", name = "wlan0", type = InterfaceType.WIFI,
        address = InetAddress.getByName(ip), prefixLength = 64,
        gatewayLike = false, isSelected = true, status = InterfaceStatus.UP,
    )

    private fun visible() = observe(vm.uiState).visibleInterfaces.map { it.displayAddress }

    @Test fun `默认隐藏 IPv6 接口而 IPv4 照常列出`() {
        shareFlow.value = ShareState(interfaces = listOf(iface("192.168.1.34"), iface6("fd00::1")))
        assertFalse(ProxySettings().showIpv6)
        assertEquals(listOf("192.168.1.34"), visible())
    }

    @Test fun `打开开关后 IPv6 出现且带方括号`() {
        shareFlow.value = ShareState(interfaces = listOf(iface("192.168.1.34"), iface6("fd00::1")))
        vm.setShowIpv6(true)
        // 方括号不是装饰——用户照着抄进别的设备，fd00::1:8080 是非法的（RFC 3986 §3.2.2）。
        //
        // ⚠️ 期望值里的 `fd00:0:0:0:0:0:0:1` 是 **JVM 的**输出：OpenJDK 的
        // `Inet6Address.getHostAddress()` 不做 `::` 压缩。**Android 上不是这样**——
        // libcore 走 inet_ntop，模拟器实测显示的是压缩形式 `[fec0::5054:ff:fe12:3456]`。
        // 所以别拿这一行去推断界面上的样子，两边平台行为不同。
        assertEquals(listOf("192.168.1.34", "[fd00:0:0:0:0:0:0:1]"), visible())
    }

    @Test fun `只有 IPv6 接口时照常显示——否则界面空白而共享其实是好的`() {
        shareFlow.value = ShareState(interfaces = listOf(iface6("fd00::1"), iface6("fd00::2")))
        // 全过滤掉的话，首页写着「无可共享接口」、入口卡空白，用户会以为软件坏了，
        // 而那比看到一串长地址严重得多。
        assertEquals(listOf("[fd00:0:0:0:0:0:0:1]", "[fd00:0:0:0:0:0:0:2]"), visible())
    }

    @Test fun `隐藏不影响准入——share 里仍是全量接口`() {
        val all = listOf(iface("192.168.1.34"), iface6("fd00::1"))
        shareFlow.value = ShareState(interfaces = all)
        // entrySubnets / accessController 喂的就是 share.interfaces。它一旦被过滤，
        // v6 客户端会被 fail-closed 拒掉，而 UI 上没有任何迹象说明为什么。
        assertEquals(all, observe(vm.uiState).share.interfaces)
    }

    @Test fun `IPv6 历史入口在隐藏时仍标为可用`() {
        val v6 = iface6("fd00::1")
        shareFlow.value = ShareState(interfaces = listOf(iface("192.168.1.34"), v6))
        // 历史里存的是 ProxyEntry.host，也就是 address.hostAddress——与接口同源同形式。
        // 手写 "fd00::1" 会因为压缩形式不同而对不上，那测的就不是这条性质了。
        historyFlow.value = listOf(ep(ProxyProtocol.HTTP, v6.address.hostAddress!!, 8080))
        // 可用性判定走全量接口。若跟着显示一起过滤，这条会被标成不可用——
        // 「隐藏了」就被读成了「连不上」，这正是要避免的那类误导。
        assertEquals(listOf(true), historyViews().map { it.available })
    }

    @Test fun `入口过滤认的是地址里的冒号`() {
        val v4 = ProxyEntry("192.168.1.34", 8080, ProxyProtocol.HTTP, "wlan0/192.168.1.34")
        val v6 = ProxyEntry("fd00::1", 8080, ProxyProtocol.HTTP, "wlan0/fd00::1")
        assertFalse(v4.isIpv6)
        assertTrue(v6.isIpv6)
        assertEquals(listOf(v4), visibleUnderIpv6Pref(listOf(v4, v6), showIpv6 = false) { it.isIpv6 })
        assertEquals(listOf(v4, v6), visibleUnderIpv6Pref(listOf(v4, v6), showIpv6 = true) { it.isIpv6 })
        // 纯 v6 兜底同样适用于入口：否则「开始共享」之后一个可填的地址都给不出来。
        assertEquals(listOf(v6), visibleUnderIpv6Pref(listOf(v6), showIpv6 = false) { it.isIpv6 })
    }
}
