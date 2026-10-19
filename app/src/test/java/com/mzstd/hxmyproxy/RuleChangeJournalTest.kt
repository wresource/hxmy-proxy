package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.model.RuleEntry
import com.mzstd.hxmyproxy.core.rules.*
import org.junit.Assert.*
import org.junit.Test

class RuleChangeJournalTest {
    private fun edit(before: ProxySettings, journal: RuleChangeJournal, host: String, action: RuleAction, id: String): Pair<ProxySettings, RuleChangeJournal> {
        val after = HostRuleEdits.apply(before, host, action, RuleScope.EXACT, 10)
        return after to journal.record(before, after, host, "=$host", action, 10, id)
    }

    @Test fun laterEditMustBeUndoneBeforeEarlierEditAndUnrelatedRulesSurvive() {
        val original = ProxySettings(userDirectRules = listOf(RuleEntry("*.example.org", false, 1, 2)))
        val (one, j1) = edit(original, RuleChangeJournal(), "example.org", RuleAction.PROXY, "one")
        val (two, j2) = edit(one, j1, "example.org", RuleAction.REJECT, "two")
        assertNull(j2.revert(two, "one"))
        val extra = two.copy(httpPort = 19000, userDirectRules = two.userDirectRules + RuleEntry("=other.example"))
        val (backOne, backJ1) = j2.revert(extra, "two")!!
        val (restored, _) = backJ1.revert(backOne, "one")!!
        assertEquals(19000, restored.httpPort)
        assertEquals(original.userDirectRules + RuleEntry("=other.example"), restored.userDirectRules.sortedBy { it.value })
        assertTrue(restored.userRejectRules.isEmpty())
        assertTrue(restored.hostOverrides.isEmpty())
    }

    @Test fun changingAwayAndBackCannotMakeAnOldUndoValidAgain() {
        val (one, j1) = edit(ProxySettings(), RuleChangeJournal(), "example.org", RuleAction.PROXY, "one")
        val other = HostRuleEdits.apply(one, "example.org", RuleAction.DIRECT, RuleScope.EXACT, 11)
        val j2 = j1.observeOtherEdit(one, other) { "outside-one" }
        val j3 = j2.observeOtherEdit(other, one) { "outside-two" }
        assertEquals(j1.changes.single().after, HostRuleEdits.capture(one, "example.org"))
        assertNull(j3.revert(one, "one"))
        assertTrue(j3.forDisplay().single().superseded)
    }

    @Test fun unrelatedSettingsAndHostsDoNotInvalidateTheUndo() {
        val (one, j1) = edit(ProxySettings(), RuleChangeJournal(), "example.org", RuleAction.PROXY, "one")
        val other = one.copy(httpPort = 19000, userRejectRules = listOf(RuleEntry("=other.example")))
        assertEquals(j1, j1.observeOtherEdit(one, other))
        val restored = j1.revert(other, "one")!!.first
        assertEquals(19000, restored.httpPort)
        assertEquals(other.userRejectRules, restored.userRejectRules)
    }

    @Test fun identicalProxySelectionDoesNotCreateAFalseChange() {
        val (one, j1) = edit(ProxySettings(), RuleChangeJournal(), "example.org", RuleAction.PROXY, "one")
        val (same, sameJournal) = edit(one, j1, "example.org", RuleAction.PROXY, "unused")
        assertEquals(one, same)
        assertEquals(j1, sameJournal)
    }

    @Test fun historyAndVersionMapStayBoundedAndExpiredIdsCannotRestore() {
        var settings = ProxySettings(); var journal = RuleChangeJournal()
        repeat(30) { i ->
            val result = edit(settings, journal, "h$i.example", RuleAction.PROXY, "id-$i")
            settings = result.first; journal = result.second
        }
        assertEquals(20, journal.changes.size)
        assertEquals(20, journal.versions.size)
        assertEquals("id-10", journal.changes.first().id)
        assertNull(journal.revert(settings, "id-0"))
        assertNotNull(journal.revert(settings, "id-29"))
    }

    @Test fun undoIsIdempotentAndStillChecksTheActualRuleSnapshot() {
        val (one, j1) = edit(ProxySettings(), RuleChangeJournal(), "example.org", RuleAction.PROXY, "one")
        assertNull(j1.revert(one.copy(hostOverrides = emptyMap()), "one"))
        assertNull(j1.revert(one, "unknown"))
        val (restored, journal) = j1.revert(one, "one")!!
        assertNull(journal.revert(restored, "one"))
        assertTrue(journal.forDisplay().single().reverted)
    }
}
