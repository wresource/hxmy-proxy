package com.mzstd.hxmyproxy.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.ui.theme.LocalDarkTheme

/**
 * 全 app 卡片底色（按明暗分）：**浅色用 surfaceContainerLow**（N96，接近白、不发灰——High N92 在浅色下发灰,
 * 用户反馈奇怪）；**深色用 surfaceContainerHigh**（N17，与深背景 N6 拉开 ~11 级 tone，靠明度对比浮出）。
 * 不画外描边（太刻意）。GroupCard / 首页 ElevatedCard / 规则 SectionCard 统一取此色。
 */
@Composable
fun cardContainerColor(): Color =
    if (LocalDarkTheme.current) MaterialTheme.colorScheme.surfaceContainerHigh
    else MaterialTheme.colorScheme.surfaceContainerLow

/**
 * FilterChip 统一选中态配色（全 app 一致）：浅色 primaryContainer/onPrimaryContainer、
 * 深色 primary/onPrimary（与设置页语言/外观/性能预设 chip 同款）。
 */
@Composable
fun stdFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = if (LocalDarkTheme.current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = if (LocalDarkTheme.current) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
)

/**
 * 分组卡片（Pixel 系统设置样式）：ElevatedCard（默认 1dp elevation，浅色下有「卡片高度」阴影）+
 * [cardContainerColor] 底 + 组标题（可带尾部控件，title=null 则无标题行），组内 8dp 节奏。
 * 与首页/规则页的 ElevatedCard 完全同款——设置页/监控页共用，全 app 卡片一致（同高度/圆角/底色）。
 */
@Composable
fun GroupCard(
    title: String?,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    ElevatedCard(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = cardContainerColor()),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                title != null && trailing != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    trailing()
                }
                title != null -> Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

/** 导航行：标题 + 右箭头。「更多/帮助」类卡内条目（监控页/设置页共用）。 */
@Composable
fun NavRow(title: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
