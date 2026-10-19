package com.mzstd.hxmyproxy.core.rules

import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.model.RuleEntry
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleInput
import com.mzstd.hxmyproxy.core.rules.RuleScope

data class HostRuleState(val direct: List<RuleEntry>, val reject: List<RuleEntry>, val overrides: Map<String, RuleAction>)
data class RecentRuleChange(val id: String, val host: String, val rule: String, val action: RuleAction?,
    val before: HostRuleState, val after: HostRuleState, val at: Long, val reverted: Boolean = false,
    val beforeVersion: String? = null, val superseded: Boolean = false)

/** Independent from proxy tunables: reading/clearing history must not reconfigure listeners. */
data class RuleChangeJournal(
    val changes: List<RecentRuleChange> = emptyList(),
    val versions: Map<String, String> = emptyMap(),
    val unreadable: Boolean = false,
) {
    fun forDisplay(): List<RecentRuleChange> = changes.map {
        it.copy(superseded = !it.reverted && versions[it.host] != it.id)
    }

    fun record(before: ProxySettings, after: ProxySettings, host: String, rule: String,
        action: RuleAction?, at: Long, id: String = java.util.UUID.randomUUID().toString()): RuleChangeJournal {
        val from = HostRuleEdits.capture(before, host)
        val to = HostRuleEdits.capture(after, host)
        if (from == to) return this
        val next = (changes + RecentRuleChange(id, host, rule, action, from, to, at,
            beforeVersion = versions[host])).takeLast(LIMIT)
        val hosts = next.mapTo(hashSetOf()) { it.host }
        return RuleChangeJournal(next, (versions + (host to id)).filterKeys { it in hosts })
    }

    /** An unrelated edit leaves this host alone. A change-and-change-back still invalidates old undo. */
    fun observeOtherEdit(before: ProxySettings, after: ProxySettings,
        newVersion: () -> String = { java.util.UUID.randomUUID().toString() }): RuleChangeJournal {
        if (before.userDirectRules == after.userDirectRules && before.userRejectRules == after.userRejectRules &&
            before.hostOverrides == after.hostOverrides) return this
        val changed = changes.mapTo(hashSetOf()) { it.host }.filter {
            HostRuleEdits.capture(before, it) != HostRuleEdits.capture(after, it)
        }
        return if (changed.isEmpty()) this else copy(versions = versions + changed.associateWith { newVersion() })
    }

    fun revert(settings: ProxySettings, id: String): Pair<ProxySettings, RuleChangeJournal>? {
        val change = changes.firstOrNull { it.id == id } ?: return null
        if (versions[change.host] != id) return null
        val restored = HostRuleEdits.revert(settings, change) ?: return null
        val previous = if (change.beforeVersion == null) versions - change.host else versions + (change.host to change.beforeVersion)
        return restored to copy(changes = changes.map { if (it.id == id) it.copy(reverted = true) else it }, versions = previous)
    }

    companion object { const val LIMIT = 20 }
}

object HostRuleEdits {
    private fun matches(value: String, host: String) = RuleInput.parse(value)?.host == host
    fun capture(settings: ProxySettings, host: String) = HostRuleState(
        settings.userDirectRules.filter { matches(it.value, host) },
        settings.userRejectRules.filter { matches(it.value, host) },
        settings.hostOverrides.filterKeys { matches(it, host) },
    )
    fun restore(settings: ProxySettings, host: String, value: HostRuleState): ProxySettings = settings.copy(
        userDirectRules = settings.userDirectRules.filterNot { matches(it.value, host) } + value.direct,
        userRejectRules = settings.userRejectRules.filterNot { matches(it.value, host) } + value.reject,
        hostOverrides = settings.hostOverrides.filterKeys { !matches(it, host) } + value.overrides,
    )
    /** This is an explicitly scoped edit. Existing quick actions keep their historical SINGLE default. */
    fun apply(settings: ProxySettings, input: String, action: RuleAction?, scope: RuleScope, now: Long): ProxySettings {
        val parsed = RuleInput.forScope(input, scope) ?: return settings
        val value = when (action) {
            RuleAction.DIRECT -> HostRuleState(listOf(RuleEntry(parsed.value, addedAt = now)), emptyList(), emptyMap())
            RuleAction.REJECT -> HostRuleState(emptyList(), listOf(RuleEntry(parsed.value, addedAt = now)), emptyMap())
            RuleAction.PROXY -> HostRuleState(emptyList(), emptyList(), mapOf(parsed.value to RuleAction.PROXY))
            null -> HostRuleState(emptyList(), emptyList(), emptyMap())
        }
        return restore(settings, parsed.host, value)
    }
    fun revert(settings: ProxySettings, change: RecentRuleChange): ProxySettings? =
        if (change.reverted || capture(settings, change.host) != change.after) null else restore(settings, change.host, change.before)
}
