package com.mzstd.hxmyproxy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 语义状态色（好/中/差），统一一处、适配明暗，替代各处硬编码的绿/黄/红。
 * 色值与蓝粉主题同一 tone 规则生成（见 [Color.kt]），视觉重量一致不突兀。差=主题 error。
 */
object StatusColors {
    @Composable
    fun good(): Color = if (isSystemInDarkTheme()) SuccessDark else SuccessLight

    @Composable
    fun warn(): Color = if (isSystemInDarkTheme()) WarningDark else WarningLight

    @Composable
    fun bad(): Color = MaterialTheme.colorScheme.error

    /** 状态容器色（做徽标/格子的浅底）。 */
    @Composable
    fun goodContainer(): Color = if (isSystemInDarkTheme()) SuccessContainerDark else SuccessContainerLight

    @Composable
    fun warnContainer(): Color = if (isSystemInDarkTheme()) WarningContainerDark else WarningContainerLight
}
