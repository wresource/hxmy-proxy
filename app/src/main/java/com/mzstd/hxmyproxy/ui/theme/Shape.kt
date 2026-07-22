package com.mzstd.hxmyproxy.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 全局圆角体系（Bento 重设计定稿 24/16）：large=卡片 24dp、medium=卡内嵌块/输入框/横幅 16dp、
 * small 给小组件（chips/徽标裁剪），extraLarge 给对话框/底部弹层（M3 惯例保留层次）。
 */
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
