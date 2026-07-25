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
import com.mzstd.hxmyproxy.core.model.ThemeMode
import com.mzstd.hxmyproxy.core.model.DirectEgressChoice
import com.mzstd.hxmyproxy.core.model.RuleEntry
import com.mzstd.hxmyproxy.core.model.EgressNetworkChoice
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.UserRuleSet
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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

    /**
     * 导出全部设置为 JSON（换机/重装迁移用）。**不含代理凭据**——那是单独加密存储的，
     * 不落进可分享的明文备份。
     *
     * 采用「遍历 DataStore 键值 + 带类型标记」的通用做法而非逐字段手写：新增设置项自动进备份，
     * 不会因为漏改这里而静默丢配置。
     */
    suspend fun exportJson(): String {
        val prefs = ds.data.first()
        val items = org.json.JSONObject()
        prefs.asMap().forEach { (k, v) ->
            val o = org.json.JSONObject()
            when (v) {
                is Boolean -> { o.put("t", "b"); o.put("v", v) }
                is Int -> { o.put("t", "i"); o.put("v", v) }
                is Long -> { o.put("t", "l"); o.put("v", v) }
                is Float -> { o.put("t", "f"); o.put("v", v.toDouble()) }
                is Double -> { o.put("t", "d"); o.put("v", v) }
                is String -> { o.put("t", "s"); o.put("v", v) }
                is Set<*> -> { o.put("t", "ss"); o.put("v", org.json.JSONArray(v.toList())) }
                else -> return@forEach
            }
            items.put(k.name, o)
        }
        return org.json.JSONObject()
            .put("app", BACKUP_APP_ID)
            .put("format", BACKUP_FORMAT)
            .put("exportedAt", System.currentTimeMillis())
            .put("settings", items)
            .toString(2)
    }

    /**
     * 从导出的 JSON 恢复设置（**整体替换**）。返回恢复的条目数；格式不符则抛异常由 UI 提示。
     * 恢复后强制标记引导已完成——能导入备份的显然不是新用户。
     */
    suspend fun importJson(json: String): Int {
        val root = org.json.JSONObject(json)
        require(root.optString("app") == BACKUP_APP_ID) { "not a hxmy proxy settings backup" }
        val items = root.getJSONObject("settings")
        var n = 0
        ds.edit { prefs ->
            prefs.clear()
            items.keys().forEach { name ->
                val o = items.optJSONObject(name) ?: return@forEach
                when (o.optString("t")) {
                    "b" -> prefs[booleanPreferencesKey(name)] = o.getBoolean("v")
                    "i" -> prefs[intPreferencesKey(name)] = o.getInt("v")
                    "l" -> prefs[androidx.datastore.preferences.core.longPreferencesKey(name)] = o.getLong("v")
                    "f" -> prefs[androidx.datastore.preferences.core.floatPreferencesKey(name)] = o.getDouble("v").toFloat()
                    "d" -> prefs[androidx.datastore.preferences.core.doublePreferencesKey(name)] = o.getDouble("v")
                    "s" -> prefs[stringPreferencesKey(name)] = o.getString("v")
                    "ss" -> {
                        val arr = o.getJSONArray("v")
                        prefs[stringSetPreferencesKey(name)] = (0 until arr.length()).map { arr.getString(it) }.toSet()
                    }
                    else -> return@forEach
                }
                n++
            }
            prefs[ONBOARDING_DONE] = true
        }
        return n
    }

    /** 首次引导是否已完成（独立于代理配置的一次性标志）。 */
    val onboardingCompleted: Flow<Boolean> = ds.data.map { it[ONBOARDING_DONE] ?: false }

    suspend fun setOnboardingCompleted(done: Boolean) {
        ds.edit { it[ONBOARDING_DONE] = done }
    }

    /** 经 hxmy 访问过的域名历史（持久，供规则页「从历史添加」白名单）。独立 key，不进 ProxySettings、不触发规则重建。 */
    val domainHistory: Flow<Set<String>> = ds.data.map { it[DOMAIN_HISTORY] ?: emptySet() }

    suspend fun addDomainHistory(hosts: Collection<String>) {
        if (hosts.isEmpty()) return
        ds.edit { p ->
            val merged = (p[DOMAIN_HISTORY] ?: emptySet()) + hosts.map { it.lowercase() }
            p[DOMAIN_HISTORY] = if (merged.size > 300) merged.toList().takeLast(300).toSet() else merged
        }
    }

    private companion object {
        val HTTP_ENABLED = booleanPreferencesKey("http_enabled")
        val SOCKS_ENABLED = booleanPreferencesKey("socks_enabled")
        val PAC_ENABLED = booleanPreferencesKey("pac_enabled")
        val HTTP_PORT = intPreferencesKey("http_port")
        val SOCKS_PORT = intPreferencesKey("socks_port")
        val PAC_PORT = intPreferencesKey("pac_port")
        val SELECTED = stringSetPreferencesKey("selected_interface_ids")
        val EGRESS_CHOICE = stringPreferencesKey("egress_choice")
        val DIRECT_EGRESS_CHOICE = stringPreferencesKey("direct_egress_choice")
        val CELLULAR_EGRESS_CONFIRMED = booleanPreferencesKey("cellular_egress_confirmed")
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
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val HIDDEN_TABS = stringSetPreferencesKey("hidden_tabs")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_completed")

        /** 设置备份文件的应用标识与格式版本（导入时校验，防止导错文件）。 */
        const val BACKUP_APP_ID = "hxmy-proxy"
        const val BACKUP_FORMAT = 1
        val RULE_ENABLED = booleanPreferencesKey("rule_engine_enabled")
        val RULE_GROUPS = stringSetPreferencesKey("enabled_rule_groups")
        val USER_DIRECT = stringSetPreferencesKey("user_direct_rules")           // 旧格式:仅迁移读
        val USER_REJECT = stringSetPreferencesKey("user_reject_rules")           // 旧格式:仅迁移读
        val USER_DIRECT_JSON = stringPreferencesKey("user_direct_rules_json")    // 带启用状态的条目列表
        val USER_REJECT_JSON = stringPreferencesKey("user_reject_rules_json")
        val BACKUP_DNS = booleanPreferencesKey("backup_dns_enabled")
        val REJECTED_GROUPS = stringSetPreferencesKey("rejected_groups")
        val RULE_SUBS = stringSetPreferencesKey("rule_subscription_urls")
        val USER_RULE_SETS = stringPreferencesKey("user_rule_sets")
        val RULE_OVERRIDES = stringPreferencesKey("rule_set_overrides")
        val HOST_OVERRIDES = stringPreferencesKey("host_overrides")
        val USER_DIRECT_ENABLED = booleanPreferencesKey("user_direct_enabled")
        val USER_REJECT_ENABLED = booleanPreferencesKey("user_reject_enabled")
        val DOMAIN_HISTORY = stringSetPreferencesKey("domain_history")
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
            egressChoice = this[EGRESS_CHOICE]?.let { runCatching { EgressNetworkChoice.valueOf(it) }.getOrNull() } ?: d.egressChoice,
            directEgressChoice = this[DIRECT_EGRESS_CHOICE]?.let { runCatching { DirectEgressChoice.valueOf(it) }.getOrNull() } ?: d.directEgressChoice,
            cellularEgressConfirmed = this[CELLULAR_EGRESS_CONFIRMED] ?: d.cellularEgressConfirmed,
            mdnsEnabled = this[MDNS] ?: d.mdnsEnabled,
            backupDnsEnabled = this[BACKUP_DNS] ?: d.backupDnsEnabled,
            authEnabled = this[AUTH] ?: d.authEnabled,
            blockPrivateLanEgress = this[BLOCK_PRIVATE] ?: d.blockPrivateLanEgress,
            preset = this[PRESET]?.let { runCatching { PerformancePreset.valueOf(it) }.getOrNull() } ?: d.preset,
            limits = limits,
            language = this[LANGUAGE]?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() } ?: d.language,
            themeMode = this[THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
            hiddenTabs = this[HIDDEN_TABS] ?: d.hiddenTabs,
            ruleEngineEnabled = this[RULE_ENABLED] ?: d.ruleEngineEnabled,
            enabledRuleGroups = this[RULE_GROUPS] ?: d.enabledRuleGroups,
            userDirectEnabled = this[USER_DIRECT_ENABLED] ?: d.userDirectEnabled,
            userRejectEnabled = this[USER_REJECT_ENABLED] ?: d.userRejectEnabled,
            // 优先读带状态的 JSON;无则回退旧 stringSet(升级迁移,当作全部已启用);再无则默认。
            userDirectRules = decodeRuleEntries(this[USER_DIRECT_JSON]) ?: this[USER_DIRECT]?.map { RuleEntry(it) } ?: d.userDirectRules,
            userRejectRules = decodeRuleEntries(this[USER_REJECT_JSON]) ?: this[USER_REJECT]?.map { RuleEntry(it) } ?: d.userRejectRules,
            rejectedGroups = this[REJECTED_GROUPS] ?: d.rejectedGroups,
            userRuleSets = decodeRuleSets(this[USER_RULE_SETS]),
            ruleSetOverrides = decodeOverrides(this[RULE_OVERRIDES]),
            hostOverrides = decodeHostOverrides(this[HOST_OVERRIDES]),
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
        prefs[EGRESS_CHOICE] = egressChoice.name
        prefs[DIRECT_EGRESS_CHOICE] = directEgressChoice.name
        prefs[CELLULAR_EGRESS_CONFIRMED] = cellularEgressConfirmed
        prefs[MDNS] = mdnsEnabled
        prefs[BACKUP_DNS] = backupDnsEnabled
        prefs[AUTH] = authEnabled
        prefs[BLOCK_PRIVATE] = blockPrivateLanEgress
        prefs[PRESET] = preset.name
        prefs[LIM_GLOBAL] = limits.maxGlobalConnections
        prefs[LIM_PER_CLIENT] = limits.maxPerClientConnections
        prefs[LIM_PARALLEL] = limits.relayParallelism
        prefs[LIM_BUFFER] = limits.relayBufferBytes
        prefs[LIM_IDLE] = limits.idleTimeoutSeconds
        prefs[LANGUAGE] = language.name
        prefs[THEME_MODE] = themeMode.name
        prefs[HIDDEN_TABS] = hiddenTabs
        prefs[RULE_ENABLED] = ruleEngineEnabled
        prefs[RULE_GROUPS] = enabledRuleGroups
        prefs[USER_DIRECT_ENABLED] = userDirectEnabled
        prefs[USER_REJECT_ENABLED] = userRejectEnabled
        prefs[USER_DIRECT_JSON] = encodeRuleEntries(userDirectRules)
        prefs[USER_REJECT_JSON] = encodeRuleEntries(userRejectRules)
        prefs[REJECTED_GROUPS] = rejectedGroups
        prefs[USER_RULE_SETS] = encodeRuleSets(userRuleSets)
        prefs[RULE_OVERRIDES] = encodeOverrides(ruleSetOverrides)
        prefs[HOST_OVERRIDES] = encodeHostOverrides(hostOverrides)
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
                    action = runCatching { RuleAction.valueOf(o.optString("action")) }.getOrDefault(RuleAction.PROXY),
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

    private fun encodeHostOverrides(m: Map<String, RuleAction>): String {
        val o = org.json.JSONObject()
        m.forEach { (k, v) -> o.put(k, v.name) }
        return o.toString()
    }

    private fun decodeHostOverrides(json: String?): Map<String, RuleAction> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val o = org.json.JSONObject(json)
            o.keys().asSequence().mapNotNull { k ->
                runCatching { RuleAction.valueOf(o.getString(k)) }.getOrNull()?.let { k to it }
            }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun encodeRuleEntries(list: List<RuleEntry>): String {
        val arr = org.json.JSONArray()
        list.forEach { e ->
            arr.put(org.json.JSONObject().put("v", e.value).put("e", e.enabled).put("a", e.addedAt).put("d", e.disabledAt))
        }
        return arr.toString()
    }

    /** 解析带状态的规则条目；返回 null 表示无 JSON（交由调用方回退旧 stringSet 迁移）。 */
    private fun decodeRuleEntries(json: String?): List<RuleEntry>? {
        if (json.isNullOrBlank()) return null
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val v = o.optString("v").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                RuleEntry(v, o.optBoolean("e", true), o.optLong("a", 0L), o.optLong("d", 0L))
            }
        } catch (e: Exception) {
            null
        }
    }
}
