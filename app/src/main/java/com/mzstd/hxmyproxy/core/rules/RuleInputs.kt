package com.mzstd.hxmyproxy.core.rules

import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.model.RuleEntry

/**
 * The existing nine matching inputs, shared by the memo and the session worker.
 * Preserve whole RuleEntry values: enabled controls membership and addedAt breaks action ties.
 * Subscription names/status and inactive named sets never change matching; theme/language/ports
 * must not reload the 61,063-entry table. Adding an input here requires checking the builder too.
 * Equality may page disk lists: use off Main and outside the lifecycle lock.
 */
internal data class RuleInputs(
    val groups: Set<String>,
    val rejectedGroups: Set<String>,
    val overrides: Map<String, List<String>>,
    val hostOverrides: Map<String, RuleAction>,
    val userDirectEnabled: Boolean,
    val userRejectEnabled: Boolean,
    val userDirect: List<RuleEntry>,
    val userReject: List<RuleEntry>,
    val sets: List<NamedInput>,
) {
    data class NamedInput(val id: String, val action: RuleAction, val domains: List<String>)
    companion object {
        fun of(s: ProxySettings) = RuleInputs(s.enabledRuleGroups, s.rejectedGroups, s.ruleSetOverrides,
            s.hostOverrides, s.userDirectEnabled, s.userRejectEnabled, s.userDirectRules, s.userRejectRules,
            s.userRuleSets.filter { it.enabled }.map { NamedInput(it.id, it.action, it.domains) })
    }
}
