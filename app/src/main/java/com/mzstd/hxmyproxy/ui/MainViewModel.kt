package com.mzstd.hxmyproxy.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mzstd.hxmyproxy.core.model.AppLanguage
import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.model.PerformancePreset
import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.model.ShareState
import com.mzstd.hxmyproxy.core.model.VpnDownStrategy
import com.mzstd.hxmyproxy.data.repository.ProxyServerRepository
import com.mzstd.hxmyproxy.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 屏幕状态 = 引擎运行态 + 用户设置（单一不可变 uiState）。 */
data class MainUiState(
    val share: ShareState = ShareState(),
    val settings: ProxySettings = ProxySettings(),
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    proxyServerRepository: ProxyServerRepository,
) : ViewModel() {

    val uiState: StateFlow<MainUiState> =
        combine(proxyServerRepository.state, settingsRepository.settings) { share, settings ->
            MainUiState(share, settings)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

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
    fun setVpnStrategy(s: VpnDownStrategy) = update { it.copy(vpnDownStrategy = s) }
    fun setMdnsEnabled(v: Boolean) = update { it.copy(mdnsEnabled = v) }
    fun setBlockPrivateLan(v: Boolean) = update { it.copy(blockPrivateLanEgress = v) }

    private fun Int.coercePort(): Int = coerceIn(1024, 65535)

    private fun update(transform: (ProxySettings) -> ProxySettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }
}
