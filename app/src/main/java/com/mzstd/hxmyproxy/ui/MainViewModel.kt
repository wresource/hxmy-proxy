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
import com.mzstd.hxmyproxy.core.model.ShareState
import com.mzstd.hxmyproxy.core.model.EgressNetworkChoice
import com.mzstd.hxmyproxy.core.model.RuleEntry
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
)

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

    /** 添加用户直连白名单域名（走出口分流：绕过共享 VPN）。 */
    fun addUserDirectRule(domain: String) = update {
        val d = domain.trim().lowercase().removePrefix("*.")
        // 必须含 '.'（域名/IPv4/CIDR）或 ':'（IPv6）：拒绝单段(如 "com")整段 TLD，防误杀/误放行。已存在则不重复加。
        if ((!d.contains('.') && !d.contains(':')) || it.userDirectRules.any { e -> e.value == d }) it
        else it.copy(userDirectRules = it.userDirectRules + RuleEntry(d, addedAt = System.currentTimeMillis()))
    }

    /** 添加快速拦截名单（域名/IP/CIDR/IPv6）；进 userReject 表，命中即拒绝连接。 */
    fun addUserRejectRule(rule: String) = update {
        val d = rule.trim().lowercase().removePrefix("*.")
        if ((!d.contains('.') && !d.contains(':')) || it.userRejectRules.any { e -> e.value == d }) it
        else it.copy(userRejectRules = it.userRejectRules + RuleEntry(d, addedAt = System.currentTimeMillis()))
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
    /** 直连：进 allow 列表 + 从 block 互斥移除。 */
    fun setDomainDirect(host: String) = update {
        val h = host.trim().lowercase().removePrefix("*.")
        if (h.isEmpty()) it else it.copy(
            userRejectRules = it.userRejectRules.filterNot { e -> e.value == h },
            userDirectRules = if (it.userDirectRules.any { e -> e.value == h }) it.userDirectRules
            else it.userDirectRules + RuleEntry(h, addedAt = System.currentTimeMillis()),
        )
    }

    /** 拦截：进 block 列表 + 从 allow 互斥移除。 */
    fun setDomainReject(host: String) = update {
        val h = host.trim().lowercase().removePrefix("*.")
        if (h.isEmpty()) it else it.copy(
            userDirectRules = it.userDirectRules.filterNot { e -> e.value == h },
            userRejectRules = if (it.userRejectRules.any { e -> e.value == h }) it.userRejectRules
            else it.userRejectRules + RuleEntry(h, addedAt = System.currentTimeMillis()),
        )
    }

    /** 清除：从 block/allow 两列表都移除该域名（回默认判定）。 */
    fun clearDomainRule(host: String) = update {
        val h = host.trim().lowercase().removePrefix("*.")
        it.copy(
            userDirectRules = it.userDirectRules.filterNot { e -> e.value == h },
            userRejectRules = it.userRejectRules.filterNot { e -> e.value == h },
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
    fun setMdnsEnabled(v: Boolean) = update { it.copy(mdnsEnabled = v) }

    fun setBackupDnsEnabled(v: Boolean) = update { it.copy(backupDnsEnabled = v) }
    fun setBlockPrivateLan(v: Boolean) = update { it.copy(blockPrivateLanEgress = v) }

    private fun Int.coercePort(): Int = coerceIn(1024, 65535)

    private fun update(transform: (ProxySettings) -> ProxySettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }
}
