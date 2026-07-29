package com.mzstd.hxmyproxy.ui

import java.util.Locale

/**
 * 字节量与速率的**唯一**格式化口径：全 app 一律 **1024 进制**（用户拍板 2026-07-29）。
 *
 * 别再引入 `android.text.format.Formatter.formatShortFileSize` —— 那是 SI 1000 进制，
 * 混用的后果是同一笔流量在监控页显示 `2.3 kB`、在首页 Total 显示 `2.2 KB`，用户没法判断哪个是真的。
 * 新增任何「把字节数显示给人看」的地方，都用这两个函数。
 */

/** 字节/秒 → 人类可读速率（B/s · KB/s · MB/s），用 Locale.US 避免本地化数字差异。 */
fun formatRate(bytesPerSec: Long): String = when {
    bytesPerSec >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024))
    bytesPerSec >= 1024L -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
    else -> "$bytesPerSec B/s"
}

/** 字节 → 人类可读体量（B · KB · MB · GB），用 Locale.US 避免本地化数字差异。 */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
