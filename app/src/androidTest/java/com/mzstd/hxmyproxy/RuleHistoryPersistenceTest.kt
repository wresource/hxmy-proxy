package com.mzstd.hxmyproxy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.model.RuleEntry
import com.mzstd.hxmyproxy.core.rules.*
import com.mzstd.hxmyproxy.data.repository.RuleChangeJournalCodec
import com.mzstd.hxmyproxy.data.repository.SettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuleHistoryPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun newRepositoryAndBackupRoundTripKeepTheUndoSnapshot() = runBlocking {
        val repo = SettingsRepository(context)
        val original = repo.exportJson()
        try {
            val before = ProxySettings(httpPort = 19123, userDirectRules = listOf(RuleEntry("*.example.org", false, 1, 2)))
            repo.update { before }; repo.clearRuleChangeHistory()
            repo.applyDomainRule("https://example.org/private?token=never-save-this", RuleAction.PROXY, RuleScope.EXACT)
            val reopened = SettingsRepository(context)
            val journal = reopened.ruleChangeHistory.first()
            val change = journal.changes.single()
            assertEquals("=example.org", change.rule)
            assertEquals(HostRuleEdits.capture(before, "example.org"), change.before)
            val backup = reopened.exportJson()
            assertFalse(backup.contains("never-save-this"))
            repo.clearRuleChangeHistory(); repo.update { ProxySettings() }
            repo.importJson(backup)
            assertEquals(journal, repo.ruleChangeHistory.first())
            assertTrue(repo.revertDomainChange(change.id))
            assertEquals(before, repo.settings.first())
            assertTrue(SettingsRepository(context).ruleChangeHistory.first().changes.single().reverted)
            assertFalse(repo.revertDomainChange(change.id))
        } finally { repo.importJson(original) }
    }

    @Test fun laterManualEditsAndClearHistoryPreserveCurrentRules() = runBlocking {
        val repo = SettingsRepository(context); val original = repo.exportJson()
        try {
            repo.update { ProxySettings() }; repo.clearRuleChangeHistory()
            repo.applyDomainRule("example.org", RuleAction.PROXY, RuleScope.EXACT)
            val change = repo.ruleChangeHistory.first().changes.single()
            val after = repo.settings.first()
            repo.update { HostRuleEdits.apply(it, "example.org", RuleAction.REJECT, RuleScope.EXACT, 3) }
            repo.update { after }
            assertEquals(change.after, HostRuleEdits.capture(repo.settings.first(), "example.org"))
            assertTrue(repo.ruleChangeHistory.first().forDisplay().single().superseded)
            assertFalse(repo.revertDomainChange(change.id))
            repo.clearRuleChangeHistory()
            assertEquals(after, repo.settings.first())
            assertTrue(repo.ruleChangeHistory.first().changes.isEmpty())
        } finally { repo.importJson(original) }
    }

    @Test fun malformedOrCrossHostUndoInBackupRejectsTheWholeImport() = runBlocking {
        val repo = SettingsRepository(context); val original = repo.exportJson()
        try {
            repo.update { ProxySettings(httpPort = 19001) }; repo.clearRuleChangeHistory()
            repo.applyDomainRule("example.org", RuleAction.PROXY, RuleScope.EXACT)
            val settings = repo.settings.first(); val history = repo.ruleChangeHistory.first()
            val valid = repo.exportJson()
            fun poisoned(value: String): String = JSONObject(valid).apply {
                getJSONObject("settings").getJSONObject("http_port").put("v", 19999)
                getJSONObject("settings").getJSONObject("recent_rule_changes_v1").put("v", value)
            }.toString()
            val foreign = JSONObject(RuleChangeJournalCodec.encode(history)).apply {
                getJSONArray("changes").getJSONObject(0).getJSONObject("before")
                    .getJSONObject("overrides").put("=other.example", "DIRECT")
            }.toString()
            for (bad in listOf("{", foreign)) {
                assertTrue(runCatching { repo.importJson(poisoned(bad)) }.isFailure)
                assertEquals(settings, repo.settings.first())
                assertEquals(history, repo.ruleChangeHistory.first())
            }
            assertTrue(RuleChangeJournalCodec.readLocal("{").unreadable)
            assertTrue(RuleChangeJournalCodec.readLocal("{").changes.isEmpty())
        } finally { repo.importJson(original) }
    }

    @Test fun concurrentQuickChangesAreAllCommittedWithTheirRecords() = runBlocking {
        val repo = SettingsRepository(context); val original = repo.exportJson()
        try {
            repo.update { ProxySettings() }; repo.clearRuleChangeHistory()
            coroutineScope { (0 until 20).map { i -> async {
                repo.applyDomainRule("concurrent-$i.example", RuleAction.PROXY, RuleScope.EXACT)
            } }.awaitAll() }
            val journal = SettingsRepository(context).ruleChangeHistory.first()
            assertEquals(20, journal.changes.size)
            assertEquals(20, journal.changes.map { it.id }.distinct().size)
            val settings = repo.settings.first()
            journal.changes.forEach { assertEquals(it.after, HostRuleEdits.capture(settings, it.host)) }
        } finally { repo.importJson(original) }
    }
}
