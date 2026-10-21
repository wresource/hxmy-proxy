package com.mzstd.hxmyproxy.ui.rules

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.data.repository.RuleContentStore
import com.mzstd.hxmyproxy.data.repository.RuleImportRepository
import com.mzstd.hxmyproxy.data.repository.RuleLibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

@HiltViewModel
class RuleLibraryViewModel @Inject constructor(val library: RuleLibraryRepository,
    val imports: RuleImportRepository) : ViewModel() {
    enum class Step { SELECT, READING, REVIEW, TARGET, SAVING, RESULT, FAILED }
    enum class MessageKind { SAVED, EXPORTED, REVERTED, PROTECTED, ERROR }
    data class Notice(val kind: MessageKind, val detail: String? = null, val undoId: String? = null, val token: Long = System.nanoTime())
    data class ImportState(val step: Step = Step.SELECT, val job: RuleImportRepository.Job? = null,
        val requestedTarget: String? = null, val action: RuleAction = RuleAction.DIRECT,
        val skipInvalid: Boolean = false, val relations: List<RuleLibraryRepository.Related> = emptyList(),
        val checking: Boolean = false, val checkedLines: Int = 0, val checkError: String? = null,
        val hasDomainAndIpRules: Boolean = false)
    private val _sources = MutableStateFlow<List<RuleLibraryRepository.Source>>(emptyList())
    val sources = _sources.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _notice = MutableStateFlow<Notice?>(null)
    val notice = _notice.asStateFlow()
    private val _queryRevision = MutableStateFlow(0)
    val queryRevision = _queryRevision.asStateFlow()
    fun retryQuery() { _queryRevision.update { it + 1 }; reload() }
    private val _importState = MutableStateFlow(ImportState())
    val importState = _importState.asStateFlow()
    private val _recentImports = MutableStateFlow<List<RuleImportRepository.Job>>(emptyList())
    val recentImports = _recentImports.asStateFlow()
    val history = library.settings.libraryChanges.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private var collection: Job? = null
    private var operation: Job? = null
    private var inspection: Job? = null
    private val importEpoch = java.util.concurrent.atomic.AtomicLong()
    private val inspectEpoch = java.util.concurrent.atomic.AtomicLong()
    var settingsSnapshot = ProxySettings()
        private set
    val pendingAddSource = MutableStateFlow<String?>(null)

    fun activate() {
        if (collection?.isActive == true) return
        collection = viewModelScope.launch {
            runCatching { imports.recover() }.onSuccess { _recentImports.value = it }
            runCatching { library.settings.cleanRuleStorage(imports.activeJobs()) }
            library.settings.settings.catch { error -> _loading.value = false; _error.value = error.message ?: error.javaClass.simpleName }.collectLatest { settings ->
                settingsSnapshot = settings; _loading.value = true; _error.value = null
                try {
                    if (_sources.value.isEmpty()) _sources.value = library.sources(settings, includeBuiltins = false)
                    _sources.value = library.sources(settings)
                    if (_importState.value.step in listOf(Step.REVIEW, Step.TARGET)) inspect()
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { _error.value = error.message ?: error.javaClass.simpleName }
                finally { _loading.value = false }
            }
        }
    }
    fun reload() { collection?.cancel(); collection = null; activate() }
    fun dismissNotice(token: Long) { if (_notice.value?.token == token) _notice.value = null }

    fun command(onDone: () -> Unit = {}, success: MessageKind = MessageKind.SAVED, block: suspend () -> Any?) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { block() }
                _notice.value = Notice(success, undoId = (result as? com.mzstd.hxmyproxy.data.repository.SettingsRepository.LibraryMutation)?.changeId)
                onDone()
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { _notice.value = Notice(MessageKind.ERROR, error.message) }
            finally { _busy.value = false }
        }
    }
    fun undo(id: String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try { _notice.value = Notice(if (library.settings.revertLibraryChange(id)) MessageKind.REVERTED else MessageKind.PROTECTED) }
            catch (error: Exception) { _notice.value = Notice(MessageKind.ERROR, error.message) }
            finally { _busy.value = false }
        }
    }
    fun beginImport(target: String? = null) {
        if (operation?.isActive == true) return
        importEpoch.incrementAndGet(); inspectEpoch.incrementAndGet()
        inspection?.cancel()
        val action = _sources.value.firstOrNull { it.id == target }?.action ?: RuleAction.DIRECT
        _importState.value = ImportState(requestedTarget = target, action = action)
    }
    fun choose(uri: Uri) = startRead { progress -> imports.read(uri, progress) }
    fun paste(text: String) = startRead { progress -> imports.read("pasted-rules.txt", { text.byteInputStream(Charsets.UTF_8) }, progress) }
    private fun startRead(read: suspend ((RuleImportRepository.Job) -> Unit) -> RuleImportRepository.Job) {
        if (operation?.isActive == true) return
        val epoch = importEpoch.incrementAndGet(); inspectEpoch.incrementAndGet()
        inspection?.cancel()
        _importState.update { it.copy(step = Step.READING, job = null, relations = emptyList(), checkError = null) }
        operation = viewModelScope.launch {
            try {
                val job = read { current -> if (importEpoch.get() == epoch) _importState.update { it.copy(job = current) } }
                if (importEpoch.get() != epoch) return@launch
                _importState.update { it.copy(job = job, step = if (job.phase == RuleImportRepository.Phase.READY) Step.REVIEW else Step.FAILED) }
                if (job.phase == RuleImportRepository.Phase.READY) inspect()
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (importEpoch.get() == epoch) _importState.update { it.copy(step = Step.FAILED, checkError = error.message) }
            } finally { runCatching { imports.recover() }.onSuccess { _recentImports.value = it } }
        }
    }
    fun cancelImport() {
        val epoch = importEpoch.incrementAndGet(); inspectEpoch.incrementAndGet()
        val current = operation
        val cancelledId = _importState.value.job?.id
        cancelledId?.let(imports::cancel)
        current?.cancel(); inspection?.cancel()
        viewModelScope.launch {
            current?.join()
            val jobs = imports.recover(); _recentImports.value = jobs
            val latest = jobs.firstOrNull { it.id == cancelledId }
            if (latest?.phase == RuleImportRepository.Phase.DONE) _notice.value = Notice(MessageKind.SAVED, latest.name, latest.undoId)
            if (importEpoch.get() != epoch) return@launch
            _importState.update { it.copy(job = latest, checking = false, step = when (latest?.phase) {
                RuleImportRepository.Phase.DONE -> Step.RESULT
                RuleImportRepository.Phase.READY -> Step.REVIEW
                else -> Step.SELECT
            }) }
        }
    }
    fun resume(job: RuleImportRepository.Job) {
        if (operation?.isActive == true) return
        importEpoch.incrementAndGet(); inspectEpoch.incrementAndGet(); inspection?.cancel()
        _importState.value = ImportState(job = job, requestedTarget = job.target.takeUnless { job.destinationNew }, action = job.destinationAction ?: RuleAction.DIRECT, step = when (job.phase) {
            RuleImportRepository.Phase.DONE -> Step.RESULT
            RuleImportRepository.Phase.READY -> Step.REVIEW
            RuleImportRepository.Phase.FAILED -> if (job.collection != null) Step.REVIEW else Step.FAILED
            else -> Step.SELECT
        })
        if (_importState.value.step == Step.REVIEW) inspect()
    }
    fun skipInvalid(value: Boolean) { _importState.update { it.copy(skipInvalid = value) } }
    fun target() { _importState.update { it.copy(step = Step.TARGET) } }
    fun review() { _importState.update { it.copy(step = Step.REVIEW) } }
    fun inspect(action: RuleAction = _importState.value.action) {
        val epoch = inspectEpoch.incrementAndGet()
        inspection?.cancel()
        val job = _importState.value.job ?: return
        val id = job.collection ?: return
        _importState.update { it.copy(action = action, checking = true, checkedLines = 0, checkError = null, relations = emptyList()) }
        inspection = viewModelScope.launch {
            try {
                val all = library.sources(activeBuiltinsOnly = true)
                val ref = withContext(Dispatchers.IO) { requireNotNull(imports.store.ref(id)) }
                val mixed = withContext(Dispatchers.IO) { (imports.store.counts(ref)["domain"] ?: 0) > 0 && all.any { it.enabled && it.ipCount > 0 } }
                val related = library.relatedImport(ref, action, all) { n -> if (inspectEpoch.get() == epoch) _importState.update { it.copy(checkedLines = n) } }
                if (inspectEpoch.get() == epoch) _importState.update { it.copy(relations = related, checking = false, hasDomainAndIpRules = mixed) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (inspectEpoch.get() == epoch) _importState.update { it.copy(checking = false, checkError = error.message) } }
        }
    }
    fun save(destination: RuleImportRepository.Destination) {
        if (operation?.isActive == true) return
        val checked = _importState.value
        if (checked.checking || checked.checkError != null || checked.action != destination.action) return
        val job = _importState.value.job ?: return
        val epoch = importEpoch.incrementAndGet()
        _importState.update { it.copy(step = Step.SAVING) }
        operation = viewModelScope.launch {
            try {
                val result = imports.commit(job, destination.copy(skipInvalid = _importState.value.skipInvalid)) { current -> if (importEpoch.get() == epoch) _importState.update { it.copy(job = current) } }
                if (importEpoch.get() == epoch) _importState.update { it.copy(job = result, step = if (result.phase == RuleImportRepository.Phase.DONE) Step.RESULT else Step.FAILED) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (importEpoch.get() == epoch) _importState.update { it.copy(step = Step.FAILED, checkError = error.message) } }
            finally { runCatching { imports.recover() }.onSuccess { _recentImports.value = it } }
        }
    }
    fun disableRelated(related: RuleLibraryRepository.Related) = command(onDone = { inspect() }) { library.disableRelated(related) }
    fun enableRelated(related: RuleLibraryRepository.Related) = command(onDone = { inspect() }) {
        library.enableRelated(related)
    }

    fun updateSubscription(source: RuleLibraryRepository.Source) {
        if (operation?.isActive == true) return
        val url = source.url ?: return
        beginImport(source.id)
        startRead { progress ->
            val job = imports.readCancellable(source.name + ".txt", { id ->
                library.validateUrl(url)
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000; connection.readTimeout = 15_000
                connection.setRequestProperty("Accept", "text/plain, application/octet-stream")
                imports.onCancel(id, connection::disconnect)
                val code = connection.responseCode
                if (code !in 200..299) { connection.disconnect(); throw IOException("Subscription HTTP $code") }
                if (connection.contentLengthLong > RuleImportRepository.MAX_BYTES) { connection.disconnect(); throw IOException("Subscription exceeds 32 MB") }
                object : java.io.FilterInputStream(connection.inputStream) {
                    override fun close() { try { super.close() } finally { connection.disconnect() } }
                }
            }, progress, origin = source)
            if (job.phase == RuleImportRepository.Phase.FAILED) library.settings.update { s -> s.copy(userRuleSets = s.userRuleSets.map {
                if ("user:${it.id}" == source.id && it.subscriptionUrl == url) it.copy(updateError = job.message ?: "Update failed") else it
            }) }
            job // Updates go through the same visible preview/replace confirmation as local files.
        }
    }
    fun export(source: RuleLibraryRepository.Source, uri: Uri, resolver: ContentResolver) = command(success = MessageKind.EXPORTED) {
        val output = resolver.openOutputStream(uri) ?: throw IOException("Unable to create export file")
        library.export(source, output)
    }
    fun exportIssues(uri: Uri, resolver: ContentResolver) = command(success = MessageKind.EXPORTED) {
        val job = requireNotNull(_importState.value.job)
        val output = resolver.openOutputStream(uri) ?: throw IOException("Unable to create report")
        imports.exportIssues(job, output)
    }
    override fun onCleared() {
        if (operation?.isActive == true) _importState.value.job?.id?.let(imports::cancel)
    }
}
