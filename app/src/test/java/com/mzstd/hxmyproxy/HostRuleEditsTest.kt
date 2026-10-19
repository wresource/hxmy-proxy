package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.model.RuleEntry
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleMatcher
import com.mzstd.hxmyproxy.core.rules.RuleScope
import com.mzstd.hxmyproxy.core.rules.HostRuleEdits
import com.mzstd.hxmyproxy.core.rules.RecentRuleChange
import org.junit.Assert.*
import org.junit.Test

class HostRuleEditsTest {
    @Test fun explicitExactEditNormalizesUrlAndKeepsRulesVisibleInTheirLists() {
        val before = ProxySettings(userRejectRules = listOf(RuleEntry("*.example.com"), RuleEntry("=api.example.com")))
        val after = HostRuleEdits.apply(before, "https://api.example.com/path?private=yes", RuleAction.DIRECT, RuleScope.EXACT, 1)
        assertEquals(listOf("=api.example.com"), after.userDirectRules.map { it.value })
        assertEquals(listOf("*.example.com"), after.userRejectRules.map { it.value })
        assertTrue(after.hostOverrides.isEmpty())
        assertFalse(after.toString().contains("private=yes"))
    }
    @Test fun proxyOverrideAndRestoreDefaultRemainSeparateOperations() {
        val before = ProxySettings(userDirectRules = listOf(RuleEntry("*.apple.com")), userRejectRules = listOf(RuleEntry("=child.apple.com")))
        val proxy = HostRuleEdits.apply(before, "apple.com", RuleAction.PROXY, RuleScope.EXACT, 2)
        assertEquals(mapOf("=apple.com" to RuleAction.PROXY), proxy.hostOverrides)
        assertTrue(proxy.userDirectRules.isEmpty())
        assertEquals(before.userRejectRules, proxy.userRejectRules)
        val cleared = HostRuleEdits.apply(proxy, "apple.com", null, RuleScope.EXACT, 3)
        assertTrue(cleared.hostOverrides.isEmpty()); assertEquals(before.userRejectRules, cleared.userRejectRules)
    }
    @Test fun invalidInputIsAtomicAndCannotClearExistingRules() {
        val before = ProxySettings(userDirectRules = listOf(RuleEntry("=api.example")))
        assertEquals(before, HostRuleEdits.apply(before, "http://[broken", RuleAction.REJECT, RuleScope.EXACT, 3))
    }
    @Test fun undoRestoresOnlyTargetAndRefusesAConflictingLaterEdit() {
        val original = ProxySettings(userDirectRules = listOf(RuleEntry("*.example.org", enabled = false, addedAt = 1)))
        val edited = HostRuleEdits.apply(original, "example.org", RuleAction.PROXY, RuleScope.EXACT, 2)
        val change = RecentRuleChange("change-1", "example.org", "=example.org", RuleAction.PROXY,
            HostRuleEdits.capture(original, "example.org"), HostRuleEdits.capture(edited, "example.org"), 2)
        val unrelated = edited.copy(userRejectRules = listOf(RuleEntry("=unrelated.org")))
        val restored = HostRuleEdits.revert(unrelated, change)!!
        assertEquals(original.userDirectRules, restored.userDirectRules)
        assertEquals(unrelated.userRejectRules, restored.userRejectRules)
        assertNull(HostRuleEdits.revert(HostRuleEdits.apply(edited, "example.org", RuleAction.REJECT, RuleScope.SINGLE, 3), change))
    }
    @Test fun ipAndCidrDoNotAcquireDomainScopePrefixes() {
        val after = HostRuleEdits.apply(ProxySettings(), "192.0.2.0/24", RuleAction.PROXY, RuleScope.EXACT, 2)
        assertEquals(setOf("192.0.2.0/24"), after.hostOverrides.keys)
        val matcher = RuleMatcher()
        // JVM android.jar returns false/null for InetAddresses. Bridge these
        // three literal addresses only; production CIDR comparison remains real.
        val addresses = listOf("192.0.2.0", "192.0.2.42", "198.51.100.1").associateWith(java.net.InetAddress::getByName)
        io.mockk.mockkStatic(android.net.InetAddresses::class)
        try {
            io.mockk.every { android.net.InetAddresses.isNumericAddress(any()) } answers { firstArg<String>() in addresses }
            io.mockk.every { android.net.InetAddresses.parseNumericAddress(any()) } answers { addresses[firstArg<String>()] ?: error("Unexpected address") }
            after.hostOverrides.keys.forEach { matcher.add(it) }
            assertTrue(matcher.matches("192.0.2.42")); assertFalse(matcher.matches("198.51.100.1"))
        } finally { io.mockk.unmockkStatic(android.net.InetAddresses::class) }
    }
}
