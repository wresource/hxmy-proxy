package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.rules.*
import org.junit.Assert.*
import org.junit.Test

class TopLevelSuffixRuleTest {
    @Test fun leadingDotAcceptsTopLevelSuffixAndRoundTrips() {
        for (text in listOf(".cn", " .CN ", ".cn.")) {
            val parsed = requireNotNull(RuleInput.parse(text))
            assertEquals("cn", parsed.host)
            assertEquals(".cn", parsed.value)
            assertEquals(parsed.value, RuleInput.normalize(parsed.value))
            assertEquals(".cn", RuleInput.forScope(text, RuleScope.SINGLE)?.value)
        }
        assertEquals("com.cn", RuleInput.normalize(".com.cn"))
        assertEquals("example.com", RuleInput.normalize(".EXAMPLE.COM"))
    }

    @Test fun suffixMatchesAtLabelBoundaryAcrossEveryDepthAndKeepsExceptions() {
        val rule = requireNotNull(RuleInput.normalize(".cn"))
        val engine = RuleEngine().apply { update(RuleEngine.Snapshot(
            userDirect = RuleMatcher().apply { add(rule) },
            userReject = RuleMatcher().apply { add("ads.example.cn") },
            ovrProxy = RuleMatcher().apply { add("proxy.example.cn") },
        )) }
        for (host in listOf("example.cn", "a.example.cn", "a.b.example.com.cn"))
            assertEquals(host, RuleAction.DIRECT, engine.decide(host))
        for (host in listOf("example.cn.com", "examplecn.com", "example.cn.evil.example", "192.0.2.1"))
            assertEquals(host, RuleAction.PROXY, engine.decide(host))
        assertEquals(RuleAction.REJECT, engine.decide("ads.example.cn"))
        assertEquals(RuleAction.PROXY, engine.decide("proxy.example.cn"))
    }

    @Test fun ordinaryMalformedInputAndOldSingleScopeStayProtected() {
        for (text in listOf("cn", "nas", "..cn", ".", ".*.cn", ".=cn", ".127.0.0.1", ".192.0.2.0/24",
            ".192.0.2.1-192.0.2.2", ".192.0.2.1 192.0.2.2", ".https://example.cn", "a..cn"))
            assertNull(text, RuleInput.parse(text))
        val matcher = RuleMatcher().apply { add("*.example.cn") }
        assertTrue(matcher.matches("a.example.cn"))
        assertFalse(matcher.matches("a.b.example.cn"))
    }

    @Test fun addReplaceAndUndoKeepTheCanonicalSuffixRule() {
        val before = ProxySettings()
        val direct = HostRuleEdits.apply(before, ".cn", RuleAction.DIRECT, RuleScope.SINGLE, 10)
        assertEquals(listOf(".cn"), direct.userDirectRules.map { it.value })
        val blocked = HostRuleEdits.apply(direct, ".cn", RuleAction.REJECT, RuleScope.EXACT, 20)
        assertTrue(blocked.userDirectRules.isEmpty())
        assertEquals(listOf(".cn"), blocked.userRejectRules.map { it.value })
        val parsed = requireNotNull(RuleInput.parse(".cn"))
        val journal = RuleChangeJournal().record(direct, blocked, parsed.host, parsed.value, RuleAction.REJECT, 20)
        val reverted = requireNotNull(journal.revert(blocked, journal.changes.single().id)).first
        assertEquals(direct.userDirectRules, reverted.userDirectRules)
        assertEquals(direct.userRejectRules, reverted.userRejectRules)
    }
}
