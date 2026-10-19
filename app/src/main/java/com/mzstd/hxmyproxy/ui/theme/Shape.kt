package com.mzstd.hxmyproxy.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 全局圆角体系（Bento 重设计定稿 24/16）：large=卡片 24dp、medium=卡内嵌块/输入框/横幅 16dp、
 * small=选项/行反馈12dp，extraSmall=小徽章8dp，extraLarge=对话框/底部弹层28dp。
 * 同类控件共用角色；圆形图标/胶囊保留全圆。点击反馈必须与背景使用同一裁剪形状。
 */
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
