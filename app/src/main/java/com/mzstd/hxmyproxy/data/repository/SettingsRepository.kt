package com.mzstd.hxmyproxy.data.repository

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mzstd.hxmyproxy.core.model.AppLanguage
import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.model.PerformancePreset
import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.model.VpnDownStrategy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "hxmy_settings")

/** [ProxySettings] 的持久化（DataStore Preferences），单一来源。 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val ds get() = context.settingsDataStore

    val settings: Flow<ProxySettings> = ds.data.map { it.toSettings() }

    suspend fun update(transform: (ProxySettings) -> ProxySettings) {
        ds.edit { prefs -> transform(prefs.toSettings()).writeTo(prefs) }
    }

    private companion object {
        val HTTP_ENABLED = booleanPreferencesKey("http_enabled")
        val SOCKS_ENABLED = booleanPreferencesKey("socks_enabled")
        val PAC_ENABLED = booleanPreferencesKey("pac_enabled")
        val HTTP_PORT = intPreferencesKey("http_port")
        val SOCKS_PORT = intPreferencesKey("socks_port")
        val PAC_PORT = intPreferencesKey("pac_port")
        val SELECTED = stringSetPreferencesKey("selected_interface_ids")
        val VPN_STRATEGY = stringPreferencesKey("vpn_down_strategy")
        val MDNS = booleanPreferencesKey("mdns_enabled")
        val AUTH = booleanPreferencesKey("auth_enabled")
        val BLOCK_PRIVATE = booleanPreferencesKey("block_private_lan")
        val PRESET = stringPreferencesKey("preset")
        val LIM_GLOBAL = intPreferencesKey("lim_global")
        val LIM_PER_CLIENT = intPreferencesKey("lim_per_client")
        val LIM_PARALLEL = intPreferencesKey("lim_parallel")
        val LIM_BUFFER = intPreferencesKey("lim_buffer")
        val LIM_IDLE = intPreferencesKey("lim_idle")
        val LANGUAGE = stringPreferencesKey("language")
    }

    private fun Preferences.toSettings(): ProxySettings {
        val d = ProxySettings()
        val limits = ConnectionLimits(
            maxGlobalConnections = this[LIM_GLOBAL] ?: d.limits.maxGlobalConnections,
            maxPerClientConnections = this[LIM_PER_CLIENT] ?: d.limits.maxPerClientConnections,
            relayParallelism = this[LIM_PARALLEL] ?: d.limits.relayParallelism,
            relayBufferBytes = this[LIM_BUFFER] ?: d.limits.relayBufferBytes,
            idleTimeoutSeconds = this[LIM_IDLE] ?: d.limits.idleTimeoutSeconds,
        ).coerced()
        return ProxySettings(
            httpEnabled = this[HTTP_ENABLED] ?: d.httpEnabled,
            socksEnabled = this[SOCKS_ENABLED] ?: d.socksEnabled,
            pacEnabled = this[PAC_ENABLED] ?: d.pacEnabled,
            httpPort = this[HTTP_PORT] ?: d.httpPort,
            socksPort = this[SOCKS_PORT] ?: d.socksPort,
            pacPort = this[PAC_PORT] ?: d.pacPort,
            selectedInterfaceIds = this[SELECTED] ?: d.selectedInterfaceIds,
            vpnDownStrategy = this[VPN_STRATEGY]?.let { runCatching { VpnDownStrategy.valueOf(it) }.getOrNull() } ?: d.vpnDownStrategy,
            mdnsEnabled = this[MDNS] ?: d.mdnsEnabled,
            authEnabled = this[AUTH] ?: d.authEnabled,
            blockPrivateLanEgress = this[BLOCK_PRIVATE] ?: d.blockPrivateLanEgress,
            preset = this[PRESET]?.let { runCatching { PerformancePreset.valueOf(it) }.getOrNull() } ?: d.preset,
            limits = limits,
            language = this[LANGUAGE]?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() } ?: d.language,
        )
    }

    private fun ProxySettings.writeTo(prefs: MutablePreferences) {
        prefs[HTTP_ENABLED] = httpEnabled
        prefs[SOCKS_ENABLED] = socksEnabled
        prefs[PAC_ENABLED] = pacEnabled
        prefs[HTTP_PORT] = httpPort
        prefs[SOCKS_PORT] = socksPort
        prefs[PAC_PORT] = pacPort
        prefs[SELECTED] = selectedInterfaceIds
        prefs[VPN_STRATEGY] = vpnDownStrategy.name
        prefs[MDNS] = mdnsEnabled
        prefs[AUTH] = authEnabled
        prefs[BLOCK_PRIVATE] = blockPrivateLanEgress
        prefs[PRESET] = preset.name
        prefs[LIM_GLOBAL] = limits.maxGlobalConnections
        prefs[LIM_PER_CLIENT] = limits.maxPerClientConnections
        prefs[LIM_PARALLEL] = limits.relayParallelism
        prefs[LIM_BUFFER] = limits.relayBufferBytes
        prefs[LIM_IDLE] = limits.idleTimeoutSeconds
        prefs[LANGUAGE] = language.name
    }
}
