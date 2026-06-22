package com.mzstd.hxmyproxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.ui.theme.StatusColors

/**
 * 安卓 Wi-Fi 风格分格信号指示器：4 格递增高度，填充到 [level]（0..4）。
 * 按强弱整体着色：强=绿 / 中=黄 / 弱=红；未填充格为浅灰。
 *
 * 纯布局（Row + 背景色 Box），无自绘、无状态、无分配——仅当 [level] 变化才重组，
 * 频繁刷新也不会崩。
 */
@Composable
fun SignalBars(level: Int, modifier: Modifier = Modifier) {
    val lvl = level.coerceIn(0, 4)
    val filled = when {
        lvl >= 3 -> StatusColors.good()
        lvl == 2 -> StatusColors.warn()
        else -> StatusColors.bad()
    }
    val empty = MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (i in 1..4) {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .width(4.dp)
                    .height((4 + i * 3).dp) // 7 / 10 / 13 / 16 dp 递增
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i <= lvl) filled else empty),
            )
        }
    }
}
