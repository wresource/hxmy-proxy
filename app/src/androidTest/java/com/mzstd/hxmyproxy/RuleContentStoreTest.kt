package com.mzstd.hxmyproxy

import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.model.RuleEntry
import com.mzstd.hxmyproxy.core.rules.*
import com.mzstd.hxmyproxy.data.repository.RuleContentStore
import com.mzstd.hxmyproxy.data.repository.SettingsRepository
import com.mzstd.hxmyproxy.data.repository.RuleRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RuleContentStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = RuleContentStore.get(context)

    @Test fun actualAndroidRangeMatchingKeepsBothBoundaries() {
        val matcher = RuleMatcher().apply { add(RuleInput.normalize("1.2.3.4 1.2.3.255")!!); add(RuleInput.normalize("2001:db8::1-2001:db8::ff")!!) }
        listOf("1.2.3.4", "1.2.3.255", "2001:db8::1", "2001:db8::ff").forEach { assertTrue(it, matcher.matches(it)) }
        listOf("1.2.3.3", "1.2.4.0", "2001:db8::", "2001:db8::100").forEach { assertFalse(it, matcher.matches(it)) }
    }

    @Test fun indexedPagesReachTheEndWithoutMaterializingTheCollection() {
        val values = store.persistStrings((1..100_000).asSequence().map { "item-$it.example.org" })
        val ref = RuleContentStore.reference(values)!!
        assertEquals(100_000, values.size)
        val first = store.query(ref); val last = store.query(ref, offset = 99_950)
        assertEquals(50, first.rows.size); assertEquals(50, last.rows.size)
        assertEquals("item-100000.example.org", last.rows.last().value)
        val found = store.query(ref, "item-99999.")
        assertEquals(1, found.total); assertEquals("item-99999.example.org", found.rows.single().value)
    }

    @Test fun incompleteWriterIsInvisibleAndCloseRemovesItsStagingRows() {
        val w = store.writer(deduplicate = true)
        repeat(300) { w.add(RuleContentStore.Row("1.2.3.4")) }
        w.flush(); assertNull(store.ref(w.id)); assertEquals(299, w.duplicates)
        w.close(); assertNull(store.ref(w.id))
        store.writer(deduplicate = true).use { complete ->
            repeat(300) { complete.add(RuleContentStore.Row("1.2.3.4")) }
            val ref = complete.finish(); assertEquals(1, ref.size); assertEquals(299, complete.duplicates)
            assertEquals(listOf("1.2.3.4"), store.strings(ref))
        }
    }

    @Test fun settingsAndPortableBackupKeepRulesAfterReopeningRepository() = runBlocking {
        val repo = SettingsRepository(context); val before = repo.settings.first()
        try {
            val rules = (1..100).map { RuleEntry("$it.example.org", enabled = it % 2 == 0, addedAt = it.toLong()) }
            repo.update { ProxySettings(userDirectRules = rules, userRuleSets = listOf(UserRuleSet("store-test", "test", RuleAction.DIRECT, (1..100).map { "172.16.$it.0/24" }))) }
            val actual = SettingsRepository(context).settings.first()
            assertTrue(actual.userDirectRules is RuleContentStore.StoredList<*>)
            assertEquals(rules, actual.userDirectRules); assertEquals(rules.hashCode(), actual.userDirectRules.hashCode())
            val backup = repo.exportJson(); assertFalse(backup.contains("\\\"\$rules\\\""))
            repo.update { ProxySettings() }; repo.importJson(backup)
            assertEquals(rules, repo.settings.first().userDirectRules)
            assertEquals(100, repo.settings.first().userRuleSets.single().domains.size)
        } finally { repo.update { before } }
    }

    @Test fun missingContentCannotPublishAnEmptyReplacementEngineSnapshot() = runBlocking {
        val repo = SettingsRepository(context); val before = repo.settings.first()
        val engine = RuleEngine(); val runtime = RuleRepository(context, engine)
        try {
            val good = ProxySettings(userRejectRules = listOf(RuleEntry("kept.example.org")))
            assertTrue(runtime.rebuild(good)); assertEquals(RuleAction.REJECT, engine.decide("kept.example.org"))
            val missing = RuleContentStore.Ref(java.util.UUID.randomUUID().toString(), RuleContentStore.ENTRIES, 100, 42)
            val damaged = good.copy(userRejectRules = store.entries(missing))
            assertEquals(100, damaged.userRejectRules.size)
            assertFalse(runtime.rebuild(damaged))
            assertEquals(RuleAction.REJECT, engine.decide("kept.example.org"))
            assertTrue(runCatching { repo.update { damaged } }.isFailure)
            assertEquals(before, repo.settings.first())
        } finally { repo.update { before } }
    }
}
