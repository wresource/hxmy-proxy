package com.mzstd.hxmyproxy.ui.rules

import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.model.RuleEntry
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleInput
import com.mzstd.hxmyproxy.core.rules.RuleScope

data class HostRuleState(val direct: List<RuleEntry>, val reject: List<RuleEntry>, val overrides: Map<String, RuleAction>)
data class RecentRuleChange(val id: Long, val host: String, val rule: String, val action: RuleAction?,
    val before: HostRuleState, val after: HostRuleState, val at: Long, val reverted: Boolean = false)

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
