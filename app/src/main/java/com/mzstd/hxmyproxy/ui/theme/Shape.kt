package com.mzstd.hxmyproxy.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 全局统一圆角：卡片/分组容器/主按钮一律 16dp（用户选定的弧度），整个 app 一个弧度语言。
 * small 给小组件（chips/徽标裁剪），extraLarge 给对话框/底部弹层（M3 惯例保留层次）。
 */
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
