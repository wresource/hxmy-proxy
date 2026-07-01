package com.mzstd.hxmyproxy.ui.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MonitorViewModel
import com.mzstd.hxmyproxy.ui.theme.StatusColors

@Composable
private fun latencyColor(millis: Long?): Color = when {
    millis == null -> StatusColors.bad()
    millis <= 250 -> StatusColors.good()
    millis <= 500 -> StatusColors.warn()
    else -> StatusColors.bad()
}

/** 各监控服务的品牌识别色（公开品牌主色，仅作圆形底色，不含 logo 图形）。 */
private fun serviceColor(name: String): Long = when (name) {
    "GitHub" -> 0xFF24292FL
    "Google" -> 0xFF4285F4L
    "OpenAI" -> 0xFF10A37FL
    "Claude" -> 0xFFD97757L
    "Cloudflare" -> 0xFFF38020L
    "YouTube" -> 0xFFFF0000L
    "Bing" -> 0xFF008373L
    "Wikipedia" -> 0xFF636466L
    "X" -> 0xFF111111L
    "npm" -> 0xFFCB3837L
    "Hugging Face" -> 0xFFFF9D00L
    "Docker Hub" -> 0xFF2496EDL
    else -> 0xFF5C6BC0L
}

/** 协议识别色：HTTP 蓝、SOCKS5 绿、PAC 橙（仅作圆形底色）。 */
private fun protocolColor(name: String): Long = when (name) {
    "HTTP" -> 0xFF4285F4L
    "SOCKS5" -> 0xFF10A37FL
    "PAC" -> 0xFFF38020L
    else -> 0xFF5C6BC0L
}

@Composable
private fun fmtBytes(bytes: Long): String =
    android.text.format.Formatter.formatShortFileSize(LocalContext.current, bytes)

/** 网格单元：上=圆形字符图标，中=名称（单行省略），下=值。 */
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
 * 在 LazyColumn 里以「每行 [columns] 个」网格渲染 [items]；
 * 折叠态最多 [collapsedRows] 排，超出给「展开全部/收起」切换（不用 LazyVerticalGrid，避免嵌套滚动冲突）。
 */
private fun <T> LazyListScope.gridSection(
    items: List<T>,
    collapsedRows: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    columns: Int = 4,
    cell: @Composable (Modifier, T) -> Unit,
) {
    val cap = collapsedRows * columns
    val shown = if (expanded) items else items.take(cap)
    val rows = shown.chunked(columns)
    itemsIndexed(rows) { _, row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            row.forEach { item -> cell(Modifier.weight(1f), item) }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }  // 末行补齐对齐
        }
    }
    if (items.size > cap) {
        item {
            TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (expanded) stringResource(R.string.monitor_collapse)
                    else stringResource(R.string.monitor_expand, items.size),
                )
            }
        }
    }
}

@Composable
fun MonitorScreen(
    ui: MainUiState,
    onOpenHistory: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val vm: MonitorViewModel = hiltViewModel()
    val latency by vm.latency.collectAsStateWithLifecycle()
    val measuring by vm.measuring.collectAsStateWithLifecycle()

    var diagExpanded by remember { mutableStateOf(false) }
    var latencyExpanded by remember { mutableStateOf(false) }
    var clientsExpanded by remember { mutableStateOf(false) }
    var domainsExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // —— 诊断（原独立页并入，圆形图标一眼正常/异常）——
        item { Text(stringResource(R.string.monitor_diagnostics), style = MaterialTheme.typography.titleMedium) }
        val diag = ui.share.diagnostics
        // Triple(label, enabled, up):enabled=false → 中性「未启用」(不是故障);enabled&&up → 绿✓正常;
        // enabled&&!up → 红✗异常。协议行(HTTP/SOCKS/PAC)带各自 enabled,避免用户关掉某协议就被误报成红叉异常。
        val diagItems = listOf(
            Triple(R.string.diag_local_net_perm, true, diag.localNetworkPermissionGranted),
            Triple(R.string.diag_vpn, true, diag.vpnDetected),
            Triple(R.string.diag_http_port, diag.httpEnabled, diag.httpPortUp),
            Triple(R.string.diag_socks_port, diag.socksEnabled, diag.socksPortUp),
            Triple(R.string.diag_pac_port, diag.pacEnabled, diag.pacPortUp),
        )
        gridSection(
            items = diagItems,
            collapsedRows = 2,
            expanded = diagExpanded,
            onToggle = { diagExpanded = !diagExpanded },
        ) { mod, item ->
            val (label, enabled, up) = item
            // PAC 开着但 HTTP/SOCKS 全关 → PAC 只生成 return "DIRECT"(不代理),端口虽 up 也不算「正常」;
            // 标黄「仅直连」,与主页入口卡的退化告警保持一致,避免两屏一红一绿自相矛盾(审查发现)。
            val pacDirectOnly = label == R.string.diag_pac_port &&
                diag.pacEnabled && !diag.httpEnabled && !diag.socksEnabled
            when {
                !enabled -> {
                    val c = MaterialTheme.colorScheme.onSurfaceVariant
                    GridCell(
                        modifier = mod,
                        iconText = "—",
                        iconBg = c.copy(alpha = 0.14f),
                        iconColor = c,
                        name = stringResource(label),
                        value = stringResource(R.string.diag_disabled),
                        valueColor = c,
                    )
                }
                pacDirectOnly -> {
                    val c = StatusColors.warn()
                    GridCell(
                        modifier = mod,
                        iconText = "!",
                        iconBg = c.copy(alpha = 0.18f),
                        iconColor = c,
                        name = stringResource(label),
                        value = stringResource(R.string.diag_pac_direct_only),
                        valueColor = c,
                    )
                }
                else -> {
                    val c = if (up) StatusColors.good() else StatusColors.bad()
                    GridCell(
                        modifier = mod,
                        iconText = if (up) "✓" else "✗",
                        iconBg = c.copy(alpha = 0.18f),
                        iconColor = c,
                        name = stringResource(label),
                        value = stringResource(if (up) R.string.diag_ok else R.string.diag_fail),
                        valueColor = c,
                    )
                }
            }
        }

        // —— 服务延迟 ——
        item { HorizontalDivider(Modifier.padding(vertical = 10.dp)) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.monitor_latency), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (measuring) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = vm::refreshLatency) { Text(stringResource(R.string.refresh)) }
                }
            }
        }
        if (!ui.share.vpn.detected) {
            item {
                Text(
                    stringResource(R.string.monitor_novpn_hint),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        // 延迟网格：按延迟升序（超时排末尾），默认 2 排，超出可展开。
        gridSection(
            items = latency.sortedBy { it.millis ?: Long.MAX_VALUE },
            collapsedRows = 2,
            expanded = latencyExpanded,
            onToggle = { latencyExpanded = !latencyExpanded },
        ) { mod, r ->
            GridCell(
                modifier = mod,
                iconText = (r.service.name.firstOrNull()?.uppercaseChar() ?: '?').toString(),
                iconBg = Color(serviceColor(r.service.name)),
                iconColor = Color.White,
                name = r.service.name,
                value = if (r.millis == null) stringResource(R.string.latency_timeout) else "${r.millis} ms",
                valueColor = latencyColor(r.millis),
            )
        }

        // —— 客户端会话（按来源 IP 聚合）——标题始终显示；空时给提示。
        item { HorizontalDivider(Modifier.padding(vertical = 10.dp)) }
        item { Text(stringResource(R.string.monitor_clients), style = MaterialTheme.typography.titleMedium) }
        if (ui.share.clients.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.monitor_no_clients),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // 客户端网格：默认 1 排（4 个），超出可展开。
            gridSection(
                items = ui.share.clients,
                collapsedRows = 1,
                expanded = clientsExpanded,
                onToggle = { clientsExpanded = !clientsExpanded },
            ) { mod, c ->
                val ipStr = c.clientIp.hostAddress ?: "?"
                GridCell(
                    modifier = mod,
                    // 用 IP 末段（如 .34）作图标字符，比首字符「1」更能区分不同客户端
                    iconText = ipStr.substringAfterLast('.').ifEmpty { "?" },
                    iconBg = MaterialTheme.colorScheme.primaryContainer,
                    iconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    name = ipStr,
                    value = "↓${fmtBytes(c.downloadBytes)} ↑${fmtBytes(c.uploadBytes)}",
                    valueColor = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // —— 目标域名 Top N（隐私：只显示 host + 字节）——标题始终显示；空时给提示。
        item { HorizontalDivider(Modifier.padding(vertical = 10.dp)) }
        item { Text(stringResource(R.string.monitor_top_domains), style = MaterialTheme.typography.titleMedium) }
        if (ui.share.topDomains.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.monitor_no_domains),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // 域名网格：按最近转发时间降序，一行 3 个（给协议标注留宽度）；圆圈底色=协议、value 前缀=协议名。
            gridSection(
                items = ui.share.topDomains.sortedByDescending { it.lastSeenAtEpochMs },
                collapsedRows = 1,
                expanded = domainsExpanded,
                onToggle = { domainsExpanded = !domainsExpanded },
                columns = 3,
            ) { mod, d ->
                GridCell(
                    modifier = mod,
                    iconText = (d.host.firstOrNull()?.uppercaseChar() ?: '?').toString(),
                    iconBg = Color(protocolColor(d.protocol.name)),
                    iconColor = Color.White,
                    name = d.host,
                    value = "${d.protocol.name} · ${fmtBytes(d.uploadBytes + d.downloadBytes)}",
                    valueColor = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        item { HorizontalDivider(Modifier.padding(vertical = 10.dp)) }
        item {
            OutlinedButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
                Text("${stringResource(R.string.monitor_open_history)} (${ui.history.size})")
            }
        }
        item { Spacer(Modifier.size(8.dp)) }
        item {
            OutlinedButton(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.error_logs))
            }
        }
    }
}
