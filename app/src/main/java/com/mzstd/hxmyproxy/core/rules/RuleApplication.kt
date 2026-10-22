package com.mzstd.hxmyproxy.core.rules

/** Runtime acknowledgement, separate from the user's persisted switches. */
enum class RuleApplyPhase { SAVED, APPLYING, APPLIED, FAILED }
enum class RuleLoadFailure { ASSET_READ, VALIDATION, STORAGE }

data class RuleCounts(
    val reject: Int = 0, val direct: Int = 0, val proxy: Int = 0,
    val userDirect: Int = 0, val userReject: Int = 0,
    val overrideDirect: Int = 0, val overrideReject: Int = 0, val overrideProxy: Int = 0,
) {
    // The always-loaded ads allowlist is NOT a routing/protection table.
    val total: Int get() = reject + direct + proxy + userDirect + userReject + overrideDirect + overrideReject + overrideProxy
    val protection: Int get() = reject + direct + userDirect + userReject + overrideDirect + overrideReject
    companion object {
        fun of(s: RuleEngine.Snapshot) = RuleCounts(s.reject.size, s.direct.size, s.proxy.size,
            s.userDirect.size, s.userReject.size, s.ovrDirect.size, s.ovrReject.size, s.ovrProxy.size)
    }
}

data class RuleApplicationState(
    val phase: RuleApplyPhase = RuleApplyPhase.SAVED,
    val requestId: Long = 0,
    val appliedVersion: Int = 0,
    val counts: RuleCounts = RuleCounts(),
    val failure: RuleLoadFailure? = null,
    val failureDetail: String? = null,
    val attempt: Int = 0,
    val retryScheduled: Boolean = false,
    val stoppedForFailure: Boolean = false,
)

sealed interface RuleBuildResult {
    data class Ready(val version: Int, val counts: RuleCounts, val changed: Boolean) : RuleBuildResult
    data object Superseded : RuleBuildResult
    data class Failed(
        val reason: RuleLoadFailure,
        val detail: String,
        val previousVersion: Int,
        val previousCounts: RuleCounts,
        val requestedProtection: Boolean,
    ) : RuleBuildResult {
        val retryable: Boolean get() = reason == RuleLoadFailure.ASSET_READ
    }
}
