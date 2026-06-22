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
