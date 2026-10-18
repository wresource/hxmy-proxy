package com.mzstd.hxmyproxy.core.log

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量持久化日志：带时间戳的行写入滚动文件，跨重启留存，供用户一键导出给开发分析。
 *
 * **双环设计**（[key] 参数）：高频事件（DNS/连接失败风暴，实测可达 3000 条/天）会把崩溃栈、
 * 监听起落这类低频高价值证据冲出滚动窗口。故关键事件在写入主环的同时**镜像进独立的 key.log**，
 * 该环只有几百条、永远不被风暴淘汰。导出时 key.log 置于正文之前。
 *
 * **写入路径**：常驻 [BufferedWriter] + 按条数 flush，取代「每条 open/write/close + File.length()」
 * ——后者在 accept 热路径上会让所有线程在全局锁上排队。崩溃处理器须显式调用 [flush]。
 *
 * 与 Android 解耦（只操作 [java.io.File]）便于单测；写失败一律吞掉——日志绝不能影响主流程。线程安全。
 */
object FileLog {
    // 主文件 + 1 个备份，故磁盘占用上限约为本值的 2 倍 ≈ 10MB（用户拍板：保持 10MB 左右，滚动淘汰旧日志）。
    // 原 512KB 太小：实测行均 ~112B ⇒ 保底仅约 4600 行，一次失败风暴几小时即冲掉崩溃栈。
    private const val DEFAULT_MAX_BYTES = 5 * 1024 * 1024L
    /** 关键事件环独立滚动，不受主环的连接日志风暴影响。 */
    private const val KEY_MAX_BYTES = 256 * 1024L
    private const val MAIN = "app.log"
    private const val BACKUP = "app.log.1"
    private const val KEY_MAIN = "key.log"
    private const val KEY_BACKUP = "key.log.1"
    /** 每这么多条 flush 一次（崩溃/导出/停止时会强制 flush）。 */
    private const val FLUSH_EVERY = 20

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    @Volatile private var dir: File? = null
    @Volatile private var maxBytes: Long = DEFAULT_MAX_BYTES

    /** 日志总开关（用户可在设置里关闭；关闭后不再写盘，已有文件保留）。 */
    @Volatile var enabled: Boolean = true

    private class Sink(val main: File, val backup: File, val limit: Long, val flushEvery: Int = FLUSH_EVERY) {
        var writer: BufferedWriter? = null
        var size: Long = 0
        var pending: Int = 0

        fun write(line: String) {
            if (writer == null) {
                size = if (main.exists()) main.length() else 0
                writer = BufferedWriter(FileWriter(main, true))
            }
            if (size >= limit) {
                runCatching { writer?.flush(); writer?.close() }
                check(!backup.exists() || backup.delete()) { "Cannot remove log backup" }
                check(main.renameTo(backup)) { "Cannot rotate log" }
                writer = BufferedWriter(FileWriter(main, false))
                size = 0
            }
            writer?.write(line)
            size += line.length.toLong()
            if (++pending >= flushEvery) { writer?.flush(); pending = 0 }
        }

        fun flush() = runCatching { writer?.flush(); pending = 0 }
        fun close() = runCatching { writer?.flush(); writer?.close(); writer = null }
        fun text(): String = buildString {
            if (backup.exists()) runCatching { append(backup.readText()) }
            if (main.exists()) runCatching { append(main.readText()) }
        }
        fun delete() {
            close()
            runCatching { main.delete() }
            runCatching { backup.delete() }
            size = 0
        }
    }

    private var mainSink: Sink? = null
    private var keySink: Sink? = null

    fun init(logDir: File, maxBytes: Long = DEFAULT_MAX_BYTES) {
        synchronized(lock) {
            runCatching { logDir.mkdirs() }
            dir = logDir
            this.maxBytes = maxBytes
            mainSink?.close(); keySink?.close()
            mainSink = Sink(File(logDir, MAIN), File(logDir, BACKUP), maxBytes)
            keySink = Sink(File(logDir, KEY_MAIN), File(logDir, KEY_BACKUP), KEY_MAX_BYTES, flushEvery = 1)
        }
    }

    fun i(tag: String, msg: String) = append("I", tag, msg, null)
    fun w(tag: String, msg: String, e: Throwable? = null) = append("W", tag, msg, e)
    fun e(tag: String, msg: String, e: Throwable? = null) = append("E", tag, msg, e)

    /** 未捕获异常必须进关键环，并在系统终止进程前强制落盘。遵守诊断日志总开关。 */
    fun crash(threadName: String, error: Throwable, context: String = "") {
        append("E", "crash", "Uncaught in thread $threadName${if (context.isEmpty()) "" else " $context"}", error, key = true)
        flush()
    }

    /** [key]=true 时同时镜像进 key.log（关键事件，不被高频日志冲掉）。 */
    fun append(level: String, tag: String, msg: String, e: Throwable?, key: Boolean = false) {
        appendConfirmed(level, tag, msg, e, key)
    }

    /** key=true 时返回关键环已写出进程缓冲的结果；失败/关闭日志不允许推进持久化游标。 */
    fun appendConfirmed(level: String, tag: String, msg: String, e: Throwable?, key: Boolean): Boolean {
        if (!enabled) return false
        // **时间戳在进锁之前取。** 放在锁内取到的是「轮到我写的时刻」而不是「事件发生的时刻」——
        // 写盘竞争越激烈、偏差越大，而日志最有价值的时候恰恰是竞争最激烈的时候。
        // 0814 实测 66/432 次失败的墙钟比事件自带的 ms= 多出 >2s（最大 30.1s），
        // 这个锁内取时间是两个候选成因之一（另一个是设备 suspend，由 RequestTrace 的 rt= 区分）。
        val eventAt = System.currentTimeMillis()
        return synchronized(lock) {
            if (!enabled) return@synchronized false
            try {
                // SimpleDateFormat 不是线程安全的。先取事件时刻，进锁后才格式化，
                // 既不把日志排队时间算进时间戳，也不会由并发日志触发格式化异常。
                val ts = fmt.format(Date(eventAt))
                val sb = StringBuilder()
                    .append(ts).append(' ').append(level).append('/').append(tag)
                    .append(": ").append(msg).append('\n')
                if (e != null) sb.append(stackTrace(e)).append('\n')
                val line = sb.toString()
                // 两环独立：主环写失败不能阻止更重要的关键证据落盘。
                fun write(sink: Sink?): Boolean = if (sink == null) false else try {
                    sink.write(line)
                    true
                } catch (_: Throwable) {
                    sink.close()
                    sink.writer = null
                    false
                }
                val mainOk = write(mainSink)
                if (key) write(keySink) else mainOk
            } catch (_: Throwable) {
                // 日志失败不可影响主流程
                false
            }
        }
    }

    /** 强制落盘（崩溃处理器、导出、停止共享时调用）。 */
    fun flush() {
        synchronized(lock) {
            mainSink?.flush()
            keySink?.flush()
        }
    }

    /** 关键事件环 + 主环（各自 backup+main 顺序拼接），导出用；无内容返回空串。 */
    fun snapshot(): String {
        synchronized(lock) {
            mainSink?.flush(); keySink?.flush()
            val key = keySink?.text().orEmpty()
            val main = mainSink?.text().orEmpty()
            if (key.isBlank()) return main
            return buildString {
                append("===== KEY EVENTS (independent rolling buffer) =====\n")
                append(key)
                append("\n===== FULL LOG =====\n")
                append(main)
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            mainSink?.delete()
            keySink?.delete()
        }
    }

    private fun stackTrace(e: Throwable): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        return sw.toString().trimEnd()
    }
}
