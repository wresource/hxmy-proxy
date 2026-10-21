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
import com.mzstd.hxmyproxy.core.rules.RuleInput
import com.mzstd.hxmyproxy.core.rules.RuleScope
import com.mzstd.hxmyproxy.core.rules.HostRuleEdits
import com.mzstd.hxmyproxy.core.rules.RuleChangeJournal
import com.mzstd.hxmyproxy.core.rules.UserRuleSet
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "hxmy_settings")

/** [ProxySettings] 的持久化（DataStore Preferences），单一来源。 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val ds get() = context.settingsDataStore
    val ruleContents: RuleContentStore by lazy { RuleContentStore.get(context) }
    private val migrationMutex = Mutex()
    @Volatile private var migrated = false
    @Volatile private var storageCleaned = false

    /**
     * 设置流。两个操作符都不是可有可无的：
     *
     * **[distinctUntilChanged]** —— [onboardingCompleted] 与 [domainHistory] 跟本流**共用同一个
     * DataStore**，写它们同样会让 `ds.data` 发一帧，而 [ProxySettings] 的值一个字段都没变。
     * 下游 ProxyServerRepository 对每一帧都做 applyTunables + 规则重建 + refresh，
     * 于是「删一条白名单」（会顺带写 domainHistory）就要多付一次全量重建。
     * ProxySettings 及其成员全是 data class / 值类型，结构化 equals 可靠。
     *
     * **[flowOn]** —— 上游 `toSettings()` 里有 **5 次 org.json 解析**（两张规则表、自建集、
     * 内置覆盖、host 覆盖）。冷流的操作符默认跑在**收集者**的上下文里，而 MainViewModel 经
     * viewModelScope 收集 = Dispatchers.Main —— 也就是每次设置变更都在主线程上解析一遍 JSON。
     * 这是「改个主题界面顿一下」最直接的来源，比 IO 线程上的规则重建更贴近用户感知。
     */
    val settings: Flow<ProxySettings> = ds.data
        .onStart { migrateRuleStorage() }
        .map { it.toSettings() }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    /** Publish references only after all SQLite collections have finished. Old JSON survives any failure. */
    private suspend fun migrateRuleStorage() = withContext(Dispatchers.IO) {
        migrationMutex.withLock {
            if (migrated) return@withLock
            try {
                ds.edit { prefs ->
                    if (prefs[RULE_STORAGE_VERSION] != 1) {
                        migrateSubscriptions(prefs.toSettings(strict = true)).writeTo(prefs)
                        prefs[RULE_STORAGE_VERSION] = 1
                    }
                }
                migrated = true
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (error: Exception) {
                // The legacy payload remains intact; never turn a failed migration into empty rules.
                com.mzstd.hxmyproxy.core.log.Ev.e(com.mzstd.hxmyproxy.core.log.LogCat.RULE, "rules.migrationFailed", error)
            }
        }
    }

    /** 进程级日志策略只读取一个布尔值，不在Application初始化时解析所有规则JSON。 */
    val loggingEnabled: Flow<Boolean> = ds.data.map { it[LOG_ENABLED] ?: true }.distinctUntilChanged()

    suspend fun update(transform: (ProxySettings) -> ProxySettings): Unit = withContext(Dispatchers.Default) {
        ds.edit { prefs ->
            val before = prefs.toSettings()
            val after = reuseRuleCollections(before, transform(before))
            val journal = RuleChangeJournalCodec.readLocal(prefs[RULE_CHANGES])
            val observed = journal.observeOtherEdit(before, after)
            after.writeTo(prefs)
            invalidateLibraryChanges(prefs, before, prefs.toSettings())
            if (observed != journal) prefs[RULE_CHANGES] = RuleChangeJournalCodec.encode(observed)
        }
    }

    val ruleChangeHistory: Flow<RuleChangeJournal> = ds.data
        .map { RuleChangeJournalCodec.readLocal(it[RULE_CHANGES]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    /** Commit the rule and its undo snapshot together, before reporting success to the UI. */
    suspend fun applyDomainRule(input: String, action: RuleAction?, scope: RuleScope): String = withContext(Dispatchers.Default) {
        val parsed = requireNotNull(RuleInput.forScope(input, scope)) { "Invalid rule" }
        ds.edit { prefs ->
            val before = prefs.toSettings()
            val now = System.currentTimeMillis()
            val after = reuseRuleCollections(before, HostRuleEdits.apply(before, input, action, scope, now))
            val journal = RuleChangeJournalCodec.readLocal(prefs[RULE_CHANGES])
                .record(before, after, parsed.host, parsed.value, action, now)
            val encoded = RuleChangeJournalCodec.encode(journal)
            after.writeTo(prefs)
            invalidateLibraryChanges(prefs, before, prefs.toSettings())
            prefs[RULE_CHANGES] = encoded
        }
        parsed.host
    }

    suspend fun revertDomainChange(id: String): Boolean = withContext(Dispatchers.Default) {
        var applied = false
        ds.edit { prefs ->
            val before = prefs.toSettings()
            val journal = RuleChangeJournalCodec.readLocal(prefs[RULE_CHANGES])
            journal.revert(prefs.toSettings(), id)?.let { (settings, history) ->
                val encoded = RuleChangeJournalCodec.encode(history)
                settings.writeTo(prefs)
                invalidateLibraryChanges(prefs, before, prefs.toSettings())
                prefs[RULE_CHANGES] = encoded
                applied = true
            }
        }
        applied
    }

    suspend fun clearRuleChangeHistory() { ds.edit { it.remove(RULE_CHANGES); it.remove(LIBRARY_CHANGES); it.remove(LIBRARY_VERSIONS) } }

    data class LibraryChange(val id: String, val target: String, val kind: String, val label: String,
        val at: Long, val reverted: Boolean, val superseded: Boolean)
    data class LibraryMutation(val settings: ProxySettings, val changeId: String?)

    val libraryChanges: Flow<List<LibraryChange>> = ds.data.map { prefs ->
        val versions = libraryVersions(prefs)
        libraryJournal(prefs).map { obj -> LibraryChange(obj.getString("id"), obj.getString("target"),
            obj.getString("kind"), obj.optString("label"), obj.getLong("at"), obj.optBoolean("reverted"),
            versions.optString(obj.getString("target")) != obj.getString("id")) }.asReversed()
    }.flowOn(Dispatchers.IO)

    /** Save data pointer and undo in one settings transaction; unrelated settings remain untouched. */
    suspend fun changeLibrary(target: String, kind: String, label: String,
        expected: ProxySettings? = null, operationId: String? = null,
        receipt: org.json.JSONObject? = null,
        transform: (ProxySettings) -> ProxySettings): LibraryMutation = withContext(Dispatchers.IO) {
        var saved: ProxySettings? = null
        var changeId: String? = null
        ds.edit { prefs ->
            val before = prefs.toSettings()
            val operations = org.json.JSONObject(prefs[APPLIED_IMPORTS] ?: "{}")
            if (operationId != null && operations.has(operationId)) { saved = before; return@edit }
            check(expected == null || sameLibraryTarget(expected, before, target)) { "Rules changed while preparing this operation. Review and retry." }
            val previous = librarySnapshot(before, target)
            val previousVersion = libraryVersions(prefs).optString(target)
            reuseRuleCollections(before, transform(before)).writeTo(prefs)
            val after = prefs.toSettings()
            val hostJournal = RuleChangeJournalCodec.readLocal(prefs[RULE_CHANGES])
            val observed = hostJournal.observeOtherEdit(before, after)
            if (observed != hostJournal) prefs[RULE_CHANGES] = RuleChangeJournalCodec.encode(observed)
            invalidateLibraryChanges(prefs, before, after)
            if (!sameLibraryTarget(before, after, target)) {
                val id = java.util.UUID.randomUUID().toString()
                changeId = id
                val versions = libraryVersions(prefs)
                val obj = org.json.JSONObject().put("id", id).put("target", target).put("kind", kind)
                    .put("label", label).put("at", System.currentTimeMillis()).put("before", previous)
                    .put("after", librarySnapshot(after, target)).put("previousVersion", previousVersion)
                    .put("reverted", false)
                versions.put(target, id)
                val changes = (libraryJournal(prefs) + obj).takeLast(20)
                prefs[LIBRARY_CHANGES] = org.json.JSONArray(changes).toString()
                prefs[LIBRARY_VERSIONS] = versions.toString()
            }
            saved = after
            if (operationId != null) {
                operations.put(operationId, org.json.JSONObject().put("target", target)
                    .put("changeId", changeId ?: org.json.JSONObject.NULL).put("result", receipt ?: org.json.JSONObject()))
                while (operations.length() > 100) operations.remove(operations.keys().next())
                prefs[APPLIED_IMPORTS] = operations.toString()
            }
        }
        LibraryMutation(requireNotNull(saved), changeId)
    }
    suspend fun appliedImport(id: String): String? = importReceipt(id)?.optString("target")?.takeIf(String::isNotEmpty)
    suspend fun importReceipt(id: String): org.json.JSONObject? {
        val value = org.json.JSONObject(ds.data.first()[APPLIED_IMPORTS] ?: "{}").opt(id)
        return when (value) { is org.json.JSONObject -> value; is String -> org.json.JSONObject().put("target", value); else -> null }
    }

    suspend fun revertLibraryChange(id: String): Boolean = withContext(Dispatchers.IO) {
        var reverted = false
        ds.edit { prefs ->
            val changes = libraryJournal(prefs)
            val change = changes.firstOrNull { it.getString("id") == id } ?: return@edit
            val target = change.getString("target"); val versions = libraryVersions(prefs)
            if (change.optBoolean("reverted") || versions.optString(target) != id) return@edit
            val before = prefs.toSettings()
            val recordedAfter = applyLibrarySnapshot(before, target, change.getJSONObject("after"))
            if (!sameLibraryTarget(before, recordedAfter, target)) return@edit
            val restored = applyLibrarySnapshot(before, target, change.getJSONObject("before"))
            // Strict snapshot decoding verifies every reference, including combined manual edits.
            restored.writeTo(prefs)
            val journal = RuleChangeJournalCodec.readLocal(prefs[RULE_CHANGES])
            val observed = journal.observeOtherEdit(before, restored)
            if (observed != journal) prefs[RULE_CHANGES] = RuleChangeJournalCodec.encode(observed)
            change.put("reverted", true)
            val previous = change.optString("previousVersion")
            if (previous.isEmpty()) versions.remove(target) else versions.put(target, previous)
            prefs[LIBRARY_CHANGES] = org.json.JSONArray(changes).toString()
            prefs[LIBRARY_VERSIONS] = versions.toString()
            reverted = true
        }
        reverted
    }
    suspend fun clearLibraryChanges() { ds.edit { it.remove(LIBRARY_CHANGES); it.remove(LIBRARY_VERSIONS) } }

    suspend fun cleanRuleStorage(activeJobs: Set<String>) = withContext(Dispatchers.IO) {
        migrationMutex.withLock {
            if (storageCleaned) return@withLock
            val roots = ds.data.first().asMap().values.flatMap { RuleContentStore.referencesIn(it.toString()) }.toSet()
            ruleContents.collectGarbage(roots, activeJobs, System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000)
            storageCleaned = true
        }
    }

    private fun libraryJournal(prefs: Preferences): List<org.json.JSONObject> = runCatching {
        val array = org.json.JSONArray(prefs[LIBRARY_CHANGES] ?: "[]")
        (maxOf(0, array.length() - 20) until array.length()).map { array.getJSONObject(it) }.also { entries ->
            require(entries.map { it.getString("id") }.distinct().size == entries.size)
            entries.forEach { entry ->
                require(validLibraryTarget(entry.getString("target")))
                require(entry.getString("id").isNotBlank())
                entry.getString("kind"); entry.getLong("at"); entry.getJSONObject("before"); entry.getJSONObject("after")
            }
        }
    }.getOrDefault(emptyList())
    private fun validLibraryTarget(target: String) = target in setOf("direct", "reject", "overrides", "overrides-paused", "manual", "builtins") ||
        (target.startsWith("user:") || target.startsWith("builtin:")) && target.substringAfter(':').isNotBlank()
    /** Keep the existing immutable pointer when content is equal, so UI equality never scans SQL. */
    private fun reuseRuleCollections(before: ProxySettings, after: ProxySettings): ProxySettings {
        fun <T> reuse(a: T, b: T): T = if (a == b) a else b
        val oldSets = before.userRuleSets.associateBy { it.id }
        return after.copy(
            userDirectRules = reuse(before.userDirectRules, after.userDirectRules),
            userRejectRules = reuse(before.userRejectRules, after.userRejectRules),
            hostOverrides = reuse(before.hostOverrides, after.hostOverrides),
            disabledHostOverrides = reuse(before.disabledHostOverrides, after.disabledHostOverrides),
            userRuleSets = after.userRuleSets.map { set -> oldSets[set.id]?.let { set.copy(domains = reuse(it.domains, set.domains)) } ?: set },
            ruleSetOverrides = after.ruleSetOverrides.mapValues { (id, values) -> before.ruleSetOverrides[id]?.let { reuse(it, values) } ?: values },
        )
    }
    private fun libraryVersions(prefs: Preferences) = runCatching { org.json.JSONObject(prefs[LIBRARY_VERSIONS] ?: "{}") }.getOrDefault(org.json.JSONObject())
    private fun invalidateLibraryChanges(prefs: MutablePreferences, before: ProxySettings, after: ProxySettings) {
        val changes = libraryJournal(prefs)
        if (changes.isEmpty()) return
        val versions = libraryVersions(prefs)
        changes.map { it.getString("target") }.distinct().forEach { target ->
            if (!sameLibraryTarget(before, after, target)) versions.put(target, java.util.UUID.randomUUID().toString())
        }
        prefs[LIBRARY_VERSIONS] = versions.toString()
    }
    private fun sameLibraryTarget(a: ProxySettings, b: ProxySettings, target: String): Boolean = when (target) {
        "direct" -> a.userDirectEnabled == b.userDirectEnabled && a.userDirectRules == b.userDirectRules
        "reject" -> a.userRejectEnabled == b.userRejectEnabled && a.userRejectRules == b.userRejectRules
        "overrides" -> a.hostOverrides == b.hostOverrides
        "overrides-paused" -> a.disabledHostOverrides == b.disabledHostOverrides
        "manual" -> sameLibraryTarget(a, b, "direct") && sameLibraryTarget(a, b, "reject") &&
            sameLibraryTarget(a, b, "overrides") && sameLibraryTarget(a, b, "overrides-paused")
        "builtins" -> a.enabledRuleGroups == b.enabledRuleGroups
        else -> {
            val id = target.substringAfter(':')
            if (target.startsWith("user:")) a.userRuleSets.firstOrNull { it.id == id } == b.userRuleSets.firstOrNull { it.id == id }
            else a.enabledRuleGroups.contains(id) == b.enabledRuleGroups.contains(id) &&
                a.rejectedGroups.contains(id) == b.rejectedGroups.contains(id) && a.ruleSetOverrides[id] == b.ruleSetOverrides[id]
        }
    }
    private fun librarySnapshot(s: ProxySettings, target: String): org.json.JSONObject = when (target) {
        "direct" -> org.json.JSONObject().put("enabled", s.userDirectEnabled).put("rules", encodeRuleEntries(s.userDirectRules))
        "reject" -> org.json.JSONObject().put("enabled", s.userRejectEnabled).put("rules", encodeRuleEntries(s.userRejectRules))
        "overrides" -> org.json.JSONObject().put("rules", encodeHostOverrides(s.hostOverrides))
        "overrides-paused" -> org.json.JSONObject().put("rules", encodeHostOverrides(s.disabledHostOverrides))
        "manual" -> org.json.JSONObject().apply { listOf("direct", "reject", "overrides", "overrides-paused").forEach { put(it, librarySnapshot(s, it)) } }
        "builtins" -> org.json.JSONObject().put("enabled", org.json.JSONArray(s.enabledRuleGroups.toList()))
        else -> {
            val id = target.substringAfter(':')
            if (target.startsWith("user:")) org.json.JSONObject().put("sets", encodeRuleSets(s.userRuleSets.filter { it.id == id }))
            else org.json.JSONObject().put("enabled", id in s.enabledRuleGroups).put("rejected", id in s.rejectedGroups)
                .put("override", s.ruleSetOverrides[id]?.let { encodeStrings(it, false) } ?: org.json.JSONObject.NULL)
        }
    }
    private fun applyLibrarySnapshot(s: ProxySettings, target: String, o: org.json.JSONObject): ProxySettings = when (target) {
        "direct" -> s.copy(userDirectEnabled = o.getBoolean("enabled"), userDirectRules = requireNotNull(decodeRuleEntries(o.getString("rules"), true)))
        "reject" -> s.copy(userRejectEnabled = o.getBoolean("enabled"), userRejectRules = requireNotNull(decodeRuleEntries(o.getString("rules"), true)))
        "overrides" -> s.copy(hostOverrides = decodeHostOverrides(o.getString("rules"), true))
        "overrides-paused" -> s.copy(disabledHostOverrides = decodeHostOverrides(o.getString("rules"), true))
        "manual" -> listOf("direct", "reject", "overrides", "overrides-paused").fold(s) { current, key -> applyLibrarySnapshot(current, key, o.getJSONObject(key)) }
        "builtins" -> s.copy(enabledRuleGroups = o.getJSONArray("enabled").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
        else -> {
            val id = target.substringAfter(':')
            if (target.startsWith("user:")) {
                val sets = decodeRuleSets(o.getString("sets"), true)
                require(sets.size <= 1 && sets.all { it.id == id }) { "Undo snapshot belongs to a different rule set" }
                s.copy(userRuleSets = s.userRuleSets.filterNot { it.id == id } + sets)
            }
            else s.copy(enabledRuleGroups = if (o.getBoolean("enabled")) s.enabledRuleGroups + id else s.enabledRuleGroups - id,
                rejectedGroups = if (o.getBoolean("rejected")) s.rejectedGroups + id else s.rejectedGroups - id,
                ruleSetOverrides = if (o.isNull("override")) s.ruleSetOverrides - id else s.ruleSetOverrides + (id to decodeStrings(o.get("override"), true)))
        }
    }

    /**
     * 导出全部设置为 JSON（换机/重装迁移用）。**不含代理凭据**——那是单独加密存储的，
     * 不落进可分享的明文备份。
     *
     * 采用「遍历 DataStore 键值 + 带类型标记」的通用做法而非逐字段手写：新增设置项自动进备份，
     * 不会因为漏改这里而静默丢配置。
     */
    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        val prefs = ds.data.first().toMutablePreferences()
        // Portable backups contain the actual rules, never device-local SQLite references.
        prefs.toSettings(strict = true).writeTo(prefs, portable = true)
        prefs.remove(RULE_STORAGE_VERSION)
        // Batch undo is local recovery data. Backups still include every active/inactive rule.
        prefs.remove(LIBRARY_CHANGES); prefs.remove(LIBRARY_VERSIONS)
        prefs.remove(APPLIED_IMPORTS)
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
        org.json.JSONObject()
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
        listOf(USER_DIRECT_JSON, USER_REJECT_JSON, USER_RULE_SETS, RULE_OVERRIDES, HOST_OVERRIDES, DISABLED_OVERRIDES).forEach { key ->
            require(items.optJSONObject(key.name)?.optString("v")?.contains("\"\$rules\"") != true) {
                "Settings backup must include rule contents, not local storage references"
            }
        }
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
            // edit is one DataStore transaction: a malformed backup must leave the
            // previous settings intact, not restore a mixture containing dead rules.
            fun rule(value: String) = requireNotNull(RuleInput.normalize(value)) {
                "Invalid rule in settings backup: $value"
            }
            val imported = migrateSubscriptions(prefs.toSettings(strict = true))
            fun entries(values: List<RuleEntry>): List<RuleEntry> {
                val normalized = values.map { it.copy(value = rule(it.value)) }
                require(normalized.map { it.value }.distinct().size == normalized.size) {
                    "Duplicate normalized rules in settings backup"
                }
                return normalized
            }
            val overrides = linkedMapOf<String, RuleAction>()
            imported.hostOverrides.forEach { (value, action) ->
                val normalized = rule(value)
                require(overrides[normalized] == null || overrides[normalized] == action) {
                    "Conflicting normalized host overrides in settings backup"
                }
                overrides[normalized] = action
            }
            imported.copy(
                userDirectRules = entries(imported.userDirectRules),
                userRejectRules = entries(imported.userRejectRules),
                userRuleSets = imported.userRuleSets.map { set -> set.copy(domains = set.domains.map(::rule).distinct()) },
                ruleSetOverrides = imported.ruleSetOverrides.mapValues { (_, rules) -> rules.map(::rule).distinct() },
                hostOverrides = overrides,
                disabledHostOverrides = imported.disabledHostOverrides.mapKeys { rule(it.key) }.also { paused ->
                    require(paused.size == imported.disabledHostOverrides.size && paused.keys.intersect(overrides.keys).isEmpty()) { "Conflicting paused overrides" }
                },
            ).writeTo(prefs)
            prefs[RULE_STORAGE_VERSION] = 1
            // These journals contain device-local collection pointers and are not portable input.
            prefs.remove(LIBRARY_CHANGES); prefs.remove(LIBRARY_VERSIONS); prefs.remove(APPLIED_IMPORTS)
            // A malformed undo snapshot must roll back the whole import, just like an invalid active rule.
            RuleChangeJournalCodec.decode(prefs[RULE_CHANGES])
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
        val SHOW_IPV6 = booleanPreferencesKey("show_ipv6")
        val EGRESS_CHOICE = stringPreferencesKey("egress_choice")
        val DIRECT_EGRESS_CHOICE = stringPreferencesKey("direct_egress_choice")
        val EGRESS_PRIORITY = stringPreferencesKey("egress_priority")
        val DOH_EGRESS_CHOICE = stringPreferencesKey("doh_egress_choice")
        val EGRESS_FALLBACK = stringPreferencesKey("egress_fallback")
        val CELLULAR_EGRESS_CONFIRMED = booleanPreferencesKey("cellular_egress_confirmed")
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
        val LOG_ENABLED = booleanPreferencesKey("log_enabled")

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
        val RULE_STORAGE_VERSION = intPreferencesKey("rule_storage_version")
        val LIBRARY_CHANGES = stringPreferencesKey("rule_library_changes")
        val LIBRARY_VERSIONS = stringPreferencesKey("rule_library_versions")
        val APPLIED_IMPORTS = stringPreferencesKey("rule_applied_imports")
        val USER_RULE_SETS = stringPreferencesKey("user_rule_sets")
        val RULE_OVERRIDES = stringPreferencesKey("rule_set_overrides")
        val HOST_OVERRIDES = stringPreferencesKey("host_overrides")
        val DISABLED_OVERRIDES = stringPreferencesKey("disabled_host_overrides")
        val USER_DIRECT_ENABLED = booleanPreferencesKey("user_direct_enabled")
        val USER_REJECT_ENABLED = booleanPreferencesKey("user_reject_enabled")
        val DOMAIN_HISTORY = stringSetPreferencesKey("domain_history")
        val RULE_CHANGES = stringPreferencesKey("recent_rule_changes_v1")
    }

    private fun Preferences.toSettings(strict: Boolean = false): ProxySettings {
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
            showIpv6 = this[SHOW_IPV6] ?: d.showIpv6,
            egressChoice = this[EGRESS_CHOICE]?.let { runCatching { EgressNetworkChoice.valueOf(it) }.getOrNull() } ?: d.egressChoice,
            directEgressChoice = this[DIRECT_EGRESS_CHOICE]?.let { runCatching { DirectEgressChoice.valueOf(it) }.getOrNull() } ?: d.directEgressChoice,
            // **必须是有序字符串,不能用 stringSet**——Set 不保序,存进去顺序就没了。
            egressPriority = com.mzstd.hxmyproxy.core.model.normalizeEgressPriority(
                this[EGRESS_PRIORITY]?.split(',').orEmpty()
                    .mapNotNull { runCatching { DirectEgressChoice.valueOf(it.trim()) }.getOrNull() },
            ),
            dohEgressChoice = this[DOH_EGRESS_CHOICE]?.let { runCatching { com.mzstd.hxmyproxy.core.model.DohEgressChoice.valueOf(it) }.getOrNull() } ?: d.dohEgressChoice,
            egressFallback = this[EGRESS_FALLBACK]?.let { runCatching { com.mzstd.hxmyproxy.core.model.EgressFallback.valueOf(it) }.getOrNull() } ?: d.egressFallback,
            cellularEgressConfirmed = this[CELLULAR_EGRESS_CONFIRMED] ?: d.cellularEgressConfirmed,
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
            userDirectRules = decodeRuleEntries(this[USER_DIRECT_JSON], strict) ?: this[USER_DIRECT]?.map { RuleEntry(it) } ?: d.userDirectRules,
            userRejectRules = decodeRuleEntries(this[USER_REJECT_JSON], strict) ?: this[USER_REJECT]?.map { RuleEntry(it) } ?: d.userRejectRules,
            rejectedGroups = this[REJECTED_GROUPS] ?: d.rejectedGroups,
            userRuleSets = decodeRuleSets(this[USER_RULE_SETS], strict),
            ruleSetOverrides = decodeOverrides(this[RULE_OVERRIDES], strict),
            hostOverrides = decodeHostOverrides(this[HOST_OVERRIDES], strict),
            disabledHostOverrides = decodeHostOverrides(this[DISABLED_OVERRIDES], strict),
            ruleSubscriptionUrls = this[RULE_SUBS] ?: d.ruleSubscriptionUrls,
            logEnabled = this[LOG_ENABLED] ?: d.logEnabled,
        )
    }

    private fun ProxySettings.writeTo(prefs: MutablePreferences, portable: Boolean = false) {
        prefs[HTTP_ENABLED] = httpEnabled
        prefs[SOCKS_ENABLED] = socksEnabled
        prefs[PAC_ENABLED] = pacEnabled
        prefs[HTTP_PORT] = httpPort
        prefs[SOCKS_PORT] = socksPort
        prefs[PAC_PORT] = pacPort
        prefs[SELECTED] = selectedInterfaceIds
        prefs[SHOW_IPV6] = showIpv6
        prefs[EGRESS_CHOICE] = egressChoice.name
        prefs[DIRECT_EGRESS_CHOICE] = directEgressChoice.name
        prefs[EGRESS_PRIORITY] = egressPriority.joinToString(",") { it.name }
        prefs[DOH_EGRESS_CHOICE] = dohEgressChoice.name
        prefs[EGRESS_FALLBACK] = egressFallback.name
        prefs[CELLULAR_EGRESS_CONFIRMED] = cellularEgressConfirmed
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
        prefs[USER_DIRECT_JSON] = encodeRuleEntries(userDirectRules, portable)
        prefs[USER_REJECT_JSON] = encodeRuleEntries(userRejectRules, portable)
        prefs[REJECTED_GROUPS] = rejectedGroups
        prefs[USER_RULE_SETS] = encodeRuleSets(userRuleSets, portable)
        prefs[RULE_OVERRIDES] = encodeOverrides(ruleSetOverrides, portable)
        prefs[HOST_OVERRIDES] = encodeHostOverrides(hostOverrides, portable)
        prefs[DISABLED_OVERRIDES] = encodeHostOverrides(disabledHostOverrides, portable)
        prefs[RULE_SUBS] = ruleSubscriptionUrls
        prefs[LOG_ENABLED] = logEnabled
    }

    private fun encodeRuleSets(sets: List<UserRuleSet>, portable: Boolean = false): String {
        val arr = org.json.JSONArray()
        sets.forEach { s ->
            arr.put(
                org.json.JSONObject()
                    .put("id", s.id).put("name", s.name).put("action", s.action.name)
                    .put("enabled", s.enabled).put("domains", encodeStrings(s.domains, portable))
                    .put("subscriptionUrl", s.subscriptionUrl ?: org.json.JSONObject.NULL)
                    .put("updatedAt", s.updatedAt).put("updateError", s.updateError ?: org.json.JSONObject.NULL),
            )
        }
        return arr.toString()
    }

    private fun decodeRuleSets(json: String?, strict: Boolean = false): List<UserRuleSet> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                UserRuleSet(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    action = if (strict) RuleAction.valueOf(o.getString("action")) else runCatching { RuleAction.valueOf(o.optString("action")) }.getOrDefault(RuleAction.PROXY),
                    domains = if (o.has("domains")) decodeStrings(o.get("domains"), strict) else emptyList(),
                    enabled = o.optBoolean("enabled", true),
                    subscriptionUrl = if (o.isNull("subscriptionUrl")) null else o.optString("subscriptionUrl").takeIf(String::isNotBlank),
                    updatedAt = o.optLong("updatedAt", 0),
                    updateError = if (o.isNull("updateError")) null else o.optString("updateError").takeIf(String::isNotBlank),
                )
            }.also { if (strict) require(it.map { s -> s.id }.distinct().size == it.size && it.all { s -> s.id.isNotBlank() }) { "Duplicate or empty rule set IDs" } }
        } catch (e: Exception) {
            if (strict) throw e
            emptyList()
        }
    }

    private fun encodeOverrides(m: Map<String, List<String>>, portable: Boolean = false): String {
        val o = org.json.JSONObject()
        m.forEach { (k, v) -> o.put(k, encodeStrings(v, portable)) }
        return o.toString()
    }

    private fun decodeOverrides(json: String?, strict: Boolean = false): Map<String, List<String>> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val o = org.json.JSONObject(json)
            o.keys().asSequence().associateWith { k ->
                decodeStrings(o.get(k), strict)
            }
        } catch (e: Exception) {
            if (strict) throw e
            emptyMap()
        }
    }

    private fun encodeHostOverrides(m: Map<String, RuleAction>, portable: Boolean = false): String {
        if (!portable && m.size > RuleContentStore.INLINE_LIMIT) {
            val stored = RuleContentStore.reference(m) ?: RuleContentStore.reference(ruleContents.persistOverrides(m.asSequence().map { it.key to it.value }))!!
            return encodeReference(stored).toString()
        }
        val o = org.json.JSONObject()
        m.forEach { (k, v) -> o.put(k, v.name) }
        return o.toString()
    }

    private fun decodeHostOverrides(json: String?, strict: Boolean = false): Map<String, RuleAction> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val o = org.json.JSONObject(json)
            if (o.has("\$rules")) return ruleContents.overrides(decodeReference(o, RuleContentStore.OVERRIDES, strict))
            o.keys().asSequence().mapNotNull { k ->
                if (strict) k to RuleAction.valueOf(o.getString(k)) else runCatching { RuleAction.valueOf(o.getString(k)) }.getOrNull()?.let { k to it }
            }.toMap()
        } catch (e: Exception) {
            if (strict) throw e
            emptyMap()
        }
    }

    private fun encodeRuleEntries(list: List<RuleEntry>, portable: Boolean = false): String {
        if (!portable && list.size > RuleContentStore.INLINE_LIMIT) {
            val stored = RuleContentStore.reference(list) ?: RuleContentStore.reference(ruleContents.persistEntries(list.asSequence()))!!
            return encodeReference(stored).toString()
        }
        val arr = org.json.JSONArray()
        list.forEach { e ->
            arr.put(org.json.JSONObject().put("v", e.value).put("e", e.enabled).put("a", e.addedAt).put("d", e.disabledAt))
        }
        return arr.toString()
    }

    /** 解析带状态的规则条目；返回 null 表示无 JSON（交由调用方回退旧 stringSet 迁移）。 */
    private fun decodeRuleEntries(json: String?, strict: Boolean = false): List<RuleEntry>? {
        if (json.isNullOrBlank()) return null
        return try {
            if (json.trimStart().startsWith('{')) return ruleContents.entries(decodeReference(org.json.JSONObject(json), RuleContentStore.ENTRIES, strict))
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val v = o.optString("v").takeIf { it.isNotBlank() } ?: if (strict) error("Missing rule value") else return@mapNotNull null
                RuleEntry(v, o.optBoolean("e", true), o.optLong("a", 0L), o.optLong("d", 0L))
            }
        } catch (e: Exception) {
            if (strict) throw e
            null
        }
    }

    private fun encodeStrings(values: List<String>, portable: Boolean): Any {
        if (portable || values.size <= RuleContentStore.INLINE_LIMIT) return org.json.JSONArray(values)
        val ref = RuleContentStore.reference(values) ?: RuleContentStore.reference(ruleContents.persistStrings(values.asSequence()))!!
        return encodeReference(ref)
    }
    private fun decodeStrings(value: Any?, strict: Boolean = false): List<String> = when (value) {
        is org.json.JSONObject -> ruleContents.strings(decodeReference(value, RuleContentStore.STRINGS, strict))
        is org.json.JSONArray -> (0 until value.length()).map { if (strict) value.get(it) as? String ?: error("Expected rule text") else value.getString(it) }
        else -> if (strict) error("Expected rule content array") else emptyList()
    }
    private fun encodeReference(ref: RuleContentStore.Ref): org.json.JSONObject {
        check(ruleContents.ref(ref.id) == ref) { "Cannot save unavailable rule content" }
        return org.json.JSONObject().put("\$rules", ref.id).put("kind", ref.kind).put("count", ref.size).put("hash", ref.hash)
    }
    private fun decodeReference(value: org.json.JSONObject, kind: Int, verify: Boolean = false): RuleContentStore.Ref {
        val id = value.getString("\$rules")
        require(java.util.UUID.fromString(id).toString() == id)
        require(value.getInt("kind") == kind)
        val size = value.getInt("count"); require(size >= 0)
        val ref = RuleContentStore.Ref(id, kind, size, value.getInt("hash"))
        // Active lazy references must reach the repository intact: reads fail explicitly and
        // keep its last complete engine snapshot. Never turn missing storage into an empty list.
        if (verify) check(ruleContents.ref(id) == ref) { "Rule content is unavailable: $id" }
        return ref
    }

    private fun migrateSubscriptions(s: ProxySettings): ProxySettings {
        val existing = s.userRuleSets.mapNotNull { it.subscriptionUrl }.toSet()
        val additions = s.ruleSubscriptionUrls.filterNot { it in existing }.map { url ->
            var id = java.util.UUID.nameUUIDFromBytes("subscription:$url".toByteArray(Charsets.UTF_8)).toString()
            if (s.userRuleSets.any { it.id == id }) id = java.util.UUID.randomUUID().toString()
            UserRuleSet(id, runCatching { java.net.URI(url).host }.getOrNull()?.takeIf(String::isNotBlank) ?: "Subscription",
                RuleAction.DIRECT, enabled = false, subscriptionUrl = url)
        }
        return if (additions.isEmpty()) s else s.copy(userRuleSets = s.userRuleSets + additions)
    }
}
