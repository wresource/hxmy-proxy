package com.mzstd.hxmyproxy.ui

import java.util.Locale

/** 字节/秒 → 人类可读速率（B/s · KB/s · MB/s），用 Locale.US 避免本地化数字差异。 */
fun formatRate(bytesPerSec: Long): String = when {
    bytesPerSec >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024))
    bytesPerSec >= 1024L -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
    else -> "$bytesPerSec B/s"
}

/** 信号等级 0..4 → 四格条（▰ 实 / ▱ 空）。 */
fun signalBars(level: Int): String {
    val n = level.coerceIn(0, 4)
    return "▰".repeat(n) + "▱".repeat(4 - n)
}

/** 字节 → 人类可读体量（B · KB · MB · GB），用 Locale.US 避免本地化数字差异。 */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
