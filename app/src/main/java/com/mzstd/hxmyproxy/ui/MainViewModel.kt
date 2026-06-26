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
import com.mzstd.hxmyproxy.core.model.VpnDownStrategy
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

    private val replayRequested = MutableStateFlow(false)

    /** 是否显示首次引导：null=加载中；未完成、或用户「重新查看」→ true。 */
    val showOnboarding: StateFlow<Boolean?> =
        combine(settingsRepository.onboardingCompleted, replayRequested) { done, replay ->
            !done || replay
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    /** 添加用户直连白名单域名（走出口分流：绕过共享 VPN）。 */
    fun addUserDirectRule(domain: String) = update {
        val d = domain.trim().lowercase().removePrefix("*.")
        if (d.isEmpty()) it else it.copy(userDirectRules = it.userDirectRules + d)
    }

    fun removeUserDirectRule(domain: String) = update {
        it.copy(userDirectRules = it.userDirectRules - domain)
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
        if (d.isEmpty()) it
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

    fun setAuthEnabled(v: Boolean) = update { it.copy(authEnabled = v) }

    /** 更新认证凭据（密码经 Keystore 加密后持久化）。 */
    fun setCredentials(username: String, password: String) {
        viewModelScope.launch { credentialStore.update(username.trim(), password) }
    }
    fun setVpnStrategy(s: VpnDownStrategy) = update { it.copy(vpnDownStrategy = s) }
    fun setMdnsEnabled(v: Boolean) = update { it.copy(mdnsEnabled = v) }
    fun setBlockPrivateLan(v: Boolean) = update { it.copy(blockPrivateLanEgress = v) }

    private fun Int.coercePort(): Int = coerceIn(1024, 65535)

    private fun update(transform: (ProxySettings) -> ProxySettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }
}
