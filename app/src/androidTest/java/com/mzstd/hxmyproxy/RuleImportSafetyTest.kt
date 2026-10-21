package com.mzstd.hxmyproxy

import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.log.LogArchive
import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.model.RuleEntry
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.UserRuleSet
import com.mzstd.hxmyproxy.core.rules.RuleScope
import com.mzstd.hxmyproxy.data.repository.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.TimeZone

class RuleImportSafetyTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun pausedOverrideCanChangeActionWithoutBecomingEnabledAndCanBeUndone() = runBlocking {
        val repo = SettingsRepository(context); val before = repo.settings.first()
        val library = RuleLibraryRepository(context, repo)
        try {
            val baseline = ProxySettings(disabledHostOverrides = mapOf("=paused.example.org" to RuleAction.PROXY))
            repo.update { baseline }
            val source = library.sources(includeBuiltins = false).single { it.kind == RuleLibraryRepository.Kind.PAUSED_OVERRIDES }
            val item = library.page(listOf(source)).items.single()
            val change = library.editManual(item, "paused.example.org", RuleAction.DIRECT, RuleScope.EXACT)
            val current = repo.settings.first()
            assertTrue(current.hostOverrides.isEmpty()); assertTrue(current.disabledHostOverrides.isEmpty())
            assertEquals("=paused.example.org", current.userDirectRules.single().value)
            assertFalse(current.userDirectRules.single().enabled)
            assertTrue(repo.revertLibraryChange(change.changeId!!)); assertEquals(baseline, repo.settings.first())
        } finally { repo.update { before } }
    }

    @Test fun malformedBackupDoesNotEraseExistingRulesAndOldSubscriptionsMigrate() = runBlocking {
        val repo = SettingsRepository(context); val before = repo.settings.first()
        try {
            repo.update { ProxySettings(userDirectRules = listOf(RuleEntry("keep.example.org")),
                userRuleSets = listOf(UserRuleSet("safe", "Safe", RuleAction.DIRECT, listOf("keep.example.org")))) }
            val backup = repo.exportJson(); val baseline = repo.settings.first()
            val root = JSONObject(backup); val values = root.getJSONObject("settings")
            val key = values.keys().asSequence().single { values.getJSONObject(it).optString("v").contains("\"id\":\"safe\"") }
            for (broken in listOf("{}", "[null]", "[{\"id\":\"safe\",\"action\":\"DIRECT\",\"domains\":42}]")) {
                val edited = JSONObject(backup)
                edited.getJSONObject("settings").getJSONObject(key).put("v", broken)
                assertTrue(runCatching { repo.importJson(edited.toString()) }.isFailure)
                assertEquals(baseline, repo.settings.first())
            }
            val local = JSONObject(backup)
            local.getJSONObject("settings").getJSONObject(key).put("v", "[{\"id\":\"safe\",\"action\":\"DIRECT\",\"domains\":{\"\$rules\":\"00000000-0000-0000-0000-000000000000\"}}]")
            assertTrue(runCatching { repo.importJson(local.toString()) }.isFailure)
            assertEquals(baseline, repo.settings.first())
            repo.update { ProxySettings(ruleSubscriptionUrls = setOf("https://example.org/rules.txt")) }
            val legacy = repo.exportJson(); repo.importJson(legacy)
            val subscription = repo.settings.first().userRuleSets.single()
            assertFalse(subscription.enabled); assertTrue(subscription.domains.isEmpty())
            assertEquals("https://example.org/rules.txt", subscription.subscriptionUrl)
        } finally { repo.update { before } }
    }

    @Test fun staleTargetAndChangedSubscriptionCannotReceiveAnOldDownload() = runBlocking {
        val repo = SettingsRepository(context); val before = repo.settings.first()
        val library = RuleLibraryRepository(context, repo); val io = RuleImportRepository(context, library)
        try {
            repo.update { ProxySettings(userRuleSets = listOf(UserRuleSet("subscription", "Sub", RuleAction.DIRECT,
                listOf("old.example.org"), subscriptionUrl = "https://example.org/old.txt"))) }
            val source = library.sources(includeBuiltins = false).single { it.id == "user:subscription" }
            val job = io.readCancellable("download.txt", { "downloaded.example.org".byteInputStream() }, origin = source)
            library.rename(source, "Sub", RuleAction.DIRECT, "https://example.org/new.txt")
            val current = repo.settings.first()
            assertEquals(RuleImportRepository.Phase.FAILED, io.commit(job, RuleImportRepository.Destination(source = source, replace = true)).phase)
            assertEquals(current, repo.settings.first())
            val fresh = library.sources(includeBuiltins = false).single { it.id == source.id }
            // Reselecting the same set must not bypass the downloaded URL provenance check.
            assertEquals(RuleImportRepository.Phase.FAILED, io.commit(job, RuleImportRepository.Destination(source = fresh, replace = true)).phase)
            assertEquals(current, repo.settings.first())
            assertTrue(io.activeJobs().isEmpty())
        } finally { repo.update { before } }
    }

    @Test fun sharedContentsKeepBothActionsAndAbaEditsProtectUndo() = runBlocking {
        val repo = SettingsRepository(context); val before = repo.settings.first()
        val library = RuleLibraryRepository(context, repo)
        try {
            val content = repo.ruleContents.persistStrings((1..100).asSequence().map { "$it.example.org" })
            repo.update { ProxySettings(userRuleSets = listOf(UserRuleSet("a", "A", RuleAction.DIRECT, content), UserRuleSet("b", "B", RuleAction.REJECT, content))) }
            val sources = library.sources(includeBuiltins = false).filter { !it.isManual }
            val page = library.page(sources, "100.example.org")
            assertEquals(2, page.total); assertEquals(setOf(RuleAction.DIRECT, RuleAction.REJECT), page.items.map { it.action }.toSet())
            val change = library.setEnabled(sources.first(), false)
            repo.update { it.copy(userRuleSets = it.userRuleSets.map { s -> if (s.id == "a") s.copy(enabled = true) else s }) }
            repo.update { it.copy(userRuleSets = it.userRuleSets.map { s -> if (s.id == "a") s.copy(enabled = false) else s }) }
            assertFalse(repo.revertLibraryChange(change.changeId!!))
            assertFalse(repo.settings.first().userRuleSets.first().enabled)
        } finally { repo.update { before } }
    }

    @Test fun preservedLogFindsImportCrashesBeforePaginationAndKeepsTheirFullStacks() {
        val supplied = InstrumentationRegistry.getArguments().getString("ruleLogFixture")?.let(::File)
        val file = supplied ?: File.createTempFile("log-test", ".txt", context.cacheDir).apply {
            writeText("===== KEY EVENTS (independent rolling buffer) =====\n10-21 15:49:02.704 E/crash: Uncaught pid=10937 v=238\njava.lang.IllegalArgumentException: height=466421\n\tat stack.One\n10-21 15:49:34.937 E/crash: Uncaught pid=18705 v=238\njava.lang.IllegalArgumentException: height=466368\n\tat stack.Two\n===== FULL LOG =====\n" +
                (1..1000).joinToString("\n") { "10-21 15:56:00.000 I/request: id=$it" })
        }
        try {
            assertTrue(file.isFile)
            LogArchive.fromExport(file, Instant.parse("2026-10-21T16:00:00Z").toEpochMilli(), TimeZone.getTimeZone("Asia/Shanghai")).use { archive ->
                val found = archive.query("ERROR", "v=238", crashesOnly = true)
                assertEquals(2, found.total)
                val texts = found.entries.map { archive.text(it).text }
                assertTrue(texts.any { it.contains("pid=10937") && it.contains("466421") })
                assertTrue(texts.any { it.contains("pid=18705") && it.contains("466368") })
                assertTrue(texts.all { it.contains("IllegalArgumentException") && it.contains("\tat ") })
                assertTrue(archive.query().entries.none { it.level == "E" })
                assertTrue(archive.query("ERROR").entries.all { it.level == "E" })
            }
        } finally { if (supplied == null) file.delete() }
    }
}
