package com.mzstd.hxmyproxy.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 偏大、偏圆的形状（Material 3 Expressive 方向），呼应薄荷/珊瑚的柔和品牌感。
 * 卡片 ~20–28dp 圆角、按钮可做成胶囊。
 */
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)
