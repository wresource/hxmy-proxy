package com.mzstd.hxmyproxy.core.log

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量持久化日志：把带时间戳的行写入滚动文件（主文件超过上限时滚到 `app.log.1`，保留 1 个备份）。
 *
 * 目标：记录**崩溃 + 错误**，跨重启留存，供用户一键导出给开发分析。与 Android 解耦（操作
 * `java.io.File`）便于单测；由 [com.mzstd.hxmyproxy.HxmyProxyApp] 用 `filesDir` 初始化。
 * 任何写日志失败都被吞掉——日志绝不能影响主流程。线程安全。
 */
object FileLog {
    private const val DEFAULT_MAX_BYTES = 512 * 1024L
    private const val MAIN = "app.log"
    private const val BACKUP = "app.log.1"
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    @Volatile private var dir: File? = null
    @Volatile private var maxBytes: Long = DEFAULT_MAX_BYTES

    fun init(logDir: File, maxBytes: Long = DEFAULT_MAX_BYTES) {
        synchronized(lock) {
            runCatching { logDir.mkdirs() }
            dir = logDir
            this.maxBytes = maxBytes
        }
    }

    fun i(tag: String, msg: String) = append("I", tag, msg, null)
    fun w(tag: String, msg: String, e: Throwable? = null) = append("W", tag, msg, e)
    fun e(tag: String, msg: String, e: Throwable? = null) = append("E", tag, msg, e)

    fun append(level: String, tag: String, msg: String, e: Throwable?) {
        val base = dir ?: return
        synchronized(lock) {
            try {
                val main = File(base, MAIN)
                if (main.exists() && main.length() >= maxBytes) rotate(base, main)
                val sb = StringBuilder()
                    .append(fmt.format(Date())).append(' ').append(level).append('/').append(tag)
                    .append(": ").append(msg).append('\n')
                if (e != null) sb.append(stackTrace(e)).append('\n')
                main.appendText(sb.toString())
            } catch (_: Throwable) {
                // 日志失败不可影响主流程
            }
        }
    }

    /** 主文件 → 备份（覆盖旧备份）。 */
    private fun rotate(base: File, main: File) {
        val backup = File(base, BACKUP)
        runCatching { if (backup.exists()) backup.delete() }
        runCatching { main.renameTo(backup) }
    }

    /** 备份 + 主文件按时间顺序拼接（导出用）；无内容返回空串。 */
    fun snapshot(): String {
        val base = dir ?: return ""
        synchronized(lock) {
            val sb = StringBuilder()
            val backup = File(base, BACKUP)
            val main = File(base, MAIN)
            if (backup.exists()) runCatching { sb.append(backup.readText()) }
            if (main.exists()) runCatching { sb.append(main.readText()) }
            return sb.toString()
        }
    }

    fun clear() {
        val base = dir ?: return
        synchronized(lock) {
            runCatching { File(base, MAIN).delete() }
            runCatching { File(base, BACKUP).delete() }
        }
    }

    private fun stackTrace(e: Throwable): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        return sw.toString().trimEnd()
    }
}
