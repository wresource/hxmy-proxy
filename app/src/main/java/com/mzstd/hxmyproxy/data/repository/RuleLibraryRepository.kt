package com.mzstd.hxmyproxy.data.repository

import android.content.Context
import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.model.RuleEntry
import com.mzstd.hxmyproxy.core.proxy.RequestActivityRecord
import com.mzstd.hxmyproxy.core.rules.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class RuleLibraryRepository @Inject constructor(@param:ApplicationContext private val context: Context,
    val settings: SettingsRepository) {
    enum class Kind { DIRECT, REJECT, OVERRIDES, PAUSED_OVERRIDES, USER, BUILTIN, SUBSCRIPTION }
    data class Source(val id: String, val kind: Kind, val name: String, val titleRes: Int? = null,
        val action: RuleAction, val enabled: Boolean, val ref: RuleContentStore.Ref, val editable: Boolean = true,
        val category: RuleCategory? = null, val overridden: Boolean = false, val url: String? = null,
        val updatedAt: Long = 0, val error: String? = null, val ipCount: Int = 0) {
        val count get() = ref.size
        val isManual get() = kind == Kind.DIRECT || kind == Kind.REJECT || kind == Kind.OVERRIDES || kind == Kind.PAUSED_OVERRIDES
    }
    data class Item(val row: RuleContentStore.Row, val source: Source) {
        val action get() = row.action ?: source.action
        val enabled get() = source.enabled && row.enabled
        val key get() = "${source.id}:${row.ordinal}:${row.value}"
    }
    data class Page(val items: List<Item> = emptyList(), val total: Int = 0, val offset: Int = 0)
    enum class Relation { DUPLICATE, COVERED, OVERLAP, CONFLICT, RESOLVED_IP, DISABLED }
    data class Related(val relation: Relation, val item: Item, val peer: String? = null, val observedAt: Long? = null)
    data class Inspection(val related: List<Related>, val ipEvidenceMissing: Boolean)
    private val store get() = settings.ruleContents
    private val builtinCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    // Small inline lists only; large collections already carry their immutable database reference.
    private val inline = object : LinkedHashMap<Any, RuleContentStore.Ref>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, RuleContentStore.Ref>?) = size > 64
    }

    private fun reference(values: List<String>): RuleContentStore.Ref = RuleContentStore.reference(values) ?: synchronized(inline) {
        inline.getOrPut(listOf("strings", values)) { RuleContentStore.reference(store.persistStrings(values.asSequence()))!! }
    }
    private fun entries(values: List<RuleEntry>): RuleContentStore.Ref = RuleContentStore.reference(values) ?: synchronized(inline) {
        inline.getOrPut(listOf("entries", values)) { RuleContentStore.reference(store.persistEntries(values.asSequence()))!! }
    }
    private fun overrides(values: Map<String, RuleAction>): RuleContentStore.Ref = RuleContentStore.reference(values) ?: synchronized(inline) {
        inline.getOrPut(listOf("overrides", values)) { RuleContentStore.reference(store.persistOverrides(values.asSequence().map { it.key to it.value }))!! }
    }

    suspend fun sources(input: ProxySettings? = null, includeBuiltins: Boolean = true, activeBuiltinsOnly: Boolean = false): List<Source> = withContext(Dispatchers.IO) {
        val s = input ?: settings.settings.first()
        buildList {
            add(Source("direct", Kind.DIRECT, "", action = RuleAction.DIRECT, enabled = s.userDirectEnabled, ref = entries(s.userDirectRules)))
            add(Source("reject", Kind.REJECT, "", action = RuleAction.REJECT, enabled = s.userRejectEnabled, ref = entries(s.userRejectRules)))
            add(Source("overrides", Kind.OVERRIDES, "", action = RuleAction.PROXY, enabled = true, ref = overrides(s.hostOverrides)))
            add(Source("overrides-paused", Kind.PAUSED_OVERRIDES, "", action = RuleAction.PROXY, enabled = false, ref = overrides(s.disabledHostOverrides)))
            s.userRuleSets.forEach { set -> add(Source("user:${set.id}", if (set.subscriptionUrl == null) Kind.USER else Kind.SUBSCRIPTION,
                set.name, action = if (set.action == RuleAction.REJECT) RuleAction.REJECT else RuleAction.DIRECT,
                enabled = set.enabled, ref = reference(set.domains), url = set.subscriptionUrl, updatedAt = set.updatedAt, error = set.updateError)) }
            if (includeBuiltins) for (group in RuleCatalog.all) {
                if (activeBuiltinsOnly && group.id !in s.enabledRuleGroups) continue
                coroutineContext.ensureActive()
                val values = s.ruleSetOverrides[group.id] ?: builtin(group)
                add(Source("builtin:${group.id}", Kind.BUILTIN, group.id, group.titleRes,
                    when { group.kind == RuleGroupKind.REJECT || group.id in s.rejectedGroups -> RuleAction.REJECT
                        group.kind == RuleGroupKind.PROXY -> RuleAction.PROXY; else -> RuleAction.DIRECT },
                    group.id in s.enabledRuleGroups, reference(values), group.editable, group.category, group.id in s.ruleSetOverrides))
            }
        }.map { source -> val counts = store.counts(source.ref); source.copy(ipCount = (counts["ipv4"] ?: 0) + (counts["ipv6"] ?: 0)) }
    }

    private fun builtin(group: RuleGroup): List<String> {
        builtinCache[group.assetPath]?.let { return it }
        val crc = ZipFile(context.applicationInfo.sourceDir).use { it.getEntry("assets/${group.assetPath}")?.crc ?: 0L }
        return store.asset("${group.assetPath}:$crc") { writer ->
            context.assets.open(group.assetPath).use { raw ->
                val input = if (group.assetPath.endsWith(".gz")) GZIPInputStream(raw) else raw
                input.bufferedReader().useLines { lines -> lines.forEach { line ->
                    val value = line.trim(); if (value.isNotEmpty() && !value.startsWith('#')) writer.add(RuleContentStore.Row(value))
                } }
            }
        }.also { builtinCache[group.assetPath] = it }
    }

    suspend fun page(sources: List<Source>, query: String = "", offset: Int = 0, limit: Int = 50,
        type: Int? = null, action: RuleAction? = null): Page = withContext(Dispatchers.IO) {
        if (sources.isEmpty()) return@withContext Page()
        val p = if (sources.size == 1 && action == null) store.query(sources.single().ref, query, type, offset, limit)
            else store.queryMany(sources.map { it.id to it.ref }, query, offset, limit, sources.associate { it.id to it.action }, action)
        val byId = sources.associateBy { it.ref.id }
        val byBinding = sources.associateBy { it.id }
        Page(p.rows.map { Item(it, if (it.binding.isNotEmpty()) byBinding.getValue(it.binding) else byId.getValue(it.collection)) }, p.total, offset)
    }

    suspend fun matchingSources(sources: List<Source>, query: String, displayNames: Map<String, String> = emptyMap()): List<Source> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext sources
        sources.filter { source ->
            (displayNames[source.id] ?: source.name).contains(query, true) || source.name.contains(query, true) ||
                store.query(source.ref, query, limit = 1).total > 0
        }
    }

    suspend fun related(value: String, action: RuleAction, sources: List<Source>, evidence: List<RequestActivityRecord> = emptyList()): List<Related> = withContext(Dispatchers.IO) {
        val input = RuleInput.parse(value) ?: return@withContext emptyList()
        val requested = if (input.isIp) IpRange.ofRule(input.host) else null
        val out = ArrayList<Related>()
        for (source in sources) {
            coroutineContext.ensureActive()
            if (source.count == 0) continue
            val exact = store.lookup(source.ref, input.value)
            if (exact != null && (!source.enabled || !exact.enabled) && exact.value == input.value) out += Related(Relation.DISABLED, Item(exact, source))
            if (!source.enabled) continue
            for (row in store.overlapping(source.ref, input.value)) {
                if (!row.enabled) continue
                val parsed = RuleInput.parse(row.value) ?: continue
                if (requested == null && !domainScopesOverlap(input, parsed)) continue
                val item = Item(row, source)
                val interval = if (parsed.isIp) IpRange.ofRule(parsed.host) else null
                val covered = if (requested != null && interval != null) interval.contains(requested) else domainCovers(parsed, input)
                val relation = when {
                    item.action != action -> Relation.CONFLICT
                    row.value == input.value -> Relation.DUPLICATE
                    covered -> Relation.COVERED
                    else -> Relation.OVERLAP
                }
                out += Related(relation, item)
                if (out.size >= 50) return@withContext out
            }
        }
        if (!input.isIp) {
            // Actual successful-connection peer evidence only; no speculative DNS query or rerouting.
            val recent = evidence.filter { RuleInput.parse(it.host)?.host == input.host && it.peer != null }
                .distinctBy { it.peer }.take(4)
            for (seen in recent) {
                val peer = RuleInput.parse(seen.peer.orEmpty())?.takeIf { it.isIp }?.host ?: continue
                for (source in sources.filter { it.enabled }) {
                    for (row in store.overlapping(source.ref, peer)) {
                        if (row.enabled) out += Related(Relation.RESOLVED_IP, Item(row, source), peer, seen.updatedAt)
                        if (out.size >= 50) return@withContext out
                    }
                }
            }
        }
        out.distinctBy { "${it.relation}:${it.item.key}:${it.peer}" }
    }

    suspend fun inspect(value: String, action: RuleAction, evidence: List<RequestActivityRecord>): Inspection {
        val input = RuleInput.parse(value)
        val all = sources(activeBuiltinsOnly = true)
        val missing = input != null && !input.isIp && all.any { it.enabled && it.ipCount > 0 } &&
            evidence.none { RuleInput.parse(it.host)?.host == input.host && RuleInput.parse(it.peer.orEmpty())?.isIp == true }
        return Inspection(related(value, action, all, evidence), missing)
    }

    private fun domainCovers(a: RuleInput.Parsed, b: RuleInput.Parsed): Boolean {
        if (a.isIp || b.isIp) return false
        if (a.host == b.host) return a.scope == RuleScope.SUFFIX || a.scope == b.scope || b.scope == RuleScope.EXACT
        if (!b.host.endsWith(".${a.host}")) return false
        return a.scope == RuleScope.SUFFIX || (a.scope == RuleScope.SINGLE && b.scope == RuleScope.EXACT && b.host.count { it == '.' } == a.host.count { it == '.' } + 1)
    }
    private fun domainScopesOverlap(a: RuleInput.Parsed, b: RuleInput.Parsed): Boolean {
        fun matches(rule: RuleInput.Parsed, host: String): Boolean = rule.host == host ||
            (host.endsWith(".${rule.host}") && (rule.scope == RuleScope.SUFFIX ||
                rule.scope == RuleScope.SINGLE && host.count { it == '.' } == rule.host.count { it == '.' } + 1))
        return !a.isIp && !b.isIp && (matches(a, b.host) || matches(b, a.host))
    }

    suspend fun setEnabled(source: Source, enabled: Boolean) = settings.changeLibrary(source.id, if (enabled) "enable" else "disable", source.name) { s ->
        verifyWholeSource(s, source)
        when (source.kind) {
            Kind.DIRECT -> s.copy(userDirectEnabled = enabled)
            Kind.REJECT -> s.copy(userRejectEnabled = enabled)
            Kind.OVERRIDES, Kind.PAUSED_OVERRIDES -> s // Individual overrides have their own enable/pause action.
            Kind.BUILTIN -> source.id.substringAfter(':').let { id -> s.copy(enabledRuleGroups = if (enabled) s.enabledRuleGroups + id else s.enabledRuleGroups - id) }
            else -> s.copy(userRuleSets = s.userRuleSets.map { if ("user:${it.id}" == source.id) it.copy(enabled = enabled) else it })
        }
    }

    suspend fun disableRelated(related: Related): SettingsRepository.LibraryMutation {
        val item = related.item
        return if (item.source.kind == Kind.DIRECT || item.source.kind == Kind.REJECT) setItemEnabled(item, false)
        else if (item.source.kind == Kind.OVERRIDES || item.source.kind == Kind.PAUSED_OVERRIDES) setItemEnabled(item, false)
        else setEnabled(item.source, false)
    }
    suspend fun enableRelated(related: Related): SettingsRepository.LibraryMutation {
        val item = related.item
        if ((item.source.kind == Kind.DIRECT || item.source.kind == Kind.REJECT) && !item.source.enabled) {
            return settings.changeLibrary(item.source.id, "enable", item.row.value) { s ->
                val rules = if (item.source.kind == Kind.DIRECT) s.userDirectRules else s.userRejectRules
                val updated = if (item.row.enabled) rules else rules.ruleMap { if (it.value == item.row.value) it.copy(enabled = true) else it }
                if (item.source.kind == Kind.DIRECT) s.copy(userDirectEnabled = true, userDirectRules = updated)
                else s.copy(userRejectEnabled = true, userRejectRules = updated)
            }
        }
        return if (item.source.isManual) setItemEnabled(item, true) else setEnabled(item.source, true)
    }

    suspend fun setItemEnabled(item: Item, enabled: Boolean) = settings.changeLibrary(
        if (item.source.ref.kind == RuleContentStore.OVERRIDES) "manual" else item.source.id,
        if (enabled) "enable" else "disable", item.row.value) { s ->
        if (item.source.ref.kind == RuleContentStore.OVERRIDES) {
            val value = item.row.value
            val action = if (item.source.kind == Kind.PAUSED_OVERRIDES) s.disabledHostOverrides[value] else s.hostOverrides[value]
            check(action != null && action == item.action) { "This rule has changed. Reload and retry." }
            return@changeLibrary s.copy(
                hostOverrides = s.hostOverrides.ruleRebuild(s.hostOverrides.asSequence().filter { it.key != value }.map { it.key to it.value } + if (enabled) sequenceOf(value to action) else emptySequence()),
                disabledHostOverrides = s.disabledHostOverrides.ruleRebuild(s.disabledHostOverrides.asSequence().filter { it.key != value }.map { it.key to it.value } + if (!enabled) sequenceOf(value to action) else emptySequence()),
            )
        }
        val current = if (item.source.kind == Kind.DIRECT) s.userDirectRules else s.userRejectRules
        val next = current.ruleMap { if (it.value == item.row.value) it.copy(enabled = enabled, disabledAt = if (!enabled) System.currentTimeMillis() else it.disabledAt) else it }
        if (item.source.kind == Kind.DIRECT) s.copy(userDirectRules = next) else s.copy(userRejectRules = next)
    }

    suspend fun create(name: String, action: RuleAction, url: String? = null): String {
        require(name.isNotBlank() && name.length <= 200); require(action != RuleAction.PROXY)
        url?.let(::validateUrl)
        val id = UUID.randomUUID().toString()
        settings.changeLibrary("user:$id", "create", name) { s -> s.copy(userRuleSets = s.userRuleSets + UserRuleSet(id, name.trim(), action, subscriptionUrl = url)) }
        return "user:$id"
    }

    suspend fun rename(source: Source, name: String, action: RuleAction, url: String? = source.url) = settings.changeLibrary(source.id, "edit", source.name) { s ->
        require(name.isNotBlank() && name.length <= 200); require(action != RuleAction.PROXY)
        url?.let(::validateUrl)
        if (source.kind == Kind.BUILTIN) {
            val id = source.id.substringAfter(':'); check(RuleCatalog.byId(id)?.kind == RuleGroupKind.DIRECT)
            s.copy(rejectedGroups = if (action == RuleAction.REJECT) s.rejectedGroups + id else s.rejectedGroups - id)
        } else {
            val current = requireNotNull(s.userRuleSets.firstOrNull { "user:${it.id}" == source.id }) { "Rule set no longer exists" }
            check(current.name == source.name && namedAction(current.action) == source.action && current.subscriptionUrl == source.url) { "Rule set details changed. Reopen and retry." }
            s.copy(userRuleSets = s.userRuleSets.map { if (it.id == current.id) it.copy(name = name.trim(), action = action,
                subscriptionUrl = url?.trim(), updatedAt = if (url != current.subscriptionUrl) 0 else it.updatedAt,
                updateError = if (url != current.subscriptionUrl) null else it.updateError) else it })
        }
    }
    suspend fun delete(source: Source) = settings.changeLibrary(source.id, "delete", source.name) { s ->
        require(source.kind == Kind.USER || source.kind == Kind.SUBSCRIPTION)
        verifyWholeSource(s, source)
        s.copy(userRuleSets = s.userRuleSets.filterNot { "user:${it.id}" == source.id }, ruleSubscriptionUrls = s.ruleSubscriptionUrls - setOfNotNull(source.url))
    }
    suspend fun restoreBuiltin(source: Source) = settings.changeLibrary(source.id, "restore", source.name) { s ->
        require(source.kind == Kind.BUILTIN && source.editable)
        verifyWholeSource(s, source)
        s.copy(ruleSetOverrides = s.ruleSetOverrides - source.id.substringAfter(':'))
    }
    suspend fun copy(source: Source, name: String): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        // Immutable content can be shared safely; subsequent edits create another collection.
        val values = if (source.ref.kind == RuleContentStore.STRINGS) store.strings(source.ref) else store.persistStrings(store.rows(source.ref).map { it.value })
        settings.changeLibrary("user:$id", "copy", name) { s -> s.copy(userRuleSets = s.userRuleSets + UserRuleSet(id, name, if (source.action == RuleAction.REJECT) RuleAction.REJECT else RuleAction.DIRECT, values, enabled = false)) }
        "user:$id"
    }

    suspend fun editItem(source: Source, old: String?, input: String) = withContext(Dispatchers.IO) {
        val value = requireNotNull(RuleInput.normalize(input)) { "Invalid rule" }
        check(source.editable) { "Copy this read-only collection before editing" }
        val before = settings.settings.first()
        verifySelection(before, source)
        if (source.kind == Kind.PAUSED_OVERRIDES) require(before.hostOverrides[value] == null) { "An active override already uses this target" }
        if (source.kind == Kind.OVERRIDES) require(before.disabledHostOverrides[value] == null) { "A paused override already uses this target" }
        val current = sourceRows(before, source)
        require(old == value || current.none { it.value == value }) { "This rule already exists" }
        val kind = source.ref.kind
        val ref = store.writer(kind).use { writer ->
            var found = old == null
            current.forEach { row ->
                if (row.value == old) { writer.add(row.copy(value = value)); found = true }
                else writer.add(row)
            }
            check(found) { "This rule has changed. Reload and retry." }
            if (old == null) writer.add(RuleContentStore.Row(value, addedAt = System.currentTimeMillis(), action = if (kind == RuleContentStore.OVERRIDES) source.action else null))
            writer.finish()
        }
        settings.changeLibrary(source.id, "edit", value, before) { replaceContent(it, source, ref) }
    }

    suspend fun deleteItem(item: Item) = withContext(Dispatchers.IO) {
        check(item.source.editable)
        val before = settings.settings.first()
        verifySelection(before, item.source)
        val ref = store.writer(item.source.ref.kind).use { w -> sourceRows(before, item.source).filterNot { it.value == item.row.value }.forEach(w::add); w.finish() }
        val change = settings.changeLibrary(item.source.id, "delete", item.row.value, before) { replaceContent(it, item.source, ref) }
        if (item.source.kind == Kind.DIRECT) settings.addDomainHistory(listOf(item.row.value))
        change
    }

    suspend fun editManual(item: Item, input: String, action: RuleAction, scope: RuleScope) = withContext(Dispatchers.IO) {
        require(item.source.isManual)
        val parsed = requireNotNull(RuleInput.forScope(input, scope)) { "Invalid rule" }
        if (action == item.action) return@withContext editItem(item.source, item.row.value, parsed.value)
        val before = settings.settings.first(); verifySelection(before, item.source)
        val old = item.row.value
        val rowEnabled = item.source.kind != Kind.PAUSED_OVERRIDES && item.row.enabled
        check(sourceRows(before, item.source).any { it.value == old && it.enabled == rowEnabled && (it.action ?: item.source.action) == item.action }) { "This rule changed. Reopen and retry." }
        val removed = when (item.source.kind) {
            Kind.DIRECT -> before.copy(userDirectRules = before.userDirectRules.ruleFilterNot { it.value == old })
            Kind.REJECT -> before.copy(userRejectRules = before.userRejectRules.ruleFilterNot { it.value == old })
            Kind.OVERRIDES -> before.copy(hostOverrides = before.hostOverrides.ruleFilterKeys { it != old })
            else -> before.copy(disabledHostOverrides = before.disabledHostOverrides.ruleFilterKeys { it != old })
        }
        val enabled = rowEnabled
        if (!enabled) {
            val state = HostRuleEdits.capture(removed, parsed.host)
            require(state.direct.isEmpty() && state.reject.isEmpty() && state.overrides.isEmpty() && state.disabledOverrides.isEmpty()) { "This target already has manual settings. Edit that rule instead." }
        }
        val after = if (enabled) HostRuleEdits.apply(removed, parsed.value, action, parsed.scope ?: RuleScope.SINGLE, System.currentTimeMillis())
        else when (action) {
            RuleAction.DIRECT -> removed.copy(userDirectRules = removed.userDirectRules.rulePlus(RuleEntry(parsed.value, false, item.row.addedAt, item.row.disabledAt)))
            RuleAction.REJECT -> removed.copy(userRejectRules = removed.userRejectRules.rulePlus(RuleEntry(parsed.value, false, item.row.addedAt, item.row.disabledAt)))
            RuleAction.PROXY -> removed.copy(disabledHostOverrides = removed.disabledHostOverrides.ruleRebuild(removed.disabledHostOverrides.asSequence().map { it.key to it.value } + (parsed.value to action)))
        }
        settings.changeLibrary("manual", "edit", parsed.value, before) { current -> current.copy(
            userDirectRules = after.userDirectRules, userRejectRules = after.userRejectRules,
            hostOverrides = after.hostOverrides, disabledHostOverrides = after.disabledHostOverrides,
        ) }
    }

    suspend fun setBuiltins(ids: Set<String>, enabled: Boolean) = settings.changeLibrary("builtins", if (enabled) "enable" else "disable", "") { s ->
        s.copy(enabledRuleGroups = if (enabled) s.enabledRuleGroups + ids else s.enabledRuleGroups - ids)
    }

    private suspend fun verifySelection(settings: ProxySettings, source: Source) {
        val current = sources(settings, includeBuiltins = source.kind == Kind.BUILTIN).firstOrNull { it.id == source.id }
        // Item edits rebuild from the latest collection and guard that snapshot at commit.
        // Disabling another related item during this form must not invalidate this edit.
        check(current != null && current.kind == source.kind && current.action == source.action && current.url == source.url) { "This collection changed. Reload and retry." }
    }
    private fun verifyWholeSource(s: ProxySettings, source: Source) {
        val currentRef = when (source.kind) {
            Kind.DIRECT -> entries(s.userDirectRules)
            Kind.REJECT -> entries(s.userRejectRules)
            Kind.OVERRIDES -> overrides(s.hostOverrides)
            Kind.PAUSED_OVERRIDES -> overrides(s.disabledHostOverrides)
            Kind.BUILTIN -> reference(s.ruleSetOverrides[source.id.substringAfter(':')] ?: builtin(requireNotNull(RuleCatalog.byId(source.id.substringAfter(':')))))
            else -> {
                val set = requireNotNull(s.userRuleSets.firstOrNull { "user:${it.id}" == source.id }) { "Rule set no longer exists" }
                check(set.name == source.name && namedAction(set.action) == source.action && set.subscriptionUrl == source.url) { "Rule set changed. Reopen and retry." }
                reference(set.domains)
            }
        }
        check(currentRef == source.ref) { "Rule content changed. Reopen and retry." }
    }
    private fun namedAction(action: RuleAction) = if (action == RuleAction.REJECT) RuleAction.REJECT else RuleAction.DIRECT

    /** Inspect all staged rows with indexed lookups; keep only a bounded set of actionable examples. */
    suspend fun relatedImport(ref: RuleContentStore.Ref, action: RuleAction, sources: List<Source>,
        progress: (Int) -> Unit = {}): List<Related> = withContext(Dispatchers.IO) {
        val counts = sources.associateWith { store.counts(it.ref) }
        val found = linkedMapOf<String, Related>()
        var visited = 0
        for (row in store.rows(ref)) {
            coroutineContext.ensureActive()
            val input = requireNotNull(RuleInput.parse(row.value))
            val kind = if (!input.isIp) "domain" else if (IpRange.ofRule(input.host)?.bits == 32) "ipv4" else "ipv6"
            val candidates = sources.filter { (counts[it]?.get(kind) ?: 0) > 0 }
            related(row.value, action, candidates).forEach { relation ->
                found.putIfAbsent("${relation.relation}:${relation.item.key}", relation)
            }
            visited++; if (visited % 250 == 0) progress(visited)
            if (found.size >= 50) break // UI explicitly says these are the first 50 relationships.
        }
        progress(visited)
        found.values.take(50)
    }

    internal fun sourceRows(s: ProxySettings, source: Source): Sequence<RuleContentStore.Row> = when (source.kind) {
        Kind.DIRECT -> s.userDirectRules.asSequence().map { RuleContentStore.Row(it.value, it.enabled, it.addedAt, it.disabledAt) }
        Kind.REJECT -> s.userRejectRules.asSequence().map { RuleContentStore.Row(it.value, it.enabled, it.addedAt, it.disabledAt) }
        Kind.OVERRIDES -> s.hostOverrides.asSequence().map { RuleContentStore.Row(it.key, action = it.value) }
        Kind.PAUSED_OVERRIDES -> s.disabledHostOverrides.asSequence().map { RuleContentStore.Row(it.key, enabled = false, action = it.value) }
        Kind.BUILTIN -> (s.ruleSetOverrides[source.id.substringAfter(':')] ?: store.strings(source.ref)).asSequence().map { RuleContentStore.Row(it) }
        else -> requireNotNull(s.userRuleSets.firstOrNull { "user:${it.id}" == source.id }).domains.asSequence().map { RuleContentStore.Row(it) }
    }

    internal fun replaceContent(s: ProxySettings, source: Source, ref: RuleContentStore.Ref): ProxySettings = when (source.kind) {
        Kind.DIRECT -> s.copy(userDirectRules = store.entries(ref))
        Kind.REJECT -> s.copy(userRejectRules = store.entries(ref))
        Kind.OVERRIDES -> s.copy(hostOverrides = store.overrides(ref))
        Kind.PAUSED_OVERRIDES -> s.copy(disabledHostOverrides = store.overrides(ref))
        Kind.BUILTIN -> s.copy(ruleSetOverrides = s.ruleSetOverrides + (source.id.substringAfter(':') to store.strings(ref)))
        else -> s.copy(userRuleSets = s.userRuleSets.map { if ("user:${it.id}" == source.id) it.copy(domains = store.strings(ref), updatedAt = if (it.subscriptionUrl != null) System.currentTimeMillis() else it.updatedAt, updateError = null) else it })
    }
    suspend fun export(source: Source, output: java.io.OutputStream) = withContext(Dispatchers.IO) {
        output.bufferedWriter().use { writer -> store.rows(source.ref).forEach { coroutineContext.ensureActive(); writer.write(it.value); writer.newLine() } }
    }
    fun validateUrl(value: String) {
        require(value.length <= 2048) { "Subscription URL is too long" }
        val uri = java.net.URI(value)
        require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "Enter an HTTP or HTTPS subscription URL" }
    }
}
