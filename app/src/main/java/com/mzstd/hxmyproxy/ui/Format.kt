package com.mzstd.hxmyproxy.ui

import java.util.Locale

/** 字节/秒 → 人类可读速率（B/s · KB/s · MB/s），用 Locale.US 避免本地化数字差异。 */
fun formatRate(bytesPerSec: Long): String = when {
    bytesPerSec >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024))
    bytesPerSec >= 1024L -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
    else -> "$bytesPerSec B/s"
}
