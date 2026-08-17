package com.mzstd.hxmyproxy.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mzstd.hxmyproxy.core.model.AppLanguage
import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.model.HistoryEndpoint
import com.mzstd.hxmyproxy.core.model.HistoryEndpointView
import com.mzstd.hxmyproxy.core.model.PerformancePreset
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.model.ShareInterface
import com.mzstd.hxmyproxy.core.model.ShareState
import com.mzstd.hxmyproxy.core.model.EgressNetworkChoice
import com.mzstd.hxmyproxy.core.model.RuleEntry
import com.mzstd.hxmyproxy.core.model.visibleUnderIpv6Pref
import com.mzstd.hxmyproxy.data.repository.CredentialStore
import com.mzstd.hxmyproxy.data.repository.EndpointHistoryRepository
import com.mzstd.hxmyproxy.data.repository.ProxyServerRepository
import com.mzstd.hxmyproxy.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 屏幕状态 = 引擎运行态 + 用户设置 + 历史入口（单一不可变 uiState）。 */
data class MainUiState(
    val share: ShareState = ShareState(),
    val settings: ProxySettings = ProxySettings(),
    val history: List<HistoryEndpointView> = emptyList(),
    val credentials: CredentialStore.Credentials = CredentialStore.Credentials(),
) {
    /**
     * 界面该展示的接口列表（受「显示 IPv6」偏好过滤）。
     *
     * **凡是给用户看的接口列表都用这个，别用 [share].interfaces**——后者是全量，
     * 供准入、历史入口可用性判定、诊断使用，那些地方必须看见 v6，否则会把
     * 「隐藏了」误判成「不存在」。
     */
    val visibleInterfaces: List<ShareInterface>
        get() = visibleUnderIpv6Pref(share.interfaces, settings.showIpv6) { it.isIpv6 }

    /**
     * 因「显示 IPv6」关着而**没被列出来**的接口数;为 0 表示没有东西被藏。
     *
     * 界面必须据此留一行痕迹。第一版藏得一点提示都没有,用户的第一反应是
     * 「IPv6 代理被取消了」——**找不到的功能和删掉没有区别**。
     * 用 visibleInterfaces 反推而不是自己数一遍,是为了让它和实际展示的口径
     * 永远一致(含「全是 v6 时不过滤」那条兜底)。
     */
    val hiddenIpv6Count: Int get() = share.interfaces.size - visibleInterfaces.size
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val proxyServerRepository: ProxyServerRepository,
    private val endpointHistoryRepository: EndpointHistoryRepository,
    private val credentialStore: CredentialStore,
    private val ruleEngine: com.mzstd.hxmyproxy.core.rules.RuleEngine,
) : ViewModel() {

    val uiState: StateFlow<MainUiState> =
        combine(
            proxyServerRepository.state,
            settingsRepository.settings,
            endpointHistoryRepository.history,
            credentialStore.credentials,
        ) { share, settings, history, credentials ->
            MainUiState(share, settings, historyViews(share, settings, history), credentials)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    /** 某 host 的**实时完整判定**（含内置组，按引擎优先级：你的覆盖/列表 > 内置广告 > 内置直连 > 兜底代理）。
     *  top domain 标据此显示，配合 [ruleVersion] 规则一变即重判——不再用运行时缓存（修「移除规则还显示直连」）。 */
    fun decideHost(host: String): com.mzstd.hxmyproxy.core.rules.RuleAction = ruleEngine.decide(host)

    /** 规则版本信号：规则一变 +1，供 top domain 标订阅刷新（触发重判所有域名）。 */
    val ruleVersion: kotlinx.coroutines.flow.StateFlow<Int> = ruleEngine.version

    /** 手动刷新服务：状态流 + 触发/确认（见 [ProxyServerRepository.manualReset] 的三段说明）。 */
    val manualResetState = proxyServerRepository.resetState
    fun manualReset() = proxyServerRepository.manualReset()
    fun ackManualReset() = proxyServerRepository.ackManualReset()

    /** 速率历史（最近 60 个 1s 样本，字节/秒），监控/首页 sparkline 用；停止即清空。 */
    data class RateHistory(val down: List<Float> = emptyList(), val up: List<Float> = emptyList())

    val rateHistory: StateFlow<RateHistory> = kotlinx.coroutines.flow.flow {
        val down = ArrayDeque<Float>()
        val up = ArrayDeque<Float>()
        while (true) {
            val s = uiState.value.share
            if (s.running) {
                down.addLast(s.downloadRateBps.toFloat())
                up.addLast(s.uploadRateBps.toFloat())
                while (down.size > 60) down.removeFirst()
                while (up.size > 60) up.removeFirst()
            } else {
                down.clear()
                up.clear()
            }
            emit(RateHistory(down.toList(), up.toList()))
            kotlinx.coroutines.delay(1_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RateHistory())

    private val replayRequested = MutableStateFlow(false)

    /** 是否显示首次引导：null=加载中；未完成、或用户「重新查看」→ true。 */
    val showOnboarding: StateFlow<Boolean?> =
        combine(settingsRepository.onboardingCompleted, replayRequested) { done, replay ->
            !done || replay
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 访问过的域名历史（持久），供规则页「从历史添加」白名单。 */
    val domainHistory: StateFlow<Set<String>> =
        settingsRepository.domainHistory.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** 走完/跳过引导：清「重看」请求并持久化完成标志。 */
    fun completeOnboarding() {
        replayRequested.value = false
        viewModelScope.launch { settingsRepository.setOnboardingCompleted(true) }
    }

    /** 从设置里「重新查看引导」。 */
    fun replayOnboarding() { replayRequested.value = true }

    /** 历史入口可用性：IP 仍是某个当前接口地址、且端口与当前对应协议配置一致。 */
    private fun historyViews(
        share: ShareState,
        settings: ProxySettings,
        history: List<HistoryEndpoint>,
    ): List<HistoryEndpointView> {
        val ips = share.interfaces.mapNotNull { it.address.hostAddress }.toSet()
        return history.map { ep ->
            val portOk = ep.port == when (ep.protocol) {
                ProxyProtocol.SOCKS5 -> settings.socksPort
                ProxyProtocol.HTTP -> settings.httpPort
                ProxyProtocol.PAC -> settings.pacPort
            }
            HistoryEndpointView(ep, ep.ip in ips && portOk)
        }
    }

    fun removeHistoryEndpoint(entry: HistoryEndpoint) {
        viewModelScope.launch { endpointHistoryRepository.remove(entry) }
    }

    init {
        // 停止态也扫描接口，让用户先选接口再启动
        viewModelScope.launch { proxyServerRepository.refreshInterfaces() }
    }

    /** 重新扫描接口（停止态）。 */
    fun refreshInterfaces() {
        viewModelScope.launch { proxyServerRepository.refreshInterfaces() }
    }

    fun setLanguage(language: AppLanguage) = update { it.copy(language = language) }

    fun setThemeMode(mode: com.mzstd.hxmyproxy.core.model.ThemeMode) = update { it.copy(themeMode = mode) }

    /** 隐藏/恢复顶层 tab（仅监控/规则会被传入；主页/设置在 UI 层无入口且过滤时强制保留）。 */
    fun setTabHidden(route: String, hidden: Boolean) = update {
        it.copy(hiddenTabs = if (hidden) it.hiddenTabs + route else it.hiddenTabs - route)
    }

    fun setPreset(preset: PerformancePreset) = update {
        it.copy(preset = preset, limits = if (preset == PerformancePreset.CUSTOM) it.limits else preset.toLimits())
    }

    fun setCustomLimits(limits: ConnectionLimits) =
        update { it.copy(preset = PerformancePreset.CUSTOM, limits = limits.coerced()) }

    fun setHttpEnabled(v: Boolean) = update { it.copy(httpEnabled = v) }
    fun setSocksEnabled(v: Boolean) = update { it.copy(socksEnabled = v) }
    fun setPacEnabled(v: Boolean) = update { it.copy(pacEnabled = v) }

    fun setHttpPort(p: Int) = update { it.copy(httpPort = p.coercePort()) }
    fun setSocksPort(p: Int) = update { it.copy(socksPort = p.coercePort()) }
    fun setPacPort(p: Int) = update { it.copy(pacPort = p.coercePort()) }

    fun toggleInterface(id: String, selected: Boolean) = update {
        it.copy(selectedInterfaceIds = if (selected) it.selectedInterfaceIds + id else it.selectedInterfaceIds - id)
    }

    /** 界面是否展示 IPv6 接口与入口。纯展示开关，不影响准入与转发。 */
    fun setShowIpv6(v: Boolean) = update { it.copy(showIpv6 = v) }

    /** 启用/停用一个内置规则组（广告表等）；存入 enabledRuleGroups。 */
    fun toggleRuleGroup(id: String, on: Boolean) = update {
        it.copy(enabledRuleGroups = if (on) it.enabledRuleGroups + id else it.enabledRuleGroups - id)
    }

    /** 一键开/关某一分类下的全部内置组。 */
    fun setCategoryEnabled(cat: com.mzstd.hxmyproxy.core.rules.RuleCategory, on: Boolean) = update {
        val ids = com.mzstd.hxmyproxy.core.rules.RuleCatalog.all.filter { g -> g.category == cat }.map { g -> g.id }.toSet()
        it.copy(enabledRuleGroups = if (on) it.enabledRuleGroups + ids else it.enabledRuleGroups - ids)
    }

    /** 一键开/关全部内置规则集（所有分类所有组）。 */
    fun setAllBuiltinEnabled(on: Boolean) = update {
        val ids = com.mzstd.hxmyproxy.core.rules.RuleCatalog.all.map { g -> g.id }.toSet()
        it.copy(enabledRuleGroups = if (on) it.enabledRuleGroups + ids else it.enabledRuleGroups - ids)
    }

    /** IP/域名白名单整体开关（关掉则整组临时失效、数据保留）。 */
    fun toggleUserDirectEnabled(on: Boolean) = update { it.copy(userDirectEnabled = on) }
    fun toggleUserRejectEnabled(on: Boolean) = update { it.copy(userRejectEnabled = on) }

    /**
     * 归一化用户输入的规则：保留作用域前缀（`*.` 单级 / `=` 精确 / 无前缀全层级），
     * 只对**裸域名**部分做小写与合法性校验。返回 null 表示非法。
     *
     * 注意与旧版的区别：以前无条件 `removePrefix("*.")` 把通配前缀吃掉、一律当全层级；
     * 现在前缀是语义的一部分，必须原样存进 RuleEntry.value，由 RuleMatcher 解析。
     */
    private fun normalizeRule(input: String): String? {
        val t = input.trim().lowercase()
        val (scope, bare) = com.mzstd.hxmyproxy.core.rules.RuleScope.parse(t)
        // 必须含 '.'（域名/IPv4/CIDR）或 ':'（IPv6）：拒绝单段(如 "com")整段 TLD，防误杀/误放行。
        if (bare.isEmpty() || (!bare.contains('.') && !bare.contains(':'))) return null
        // 内部不得含空白：粘贴或输入法误入的 "by wxs.qq.com" 这类串以前能存进列表，
        // 却永远匹配不上任何 host——静默的死规则，用户只会觉得「我明明加了却没生效」。
        if (bare.any { it.isWhitespace() }) return null
        // 裸域名里不得残留作用域符号。RuleScope.parse 只剥**一层**前缀，于是这几种手滑写法
        // 会把符号留在 bare 里：`*apple.com`（漏了点，SUFFIX + "*apple.com"）、
        // `==a.com`（EXACT + "=a.com"）、`=*.a.com`（EXACT + "*.a.com"）。
        // 它们能通过上面全部校验、存进列表、显示在规则页、装进字典树，却永远匹配不到任何 host
        // ——与上一条防的是同一类静默死规则，只是形态不同。
        if (bare.any { it == '*' || it == '=' }) return null
        return com.mzstd.hxmyproxy.core.rules.RuleScope.format(scope, bare)
    }

    /** 添加用户直连白名单域名（走出口分流：绕过共享 VPN）。 */
    fun addUserDirectRule(domain: String) = update {
        val d = normalizeRule(domain)
        if (d == null || it.userDirectRules.any { e -> e.value == d }) it
        else it.copy(userDirectRules = it.userDirectRules + RuleEntry(d, addedAt = System.currentTimeMillis()))
    }

    /** 添加快速拦截名单（域名/IP/CIDR/IPv6）；进 userReject 表，命中即拒绝连接。 */
    fun addUserRejectRule(rule: String) = update {
        val d = normalizeRule(rule)
        if (d == null || it.userRejectRules.any { e -> e.value == d }) it
        else it.copy(userRejectRules = it.userRejectRules + RuleEntry(d, addedAt = System.currentTimeMillis()))
    }

    /**
     * 就地改写一条规则（改域名/IP 本身，或只改作用域档位——后者在数据上就是改前缀）。
     *
     * **保留 addedAt / enabled / disabledAt**:这是「修改同一条规则」，不是删了重加。
     * addedAt 还是 most-specific-wins 同分时的兜底裁决依据（见 RuleEngine），改个作用域档位
     * 就把它刷新成"最新"，会让规则间的相对优先级莫名其妙地变——用户完全无从察觉。
     *
     * 校验与新增同一条路径（[normalizeRule]）：非法值、与**其它**条目重复都原样返回不改。
     * 改成自身值（只调了大小写/前缀又改回来）视为无操作，不算重复。
     */
    fun updateUserRejectRule(old: String, new: String) = update { s ->
        val d = normalizeRule(new) ?: return@update s
        if (d != old && s.userRejectRules.any { e -> e.value == d }) return@update s
        s.copy(userRejectRules = s.userRejectRules.map { e -> if (e.value == old) e.copy(value = d) else e })
    }

    /** 就地改写一条白名单规则（语义同 [updateUserRejectRule]）。 */
    fun updateUserDirectRule(old: String, new: String) = update { s ->
        val d = normalizeRule(new) ?: return@update s
        if (d != old && s.userDirectRules.any { e -> e.value == d }) return@update s
        s.copy(userDirectRules = s.userDirectRules.map { e -> if (e.value == old) e.copy(value = d) else e })
    }

    /** 移除快速拦截名单。 */
    fun removeUserRejectRule(rule: String) = update {
        it.copy(userRejectRules = it.userRejectRules.filterNot { e -> e.value == rule })
    }

    /** 切换单条快速拦截的启用/停用（停用=不参与判定、走默认，**不切到反面**）。停用时记停用时间用于排序。 */
    fun toggleUserRejectRule(value: String) = update {
        val now = System.currentTimeMillis()
        it.copy(userRejectRules = it.userRejectRules.map { e ->
            if (e.value == value) e.copy(enabled = !e.enabled, disabledAt = if (e.enabled) now else e.disabledAt) else e
        })
    }

    /** 切换单条白名单的启用/停用（同上，对称）。 */
    fun toggleUserDirectRule(value: String) = update {
        val now = System.currentTimeMillis()
        it.copy(userDirectRules = it.userDirectRules.map { e ->
            if (e.value == value) e.copy(enabled = !e.enabled, disabledAt = if (e.enabled) now else e.disabledAt) else e
        })
    }

    // —— top domain 便捷设规则：统一写入 block/allow 列表（免手输），互斥（一个域名同刻只在一个列表）——
    /**
     * 点域名设规则时写成**单级**（`*.host`）：列表里点的是实际访问过的具体域名，用户意图是
     * 「这个域名」而非它底下的整棵子树；单级既覆盖自身、又容忍一层子域，与输入框的默认档位一致。
     */
    private fun scopedHost(host: String): String? {
        val bare = com.mzstd.hxmyproxy.core.rules.RuleScope.parse(host.trim().lowercase()).second
        if (bare.isEmpty()) return null
        // IP/CIDR 无作用域概念，原样写入。
        if (com.mzstd.hxmyproxy.core.rules.IpCidrSet.looksLikeIpOrCidr(bare)) return bare
        return com.mzstd.hxmyproxy.core.rules.RuleScope.format(
            com.mzstd.hxmyproxy.core.rules.RuleScope.SINGLE, bare,
        )
    }

    /** 互斥比较按**裸域名**：同一域名的不同作用域写法（`a.com` / `*.a.com` / `=a.com`）视为同一条，
     *  否则「设直连」时移不掉 block 里前缀不同的同名规则，两边并存反而要靠具体度裁决。 */
    private fun sameHost(entryValue: String, bare: String): Boolean =
        com.mzstd.hxmyproxy.core.rules.RuleScope.parse(entryValue).second == bare

    /** 直连：进 allow 列表 + 从 block 互斥移除。 */
    fun setDomainDirect(host: String) = update { s ->
        val v = scopedHost(host) ?: return@update s
        val bare = com.mzstd.hxmyproxy.core.rules.RuleScope.parse(v).second
        s.copy(
            userRejectRules = s.userRejectRules.filterNot { sameHost(it.value, bare) },
            userDirectRules = if (s.userDirectRules.any { sameHost(it.value, bare) }) s.userDirectRules
            else s.userDirectRules + RuleEntry(v, addedAt = System.currentTimeMillis()),
        )
    }

    /** 拦截：进 block 列表 + 从 allow 互斥移除。 */
    fun setDomainReject(host: String) = update { s ->
        val v = scopedHost(host) ?: return@update s
        val bare = com.mzstd.hxmyproxy.core.rules.RuleScope.parse(v).second
        s.copy(
            userDirectRules = s.userDirectRules.filterNot { sameHost(it.value, bare) },
            userRejectRules = if (s.userRejectRules.any { sameHost(it.value, bare) }) s.userRejectRules
            else s.userRejectRules + RuleEntry(v, addedAt = System.currentTimeMillis()),
        )
    }

    /** 清除：从 block/allow 两列表都移除该域名（回默认判定），不论它是以哪种作用域写入的。 */
    fun clearDomainRule(host: String) = update { s ->
        val bare = com.mzstd.hxmyproxy.core.rules.RuleScope.parse(host.trim().lowercase()).second
        s.copy(
            userDirectRules = s.userDirectRules.filterNot { sameHost(it.value, bare) },
            userRejectRules = s.userRejectRules.filterNot { sameHost(it.value, bare) },
        )
    }

    /** 把内置 app/服务组在规则页「放行 ↔ 拦截」两行间移动（拦截行=该组域名进 reject 表）。 */
    fun setGroupRejected(id: String, rejected: Boolean) = update {
        it.copy(rejectedGroups = if (rejected) it.rejectedGroups + id else it.rejectedGroups - id)
    }

    /** 切换用户自建集的动作（放行 ↔ 拦截），规则页两行 ⇄ 徽标用。 */
    fun setRuleSetAction(id: String, action: com.mzstd.hxmyproxy.core.rules.RuleAction) = update {
        it.copy(userRuleSets = it.userRuleSets.map { s -> if (s.id == id) s.copy(action = action) else s })
    }

    fun removeUserDirectRule(domain: String) {
        update { it.copy(userDirectRules = it.userDirectRules.filterNot { e -> e.value == domain }) }
        // 记入「移除历史」,供「从历史添加」快速加回(只记加过又删的、量小且精准)
        viewModelScope.launch { settingsRepository.addDomainHistory(listOf(domain)) }
    }

    // —— 用户自建规则集（规则集管理界面）——
    fun addRuleSet(name: String, action: com.mzstd.hxmyproxy.core.rules.RuleAction) = update {
        val set = com.mzstd.hxmyproxy.core.rules.UserRuleSet(
            id = java.util.UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "·" },
            action = action,
        )
        it.copy(userRuleSets = it.userRuleSets + set)
    }

    fun deleteRuleSet(id: String) = update {
        it.copy(userRuleSets = it.userRuleSets.filterNot { s -> s.id == id })
    }

    fun toggleRuleSet(id: String, enabled: Boolean) = update {
        it.copy(userRuleSets = it.userRuleSets.map { s -> if (s.id == id) s.copy(enabled = enabled) else s })
    }

    fun addDomainToSet(id: String, domain: String) = update {
        val d = domain.trim().lowercase().removePrefix("*.")
        if (!d.contains('.')) it
        else it.copy(userRuleSets = it.userRuleSets.map { s ->
            if (s.id == id && d !in s.domains) s.copy(domains = s.domains + d) else s
        })
    }

    fun removeDomainFromSet(id: String, domain: String) = update {
        it.copy(userRuleSets = it.userRuleSets.map { s ->
            if (s.id == id) s.copy(domains = s.domains - domain) else s
        })
    }

    /** 批量设置某用户集的域名（多行文本编辑保存）。 */
    fun setRuleSetDomains(id: String, domains: List<String>) = update {
        it.copy(userRuleSets = it.userRuleSets.map { s -> if (s.id == id) s.copy(domains = domains) else s })
    }

    /** 覆盖某内置集的域名（多行文本编辑保存）。 */
    fun setGroupOverride(groupId: String, domains: List<String>) = update {
        it.copy(ruleSetOverrides = it.ruleSetOverrides + (groupId to domains))
    }

    /** 恢复内置集为默认（删除覆盖）。 */
    fun clearGroupOverride(groupId: String) = update {
        it.copy(ruleSetOverrides = it.ruleSetOverrides - groupId)
    }

    /** 设置某 host 的三态覆盖（最高优先级：误拦救济/手动指定）；host 支持泛域名/IP/CIDR。 */
    fun setHostOverride(host: String, action: com.mzstd.hxmyproxy.core.rules.RuleAction) = update {
        val h = host.trim().lowercase()
        if (h.isEmpty()) it else it.copy(hostOverrides = it.hostOverrides + (h to action))
    }

    /**
     * 把某 host 从「直连」改回「走代理」，用于防护页的「直连不通」一键修复。
     *
     * 语义要点：用户点「允许」时想要的是「别拦它」，而三态里对应的其实是 **PROXY（走 VPN）**；
     * 「直连」是绕过 VPN——在 VPN 在线时这两个是相反的结果。真机上正是这个错配导致
     * 遥测域名被设成直连后连不通，每次白等一个超时（237 次）。
     * 同时清掉失败计数，让该行立刻从提示里消失，不必等下一次连接成功。
     */
    fun switchToProxyEgress(host: String) {
        setHostOverride(host, com.mzstd.hxmyproxy.core.rules.RuleAction.PROXY)
        com.mzstd.hxmyproxy.core.proxy.DirectEgressFailures.forget(host)
    }

    /** 移除某 host 的覆盖（回归默认规则链）。 */
    fun clearHostOverride(host: String) = update {
        it.copy(hostOverrides = it.hostOverrides - host.trim().lowercase())
    }

    fun setAuthEnabled(v: Boolean) = update { it.copy(authEnabled = v) }

    /** 更新认证凭据（密码经 Keystore 加密后持久化）。 */
    fun setCredentials(username: String, password: String) {
        viewModelScope.launch { credentialStore.update(username.trim(), password) }
    }
    fun setEgressChoice(c: EgressNetworkChoice) = update { it.copy(egressChoice = c) }
    fun setDirectEgressChoice(c: com.mzstd.hxmyproxy.core.model.DirectEgressChoice) = update { it.copy(directEgressChoice = c) }
    fun confirmCellularEgress() = update { it.copy(cellularEgressConfirmed = true) }

    /** 诊断日志总开关。关闭后不再写盘（已有日志保留，仍可查看/导出）。 */
    fun setLogEnabled(on: Boolean) = update { it.copy(logEnabled = on) }

    // —— 设置备份：换机/重装迁移（关闭系统云备份后，这是保住配置的唯一途径）——
    /** 导出全部设置为 JSON 文本（不含代理凭据）。 */
    fun exportSettings(onDone: (Result<String>) -> Unit) = viewModelScope.launch {
        onDone(runCatching { settingsRepository.exportJson() })
    }

    /** 从 JSON 恢复设置（整体替换）；回调返回恢复的条目数或失败原因。 */
    fun importSettings(json: String, onDone: (Result<Int>) -> Unit) = viewModelScope.launch {
        onDone(runCatching { settingsRepository.importJson(json) })
    }
    fun setBackupDnsEnabled(v: Boolean) = update { it.copy(backupDnsEnabled = v) }

    /** 备用 DNS(DoH)走哪张网。见 [com.mzstd.hxmyproxy.core.model.DohEgressChoice]。 */
    fun setDohEgressChoice(c: com.mzstd.hxmyproxy.core.model.DohEgressChoice) =
        update { it.copy(dohEgressChoice = c) }
    /** 指定出口连不通时：断开还是降级。见 [com.mzstd.hxmyproxy.core.model.EgressFallback]。 */
    fun setEgressFallback(c: com.mzstd.hxmyproxy.core.model.EgressFallback) =
        update { it.copy(egressFallback = c) }
    fun setBlockPrivateLan(v: Boolean) = update { it.copy(blockPrivateLanEgress = v) }

    private fun Int.coercePort(): Int = coerceIn(1024, 65535)

    private fun update(transform: (ProxySettings) -> ProxySettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }
}
