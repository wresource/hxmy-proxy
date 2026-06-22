package com.mzstd.hxmyproxy.core.log

import android.content.Context
import android.net.Uri
import android.os.Process
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把日志拼成单个可分享文本文件并返回其 `content://` Uri（经 FileProvider）。
 *
 * 内容 = 持久化错误/崩溃日志（[FileLog]，跨重启留存）+ 本进程最近 logcat 快照
 * （含运行时连接/CONNECT 等 info 级细节）。两者互补，便于分析。
 */
object LogExport {

    fun buildShareUri(context: Context): Uri {
        val dir = File(context.cacheDir, "exported").apply { mkdirs() }
        val out = File(dir, "hxmyproxy-log.txt")
        out.writeText(buildText(context))
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
    }

    private fun buildText(context: Context): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val version = runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pi.versionName} (${pi.longVersionCode})"
        }.getOrDefault("?")
        return buildString {
            append("=== hxmy proxy 日志导出 ===\n")
            append("time: ").append(ts).append('\n')
            append("package: ").append(context.packageName).append('\n')
            append("version: ").append(version).append("\n\n")
            append("--- 持久化日志（错误/崩溃，跨重启留存）---\n")
            val persistent = FileLog.snapshot()
            append(if (persistent.isBlank()) "(空)\n" else persistent).append('\n')
            append("--- 本进程最近 logcat ---\n")
            append(readOwnLogcat()).append('\n')
        }
    }

    /** 仅读本进程自己的 logcat（应用可读自身日志）。 */
    private fun readOwnLogcat(): String = try {
        val proc = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-v", "time", "--pid=${Process.myPid()}"),
        )
        proc.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Throwable) {
        "logcat 不可用: ${e.message}"
    }
}
