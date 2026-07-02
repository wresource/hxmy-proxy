package com.mzstd.hxmyproxy.ui.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MonitorViewModel
import com.mzstd.hxmyproxy.ui.components.GroupCard
import com.mzstd.hxmyproxy.ui.components.NavRow
import com.mzstd.hxmyproxy.ui.theme.StatusColors

@Composable
private fun latencyColor(millis: Long?): Color = when {
    millis == null -> StatusColors.bad()
    millis <= 250 -> StatusColors.good()
    millis <= 500 -> StatusColors.warn()
    else -> StatusColors.bad()
}

/**
 * 头像配色（背景 → 前景）：**从主题派生**、按名称 hash 稳定取色——替代原先 13 个写死的
 * 第三方品牌色（不随主题、暗色下对比度未验证、与蓝粉主题打架）。container 配对保证 AA 对比。
 */
@Composable
private fun avatarPair(name: String): Pair<Color, Color> {
    val cs = MaterialTheme.colorScheme
    val palette = listOf(
        cs.primaryContainer to cs.onPrimaryContainer,
        cs.secondaryContainer to cs.onSecondaryContainer,
        cs.tertiaryContainer to cs.onTertiaryContainer,
        cs.primary to cs.onPrimary,
        cs.secondary to cs.onSecondary,
        cs.tertiary to cs.onTertiary,
    )
    // hashCode 可能为 Int.MIN_VALUE（abs 溢出），用 mod 折回非负。
    return palette[((name.hashCode() % palette.size) + palette.size) % palette.size]
}

/** 协议配色：HTTP=蓝 primary（主协议）、SOCKS5=蓝灰 secondary、PAC=粉 tertiary（点睛）。 */
@Composable
private fun protocolPair(name: String): Pair<Color, Color> {
    val cs = MaterialTheme.colorScheme
    return when (name) {
        "HTTP" -> cs.primary to cs.onPrimary
        "SOCKS5" -> cs.secondary to cs.onSecondary
        "PAC" -> cs.tertiary to cs.onTertiary
        else -> cs.surfaceVariant to cs.onSurfaceVariant
    }
}

@Composable
private fun fmtBytes(bytes: Long): String =
    android.text.format.Formatter.formatShortFileSize(LocalContext.current, bytes)

/** 网格单元：上=圆形字符图标，中=名称（单行省略），下=值。诊断/延迟网格用。 */
@Composable
private fun GridCell(
    modifier: Modifier,
    iconText: String,
    iconBg: Color,
    iconColor: Color,
    name: String,
    value: String,
    valueColor: Color,
) {
    Column(
        modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Text(iconText, style = MaterialTheme.typography.titleMedium, color = iconColor)
        }
        Spacer(Modifier.size(4.dp))
        Text(
            name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = valueColor,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * 卡内网格：「每行 [columns] 个」渲染 [items]，折叠态最多 [collapsedRows] 排，
 * 超出给「展开全部/收起」。普通 Composable（在 GroupCard 内用，数据量 ≤ 数十项无性能压力）。
 */
@Composable
private fun <T> CardGrid(
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
    if (items.size > cap) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (expanded) stringResource(R.string.monitor_collapse)
                else stringResource(R.string.monitor_expand, items.size),
            )
        }
    }
}

/** 数据列表行：小圆点/头像 + 主文本(等宽可选) + 右侧值。客户端/域名等「同质可扫读」内容用。 */
@Composable
private fun DataRow(
    dotBg: Color,
    dotFg: Color,
    dotText: String,
    title: String,
    subtitle: String? = null,
    value: String,
    mono: Boolean = false,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(dotBg),
            contentAlignment = Alignment.Center,
        ) {
            Text(dotText, style = MaterialTheme.typography.labelLarge, color = dotFg)
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = if (mono) FontFamily.Monospace else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}


/**
 * 监控页（重设计）：统一「分组卡」语言的六张卡——
 * 实时速率(运行时,大数字) / 诊断(三态网格) / 服务延迟(头像网格+刷新) /
 * 客户端(列表行) / 目标域名(列表行,协议色圆点) / 更多(历史+日志导航行)。
 * 客户端与域名按 M3 官方判据从网格改为列表：同质、可扫读、少动作的内容用 list。
 */
@Composable
fun MonitorScreen(
    ui: MainUiState,
    onOpenHistory: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val vm: MonitorViewModel = hiltViewModel()
    val latency by vm.latency.collectAsStateWithLifecycle()
    val measuring by vm.measuring.collectAsStateWithLifecycle()
    var domainsExpanded by remember { mutableStateOf(false) }
    var clientsExpanded by remember { mutableStateOf(false) }

    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomInset + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // —— 实时速率（运行时）：两个大数字并排 ——
        if (ui.share.running) {
            item {
                GroupCard(stringResource(R.string.monitor_realtime)) {
                    Row(Modifier.fillMaxWidth()) {
                        SpeedCell(
                            Modifier.weight(1f),
                            "↓ " + com.mzstd.hxmyproxy.ui.formatRate(ui.share.downloadRateBps),
                            stringResource(R.string.monitor_down),
                        )
                        SpeedCell(
                            Modifier.weight(1f),
                            "↑ " + com.mzstd.hxmyproxy.ui.formatRate(ui.share.uploadRateBps),
                            stringResource(R.string.monitor_up),
                        )
                    }
                }
            }
        }

        // —— 诊断（三态：未启用/正常/异常；PAC 退化标黄「仅直连」）——
        item {
            GroupCard(stringResource(R.string.monitor_diagnostics)) {
                val diag = ui.share.diagnostics
                val diagItems = listOf(
                    Triple(R.string.diag_local_net_perm, true, diag.localNetworkPermissionGranted),
                    Triple(R.string.diag_vpn, true, diag.vpnDetected),
                    Triple(R.string.diag_http_port, diag.httpEnabled, diag.httpPortUp),
                    Triple(R.string.diag_socks_port, diag.socksEnabled, diag.socksPortUp),
                    Triple(R.string.diag_pac_port, diag.pacEnabled, diag.pacPortUp),
                )
                CardGrid(items = diagItems, collapsedRows = 2) { mod, item ->
                    val (label, enabled, up) = item
                    val pacDirectOnly = label == R.string.diag_pac_port &&
                        diag.pacEnabled && !diag.httpEnabled && !diag.socksEnabled
                    when {
                        !enabled -> {
                            val c = MaterialTheme.colorScheme.onSurfaceVariant
                            GridCell(mod, "—", c.copy(alpha = 0.14f), c, stringResource(label), stringResource(R.string.diag_disabled), c)
                        }
                        pacDirectOnly -> {
                            val c = StatusColors.warn()
                            GridCell(mod, "!", c.copy(alpha = 0.18f), c, stringResource(label), stringResource(R.string.diag_pac_direct_only), c)
                        }
                        else -> {
                            val c = if (up) StatusColors.good() else StatusColors.bad()
                            GridCell(
                                mod, if (up) "✓" else "✗", c.copy(alpha = 0.18f), c,
                                stringResource(label), stringResource(if (up) R.string.diag_ok else R.string.diag_fail), c,
                            )
                        }
                    }
                }
            }
        }

        // —— 服务延迟（头像网格 + 刷新）——
        item {
            GroupCard(
                stringResource(R.string.monitor_latency),
                trailing = {
                    if (measuring) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = vm::refreshLatency) { Text(stringResource(R.string.refresh)) }
                    }
                },
            ) {
                if (!ui.share.vpn.detected) {
                    Text(
                        stringResource(R.string.monitor_novpn_hint),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                CardGrid(
                    items = latency.sortedBy { it.millis ?: Long.MAX_VALUE },
                    collapsedRows = 2,
                ) { mod, r ->
                    val (bg, fg) = avatarPair(r.service.name)
                    GridCell(
                        mod, (r.service.name.firstOrNull()?.uppercaseChar() ?: '?').toString(), bg, fg,
                        r.service.name,
                        if (r.millis == null) stringResource(R.string.latency_timeout) else "${r.millis} ms",
                        latencyColor(r.millis),
                    )
                }
            }
        }

        // —— 客户端（列表行：同质可扫读内容按 M3 判据用 list 而非网格）——
        item {
            GroupCard(stringResource(R.string.monitor_clients)) {
                if (ui.share.clients.isEmpty()) {
                    Text(
                        stringResource(R.string.monitor_no_clients),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val shown = if (clientsExpanded) ui.share.clients else ui.share.clients.take(4)
                    shown.forEach { c ->
                        val ipStr = c.clientIp.hostAddress ?: "?"
                        DataRow(
                            dotBg = MaterialTheme.colorScheme.primaryContainer,
                            dotFg = MaterialTheme.colorScheme.onPrimaryContainer,
                            dotText = ipStr.substringAfterLast('.').ifEmpty { "?" },
                            title = ipStr,
                            value = "↓${fmtBytes(c.downloadBytes)} ↑${fmtBytes(c.uploadBytes)}",
                            mono = true,
                        )
                    }
                    if (ui.share.clients.size > 4) {
                        TextButton(onClick = { clientsExpanded = !clientsExpanded }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                if (clientsExpanded) stringResource(R.string.monitor_collapse)
                                else stringResource(R.string.monitor_expand, ui.share.clients.size),
                            )
                        }
                    }
                }
            }
        }

        // —— 目标域名 Top N（列表行：协议色圆点 + 域名 + 流量）——
        item {
            GroupCard(stringResource(R.string.monitor_top_domains)) {
                if (ui.share.topDomains.isEmpty()) {
                    Text(
                        stringResource(R.string.monitor_no_domains),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val sorted = ui.share.topDomains.sortedByDescending { it.lastSeenAtEpochMs }
                    val shown = if (domainsExpanded) sorted else sorted.take(5)
                    shown.forEach { d ->
                        val (bg, fg) = protocolPair(d.protocol.name)
                        DataRow(
                            dotBg = bg,
                            dotFg = fg,
                            dotText = (d.host.firstOrNull()?.uppercaseChar() ?: '?').toString(),
                            title = d.host,
                            subtitle = d.protocol.name,
                            value = fmtBytes(d.uploadBytes + d.downloadBytes),
                        )
                    }
                    if (sorted.size > 5) {
                        TextButton(onClick = { domainsExpanded = !domainsExpanded }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                if (domainsExpanded) stringResource(R.string.monitor_collapse)
                                else stringResource(R.string.monitor_expand, sorted.size),
                            )
                        }
                    }
                }
            }
        }

        // —— 历史入口 / 错误日志（无标题导航卡；「更多」标题冗余已删）——
        item {
            GroupCard(title = null) {
                NavRow("${stringResource(R.string.monitor_open_history)} (${ui.history.size})", onOpenHistory)
                NavRow(stringResource(R.string.error_logs), onOpenLogs)
            }
        }
    }
}

/** 速率大格：大数字（tnum 等宽，刷新不抖）+ 标签。 */
@Composable
private fun SpeedCell(modifier: Modifier, value: String, label: String) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
