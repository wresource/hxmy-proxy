package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.*
import com.mzstd.hxmyproxy.core.rules.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuleApplySessionTest {
    private val empty = ProxySettings()
    private fun config(host: String) = ProxySettings(userDirectRules = listOf(RuleEntry(host)))
    private val old = RuleBuildResult.Ready(7, RuleCounts(userDirect = 1), true)
    private fun failure(previous: RuleBuildResult.Ready, reason: RuleLoadFailure = RuleLoadFailure.ASSET_READ,
        protection: Boolean = true) = RuleBuildResult.Failed(reason, "controlled", previous.version, previous.counts, protection)

    @Test fun validEmptyConfigurationAndUnrelatedEditsDoNotReload() = runTest {
        var calls = 0
        val states = mutableListOf<RuleApplicationState>()
        val session = RuleApplySession(backgroundScope, Any(), { true }, empty,
            RuleBuildResult.Ready(1, RuleCounts(), true),
            { _, _, _ -> calls++; error("no rebuild expected") }, states::add, { error("must not stop") },
            dispatcher = StandardTestDispatcher(testScheduler))
        session.submit(empty.copy(themeMode = ThemeMode.DARK, httpPort = 9090, language = AppLanguage.CHINESE))
        runCurrent()
        assertEquals(0, calls)
        assertEquals(RuleApplyPhase.APPLIED, states.last().phase)
        assertEquals(0, states.last().counts.total)
    }

    @Test fun emptyOldTableAndNewProtectionFailureStopsWithoutRetry() = runTest {
        var calls = 0; var stopped = 0
        val initial = RuleBuildResult.Ready(1, RuleCounts(), true)
        val states = mutableListOf<RuleApplicationState>()
        lateinit var session: RuleApplySession
        session = RuleApplySession(backgroundScope, Any(), { true }, empty, initial,
            { _, _, _ -> calls++; failure(initial) }, states::add, { stopped++; session.close() },
            dispatcher = StandardTestDispatcher(testScheduler))
        session.submit(config("direct.test")); runCurrent(); advanceTimeBy(30_000); runCurrent()
        assertEquals(1, calls); assertEquals(1, stopped)
        assertEquals(RuleApplyPhase.FAILED, states.last().phase)
        assertTrue(states.last().stoppedForFailure)
        assertFalse(states.last().retryScheduled)
        session.retry(); runCurrent(); assertEquals(1, calls)
    }

    @Test fun priorGoodRulesSurviveAndAutomaticRetriesAreBounded() = runTest {
        var calls = 0
        val states = mutableListOf<RuleApplicationState>()
        val session = RuleApplySession(backgroundScope, Any(), { true }, config("old.test"), old,
            { _, _, _ -> calls++; failure(old) }, states::add, { error("old rules must survive") },
            dispatcher = StandardTestDispatcher(testScheduler))
        session.submit(config("new.test")); runCurrent()
        assertEquals(1, calls); assertTrue(states.last().retryScheduled)
        assertEquals(old.counts, states.last().counts)
        advanceTimeBy(1_000); runCurrent(); assertEquals(2, calls)
        advanceTimeBy(3_000); runCurrent(); assertEquals(3, calls)
        assertFalse(states.last().retryScheduled)
        session.submit(config("new.test").copy(themeMode = ThemeMode.DARK))
        advanceTimeBy(60_000); runCurrent(); assertEquals(3, calls)
        assertEquals(7, states.last().appliedVersion)
    }

    @Test fun validationFailureDoesNotEnterAutomaticRetryLoop() = runTest {
        var calls = 0
        val states = mutableListOf<RuleApplicationState>()
        val session = RuleApplySession(backgroundScope, Any(), { true }, config("old.test"), old,
            { _, _, _ -> calls++; failure(old, RuleLoadFailure.VALIDATION) }, states::add, { error("must retain") },
            dispatcher = StandardTestDispatcher(testScheduler))
        session.submit(config("new.test")); runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertEquals(1, calls); assertFalse(states.last().retryScheduled)
    }

    @Test fun newRequestCancelsOldRetryAndManualRetryUsesLatestSettings() = runTest {
        val loaded = mutableListOf<String>()
        val states = mutableListOf<RuleApplicationState>()
        var succeeding = false
        val session = RuleApplySession(backgroundScope, Any(), { true }, config("old.test"), old,
            { settings, _, commit ->
                loaded.add(settings.userDirectRules.single().value)
                if (!succeeding) failure(old)
                else if (commit { }) RuleBuildResult.Ready(8, old.counts, true) else RuleBuildResult.Superseded
            }, states::add, { error("must retain") }, dispatcher = StandardTestDispatcher(testScheduler))
        session.submit(config("first.test")); runCurrent()
        session.submit(config("latest.test")); runCurrent()
        succeeding = true
        session.retry(); runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf("first.test", "latest.test", "latest.test"), loaded)
        assertEquals(RuleApplyPhase.APPLIED, states.last().phase)
        assertNull(states.last().failure)
    }

    @Test fun stoppedSessionCannotRetryOrPublishABlockedBuild() = runTest {
        var published = 0; var calls = 0
        val states = mutableListOf<RuleApplicationState>()
        lateinit var session: RuleApplySession
        session = RuleApplySession(backgroundScope, Any(), { true }, config("old.test"), old,
            { _, _, commit ->
                calls++; session.close()
                if (commit { published++ }) old else RuleBuildResult.Superseded
            }, states::add, { error("cancel is not failure") }, dispatcher = StandardTestDispatcher(testScheduler))
        session.submit(config("new.test")); runCurrent(); session.retry()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, calls); assertEquals(0, published)
        assertFalse(states.any { it.phase == RuleApplyPhase.FAILED })
    }

    @Test fun supersededFailureCannotStopNewRequestOrOverwriteItsStatus() = runTest {
        val states = mutableListOf<RuleApplicationState>()
        val published = mutableListOf<String>()
        val initial = RuleBuildResult.Ready(1, RuleCounts(), true)
        lateinit var session: RuleApplySession
        session = RuleApplySession(backgroundScope, Any(), { true }, empty, initial,
            { settings, _, commit ->
                val name = settings.userDirectRules.single().value
                if (name == "old.test") {
                    session.submit(config("new.test"))
                    assertFalse(commit { published.add(name) })
                    failure(initial)
                } else {
                    assertTrue(commit { published.add(name) })
                    RuleBuildResult.Ready(2, RuleCounts(userDirect = 1), true)
                }
            }, states::add, { error("obsolete failure must not stop") }, dispatcher = StandardTestDispatcher(testScheduler))
        session.submit(config("old.test")); runCurrent()
        assertEquals(listOf("new.test"), published)
        assertFalse(states.any { it.phase == RuleApplyPhase.FAILED })
        assertEquals(2, states.last().appliedVersion)
    }

    @Test fun emptyOldTableWithProxyOnlyEditDoesNotInventAProtectionRequirement() = runTest {
        val initial = RuleBuildResult.Ready(1, RuleCounts(), true)
        val states = mutableListOf<RuleApplicationState>()
        val session = RuleApplySession(backgroundScope, Any(), { true }, empty, initial,
            { _, _, _ -> failure(initial, protection = false) }, states::add, { error("no new protection requested") },
            retryDelaysMs = emptyList(), dispatcher = StandardTestDispatcher(testScheduler))
        session.submit(empty.copy(hostOverrides = mapOf("proxy.test" to RuleAction.PROXY))); runCurrent()
        assertEquals(RuleApplyPhase.FAILED, states.last().phase)
        assertFalse(states.last().stoppedForFailure)
    }

    @Test fun proxyOnlyOldTableIsNotProtectionWhenNewDirectOrRejectCannotLoad() = runTest {
        val initial = RuleBuildResult.Ready(1, RuleCounts(overrideProxy = 1), true)
        val states = mutableListOf<RuleApplicationState>()
        var stopped = false
        val session = RuleApplySession(backgroundScope, Any(), { true },
            empty.copy(hostOverrides = mapOf("proxy.test" to RuleAction.PROXY)), initial,
            { _, _, _ -> failure(initial) }, states::add, { stopped = true },
            dispatcher = StandardTestDispatcher(testScheduler))
        session.submit(config("direct.test")); runCurrent()
        assertTrue("PROXY overrides do not provide any prior DIRECT/REJECT protection", stopped)
        assertTrue(states.last().stoppedForFailure)
    }
}
