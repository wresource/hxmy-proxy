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
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.UserRuleSet
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

    /** 首次引导是否已完成（独立于代理配置的一次性标志）。 */
    val onboardingCompleted: Flow<Boolean> = ds.data.map { it[ONBOARDING_DONE] ?: false }

    suspend fun setOnboardingCompleted(done: Boolean) {
        ds.edit { it[ONBOARDING_DONE] = done }
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
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_completed")
        val RULE_ENABLED = booleanPreferencesKey("rule_engine_enabled")
        val RULE_GROUPS = stringSetPreferencesKey("enabled_rule_groups")
        val USER_DIRECT = stringSetPreferencesKey("user_direct_rules")
        val RULE_SUBS = stringSetPreferencesKey("rule_subscription_urls")
        val USER_RULE_SETS = stringPreferencesKey("user_rule_sets")
        val RULE_OVERRIDES = stringPreferencesKey("rule_set_overrides")
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
            ruleEngineEnabled = this[RULE_ENABLED] ?: d.ruleEngineEnabled,
            enabledRuleGroups = this[RULE_GROUPS] ?: d.enabledRuleGroups,
            userDirectRules = this[USER_DIRECT] ?: d.userDirectRules,
            userRuleSets = decodeRuleSets(this[USER_RULE_SETS]),
            ruleSetOverrides = decodeOverrides(this[RULE_OVERRIDES]),
            ruleSubscriptionUrls = this[RULE_SUBS] ?: d.ruleSubscriptionUrls,
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
        prefs[RULE_ENABLED] = ruleEngineEnabled
        prefs[RULE_GROUPS] = enabledRuleGroups
        prefs[USER_DIRECT] = userDirectRules
        prefs[USER_RULE_SETS] = encodeRuleSets(userRuleSets)
        prefs[RULE_OVERRIDES] = encodeOverrides(ruleSetOverrides)
        prefs[RULE_SUBS] = ruleSubscriptionUrls
    }

    private fun encodeRuleSets(sets: List<UserRuleSet>): String {
        val arr = org.json.JSONArray()
        sets.forEach { s ->
            arr.put(
                org.json.JSONObject()
                    .put("id", s.id).put("name", s.name).put("action", s.action.name)
                    .put("enabled", s.enabled).put("domains", org.json.JSONArray(s.domains)),
            )
        }
        return arr.toString()
    }

    private fun decodeRuleSets(json: String?): List<UserRuleSet> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val d = o.optJSONArray("domains")
                UserRuleSet(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    action = runCatching { RuleAction.valueOf(o.optString("action")) }.getOrDefault(RuleAction.DIRECT),
                    domains = if (d == null) emptyList() else (0 until d.length()).map { idx -> d.getString(idx) },
                    enabled = o.optBoolean("enabled", true),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun encodeOverrides(m: Map<String, List<String>>): String {
        val o = org.json.JSONObject()
        m.forEach { (k, v) -> o.put(k, org.json.JSONArray(v)) }
        return o.toString()
    }

    private fun decodeOverrides(json: String?): Map<String, List<String>> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val o = org.json.JSONObject(json)
            o.keys().asSequence().associateWith { k ->
                val a = o.getJSONArray(k)
                (0 until a.length()).map { a.getString(it) }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
