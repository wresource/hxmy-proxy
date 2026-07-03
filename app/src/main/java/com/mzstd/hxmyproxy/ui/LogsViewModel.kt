package com.mzstd.hxmyproxy.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mzstd.hxmyproxy.core.log.FileLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
class LogsViewModel @Inject constructor() : ViewModel() {

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            // 解析产出为文件顺序（旧→新）；取最近 500 条后整体反转 → 最近在前。
            _entries.value = parseLogEntries(FileLog.snapshot()).takeLast(500).asReversed()
        }
    }

    fun clear() {
        FileLog.clear()
        reload()
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
