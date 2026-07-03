package com.mzstd.hxmyproxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R

/**
 * 「展开全部 N / 收起」整宽按钮。列表折叠态超出时统一用它——首页入口/接口、
 * 规则白名单、[CardGrid] 等共用，消除各处重复的 take(N)+TextButton 模板。
 */
@Composable
fun ExpandCollapseButton(expanded: Boolean, total: Int, onToggle: () -> Unit) {
    TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Text(
            if (expanded) stringResource(R.string.monitor_collapse)
            else stringResource(R.string.monitor_expand, total),
        )
    }
}

/**
 * 卡内网格：每行 [columns] 个渲染 [items]，折叠态最多 [collapsedRows] 排，超出给展开/收起。
 * 监控页延迟/诊断格、规则页规则集格共用（数据量 ≤ 数十项，普通 Composable 无性能压力）。
 */
@Composable
fun <T> CardGrid(
    items: List<T>,
    collapsedRows: Int,
    columns: Int = 4,
    cell: @Composable (Modifier, T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val cap = collapsedRows * columns
    val shown = if (expanded) items else items.take(cap)
    shown.chunked(columns).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            row.forEach { item -> cell(Modifier.weight(1f), item) }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }  // 末行补齐对齐
        }
    }
    if (items.size > cap) ExpandCollapseButton(expanded, items.size) { expanded = !expanded }
}

/**
 * 圆形头像/图标容器：[size] 直径的圆、[bg] 底色、内容居中。
 * 监控页字符头像/圆点、规则页图标格共用（统一「圆形容器」模式）。
 */
@Composable
fun AvatarCircle(
    size: Dp,
    bg: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier.size(size).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * 标题 + 副标题 + 右侧 Switch 的开关行（标题 bodyLarge、副标题 bodySmall/onSurfaceVariant）。
 * 设置项、规则组、可分享接口共用——三处原本各自手写同一结构，收敛于此。
 */
@Composable
fun LabeledSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
