package com.mzstd.hxmyproxy.ui.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.stats.EgressKind
import com.mzstd.hxmyproxy.core.stats.EgressSlice
import com.mzstd.hxmyproxy.core.stats.PeriodStats
import com.mzstd.hxmyproxy.core.stats.StatsPeriod
import com.mzstd.hxmyproxy.core.stats.TrafficBucket
import com.mzstd.hxmyproxy.ui.TrafficStatsViewModel
import com.mzstd.hxmyproxy.ui.components.BentoCard
import com.mzstd.hxmyproxy.ui.components.BigStat
import com.mzstd.hxmyproxy.ui.components.CardHeader
import com.mzstd.hxmyproxy.ui.components.CardTier
import com.mzstd.hxmyproxy.ui.components.DetailScaffold
import com.mzstd.hxmyproxy.ui.components.StatLabel
import com.mzstd.hxmyproxy.ui.components.StatusDot
import com.mzstd.hxmyproxy.ui.components.stdFilterChipColors
import com.mzstd.hxmyproxy.ui.formatBytes
import com.mzstd.hxmyproxy.ui.theme.egressColor
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * 流量统计概览（规格＝原型 traffic-proto 屏②）：顶部切周期，下面两块跟着变——
 * 主卡回答「多少、什么时候用的」，出口卡回答「从哪条网出去的」。
 *
 * 不做成两个 Tab：用户真正想问的是「今天里有多少走了蜂窝」，拆开就得来回切。
 *
 * 与首页那个 Total 的区别写在副标题里（「今日 00:00 起 · N 次共享」）——两个数字口径不同，
 * 不标清楚就会打架。
 */
@Composable
fun TrafficStatsScreen(
    onBack: () -> Unit,
    embedded: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val vm: TrafficStatsViewModel = hiltViewModel()
    val period by vm.period.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()

    // 清空只在**有东西可清**时出现：空态下摆一个删不掉任何东西的按钮只会让人犹豫。
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.traffic_clear_title)) },
            text = { Text(stringResource(R.string.traffic_clear_body)) },
            confirmButton = {
                TextButton(onClick = { vm.clearHistory(); confirmClear = false }) {
                    Text(stringResource(R.string.traffic_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // 嵌入模式(工作台「流量」分段):无顶栏壳,清空动作移到列表末尾;独立路由仍走 DetailScaffold。
    val body: @Composable (PaddingValues) -> Unit = { padding ->
        val ld = androidx.compose.ui.platform.LocalLayoutDirection.current
        // 必须在 LazyColumn **之外**读 —— LazyListScope 的 lambda 不是 @Composable，
        // 在里面读 State 不会建立订阅：首帧拿到 null 之后就再也不刷新，
        // 表现为「页面只有周期 chips、下面永远空白」（模拟器上实测到过）。
        val s = stats
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp + padding.calculateStartPadding(ld),
                end = 16.dp + padding.calculateEndPadding(ld),
                top = padding.calculateTopPadding(),
                bottom = 16.dp + padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatsPeriod.entries.forEach { p ->
                        FilterChip(
                            selected = p == period,
                            onClick = { vm.select(p) },
                            label = { Text(stringResource(p.labelRes())) },
                            colors = stdFilterChipColors(),
                        )
                    }
                }
            }

            if (s == null) {
                // 首帧还没查出来：留白，别闪一个「没有数据」再跳成有数据。
            } else if (s.firstDayEpoch == null) {
                item { EmptyCard() }
            } else {
                item { TotalCard(s) }
                item { EgressCard(s) }
                item { CellularCard(s) }
                // 嵌入模式没有顶栏 action,清空入口移到列表末尾(仍有确认对话框)。
                if (embedded && s.firstDayEpoch != null) {
                    item {
                        TextButton(onClick = { confirmClear = true }) {
                            Text(stringResource(R.string.traffic_clear), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
    if (embedded) {
        body(contentPadding)
    } else {
        DetailScaffold(
            title = stringResource(R.string.traffic_stats_title),
            onBack = onBack,
            actions = {
                if (stats?.firstDayEpoch != null) {
                    TextButton(onClick = { confirmClear = true }) {
                        Text(stringResource(R.string.traffic_clear))
                    }
                }
            },
        ) { padding -> body(padding) }
    }
}

private fun StatsPeriod.labelRes(): Int = when (this) {
    StatsPeriod.TODAY -> R.string.traffic_period_today
    StatsPeriod.WEEK -> R.string.traffic_period_week
    StatsPeriod.MONTH -> R.string.traffic_period_month
    StatsPeriod.YEAR -> R.string.traffic_period_year
    StatsPeriod.ALL -> R.string.traffic_period_all
}

@Composable
private fun EmptyCard() {
    BentoCard(tier = CardTier.Primary) {
        CardHeader(
            title = stringResource(R.string.traffic_empty_title),
            icon = painterResource(R.drawable.ic_b_bars),
        )
        Text(
            stringResource(R.string.traffic_empty_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 主卡：超大总量 + 周期起点与共享次数 + 上下行两格 + 趋势柱。 */
@Composable
private fun TotalCard(s: PeriodStats) {
    BentoCard(tier = CardTier.Primary) {
        val (num, unit) = splitBytes(formatBytes(s.bytes))
        BigStat(num, unit.ifEmpty { null }, valueSize = 42)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                sinceText(s),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (s.sessions > 0) {
                Text(
                    "·",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                )
                Text(
                    pluralStringResource(R.plurals.traffic_sessions, s.sessions, s.sessions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DirectionTile(
                Modifier.weight(1f),
                icon = R.drawable.ic_b_arrow_down,
                tint = MaterialTheme.colorScheme.primary,
                label = stringResource(R.string.monitor_down),
                bytes = s.down,
            )
            DirectionTile(
                Modifier.weight(1f),
                icon = R.drawable.ic_b_arrow_up,
                tint = MaterialTheme.colorScheme.tertiary,
                label = stringResource(R.string.monitor_up),
                bytes = s.up,
            )
        }
        if (s.bytes <= 0L) {
            Text(
                stringResource(R.string.traffic_bucket_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            BarChart(s.buckets, highlightKey = currentBucketKey(s.period), color = MaterialTheme.colorScheme.primary)
            AxisRow(axisLabels(s.period, s.buckets))
        }
    }
}

/** 上行/下行一格。 */
@Composable
private fun DirectionTile(modifier: Modifier, icon: Int, tint: Color, label: String, bytes: Long) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
            StatLabel(label)
        }
        Text(
            formatBytes(bytes),
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 出口分解：堆叠条 + 每档一行（色点 · 名称/说明 · 字节 · 占比）。 */
@Composable
private fun EgressCard(s: PeriodStats) {
    BentoCard(tier = CardTier.Default) {
        CardHeader(
            title = stringResource(R.string.traffic_by_egress),
            icon = painterResource(R.drawable.ic_b_egress),
            iconBg = MaterialTheme.colorScheme.surfaceContainerHighest,
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (s.slices.isEmpty()) {
            Text(
                stringResource(R.string.traffic_bucket_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@BentoCard
        }
        StackedBar(s.slices, s.bytes)
        s.slices.forEachIndexed { i, slice ->
            if (i > 0)            EgressRow(slice, s.bytes)
        }
        Text(
            stringResource(R.string.traffic_egress_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 出口堆叠条。每段**至少 [MIN_SLICE_FRACTION] 宽**——单一出口常占 85%+，1.7% 的蜂窝按真实比例
 * 画出来只有一根发丝，看不见也点不着（用户原话「上述的流量其实绝大多数是总流量吧」）。
 */
@Composable
private fun StackedBar(slices: List<EgressSlice>, total: Long) {
    if (total <= 0) return
    Row(
        Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        slices.forEach { s ->
            val w = (s.bytes.toFloat() / total).coerceAtLeast(MIN_SLICE_FRACTION)
            Box(Modifier.weight(w).fillMaxHeight().background(egressColor(s.kind)))
        }
    }
}

@Composable
private fun EgressRow(slice: EgressSlice, total: Long) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusDot(egressColor(slice.kind), 9.dp)
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(slice.kind.labelRes()),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            slice.kind.subRes()?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            formatBytes(slice.bytes),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            maxLines = 1,
        )
        Text(
            percentText(slice.bytes, total),
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 蜂窝本月单列一格：别的出口用多少通常无所谓，蜂窝要花钱。 */
@Composable
private fun CellularCard(s: PeriodStats) {
    BentoCard(tier = CardTier.Sunken, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                StatLabel(stringResource(R.string.traffic_cellular_month))
                Text(
                    formatBytes(s.cellularThisMonth),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                )
            }
            Text(
                stringResource(R.string.traffic_cellular_note),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 趋势柱：当前时段那根实色，其余压到 34% —— 高度已经表达了大小，实色留给「现在在哪」。 */
@Composable
internal fun BarChart(
    buckets: List<TrafficBucket>,
    highlightKey: Long?,
    color: Color,
    height: androidx.compose.ui.unit.Dp = 76.dp,
) {
    if (buckets.isEmpty()) return
    val max = buckets.maxOf { it.bytes }.coerceAtLeast(1L)
    // 柱子少（「总计」刚开始只有一两个月）时给固定宽度并靠左：铺满整宽的单根柱子读起来是一块
    // 色块，不是图表——反而像「进度条 100%」这种完全不同的意思。
    val few = buckets.size < 5
    Row(
        Modifier.fillMaxWidth().height(height),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(if (buckets.size >= 20) 2.dp else 3.dp),
    ) {
        buckets.forEach { b ->
            val h = (height * (b.bytes.toFloat() / max)).coerceAtLeast(2.dp)
            Box(
                Modifier
                    .then(if (few) Modifier.width(30.dp) else Modifier.weight(1f))
                    .height(h)
                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp, bottomStart = 1.dp, bottomEnd = 1.dp))
                    .background(color.copy(alpha = if (b.key == highlightKey) 1f else 0.34f)),
            )
        }
    }
}

@Composable
private fun AxisRow(labels: List<String>) {
    if (labels.isEmpty()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEach {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

// ——— 文案与格式 ———

private fun EgressKind.labelRes(): Int = when (this) {
    EgressKind.VPN -> R.string.traffic_eg_vpn
    EgressKind.WIFI -> R.string.traffic_eg_wifi
    EgressKind.CELLULAR -> R.string.traffic_eg_cellular
    EgressKind.ETHERNET -> R.string.traffic_eg_ethernet
    EgressKind.OTHER -> R.string.traffic_eg_other
}

private fun EgressKind.subRes(): Int? = when (this) {
    EgressKind.VPN -> R.string.traffic_eg_vpn_sub
    EgressKind.WIFI -> R.string.traffic_eg_wifi_sub
    EgressKind.CELLULAR -> R.string.traffic_eg_cellular_sub
    else -> null
}

@Composable
private fun sinceText(s: PeriodStats): String = when (s.period) {
    StatsPeriod.TODAY -> stringResource(R.string.traffic_since_today)
    StatsPeriod.WEEK -> stringResource(R.string.traffic_since_week)
    StatsPeriod.MONTH -> stringResource(R.string.traffic_since_month)
    StatsPeriod.YEAR -> stringResource(R.string.traffic_since_year)
    StatsPeriod.ALL -> s.firstDayEpoch
        ?.let { stringResource(R.string.traffic_since_all, LocalDate.ofEpochDay(it).toString()) }
        ?: stringResource(R.string.traffic_since_all_empty)
}

/** "2.41 GB" → ("2.41","GB")：BigStat 数字与单位分排。 */
private fun splitBytes(s: String): Pair<String, String> {
    val i = s.lastIndexOf(' ')
    return if (i > 0) s.substring(0, i) to s.substring(i + 1) else s to ""
}

/** 占比。非零但不足 0.1% 的档显示 `<0.1%` 而不是 `0.0%`——它确实用了流量，写 0 是在说谎。 */
private fun percentText(bytes: Long, total: Long): String {
    if (total <= 0) return "—"
    val pct = bytes * 100.0 / total
    return when {
        pct >= 0.1 -> String.format(Locale.US, "%.1f%%", pct)
        bytes > 0 -> "<0.1%"
        else -> "0%"
    }
}

/** 当前时段在本周期柱状序列里的 key（高亮用）。 */
private fun currentBucketKey(period: StatsPeriod): Long {
    val now = java.time.LocalDateTime.now()
    return when (period) {
        StatsPeriod.TODAY -> now.hour.toLong()
        StatsPeriod.WEEK, StatsPeriod.MONTH -> now.toLocalDate().toEpochDay()
        StatsPeriod.YEAR -> now.monthValue.toLong()
        StatsPeriod.ALL -> now.year * 12L + (now.monthValue - 1)
    }
}

/** 轴标签：只标首/中/尾三处——柱子最多 31 根，逐格标注反而糊成一片。 */
@Composable
private fun axisLabels(period: StatsPeriod, buckets: List<TrafficBucket>): List<String> {
    if (buckets.isEmpty()) return emptyList()
    val locale: Locale = LocalConfiguration.current.locales[0]
    return when (period) {
        StatsPeriod.TODAY -> listOf("00", "06", "12", "18", "23")
        StatsPeriod.WEEK -> listOf(0, 3, 6).mapNotNull { i ->
            buckets.getOrNull(i)?.let {
                LocalDate.ofEpochDay(it.key).dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            }
        }
        StatsPeriod.MONTH -> listOf(0, buckets.size / 2, buckets.size - 1).mapNotNull { i ->
            buckets.getOrNull(i)?.let { LocalDate.ofEpochDay(it.key).dayOfMonth.toString() }
        }
        StatsPeriod.YEAR -> listOf(1, 7, 12).map {
            java.time.Month.of(it).getDisplayName(TextStyle.SHORT, locale)
        }
        StatsPeriod.ALL -> listOfNotNull(buckets.firstOrNull(), buckets.lastOrNull())
            .map { monthKeyLabel(it.key) }
            .distinct()
    }
}

private fun monthKeyLabel(key: Long): String {
    val year = key / 12
    val month = key % 12 + 1
    return String.format(Locale.US, "%d/%d", year, month)
}

/** 堆叠条每段的最小宽度占比（约 4dp @360dp 宽）：极小份额也要看得见、点得着。 */
private const val MIN_SLICE_FRACTION = 0.012f
