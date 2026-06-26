package com.mzstd.hxmyproxy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 语义状态色（好/中/差），统一一处、适配明暗，替代各处硬编码的绿/黄/红。
 * 暗色下用更亮的变体以保证可读。差=主题 error。
 */
object StatusColors {
    @Composable
    fun good(): Color = if (isSystemInDarkTheme()) Color(0xFF6FD89B) else Color(0xFF1E9E5A)

    @Composable
    fun warn(): Color = if (isSystemInDarkTheme()) Color(0xFFFFC861) else Color(0xFFE08C00)

    @Composable
    fun bad(): Color = MaterialTheme.colorScheme.error
}
