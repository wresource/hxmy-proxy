package com.mzstd.hxmyproxy.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mzstd.hxmyproxy.core.log.FileLog
import com.mzstd.hxmyproxy.core.log.LogArchive
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * 一条日志「条目」：时间戳行 + 其后所有续行（堆栈/多行消息）合为一条。
 * 按条目呈现（而非按行）——否则含异常的日志会被逐行反转打乱、每行还成独立条目，
 * 无法按时间排序、无法判断是否重复。
 *
 * @param detail 续行（堆栈等），无则 null；[hasMore] 据此决定是否可展开。
 */
data class LogEntry(
    val timestamp: String,
    val level: String,   // I / W / E
    val tag: String,
    val message: String,
    val detail: String?,
) {
    /** 折叠时是否有更多内容（有堆栈续行即可展开）。 */
    val hasMore: Boolean get() = detail != null
    /** 展开/导出用的完整原文。 */
    val full: String get() = buildString {
        append(timestamp).append(' ').append(level).append('/').append(tag).append(": ").append(message)
        if (detail != null) append('\n').append(detail)
    }
}

/** 错误日志详情页状态：读 [FileLog] 解析成条目（IO 线程，最近在前），支持清空。 */
@HiltViewModel
class LogsViewModel @Inject constructor(@param:ApplicationContext private val context: Context) : ViewModel() {

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    data class PageState(val page: LogArchive.Page? = null, val number: Int = 0, val loading: Boolean = true, val error: String? = null)
    private val _page = MutableStateFlow(PageState())
    val page = _page.asStateFlow()
    private val mutex = Mutex()
    private var archive: LogArchive? = null
    private var work: Job? = null
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private var criteria = Triple("ALL", "", false)
    private var cursors = mutableListOf<LogArchive.Cursor?>(null)
    private var number = 0

    init {
        cleanOldCaches(context.cacheDir)
        viewModelScope.launch { clears.collect { reload() } }
    }
    fun filter(level: String, search: String, crashes: Boolean) {
        val next = Triple(level, search, crashes)
        if (criteria == next) return
        criteria = next; number = 0; cursors = mutableListOf(null); load(false)
    }
    fun reload() { number = 0; cursors = mutableListOf(null); load(true) }
    fun next() {
        if (_page.value.loading || _page.value.page?.hasMore != true) return
        val cursor = _page.value.page?.next ?: return
        if (cursors.size > number + 1) cursors = cursors.take(number + 1).toMutableList()
        cursors += cursor; number++; load(false)
    }
    fun previous() { if (number > 0 && !_page.value.loading) { number--; load(false) } }
    private fun load(refresh: Boolean) {
        val epoch = generation.incrementAndGet(); work?.cancel()
        val filter = criteria; val pageNumber = number; val cursor = cursors[pageNumber]
        _page.value = PageState(number = pageNumber)
        work = viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = mutex.withLock {
                    coroutineContext.ensureActive()
                    if (refresh) { archive?.close(); archive = null }
                    if (archive == null) archive = LogArchive.capture(File(context.cacheDir, "logs-view-${UUID.randomUUID()}")) { coroutineContext.ensureActive() }
                    requireNotNull(archive).query(filter.first, filter.second, filter.third, cursor) { coroutineContext.ensureActive() }
                }
                if (generation.get() == epoch) {
                    _entries.value = result.entries.map { LogEntry(it.timestamp, it.level, it.tag, it.message, null) }
                    _page.value = PageState(result, pageNumber, false)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (generation.get() == epoch) _page.value = PageState(number = pageNumber, loading = false, error = error.message ?: error.javaClass.simpleName) }
        }
    }
    suspend fun text(entry: LogArchive.Entry, offset: Int = 0): LogArchive.TextPage = withContext(Dispatchers.IO) {
        mutex.withLock { requireNotNull(archive).text(entry, offset) }
    }
    suspend fun copyText(entry: LogArchive.Entry): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val source = requireNotNull(archive)
            val result = StringBuilder(); var offset = 0
            while (true) {
                coroutineContext.ensureActive()
                val page = source.text(entry, offset, 32_768)
                check(result.length + page.text.length <= 200_000) { "This record is too large to copy. Export the log instead." }
                result.append(page.text); if (!page.hasMore) break; offset += page.text.length
            }
            result.toString()
        }
    }
    suspend fun exportEntry(entry: LogArchive.Entry, output: java.io.OutputStream) = withContext(Dispatchers.IO) {
        mutex.withLock { output.use { requireNotNull(archive).export(entry, it) } }
    }
    fun clear(onDone: (Boolean) -> Unit = {}) {
        work?.cancel(); generation.incrementAndGet(); _entries.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                mutex.withLock { check(FileLog.clearConfirmed()) { "Unable to clear log files" }; archive?.close(); archive = null }
                clears.update { it + 1 }
                withContext(Dispatchers.Main) { onDone(true) }
            } catch (error: Exception) { _page.value = PageState(loading = false, error = error.message); withContext(Dispatchers.Main) { onDone(false) } }
        }
    }
    override fun onCleared() {
        work?.cancel()
        cleanup.launch { mutex.withLock { archive?.close(); archive = null } }
    }
    companion object {
        private val clears = MutableStateFlow(0L)
        private val cleanup by lazy { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO) }
        private var cleaned = false
        @Synchronized private fun cleanOldCaches(directory: File) {
            if (!cleaned) { directory.listFiles()?.filter { it.name.startsWith("logs-view-") }?.forEach { it.deleteRecursively() }; cleaned = true }
        }
    }
}

// 条目起始行：`MM-dd HH:mm:ss.SSS L/TAG: msg`。不匹配的非空行视为上一条的续行（堆栈）。
private val LOG_HEAD = Regex("""^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) ([IWE])/([^:]+): (.*)$""")

/**
 * 把 [FileLog] 原文按「条目」解析（文件顺序，旧→新）：一条 = 时间戳行 + 其后所有续行（堆栈）。
 * internal 供单测直接验证多行分组正确（含异常的日志不再被逐行拆散/反转）。
 */
internal fun parseLogEntries(raw: String): List<LogEntry> {
    if (raw.isEmpty()) return emptyList()
    val out = ArrayList<LogEntry>()
    val detail = StringBuilder()
    var head: MatchResult? = null
    fun flush() {
        val h = head ?: return
        val (ts, level, tag, msg) = h.destructured
        out.add(LogEntry(ts, level, tag.trim(), msg, detail.toString().trimEnd().ifEmpty { null }))
        detail.clear()
    }
    for (line in raw.split('\n')) {
        val m = LOG_HEAD.matchEntire(line)
        if (m != null) {
            flush()
            head = m
        } else if (line.isNotEmpty()) {
            detail.append(line).append('\n')   // 续行（堆栈）归属当前条目
        }
    }
    flush()
    return out
}
