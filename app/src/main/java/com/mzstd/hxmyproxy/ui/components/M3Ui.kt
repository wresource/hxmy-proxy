package com.mzstd.hxmyproxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R

/**
 * 工作台重构（2026-08）的 M3 对齐组件。数值以 Google material tokens DB 为准
 * （快照存 google-play/prototypes/m3ref/）：分段按钮高 40 / full 胶囊 / 1dp 边 / 竖分隔；
 * 列表**不用行间 divider**；提示文字收进 ⓘ 弹层。
 */

/** Native primary tabs with explicit selection semantics and a 48dp touch target. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SegTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (labels.isEmpty()) return
    val density = LocalDensity.current
    val measure = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val widths = labels.map { measure.measure(it, style).size.width }
    val widest = widths.max()
    val minimumTabWidth = with(density) { widest.toDp() } + 32.dp
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val scrollable = maxWidth < minimumTabWidth * labels.size
        val tabs: @Composable () -> Unit = {
          labels.forEachIndexed { i, label ->
            Tab(
                selected = selected == i,
                onClick = { onSelect(i) },
                // A scroll row measures children with unbounded width. Give each tab a finite
                // content-derived width so centered text also exposes valid accessibility bounds.
                modifier = Modifier.then(if (scrollable) Modifier.width(
                    (with(density) { widths[i].toDp() } + 32.dp).coerceAtLeast(90.dp)
                ) else Modifier).heightIn(min = 48.dp).clip(MaterialTheme.shapes.medium).testTag("section_tab_$i"),
                text = { Text(label, Modifier.width(with(density) { widths[i].toDp() }),
                    style = style, maxLines = 1, softWrap = false, textAlign = TextAlign.Center) },
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        if (scrollable) PrimaryScrollableTabRow(
            selectedTabIndex = selected.coerceIn(labels.indices), edgePadding = 0.dp,
            modifier = Modifier.fillMaxWidth().testTag("scrollable_sections"),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary, divider = {}, tabs = tabs,
        ) else PrimaryTabRow(
            selectedTabIndex = selected.coerceIn(labels.indices), modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary, divider = {}, tabs = tabs,
        )
    }
}

/**
 * ⓘ 提示按钮：圆圈感叹号，点开弹说明对话框。承接原先常驻的说明文字——
 * 熟练后不占空间、不占视线（用户拍板的成熟做法）。
 */
@Composable
fun InfoDot(title: String, body: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier.size(48.dp)) {
        Icon(
            painterResource(R.drawable.ic_b_info),
            contentDescription = title,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(17.dp),
        )
    }
    if (open) {
        InteractionSheet(title, { open = false }) {
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
