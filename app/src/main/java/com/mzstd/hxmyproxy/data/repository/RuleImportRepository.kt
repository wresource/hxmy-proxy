package com.mzstd.hxmyproxy.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mzstd.hxmyproxy.core.log.Ev
import com.mzstd.hxmyproxy.core.log.LogCat
import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.rules.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class RuleImportRepository @Inject constructor(@param:ApplicationContext private val context: Context,
    private val library: RuleLibraryRepository) {
    enum class Phase { READING, READY, SAVING, DONE, FAILED, CANCELLED, INTERRUPTED }
    data class Job(val id: String, val name: String, val phase: Phase, val bytes: Long = 0, val lines: Int = 0,
        val valid: Int = 0, val duplicates: Int = 0, val invalid: Int = 0, val ignored: Int = 0,
        val collection: String? = null, val target: String? = null, val saved: Int = 0,
        val skippedExisting: Int = 0, val message: String? = null, val updatedAt: Long = System.currentTimeMillis(),
        val destinationName: String? = null, val destinationAction: RuleAction? = null, val destinationEnabled: Boolean? = null,
        val destinationNew: Boolean = false, val destinationReplace: Boolean = false, val undoId: String? = null,
        val originUrl: String? = null, val originSourceId: String? = null)
    data class Destination(val source: RuleLibraryRepository.Source? = null, val name: String = "",
        val action: RuleAction = RuleAction.DIRECT, val enabled: Boolean = true, val replace: Boolean = false,
        val skipInvalid: Boolean = false, val subscriptionUrl: String? = null, val preserveTargetState: Boolean = true)
    private val settings get() = library.settings
    val store get() = settings.ruleContents
    private val running = ConcurrentHashMap.newKeySet<String>()
    fun activeJobs(): Set<String> = running.toSet()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val closers = ConcurrentHashMap<String, CopyOnWriteArrayList<() -> Unit>>()
    private data class Opened(val input: InputStream, val name: String)
    fun onCancel(id: String, close: () -> Unit) {
        closers.getOrPut(id) { CopyOnWriteArrayList() }.add(close)
        if (id in cancelled) runCatching(close)
    }
    fun cancel(id: String) { if (id !in running) return; cancelled += id; closers[id]?.forEach { runCatching(it) } }

    suspend fun read(uri: Uri, progress: (Job) -> Unit): Job = withContext(Dispatchers.IO) {
        readOpened("rules.txt", { id ->
            val signal = android.os.CancellationSignal(); onCancel(id, signal::cancel)
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, signal)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }?.take(200) ?: "rules.txt"
            val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r", signal) ?: throw IOException("Unable to open selected file")
            onCancel(id) { descriptor.close() }
            try { Opened(descriptor.createInputStream(), name) } catch (error: Exception) { descriptor.close(); throw error }
        }, progress)
    }

    suspend fun read(name: String, open: () -> InputStream, progress: (Job) -> Unit = {}): Job = readCancellable(name, { open() }, progress)
    suspend fun readCancellable(name: String, open: (String) -> InputStream, progress: (Job) -> Unit = {},
        origin: RuleLibraryRepository.Source? = null): Job = readOpened(name, { Opened(open(it), name) }, progress, origin)

    private suspend fun readOpened(name: String, open: (String) -> Opened, progress: (Job) -> Unit,
        origin: RuleLibraryRepository.Source? = null): Job = withContext(Dispatchers.IO) {
        var job = Job(UUID.randomUUID().toString(), name.take(200), Phase.READING,
            target = origin?.id, originUrl = origin?.url, originSourceId = origin?.id)
        running += job.id
        val scope = coroutineContext
        try {
            save(job); progress(job)
            Ev.k(LogCat.RULE, "import.started", "job" to job.id, "file" to job.name)
            scope.ensureActive()
            store.writer(deduplicate = true).use { writer ->
                var bytes = 0L; var lines = 0; var invalid = 0; var ignored = 0
                val issues = ArrayList<RuleContentStore.Issue>(250)
                fun flushIssues() { store.addIssues(job.id, issues); issues.clear() }
                fun line(value: String, tooLong: Boolean) {
                    scope.ensureActive(); lines++
                    if (lines > MAX_LINES) throw IOException("File has more than $MAX_LINES lines")
                    val text = (if (lines == 1) value.removePrefix("\uFEFF") else value).trim()
                    if (!tooLong && (text.isEmpty() || text.startsWith('#'))) { ignored++; return }
                    val parsed = if (tooLong) null else RuleInput.parse(text)
                    if (parsed == null) {
                        invalid++
                        issues += RuleContentStore.Issue(lines, text.take(240), if (tooLong) "line_too_long" else if (IpRange.hasRangeSyntax(text)) "invalid_range" else "invalid_format")
                        if (issues.size == 250) flushIssues()
                    } else writer.add(RuleContentStore.Row(parsed.value))
                    if (lines % 250 == 0) {
                        job = job.copy(bytes = bytes, lines = lines, invalid = invalid, ignored = ignored)
                        save(job); progress(job)
                    }
                }
                scope.ensureActive()
                val opened = open(job.id)
                opened.input.use { raw ->
                    onCancel(job.id, raw::close)
                    scope.ensureActive()
                    job = job.copy(name = opened.name.take(200)); save(job); progress(job)
                    Ev.k(LogCat.RULE, "import.fileOpened", "job" to job.id, "file" to job.name)
                    val counted = object : FilterInputStream(raw) {
                        private fun count(n: Int): Int { if (n > 0) { bytes += n; if (bytes > MAX_BYTES) throw IOException("File exceeds 32 MB") }; scope.ensureActive(); return n }
                        override fun read(): Int { val b = `in`.read(); if (b >= 0) count(1); return b }
                        override fun read(b: ByteArray, off: Int, len: Int): Int = count(`in`.read(b, off, len))
                    }
                    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    InputStreamReader(counted, decoder).use { reader ->
                        val buffer = CharArray(4096); val pending = StringBuilder(); var tooLong = false; var afterCr = false
                        while (true) {
                            scope.ensureActive(); val count = reader.read(buffer); if (count < 0) break
                            for (i in 0 until count) {
                                val c = buffer[i]
                                if (c == '\n' && afterCr) { afterCr = false; continue }
                                afterCr = c == '\r'
                                if (c == '\n' || c == '\r') { line(pending.toString(), tooLong); pending.clear(); tooLong = false }
                                else if (pending.length < 8192) pending.append(c) else tooLong = true
                            }
                        }
                        if (pending.isNotEmpty() || tooLong) line(pending.toString(), tooLong)
                    }
                }
                scope.ensureActive(); flushIssues()
                val ref = writer.finish()
                job = job.copy(phase = Phase.READY, bytes = bytes, lines = lines, valid = ref.size,
                    duplicates = writer.duplicates, invalid = invalid, ignored = ignored, collection = ref.id)
                save(job); progress(job)
                Ev.k(LogCat.RULE, "import.validated", "job" to job.id, "bytes" to bytes, "lines" to lines,
                    "valid" to ref.size, "duplicates" to writer.duplicates, "invalid" to invalid)
            }
        } catch (cancel: CancellationException) {
            job = job.copy(phase = Phase.CANCELLED); saveAfterFailure(job)
            Ev.k(LogCat.RULE, "import.cancelled", "job" to job.id)
            throw cancel
        } catch (error: Exception) {
            if (job.id in cancelled) {
                job = job.copy(phase = Phase.CANCELLED); saveAfterFailure(job)
                throw CancellationException("Import cancelled").apply { initCause(error) }
            }
            job = job.copy(phase = Phase.FAILED, message = error.message ?: error.javaClass.simpleName)
            Ev.e(LogCat.RULE, "import.failed", error, "job" to job.id, "file" to job.name, "phase" to "read", "committed" to false)
            saveAfterFailure(job); progress(job)
        } finally { running -= job.id; cancelled -= job.id; closers.remove(job.id) }
        job
    }

    suspend fun commit(input: Job, destination: Destination, progress: (Job) -> Unit = {}): Job = withContext(Dispatchers.IO) {
        settings.importReceipt(input.id)?.let { return@withContext fromReceipt(input, it).also(::save) }
        require(input.phase == Phase.READY || input.phase == Phase.FAILED)
        require(input.invalid == 0 || destination.skipInvalid) { "Review invalid lines before saving" }
        require(input.valid > 0)
        val stage = requireNotNull(input.collection?.let(store::ref)) { "Import content is unavailable; choose the file again" }
        val source = destination.source
        require(source != null || destination.name.isNotBlank() && destination.name.length <= 200)
        if (source == null) require(destination.action != RuleAction.PROXY) { "Proxy rules belong to manual overrides" }
        source?.let { require(it.editable) { "Copy a read-only rule set before importing" } }
        var job = input.copy(phase = Phase.SAVING, target = source?.id ?: "user:${input.id}", message = null,
            destinationName = destination.name, destinationAction = destination.action, destinationEnabled = destination.enabled,
            destinationNew = source == null, destinationReplace = destination.replace)
        check(running.add(job.id)) { "This import is already being saved" }
        val scope = coroutineContext
        try {
            save(job); progress(job)
            val before = settings.settings.first()
            if (input.originSourceId != null) {
                val origin = before.userRuleSets.firstOrNull { "user:${it.id}" == input.originSourceId }
                check(origin != null && origin.subscriptionUrl == input.originUrl) { "Subscription address changed. Download the new source before saving." }
            }
            if (source != null) {
                val latest = requireNotNull(library.sources(before, includeBuiltins = source.kind == RuleLibraryRepository.Kind.BUILTIN).firstOrNull { it.id == source.id }) { "Target rule set no longer exists" }
                check(latest.ref == source.ref && latest.action == source.action && latest.enabled == source.enabled && latest.url == source.url) { "Target rules changed. Review and retry." }
            }
            val kind = source?.ref?.kind ?: RuleContentStore.STRINGS
            var additions = 0; var skipped = 0
            val now = System.currentTimeMillis()
            val combined = store.writer(kind, deduplicate = true).use { writer ->
                if (source != null && !destination.replace) library.sourceRows(before, source).forEach { scope.ensureActive(); writer.addExisting(it) }
                writer.flush()
                val previousDuplicates = writer.duplicates
                for (row in store.rows(stage)) {
                    scope.ensureActive()
                    // Keep existing exact-target intent in other manual lists; broad overlaps are surfaced separately.
                    val parsed = requireNotNull(RuleInput.parse(row.value))
                    if (source?.isManual == true && conflictsWithOtherManual(before, source.kind, parsed.host)) { skipped++; continue }
                    val rowEnabled = when (kind) {
                        RuleContentStore.STRINGS -> true // Named-set switches belong to metadata, not individual rows.
                        RuleContentStore.OVERRIDES -> source?.kind != RuleLibraryRepository.Kind.PAUSED_OVERRIDES
                        else -> if (source != null && destination.preserveTargetState) true else destination.enabled
                    }
                    writer.add(row.copy(addedAt = now, enabled = rowEnabled,
                        action = if (kind == RuleContentStore.OVERRIDES) destination.action else null))
                    additions++
                }
                val ref = writer.finish()
                skipped += writer.duplicates - previousDuplicates
                additions -= writer.duplicates - previousDuplicates
                ref
            }
            scope.ensureActive()
            val mutation = settings.changeLibrary(job.target!!, "import", job.name, expected = before, operationId = job.id,
                receipt = JSONObject().put("saved", additions).put("skipped", skipped)) { current ->
                if (source != null) library.replaceContent(current, source, combined)
                else current.copy(userRuleSets = current.userRuleSets + UserRuleSet(input.id, destination.name.trim(), destination.action,
                    store.strings(combined), destination.enabled, destination.subscriptionUrl,
                    updatedAt = if (destination.subscriptionUrl != null) now else 0))
            }
            job = job.copy(phase = Phase.DONE, saved = additions, skippedExisting = skipped, undoId = mutation.changeId)
            save(job); progress(job)
            Ev.k(LogCat.RULE, "import.committed", "job" to job.id, "target" to job.target, "saved" to additions, "skipped" to skipped, "replace" to destination.replace)
        } catch (cancel: CancellationException) {
            // Cancellation after the atomic settings commit must never be reported as a rollback.
            val committed = withContext(kotlinx.coroutines.NonCancellable) { settings.importReceipt(job.id) }
            job = if (committed != null) fromReceipt(job, committed) else job.copy(phase = Phase.READY)
            saveAfterFailure(job); throw cancel
        } catch (error: Exception) {
            val committed = settings.importReceipt(job.id)
            job = if (committed != null) fromReceipt(job, committed)
                else job.copy(phase = Phase.FAILED, message = error.message ?: error.javaClass.simpleName)
            Ev.e(LogCat.RULE, "import.failed", error, "job" to job.id, "phase" to "save", "committed" to (committed != null))
            saveAfterFailure(job); progress(job)
        } finally { running -= job.id; cancelled -= job.id; closers.remove(job.id) }
        job
    }

    private fun conflictsWithOtherManual(s: ProxySettings, target: RuleLibraryRepository.Kind, host: String): Boolean {
        fun has(entries: List<com.mzstd.hxmyproxy.core.model.RuleEntry>) =
            (entries as? RebuildableRuleList<com.mzstd.hxmyproxy.core.model.RuleEntry>)?.forHost(host)?.isNotEmpty()
                ?: entries.any { RuleInput.parse(it.value)?.host == host }
        fun overrides() = (s.hostOverrides as? RebuildableRuleMap)?.forHost(host)?.isNotEmpty()
            ?: s.hostOverrides.keys.any { RuleInput.parse(it)?.host == host }
        return target != RuleLibraryRepository.Kind.DIRECT && has(s.userDirectRules) ||
            target != RuleLibraryRepository.Kind.REJECT && has(s.userRejectRules) ||
            target != RuleLibraryRepository.Kind.OVERRIDES && target != RuleLibraryRepository.Kind.PAUSED_OVERRIDES && overrides()
    }

    suspend fun recover(): List<Job> = withContext(Dispatchers.IO) {
        store.jobs().mapNotNull { runCatching { decode(it) }.getOrNull() }.map { job ->
            if (job.id in running || job.phase !in setOf(Phase.READING, Phase.SAVING)) job
            else (settings.importReceipt(job.id)?.let { fromReceipt(job, it) }
                ?: job.copy(phase = if (job.collection != null) Phase.READY else Phase.INTERRUPTED)).also(::save)
        }
    }
    suspend fun issues(job: Job, offset: Int = 0) = withContext(Dispatchers.IO) { store.issues(job.id, offset) }
    suspend fun exportIssues(job: Job, output: java.io.OutputStream) = withContext(Dispatchers.IO) {
        output.bufferedWriter().use { writer ->
            writer.write("line\tinput\tproblem\n")
            var offset = 0
            while (true) {
                coroutineContext.ensureActive(); val page = store.issues(job.id, offset); if (page.isEmpty()) break
                page.forEach { writer.write("${it.line}\t${it.value.replace('\t', ' ')}\t${it.reason}\n") }; offset += page.size
            }
        }
    }
    private fun save(job: Job) { store.saveJob(job.id, JSONObject().put("id", job.id).put("name", job.name).put("phase", job.phase.name)
        .put("bytes", job.bytes).put("lines", job.lines).put("valid", job.valid).put("duplicates", job.duplicates)
        .put("invalid", job.invalid).put("ignored", job.ignored).put("collection", job.collection ?: JSONObject.NULL)
        .put("target", job.target ?: JSONObject.NULL).put("saved", job.saved).put("skipped", job.skippedExisting)
        .put("message", job.message ?: JSONObject.NULL).put("updatedAt", System.currentTimeMillis())
        .put("destinationName", job.destinationName ?: JSONObject.NULL).put("destinationAction", job.destinationAction?.name ?: JSONObject.NULL)
        .put("destinationEnabled", job.destinationEnabled ?: JSONObject.NULL).put("destinationNew", job.destinationNew)
        .put("destinationReplace", job.destinationReplace).put("undoId", job.undoId ?: JSONObject.NULL)
        .put("originUrl", job.originUrl ?: JSONObject.NULL).put("originSourceId", job.originSourceId ?: JSONObject.NULL).toString()) }
    private fun saveAfterFailure(job: Job) {
        try { save(job) } catch (error: Exception) { Ev.e(LogCat.RULE, "import.journalFailed", error, "job" to job.id, "phase" to job.phase.name) }
    }
    private fun decode(raw: String): Job { val o = JSONObject(raw); return Job(o.getString("id"), o.getString("name"), Phase.valueOf(o.getString("phase")),
        o.optLong("bytes"), o.optInt("lines"), o.optInt("valid"), o.optInt("duplicates"), o.optInt("invalid"), o.optInt("ignored"),
        if (o.isNull("collection")) null else o.getString("collection"), if (o.isNull("target")) null else o.getString("target"),
        o.optInt("saved"), o.optInt("skipped"), if (o.isNull("message")) null else o.getString("message"), o.optLong("updatedAt"),
        if (o.isNull("destinationName")) null else o.getString("destinationName"), if (o.isNull("destinationAction")) null else RuleAction.valueOf(o.getString("destinationAction")),
        if (o.isNull("destinationEnabled")) null else o.getBoolean("destinationEnabled"), o.optBoolean("destinationNew"), o.optBoolean("destinationReplace"),
        if (o.isNull("undoId")) null else o.getString("undoId"),
        if (o.isNull("originUrl")) null else o.getString("originUrl"), if (o.isNull("originSourceId")) null else o.getString("originSourceId")) }
    private fun fromReceipt(job: Job, receipt: JSONObject): Job = job.copy(phase = Phase.DONE, target = receipt.getString("target"),
        saved = receipt.optJSONObject("result")?.optInt("saved") ?: job.saved,
        skippedExisting = receipt.optJSONObject("result")?.optInt("skipped") ?: job.skippedExisting,
        undoId = if (receipt.isNull("changeId")) null else receipt.getString("changeId"))
    companion object { const val MAX_BYTES = 32L * 1024 * 1024; const val MAX_LINES = 1_000_000 }
}
