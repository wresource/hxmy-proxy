package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.rules.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class ClientDirectRuleTest {
    private val first = InetAddress.getByName("192.0.2.10")
    private val other = InetAddress.getByName("192.0.2.11")
    private fun engine() = RuleEngine().apply {
        update(RuleEngine.Snapshot(
            ovrProxy = RuleMatcher().apply { add("proxy.example") },
            ovrReject = RuleMatcher().apply { add("blocked.example") },
            userDirect = RuleMatcher().apply { add("direct.example") },
        ))
    }

    @Test fun overrideIsSourceScopedAndDisablingRestoresEveryOriginalDecision() {
        val engine = engine()
        val hosts = listOf("proxy.example", "blocked.example", "direct.example", "default.example", "203.0.113.8")
        val before = hosts.associateWith { engine.decideDetailed(it, first) }
        assertEquals(setOf("192.0.2.10"), engine.updateDirectClients(setOf("192.0.2.10")))
        for (host in hosts) {
            assertEquals(RuleDecision(RuleAction.DIRECT, RuleSrc.CLIENT_DIRECT), engine.decideDetailed(host, first))
            assertEquals(before[host], engine.decideDetailed(host, other))
            assertEquals(before[host], engine.decideDetailed(host))
        }
        assertEquals(setOf("192.0.2.10"), engine.updateDirectClients(emptySet()))
        for (host in hosts) assertEquals(before[host], engine.decideDetailed(host, first))
    }

    @Test fun emptyOrRebuiltDomainSnapshotsKeepTheClientSelection() {
        val engine = engine()
        engine.updateDirectClients(setOf("192.0.2.10"))
        engine.update(RuleEngine.Snapshot())
        assertEquals(RuleAction.DIRECT, engine.decideDetailed("example.com", first).action)
        assertEquals(RuleAction.PROXY, engine.decideDetailed("example.com", other).action)
        val version = engine.version.value
        assertTrue(engine.updateDirectClients(setOf("192.0.2.10")).isEmpty())
        assertEquals(version, engine.version.value)
    }

    @Test fun addressAliasesAreCanonicalAndNamesOrRangesNeverBecomeClientIdentities() {
        val v6 = InetAddress.getByName("2001:db8::123")
        val engine = engine()
        engine.updateDirectClients(setOf("2001:db8::123", "::ffff:192.0.2.10"))
        assertTrue(engine.isClientDirect(first))
        assertTrue(engine.isClientDirect(v6))
        for (input in listOf("example.com", "192.0.2.0/24", "192.0.2.1-192.0.2.2", "192.000.2.10", ""))
            assertNull(input, ClientAddress.parse(input))
        assertThrows(IllegalArgumentException::class.java) { engine.updateDirectClients(setOf("bad.example")) }
        assertTrue(engine.isClientDirect(first))
    }
}
