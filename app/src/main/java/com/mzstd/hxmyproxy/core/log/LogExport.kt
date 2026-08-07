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
 * 刻意**只导出精简的错误日志**——不再附整段 logcat（框架噪声太多、不便分析）。
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
        val persistent = FileLog.snapshot()
        // 整份导出**一律英文**，不随系统语言。它是排障产物，读它的是开发者与协作方
        // （上游 shim、issue 里的陌生人），不是本机用户；中英混排还会让 grep 出来的
        // 同一类事件分成两种写法。UI 上的文案该本地化，落盘的日志不该。
        return buildString {
            append("=== hxmy proxy error log ===\n")
            append("time: ").append(ts).append('\n')
            append("package: ").append(context.packageName).append('\n')
            append("version: ").append(version).append("\n\n")
            append(if (persistent.isBlank()) "(no errors recorded)\n" else persistent)
        }
    }
}
