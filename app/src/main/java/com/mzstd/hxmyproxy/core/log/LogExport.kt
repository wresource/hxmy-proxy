package com.mzstd.hxmyproxy.core.log

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把**错误/崩溃日志**（[FileLog]，跨重启留存）拼成单个可分享文本文件并返回其 `content://` Uri。
 *
 * 保留应用关键事件和完整滚动日志，不附框架 logcat。
 */
object LogExport {

    fun buildShareUri(context: Context): Uri {
        // Retain recent shares long enough for receiving apps to open their granted URI.
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        File(context.cacheDir, "exported").listFiles()?.filter { it.isDirectory && it.lastModified() < cutoff }
            ?.forEach { it.deleteRecursively() }
        val dir = File(context.cacheDir, "exported/${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val out = File(dir, "hxmyproxy-log.txt")
        val snapshotDir = File(context.cacheDir, "log-export-${java.util.UUID.randomUUID()}")
        try {
            val snapshot = FileLog.snapshotTo(snapshotDir)
            out.outputStream().use { output ->
                output.write(header(context).toByteArray(Charsets.UTF_8))
                val parts = snapshot.parts.filter { it.file.length() > 0 }
                if (parts.isEmpty()) output.write("(no errors recorded)\n".toByteArray())
                else {
                    val key = parts.filter { it.key }; val main = parts.filterNot { it.key }
                    if (key.isNotEmpty()) output.write("===== KEY EVENTS (independent rolling buffer) =====\n".toByteArray())
                    key.forEach { it.file.inputStream().use { input -> input.copyTo(output, 64 * 1024) } }
                    if (key.isNotEmpty()) output.write("\n===== FULL LOG =====\n".toByteArray())
                    main.forEach { it.file.inputStream().use { input -> input.copyTo(output, 64 * 1024) } }
                }
            }
        } catch (error: Exception) { dir.deleteRecursively(); throw error }
        finally { snapshotDir.deleteRecursively() }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
    }

    private fun header(context: Context): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val version = runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pi.versionName} (${pi.longVersionCode})"
        }.getOrDefault("?")
        // 整份导出**一律英文**，不随系统语言。它是排障产物，读它的是开发者与协作方
        // （上游 shim、issue 里的陌生人），不是本机用户；中英混排还会让 grep 出来的
        // 同一类事件分成两种写法。UI 上的文案该本地化，落盘的日志不该。
        return buildString {
            append("=== hxmy proxy error log ===\n")
            append("time: ").append(ts).append('\n')
            append("package: ").append(context.packageName).append('\n')
            append("version: ").append(version).append("\n\n")
        }
    }
}
