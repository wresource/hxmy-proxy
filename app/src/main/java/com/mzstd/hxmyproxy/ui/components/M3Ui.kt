package com.mzstd.hxmyproxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R

/**
 * 工作台重构（2026-08）的 M3 对齐组件。数值以 Google material tokens DB 为准
 * （快照存 google-play/prototypes/m3ref/）：分段按钮高 40 / full 胶囊 / 1dp 边 / 竖分隔；
 * 列表**不用行间 divider**；提示文字收进 ⓘ 弹层。
 */

/**
 * M3 outlined segmented button（tab 内分段横切的唯一控件）。
 * 官方形态：整体 full 胶囊 + 1dp outlineVariant 外框 + 段间竖分隔线，
 * 选中段 primaryContainer 填充（品牌蓝语汇，替代默认 secondaryContainer——与底栏指示一致）。
 */
@Composable
fun SegTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(CircleShape)
            .border(1.dp, cs.outlineVariant, CircleShape)
            .background(cs.surfaceContainerLowest),
    ) {
        labels.forEachIndexed { i, label ->
            if (i > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(cs.outlineVariant),
                )
            }
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (on) cs.primaryContainer else cs.surfaceContainerLowest)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (on) cs.onPrimaryContainer else cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/**
 * ⓘ 提示按钮：圆圈感叹号，点开弹说明对话框。承接原先常驻的说明文字——
 * 熟练后不占空间、不占视线（用户拍板的成熟做法）。
 */
@Composable
fun InfoDot(title: String, body: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier.size(36.dp)) {
        Icon(
            painterResource(R.drawable.ic_b_info),
            contentDescription = title,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(17.dp),
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.done)) }
            },
        )
    }
}
