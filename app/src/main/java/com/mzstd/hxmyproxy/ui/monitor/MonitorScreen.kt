package com.mzstd.hxmyproxy.ui.monitor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import com.mzstd.hxmyproxy.ui.components.AvatarCircle
import com.mzstd.hxmyproxy.ui.components.CardGrid
import com.mzstd.hxmyproxy.ui.components.ExpandCollapseButton
import com.mzstd.hxmyproxy.ui.components.GroupCard
import com.mzstd.hxmyproxy.ui.components.NavRow
import com.mzstd.hxmyproxy.ui.theme.AvatarBgDark
import com.mzstd.hxmyproxy.ui.theme.AvatarBgLight
import com.mzstd.hxmyproxy.ui.theme.AvatarFgDark
import com.mzstd.hxmyproxy.ui.theme.AvatarFgLight
import com.mzstd.hxmyproxy.ui.theme.LocalDarkTheme
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
    // 用柔和粉彩头像色板（去掉主题高饱和实色——深色下会一片艳粉/玫红扎眼）。深浅各一套。
    val dark = LocalDarkTheme.current
    val bg = if (dark) AvatarBgDark else AvatarBgLight
    val fg = if (dark) AvatarFgDark else AvatarFgLight
    // hashCode 可能为 Int.MIN_VALUE（abs 溢出），用 mod 折回非负。
    val i = ((name.hashCode() % bg.size) + bg.size) % bg.size
    return bg[i] to fg[i]
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

/** 诊断项：label + 三态 + 可选修复引导 + 「无需授权」中性态（本地网络权限在 Android 16- 不强制）。 */
private data class DiagItem(
    val label: Int,
    val enabled: Boolean,
    val up: Boolean,
    val guide: DiagGuide? = null,
    val notApplicable: Boolean = false,
)

/** 可修复诊断的引导类型：弹「为什么需要 + 去开启」并直跳对应系统页面（错过首次授权的补全入口）。 */
private enum class DiagGuide(val titleRes: Int, val bodyRes: Int) {
    NOTIFICATION(R.string.guide_notif_title, R.string.guide_notif_body),
    BATTERY(R.string.guide_battery_title, R.string.guide_battery_body),
    LOCAL_NET(R.string.guide_localnet_title, R.string.guide_localnet_body),
    ;

    fun intent(context: android.content.Context): android.content.Intent {
        val pkg = context.packageName
        return when (this) {
            NOTIFICATION -> android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, pkg)
            // 直接弹系统「允许不受限制」授权框（manifest 已声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）。
            BATTERY -> android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:$pkg"),
            )
            LOCAL_NET -> android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:$pkg"),
            )
        }
    }
}

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
        AvatarCircle(40.dp, iconBg) {
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

// CardGrid 已提取到 components/SharedUi.kt（监控页与规则页共用）。

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
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AvatarCircle(32.dp, dotBg) {
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
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val vm: MonitorViewModel = hiltViewModel()
    val latency by vm.latency.collectAsStateWithLifecycle()
    val measuring by vm.measuring.collectAsStateWithLifecycle()
    var domainsExpanded by remember { mutableStateOf(false) }
    var clientsExpanded by remember { mutableStateOf(false) }
    var guideShown by remember { mutableStateOf<DiagGuide?>(null) }
    var showLocalNetInfo by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // 本地网络权限版本说明(16- 设备上点「无需授权」格子)：讲清各版本差异,只有「知道了」。
    if (showLocalNetInfo) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLocalNetInfo = false },
            title = { Text(stringResource(R.string.guide_localnet_title)) },
            text = { Text(stringResource(R.string.localnet_versions_body)) },
            confirmButton = {
                TextButton(onClick = { showLocalNetInfo = false }) { Text(stringResource(R.string.setup_close)) }
            },
        )
    }

    // 诊断补全引导：说明为什么需要 + 一键跳系统对应页面。
    guideShown?.let { g ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { guideShown = null },
            title = { Text(stringResource(g.titleRes)) },
            text = { Text(stringResource(g.bodyRes)) },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { context.startActivity(g.intent(context)) }
                    guideShown = null
                }) { Text(stringResource(R.string.diag_go_enable)) }
            },
            dismissButton = {
                TextButton(onClick = { guideShown = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // 沉浸式:系统栏 inset 进 contentPadding(首屏不被遮,滚动时内容穿入状态栏/手势条后方)。
    val ld = androidx.compose.ui.platform.LocalLayoutDirection.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().consumeWindowInsets(contentPadding),
        contentPadding = PaddingValues(
            start = 16.dp + contentPadding.calculateStartPadding(ld),
            end = 16.dp + contentPadding.calculateEndPadding(ld),
            top = 16.dp + contentPadding.calculateTopPadding(),
            bottom = 16.dp + contentPadding.calculateBottomPadding(),
        ),
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

        // —— 诊断（三态：未启用/正常/异常；PAC 退化标黄；异常且可修复项**可点击**弹引导）——
        item {
            GroupCard(stringResource(R.string.monitor_diagnostics)) {
                val diag = ui.share.diagnostics
                // 本地网络权限 Android 17(SDK 37) 起才强制;更低版本显示中性「无需授权」而非绿✓,
                // 避免在 16- 设备上误以为「已授过权」。
                val localNetNotRequired = android.os.Build.VERSION.SDK_INT < 37
                val diagItems = listOf(
                    DiagItem(R.string.diag_local_net_perm, true, diag.localNetworkPermissionGranted, DiagGuide.LOCAL_NET, localNetNotRequired),
                    DiagItem(R.string.diag_vpn, true, diag.vpnDetected),
                    DiagItem(R.string.diag_notif_perm, true, diag.notificationPermissionGranted, DiagGuide.NOTIFICATION),
                    DiagItem(R.string.diag_battery, true, diag.batteryOptimizationIgnored, DiagGuide.BATTERY),
                    DiagItem(R.string.diag_http_port, diag.httpEnabled, diag.httpPortUp),
                    DiagItem(R.string.diag_socks_port, diag.socksEnabled, diag.socksPortUp),
                    DiagItem(R.string.diag_pac_port, diag.pacEnabled, diag.pacPortUp),
                )
                CardGrid(items = diagItems, collapsedRows = 2) { mod, item ->
                    val pacDirectOnly = item.label == R.string.diag_pac_port &&
                        diag.pacEnabled && !diag.httpEnabled && !diag.socksEnabled
                    // 异常且有引导的项可点击 → 弹「为什么需要 + 去开启」补全引导;
                    // 本地网络「无需授权」中性格子也可点 → 各 Android 版本差异说明。
                    val clickable = item.guide != null && !item.up && !item.notApplicable
                    val cellMod = when {
                        clickable -> mod.clip(MaterialTheme.shapes.small).clickable { guideShown = item.guide }
                        item.notApplicable && item.label == R.string.diag_local_net_perm ->
                            mod.clip(MaterialTheme.shapes.small).clickable { showLocalNetInfo = true }
                        else -> mod
                    }
                    when {
                        item.notApplicable -> {
                            val c = MaterialTheme.colorScheme.onSurfaceVariant
                            GridCell(cellMod, "—", c.copy(alpha = 0.14f), c, stringResource(item.label), stringResource(R.string.diag_not_required), c)
                        }
                        !item.enabled -> {
                            val c = MaterialTheme.colorScheme.onSurfaceVariant
                            GridCell(cellMod, "—", c.copy(alpha = 0.14f), c, stringResource(item.label), stringResource(R.string.diag_disabled), c)
                        }
                        pacDirectOnly -> {
                            val c = StatusColors.warn()
                            GridCell(cellMod, "!", c.copy(alpha = 0.18f), c, stringResource(item.label), stringResource(R.string.diag_pac_direct_only), c)
                        }
                        item.label == R.string.diag_battery -> {
                            // 电池优化专属文案:无限制/受限(「正常/异常」在这里语义不清)。
                            val c = if (item.up) StatusColors.good() else StatusColors.bad()
                            GridCell(
                                cellMod, if (item.up) "✓" else "✗", c.copy(alpha = 0.18f), c,
                                stringResource(item.label),
                                stringResource(if (item.up) R.string.diag_battery_unrestricted else R.string.diag_battery_restricted), c,
                            )
                        }
                        item.label == R.string.diag_vpn -> {
                            // VPN 出口是「环境事实」而非故障:有 VPN=共享出口(绿✓);没 VPN=过滤网关模式(中性「—」,不标红)。
                            val c = if (item.up) StatusColors.good() else MaterialTheme.colorScheme.onSurfaceVariant
                            GridCell(
                                cellMod, if (item.up) "✓" else "—", c.copy(alpha = if (item.up) 0.18f else 0.14f), c,
                                stringResource(item.label),
                                stringResource(if (item.up) R.string.diag_vpn_sharing else R.string.diag_vpn_gateway), c,
                            )
                        }
                        else -> {
                            val c = if (item.up) StatusColors.good() else StatusColors.bad()
                            GridCell(
                                cellMod, if (item.up) "✓" else "✗", c.copy(alpha = 0.18f), c,
                                stringResource(item.label), stringResource(if (item.up) R.string.diag_ok else R.string.diag_fail), c,
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // 测量中保持默认顺序(逐格点亮不跳位)；测完一次性按延迟升序排(最快在前)。
                CardGrid(
                    items = if (measuring) latency else latency.sortedBy { it.millis ?: Long.MAX_VALUE },
                    collapsedRows = 2,
                ) { mod, r ->
                    val (bg, fg) = avatarPair(r.service.name)
                    // 三态：有值=数字+好坏色；测量中且无值=「测量中」中性色(首屏不闪红)；测完仍无值=「超时」红。
                    val (valueText, valueColor) = when {
                        r.millis != null -> "${r.millis} ms" to latencyColor(r.millis)
                        measuring -> stringResource(R.string.latency_measuring) to MaterialTheme.colorScheme.onSurfaceVariant
                        else -> stringResource(R.string.latency_timeout) to StatusColors.bad()
                    }
                    GridCell(
                        mod, (r.service.name.firstOrNull()?.uppercaseChar() ?: '?').toString(), bg, fg,
                        r.service.name, valueText, valueColor,
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
                        ExpandCollapseButton(clientsExpanded, ui.share.clients.size) { clientsExpanded = !clientsExpanded }
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
                        // "(其他)" 聚合桶是运行时常量,显示时本地化(英文界面不冒中文,双语检查原则)。
                        val hostLabel = if (d.host == com.mzstd.hxmyproxy.core.proxy.TrafficAccounting.OTHERS) {
                            stringResource(R.string.monitor_others)
                        } else d.host
                        DataRow(
                            dotBg = bg,
                            dotFg = fg,
                            dotText = (hostLabel.firstOrNull()?.uppercaseChar() ?: '?').toString(),
                            title = hostLabel,
                            // 「直连」标识:规则白名单命中的域名流量绕过 VPN 直连出口(仍经本代理转发,
                            // 故仍出现在监控)——有了标识,规则是否生效一眼可见。
                            subtitle = if (d.direct) {
                                "${d.protocol.name} · ${stringResource(R.string.route_direct)}"
                            } else d.protocol.name,
                            value = fmtBytes(d.uploadBytes + d.downloadBytes),
                        )
                    }
                    if (sorted.size > 5) {
                        ExpandCollapseButton(domainsExpanded, sorted.size) { domainsExpanded = !domainsExpanded }
                    }
                }
            }
        }

        // —— 已拦截（广告/拒绝规则命中；会话内累计）——
        item {
            GroupCard(stringResource(R.string.monitor_blocked)) {
                if (ui.share.blockedTotal <= 0L) {
                    Text(
                        stringResource(R.string.monitor_no_blocked),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        stringResource(R.string.protection_blocked_count, ui.share.blockedTotal),
                        style = MaterialTheme.typography.titleMedium,
                        color = StatusColors.good(),
                    )
                    ui.share.topBlockedDomains.take(8).forEach { b ->
                        DataRow(
                            dotBg = MaterialTheme.colorScheme.errorContainer,
                            dotFg = MaterialTheme.colorScheme.onErrorContainer,
                            dotText = (b.host.firstOrNull()?.uppercaseChar() ?: '?').toString(),
                            title = b.host,
                            value = "×${b.count}",
                        )
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
        Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
