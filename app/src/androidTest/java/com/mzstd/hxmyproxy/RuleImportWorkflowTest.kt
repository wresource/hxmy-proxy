package com.mzstd.hxmyproxy

import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.model.RuleEntry
import com.mzstd.hxmyproxy.core.proxy.RequestActivityRecord
import com.mzstd.hxmyproxy.core.proxy.RequestOutcome
import com.mzstd.hxmyproxy.core.rules.*
import com.mzstd.hxmyproxy.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class RuleImportWorkflowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings get() = SettingsRepository(context)
    private fun importer(repo: SettingsRepository) = RuleImportRepository(context, RuleLibraryRepository(context, repo))

    @Test fun largeFileCommitAndUndoAreAtomicAndDuplicateSubmissionIsIdempotent() = runBlocking {
        val repo = settings; val original = repo.settings.first(); val io = importer(repo)
        try {
            repo.update { ProxySettings() }
            val args = InstrumentationRegistry.getArguments()
            val path = args.getString("ruleInputFixture")
            val file = path?.let(::File)
            if (file != null) {
                assertTrue("Provided fixture must exist", file.isFile)
                args.getString("ruleInputSha256")?.let { expected ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input -> val b = ByteArray(65536); while (true) { val n = input.read(b); if (n < 0) break; digest.update(b, 0, n) } }
                    assertEquals(expected, digest.digest().joinToString("") { "%02x".format(it) })
                }
            }
            val synthetic = if (file == null) (0 until 8797).joinToString("\n") { "10.${it / 256}.${it % 256}.0/24" } else null
            val job = io.read(file?.name ?: "generated-8797.txt", { file?.inputStream() ?: synthetic!!.byteInputStream() })
            assertEquals(RuleImportRepository.Phase.READY, job.phase)
            assertEquals(8797, job.valid); assertEquals(0, job.invalid)
            assertTrue(repo.settings.first().userRuleSets.isEmpty())
            val destination = RuleImportRepository.Destination(name = "import-regression", action = RuleAction.REJECT)
            val saved = io.commit(job, destination)
            assertEquals(RuleImportRepository.Phase.DONE, saved.phase); assertEquals(8797, saved.saved)
            val first = repo.settings.first(); assertEquals(1, first.userRuleSets.size)
            assertTrue(first.userRuleSets.single().domains is RuleContentStore.StoredList<*>)
            val repeated = io.commit(job, destination)
            assertEquals(8797, repeated.saved); assertEquals(1, repo.settings.first().userRuleSets.size)
            val engine = RuleEngine(); assertTrue(RuleRepository(context, engine).rebuild(first))
            val target = IpRange.ofRule(first.userRuleSets.single().domains.first())!!.value.substringBefore('-')
            assertEquals(RuleAction.REJECT, engine.decide(target))
            assertTrue(repo.revertLibraryChange(saved.undoId!!)); assertTrue(repo.settings.first().userRuleSets.isEmpty())
        } finally { repo.update { original } }
    }

    @Test fun badInputReadFailureAndCancelNeverChangeExistingRules() = runBlocking {
        val repo = settings; val original = repo.settings.first(); val io = importer(repo)
        try {
            val baseline = ProxySettings(userDirectRules = listOf(RuleEntry("keep.example.org")))
            repo.update { baseline }
            val job = io.read("formats.txt", { "\uFEFF1.2.3.4 1.2.3.255\n1.2.3.4-1.2.3.255\n1.2.3.4/33\n".byteInputStream() })
            assertEquals(1, job.valid); assertEquals(1, job.duplicates); assertEquals(1, job.invalid)
            assertEquals(3, io.issues(job).single().line)
            assertTrue(runCatching { io.commit(job, RuleImportRepository.Destination(name = "must-not-save")) }.isFailure)
            assertEquals(baseline, repo.settings.first())
            val failed = io.read("denied.txt", { throw IOException("controlled read failure") })
            assertEquals(RuleImportRepository.Phase.FAILED, failed.phase); assertEquals(baseline, repo.settings.first())
            val started = CompletableDeferred<String>()
            val release = java.util.concurrent.CountDownLatch(1)
            val operation = launch { io.read("cancelled.txt", { (1..10000).joinToString("\n") { "item-$it.example.org" }.byteInputStream() }) { progress ->
                if (progress.lines >= 250 && !started.isCompleted) {
                    started.complete(progress.id)
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Cancellation barrier timed out" }
                }
            } }
            val id = withTimeout(10_000) { started.await() }
            try { io.cancel(id); operation.cancel() } finally { release.countDown() }
            operation.join()
            assertEquals(RuleImportRepository.Phase.CANCELLED, io.recover().single { it.id == id }.phase)
            assertEquals(baseline, repo.settings.first())
        } finally { repo.update { original } }
    }

    @Test fun appendingKeepsOldSwitchesAndTimestampsAndUndoProtectsLaterEdits() = runBlocking {
        val repo = settings; val original = repo.settings.first(); val library = RuleLibraryRepository(context, repo); val io = RuleImportRepository(context, library)
        try {
            val old = listOf(RuleEntry("1.2.3.0/24", false, 10, 20), RuleEntry("existing.example.org", true, 30))
            repo.update { ProxySettings(userDirectRules = old) }
            val source = library.sources(includeBuiltins = false).first { it.kind == RuleLibraryRepository.Kind.DIRECT }
            val job = io.read("append.txt", { "1.2.3.4/24\n2001:db8::/32\n".byteInputStream() })
            val saved = io.commit(job, RuleImportRepository.Destination(source = source, action = RuleAction.DIRECT))
            assertEquals(1, saved.saved); assertEquals(1, saved.skippedExisting)
            assertEquals(old, repo.settings.first().userDirectRules.take(2))
            repo.update { it.copy(userDirectRules = it.userDirectRules + RuleEntry("later.example.org")) }
            assertFalse(repo.revertLibraryChange(saved.undoId!!))
            assertTrue(repo.settings.first().userDirectRules.any { it.value == "later.example.org" })
        } finally { repo.update { original } }
    }

    @Test fun relatedRulesDistinguishCoverageConflictAndRecordedIpWithoutChangingDnsSemantics() = runBlocking {
        val repo = settings; val original = repo.settings.first(); val library = RuleLibraryRepository(context, repo)
        try {
            repo.update { ProxySettings(userDirectRules = listOf(RuleEntry("1.2.3.0/24")), userRejectRules = listOf(RuleEntry("example.org"))) }
            val sources = library.sources(includeBuiltins = false)
            assertTrue(library.related("1.2.3.42", RuleAction.DIRECT, sources).any { it.relation == RuleLibraryRepository.Relation.COVERED })
            assertTrue(library.related("sub.example.org", RuleAction.DIRECT, sources).any { it.relation == RuleLibraryRepository.Relation.CONFLICT })
            val event = RequestActivityRecord("e", 1, "HTTP", "site.other.org", 1000, 2000, "PROXY", "DEFAULT", RequestOutcome.CONNECTED, peer = "1.2.3.42")
            val related = library.related("site.other.org", RuleAction.PROXY, sources, listOf(event))
            val ip = related.single { it.relation == RuleLibraryRepository.Relation.RESOLVED_IP }
            assertEquals("1.2.3.42", ip.peer); assertEquals(2000L, ip.observedAt)
            val mutation = library.disableRelated(ip)
            assertFalse(repo.settings.first().userDirectRules.single().enabled)
            assertTrue(repo.revertLibraryChange(mutation.changeId!!))
            assertTrue(repo.settings.first().userDirectRules.single().enabled)
        } finally { repo.update { original } }
    }

    @Test fun proxyPauseAndRestorePreserveTheRuleAndItsAction() = runBlocking {
        val repo = settings; val original = repo.settings.first(); val library = RuleLibraryRepository(context, repo)
        try {
            repo.update { ProxySettings(hostOverrides = mapOf("=example.org" to RuleAction.PROXY)) }
            val source = library.sources(includeBuiltins = false).first { it.kind == RuleLibraryRepository.Kind.OVERRIDES }
            val item = library.page(listOf(source)).items.single()
            val mutation = library.setItemEnabled(item, false)
            assertTrue(repo.settings.first().hostOverrides.isEmpty())
            assertEquals(RuleAction.PROXY, repo.settings.first().disabledHostOverrides["=example.org"])
            assertTrue(repo.revertLibraryChange(mutation.changeId!!))
            assertEquals(RuleAction.PROXY, repo.settings.first().hostOverrides["=example.org"])
        } finally { repo.update { original } }
    }
}
