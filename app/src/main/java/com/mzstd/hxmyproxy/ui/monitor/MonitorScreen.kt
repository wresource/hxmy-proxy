package com.mzstd.hxmyproxy.ui.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.model.ClientSession
import com.mzstd.hxmyproxy.core.model.DomainTraffic
import com.mzstd.hxmyproxy.core.proxy.TrafficAccounting
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MonitorViewModel
import com.mzstd.hxmyproxy.ui.components.AvatarCircle
import com.mzstd.hxmyproxy.ui.components.BentoCard
import com.mzstd.hxmyproxy.ui.components.BigStat
import com.mzstd.hxmyproxy.ui.components.CardGrid
import com.mzstd.hxmyproxy.ui.components.CardHeader
import com.mzstd.hxmyproxy.ui.components.CardTier
import com.mzstd.hxmyproxy.ui.components.CountBadge
import com.mzstd.hxmyproxy.ui.components.ExpandCollapseButton
import com.mzstd.hxmyproxy.ui.components.TopDomainRuleDialog
import com.mzstd.hxmyproxy.ui.components.OverrideBadge
import com.mzstd.hxmyproxy.ui.components.PageHeader
import com.mzstd.hxmyproxy.ui.components.ProtoBadge
import com.mzstd.hxmyproxy.ui.components.RatioBar
import com.mzstd.hxmyproxy.ui.components.Sparkline
import com.mzstd.hxmyproxy.ui.components.StatLabel
import com.mzstd.hxmyproxy.ui.components.StatStripItem
import com.mzstd.hxmyproxy.ui.components.StatusDot
import com.mzstd.hxmyproxy.ui.components.protoBadgeColors
import com.mzstd.hxmyproxy.ui.formatBytes
import com.mzstd.hxmyproxy.ui.formatRate
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

/**
 * 全 app 统一走 [formatBytes]（1024 进制）。这里原先用 `Formatter.formatShortFileSize`，那是 SI 1000 进制，
 * 于是同一笔流量在客户端行显示 `2.3 kB`、在首页 Total 与流量统计里显示 `2.2 KB` —— 同一屏两个数字，
 * 用户没法判断哪个才是真的。口径由用户拍板统一为 1024（2026-07-29）。
 */
private fun fmtBytes(bytes: Long): String = formatBytes(bytes)

/** "2.3 MB/s" → ("2.3","MB/s")：BigStat 数字与单位分排。 */
private fun splitRate(rate: String): Pair<String, String> {
    val i = rate.lastIndexOf(' ')
    return if (i > 0) rate.substring(0, i) to rate.substring(i + 1) else rate to ""
}

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

/** 网格单元：上=圆形字符图标，中=名称（单行省略），下=值。服务延迟网格用。 */
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

/**
 * 监控页（Bento 重设计，规格=images/html/02-monitor.html）：
 * 页头(标题+运行状态胶囊) / 速率大卡(双列大数字+sparkline+地址·连接·累计 strip) /
 * 诊断(可修复异常置顶横幅+2 列小格) / 服务延迟(头像网格+刷新) /
 * 客户端|目标域名 双卡并排(RatioBar 占比) / 已拦截(绿计数+域名 chip 流) / 历史 IP|错误日志 双入口卡。
 * 行为与旧版一致：修复引导弹窗、本地网络版本说明、HostOverrideDialog、展开折叠全保留。
 */
/** 监控内容分区(工作台分段用):ALL=整页(详情路由) · DEVICES=客户端/域名/历史 · HEALTH=诊断/延迟。 */
enum class MonitorSection { ALL, DEVICES, HEALTH }

@Composable
fun MonitorScreen(
    ui: MainUiState,
    viewModel: com.mzstd.hxmyproxy.ui.MainViewModel,
    onOpenHistory: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenDomains: () -> Unit,
    onOpenTrafficStats: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    section: MonitorSection = MonitorSection.ALL,
) {
    val vm: MonitorViewModel = hiltViewModel()
    val latency by vm.latency.collectAsStateWithLifecycle()
    val measuring by vm.measuring.collectAsStateWithLifecycle()
    val trafficSummary by vm.trafficSummary.collectAsStateWithLifecycle()
    // 最近 60s 速率历史（1s 采样），速率大卡 sparkline 用。
    val rates by viewModel.rateHistory.collectAsStateWithLifecycle()
    var clientsExpanded by remember { mutableStateOf(false) }
    var guideShown by remember { mutableStateOf<DiagGuide?>(null) }
    var showLocalNetInfo by remember { mutableStateOf(false) }
    // 监控 Top domains 点击 → 三态救济弹窗（看到某 host 慢/想直连/想拦，两下改成 per-host 覆盖）。
    var editHost by remember { mutableStateOf<String?>(null) }
    // 规则版本信号：规则一变即触发重组，top domain 标据此重判（不再用运行时缓存 d.direct）。
    val ruleVersion by viewModel.ruleVersion.collectAsStateWithLifecycle()
    val context = LocalContext.current

    editHost?.let { host ->
        TopDomainRuleDialog(
            host = host,
            current = viewModel.decideHost(host).takeIf { it != RuleAction.PROXY },
            onDirect = { viewModel.setDomainDirect(host); editHost = null },
            onReject = { viewModel.setDomainReject(host); editHost = null },
            onClear = { viewModel.clearDomainRule(host); editHost = null },
            onDismiss = { editHost = null },
        )
    }

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
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // —— 页头：标题 + 右侧运行状态胶囊（绿点=共享中 / 中性点=已停止）——
        if (section == MonitorSection.ALL) item {
            PageHeader(
                title = stringResource(R.string.nav_monitor),
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusDot(if (ui.share.running) StatusColors.good() else MaterialTheme.colorScheme.onSurfaceVariant, 7.dp)
                        StatLabel(stringResource(if (ui.share.running) R.string.status_running else R.string.status_stopped))
                    }
                },
            )
        }

        // —— 实时速率大卡（运行时）：双列 BigStat+sparkline + 底部地址·连接·累计 strip ——
        // 分段模式下概览段已有速率卡，这里只在整页(详情路由)形态渲染。
        if (section == MonitorSection.ALL && ui.share.running) {
            item {
                BentoCard(tier = CardTier.Primary) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatLabel(stringResource(R.string.monitor_realtime))
                        Spacer(Modifier.weight(1f))
                        StatLabel(stringResource(R.string.monitor_last_60s))
                    }
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        RateColumn(
                            Modifier.weight(1f),
                            icon = painterResource(R.drawable.ic_b_arrow_down),
                            chipBg = MaterialTheme.colorScheme.primaryContainer,
                            chipTint = MaterialTheme.colorScheme.primary,
                            label = stringResource(R.string.monitor_down),
                            rateBps = ui.share.downloadRateBps,
                            sparkColor = MaterialTheme.colorScheme.primary,
                            samples = rates.down,
                        )
                        VerticalDivider(
                            Modifier.padding(horizontal = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )
                        RateColumn(
                            Modifier.weight(1f),
                            icon = painterResource(R.drawable.ic_b_arrow_up),
                            chipBg = MaterialTheme.colorScheme.surfaceContainerHighest,
                            chipTint = MaterialTheme.colorScheme.secondary,
                            label = stringResource(R.string.monitor_up),
                            rateBps = ui.share.uploadRateBps,
                            sparkColor = MaterialTheme.colorScheme.secondary,
                            samples = rates.up,
                        )
                    }
                    // 底部 strip：入口地址(等宽) · 连接数 · 累计流量。
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    ) {
                        val entry = ui.share.recommendedEntries.firstOrNull()
                        if (entry != null) {
                            Text(
                                entry.ipEndpoint,
                                // 收缩优先给 IP：过长时先省略地址，保证「连接」「累计」两项始终不被顶出屏幕。
                                Modifier.weight(1f, fill = false),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            StripSep()
                        }
                        StatStripItem(stringResource(R.string.stat_conns), ui.share.activeConnections.toString())
                        StripSep()
                        StatStripItem(stringResource(R.string.stat_traffic), formatBytes(ui.share.totalBytes))
                    }
                }
            }
        }

        // —— 诊断：可修复异常置顶横幅（Error 级,点弹引导）+ 其余 2 列小格 ——
        if (section != MonitorSection.DEVICES) item {
            BentoCard(tier = CardTier.Primary) {
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
                // 异常且可修复(有引导)的项抽出置顶为横幅;其余进 2 列小格。
                val (alerts, gridItems) = diagItems.partition {
                    it.guide != null && it.enabled && !it.up && !it.notApplicable
                }
                val pacDirectOnly = diag.pacEnabled && !diag.httpEnabled && !diag.socksEnabled
                // 异常计数 = 横幅 + 端口红叉(启用但没起来);VPN 网关态与 PAC 仅直连(黄)不计入。
                val issueCount = alerts.size + gridItems.count {
                    it.enabled && !it.up && !it.notApplicable && it.label != R.string.diag_vpn &&
                        !(it.label == R.string.diag_pac_port && pacDirectOnly)
                }
                CardHeader(
                    title = stringResource(R.string.monitor_diagnostics),
                    icon = painterResource(R.drawable.ic_b_activity),
                    trailing = {
                        if (issueCount > 0) {
                            CountBadge(
                                pluralStringResource(R.plurals.count_issues, issueCount, issueCount),
                                fg = MaterialTheme.colorScheme.onErrorContainer,
                                bg = MaterialTheme.colorScheme.errorContainer,
                            )
                        }
                    },
                )
                alerts.forEach { a ->
                    // 横幅标题=「项目 · 状态」;电池优化带后果副文案,其余项弹窗里已有完整解释。
                    val state = stringResource(
                        if (a.label == R.string.diag_battery) R.string.diag_battery_restricted else R.string.diag_fail,
                    )
                    DiagAlert(
                        title = "${stringResource(a.label)} · $state",
                        sub = if (a.label == R.string.diag_battery) stringResource(R.string.diag_battery_warn_sub) else null,
                        onClick = { guideShown = a.guide },
                    )
                }
                CardGrid(items = gridItems, collapsedRows = 4, columns = 2) { mod, item ->
                    // 本地网络「无需授权」中性格子可点 → 各 Android 版本差异说明(旧行为保留)。
                    val cellMod = if (item.notApplicable && item.label == R.string.diag_local_net_perm) {
                        mod.clip(MaterialTheme.shapes.small).clickable { showLocalNetInfo = true }
                    } else mod
                    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
                    when {
                        item.notApplicable ->
                            DiagCell(cellMod, null, neutral, stringResource(item.label), stringResource(R.string.diag_not_required), neutral)
                        !item.enabled ->
                            DiagCell(cellMod, null, neutral, stringResource(item.label), stringResource(R.string.diag_disabled), neutral)
                        item.label == R.string.diag_pac_port && pacDirectOnly ->
                            DiagCell(cellMod, painterResource(R.drawable.ic_b_exclamation), StatusColors.warn(), stringResource(item.label), stringResource(R.string.diag_pac_direct_only), StatusColors.warn())
                        item.label == R.string.diag_battery ->
                            // 电池优化异常已抽到横幅,格子里只剩「无限制」绿态。
                            DiagCell(cellMod, painterResource(R.drawable.ic_b_check), StatusColors.good(), stringResource(item.label), stringResource(R.string.diag_battery_unrestricted), StatusColors.good())
                        item.label == R.string.diag_vpn -> {
                            // VPN 出口是「环境事实」而非故障:有 VPN=共享出口(绿✓);没 VPN=过滤网关模式(中性,不标红)。
                            val c = if (item.up) StatusColors.good() else neutral
                            DiagCell(
                                cellMod, if (item.up) painterResource(R.drawable.ic_b_check) else null, c,
                                stringResource(item.label),
                                stringResource(if (item.up) R.string.diag_vpn_sharing else R.string.diag_vpn_gateway), c,
                            )
                        }
                        else -> {
                            val c = if (item.up) StatusColors.good() else StatusColors.bad()
                            DiagCell(
                                cellMod,
                                painterResource(if (item.up) R.drawable.ic_b_check else R.drawable.ic_b_close), c,
                                stringResource(item.label),
                                stringResource(if (item.up) R.string.diag_ok else R.string.diag_fail), c,
                            )
                        }
                    }
                }
            }
        }

        // —— 服务延迟（头像网格 + 刷新）——
        if (section != MonitorSection.DEVICES) item {
            BentoCard(tier = CardTier.Default) {
                CardHeader(
                    title = stringResource(R.string.monitor_latency),
                    icon = painterResource(R.drawable.ic_b_timer),
                    trailing = {
                        if (measuring) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(onClick = vm::refreshLatency) {
                                Icon(painterResource(R.drawable.ic_b_refresh), contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.size(5.dp))
                                Text(stringResource(R.string.refresh))
                            }
                        }
                    },
                )
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

        // —— 链路质量四格(诊断段专属;设计稿):丢包/往返 p50/p95/信号。数据=段①客户端→本机探测。 ——
        if (section == MonitorSection.HEALTH) item {
            LinkQualityCard(ui)
        }

        // —— 实时日志预览(诊断段专属;设计稿):最近 3 条快照 + 查看全部。 ——
        if (section == MonitorSection.HEALTH) item {
            LogPreviewCard(onOpenLogs)
        }

        // —— 客户端 / 目标域名：上下单列（各占满整行宽，域名/客户端行不再被挤窄）——
        if (section != MonitorSection.HEALTH) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ClientsCard(
                    Modifier.fillMaxWidth(),
                    clients = ui.share.clients,
                    expanded = clientsExpanded,
                    onToggle = { clientsExpanded = !clientsExpanded },
                )
                DomainsCard(
                    Modifier.fillMaxWidth(),
                    domains = ui.share.topDomains,
                    ruleOf = { host -> ruleVersion.let { viewModel.decideHost(host) }.takeIf { it != RuleAction.PROXY } },
                    onOpenDomains = onOpenDomains,
                    onEdit = { editHost = it },
                )
            }
        }

        // —— 已拦截（广告/拒绝规则命中；绿色计数 + 域名 chip 流）——
        if (section != MonitorSection.HEALTH) item {
            BentoCard(tier = CardTier.Primary) {
                if (ui.share.blockedTotal <= 0L) {
                    CardHeader(
                        title = stringResource(R.string.monitor_blocked),
                        icon = painterResource(R.drawable.ic_b_shield_check),
                        iconBg = StatusColors.goodContainer(),
                        iconTint = StatusColors.good(),
                    )
                    Text(
                        stringResource(R.string.monitor_no_blocked),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_b_shield_check),
                            contentDescription = null,
                            tint = StatusColors.good(),
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            stringResource(R.string.protection_blocked_count, ui.share.blockedTotal),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            color = StatusColors.good(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        StatLabel(stringResource(R.string.monitor_blocked_tag))
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        ui.share.topBlockedDomains.take(8).forEach { b -> BlockedChip(b.host, b.count) }
                    }
                }
            }
        }

        // —— 流量统计入口（整卡而非第三个 pill）：History/Error logs 是「去看列表」，
        //    而流量统计本身就有一个值得当场读到的数字——不点进去也已回答「今天用了多少」。——
        if (section == MonitorSection.ALL) item { TrafficStatsEntryCard(trafficSummary, onOpenTrafficStats) }

        // —— 双入口卡：历史 IP | 错误日志 ——
        // 排在流量统计**之后**：这两个是排障入口，用户找它们时是带着目的来的，
        // 而流量统计是随手一瞥的数字。夹在中间反而让「错误日志在哪」变得难找。
        // 设备段(DEVICES)也渲染:历史 IP 归设备,错误日志顺带可达(二合一卡不拆)。
        if (section != MonitorSection.HEALTH) item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EntryCard(
                    Modifier.weight(1f),
                    icon = painterResource(R.drawable.ic_b_history),
                    title = stringResource(R.string.monitor_open_history),
                    count = pluralStringResource(R.plurals.count_entries, ui.history.size, ui.history.size),
                    onClick = onOpenHistory,
                )
                EntryCard(
                    Modifier.weight(1f),
                    icon = painterResource(R.drawable.ic_b_doc),
                    title = stringResource(R.string.error_logs),
                    count = null,
                    onClick = onOpenLogs,
                )
            }
        }
    }
}

/** 流量统计入口卡：今日总量 + 较昨日涨跌 + 近 7 日迷你趋势条（最后一根＝今日，实色）。 */
@Composable
private fun TrafficStatsEntryCard(
    summary: com.mzstd.hxmyproxy.core.stats.TrafficSummary,
    onClick: () -> Unit,
) {
    BentoCard(tier = CardTier.Primary, onClick = onClick) {
        CardHeader(
            title = stringResource(R.string.traffic_stats_title),
            icon = painterResource(R.drawable.ic_b_bars),
            trailing = {
                Icon(
                    painterResource(R.drawable.ic_b_chevron_right),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(14.dp),
                )
            },
        )
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val text = formatBytes(summary.todayBytes)
            val sep = text.lastIndexOf(' ')
            val num = if (sep > 0) text.substring(0, sep) else text
            val unit = if (sep > 0) text.substring(sep + 1) else ""
            BigStat(num, "$unit · ${stringResource(R.string.traffic_period_today)}", valueSize = 30)
            Spacer(Modifier.weight(1f))
            deltaText(summary.todayBytes, summary.yesterdayBytes)?.let {
                Text(
                    stringResource(R.string.traffic_vs_yesterday, it),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                    maxLines = 1,
                )
            }
        }
        if (summary.last7Days.any { it > 0 }) {
            BarChart(
                buckets = summary.last7Days.mapIndexed { i, v -> com.mzstd.hxmyproxy.core.stats.TrafficBucket(i.toLong(), v) },
                highlightKey = (summary.last7Days.size - 1).toLong(),
                color = MaterialTheme.colorScheme.primary,
                height = 34.dp,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatLabel(stringResource(R.string.traffic_axis_week_ago))
                StatLabel(stringResource(R.string.traffic_period_today))
            }
        } else {
            Text(
                stringResource(if (summary.hasAnyData) R.string.traffic_bucket_empty else R.string.traffic_empty_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 「较昨日」涨跌；昨日为 0 时返回 null——除零得不出百分比，硬写个「+∞%」只会误导。 */
private fun deltaText(today: Long, yesterday: Long): String? {
    if (yesterday <= 0L) return null
    val pct = ((today - yesterday) * 100.0 / yesterday).toInt()
    return if (pct >= 0) "+$pct%" else "$pct%"
}

/** 速率半列：方向角标小方块 + 标签、BigStat 大数字+单位、60s sparkline。 */
@Composable
private fun RateColumn(
    modifier: Modifier,
    icon: Painter,
    chipBg: Color,
    chipTint: Color,
    label: String,
    rateBps: Long,
    sparkColor: Color,
    samples: List<Float>,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier.size(20.dp).clip(RoundedCornerShape(7.dp)).background(chipBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = chipTint, modifier = Modifier.size(12.dp))
            }
            StatLabel(label)
        }
        val (num, unit) = splitRate(formatRate(rateBps))
        BigStat(num, unit.ifEmpty { null }, valueSize = 26)
        Sparkline(samples, color = sparkColor)
    }
}

/** strip 分隔点。 */
@Composable
private fun StripSep() {
    Text(
        "·",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
    )
}

/**
 * 诊断异常置顶横幅（Error 级，一次性形态）：叉圆点 + 「项目 · 状态」+ 可选后果副文案 +
 * 右侧「去开启 ›」。整条可点 → 修复引导弹窗（为什么需要 + 直跳系统页面）。
 */
@Composable
private fun DiagAlert(title: String, sub: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.errorContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        AvatarCircle(22.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.14f)) {
            Icon(
                painterResource(R.drawable.ic_b_close),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(11.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (sub != null) {
                Text(
                    sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f),
                )
            }
        }
        Text(
            stringResource(R.string.diag_go_enable),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
        )
        Icon(
            painterResource(R.drawable.ic_b_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(12.dp),
        )
    }
}

/** 诊断小格（2 列网格用）：状态圆点(✓绿/!黄/✗红/—中性) + 名称 + 状态字。 */
@Composable
private fun DiagCell(
    modifier: Modifier,
    icon: Painter?,
    tint: Color,
    name: String,
    state: String,
    stateColor: Color,
) {
    Row(
        modifier.padding(horizontal = 4.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AvatarCircle(20.dp, tint.copy(alpha = 0.16f)) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(10.dp))
            } else {
                Text("—", style = MaterialTheme.typography.labelSmall, color = tint)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state, style = MaterialTheme.typography.labelSmall, color = stateColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 客户端卡：IP 徽章(末段) + 等宽 IP + RatioBar 占比(按下行流量归一) + 上下行流量。 */
@Composable
private fun ClientsCard(
    modifier: Modifier,
    clients: List<ClientSession>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    BentoCard(modifier, tier = CardTier.Default, contentPadding = 12.dp, spacing = 8.dp) {
        CardHeader(stringResource(R.string.monitor_clients), icon = painterResource(R.drawable.ic_b_phone))
        if (clients.isEmpty()) {
            Text(
                stringResource(R.string.monitor_no_clients),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val shown = if (expanded) clients else clients.take(4)
            val maxDown = shown.maxOf { it.downloadBytes }.coerceAtLeast(1L)
            shown.forEach { c -> ClientRow(c, maxDown) }
            if (clients.size > 4) {
                ExpandCollapseButton(expanded, clients.size, onToggle)
            }
        }
    }
}

/** 客户端行：l1=IP 末段徽章+等宽 IP；l2=下行占比条+「↓x ↑y」。 */
@Composable
private fun ClientRow(c: ClientSession, maxDown: Long) {
    val ipStr = c.clientIp.hostAddress ?: "?"
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                ipStr.substringAfterLast('.').ifEmpty { "?" },
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
            Text(
                ipStr,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RatioBar(
                fraction = c.downloadBytes / maxDown.toFloat(),
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
                height = 4.dp,
            )
            Text(
                "↓${fmtBytes(c.downloadBytes)} ↑${fmtBytes(c.uploadBytes)}",
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** 目标域名卡：协议色圆点 + 域名 + ProtoBadge + 直连标注 + 流量 + RatioBar；行点击弹三态救济弹窗。 */
@Composable
private fun DomainsCard(
    modifier: Modifier,
    domains: List<DomainTraffic>,
    ruleOf: (String) -> RuleAction?,
    onOpenDomains: () -> Unit,
    onEdit: (String) -> Unit,
) {
    BentoCard(modifier, tier = CardTier.Primary, contentPadding = 12.dp, spacing = 8.dp) {
        CardHeader(stringResource(R.string.monitor_top_domains), icon = painterResource(R.drawable.ic_b_globe))
        if (domains.isEmpty()) {
            Text(
                stringResource(R.string.monitor_no_domains),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val sorted = domains.sortedByDescending { it.lastSeenAtEpochMs }
            val shown = sorted.take(5)
            val maxBytes = shown.maxOf { it.uploadBytes + it.downloadBytes }.coerceAtLeast(1L)
            shown.forEach { d -> DomainRow(d, maxBytes, ruleOf(d.host), onEdit) }
            if (sorted.size > 5) {
                // 「查看全部」→ 跳转独立详情页(与 rules manage all / protection view list 一致),而非页内展开。
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClick = onOpenDomains)
                        .padding(vertical = 6.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.monitor_view_all_domains),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    CountBadge(sorted.size.toString())
                    Spacer(Modifier.weight(1f))
                    Icon(
                        painterResource(R.drawable.ic_b_chevron_right),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/** 目标域名行。"(其他)" 聚合桶：中性色、无徽章、不可点（不是真实 host）。 */
@Composable
internal fun DomainRow(d: DomainTraffic, maxBytes: Long, override: RuleAction?, onEdit: (String) -> Unit) {
    val others = d.host == TrafficAccounting.OTHERS
    // "(其他)" 聚合桶是运行时常量,显示时本地化(英文界面不冒中文,双语检查原则)。
    val hostLabel = if (others) stringResource(R.string.monitor_others) else d.host
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val dotColor = if (others) neutral.copy(alpha = 0.7f) else protoBadgeColors(d.protocol).second
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (!others) {
                    Modifier.clip(MaterialTheme.shapes.small).clickable { onEdit(d.host) }
                } else Modifier,
            ),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            StatusDot(dotColor, 8.dp)
            Text(
                hostLabel,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = if (others) neutral else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!others) {
                ProtoBadge(d.protocol)
                // 当前规则的**实时完整判定**(含内置组,按引擎优先级):直连/拦截各一个标;走代理不标。
                // 规则一变即重判(见 MainViewModel.ruleVersion),修「移除规则后还显示直连」的 stale 缓存。
                when (override) {
                    RuleAction.DIRECT -> Text(stringResource(R.string.route_direct), style = MaterialTheme.typography.labelSmall, color = neutral)
                    RuleAction.REJECT -> Text(stringResource(R.string.route_reject), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    else -> {}
                }
            }
            Text(
                fmtBytes(d.uploadBytes + d.downloadBytes),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                color = if (others) neutral else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (!others) {
                Icon(
                    painterResource(R.drawable.ic_tune),
                    contentDescription = stringResource(R.string.override_adjust),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        RatioBar(
            fraction = (d.uploadBytes + d.downloadBytes) / maxBytes.toFloat(),
            color = if (others) neutral.copy(alpha = 0.55f) else MaterialTheme.colorScheme.primary,
            height = 4.dp,
        )
    }
}

/** 已拦截域名 chip：域名 + ×次数（红调）。 */
@Composable
private fun BlockedChip(host: String, count: Long) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            host,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 1,
        )
        Text(
            "×$count",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
        )
    }
}

/** 底部入口卡（历史 IP / 错误日志）：图标 + 标题 + 可选条数 + 右尖角，整卡可点。 */
@Composable
private fun EntryCard(
    modifier: Modifier,
    icon: Painter,
    title: String,
    count: String?,
    onClick: () -> Unit,
) {
    BentoCard(modifier, tier = CardTier.Sunken, onClick = onClick, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text(
                title,
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (count != null) {
                Text(
                    count,
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Icon(
                painterResource(R.drawable.ic_b_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/** 链路质量四格(诊断段):丢包 / 往返 p50 / p95 / Wi-Fi 信号。无样本显示 —。 */
@Composable
private fun LinkQualityCard(ui: MainUiState) {
    val link = ui.share.linkStats
    val hasLink = link.samples > 0
    val hasSignal = ui.share.signalLevel >= 0
    BentoCard(tier = CardTier.Default, contentPadding = 14.dp, spacing = 8.dp) {
        CardHeader(
            title = stringResource(R.string.link_quality_title),
            icon = painterResource(R.drawable.ic_b_activity),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LinkCell(stringResource(R.string.link_loss), if (hasLink) "${link.lossPct}%" else "—", Modifier.weight(1f))
            LinkCell(stringResource(R.string.link_p50), if (hasLink) "${link.p50Ms}ms" else "—", Modifier.weight(1f))
            LinkCell(stringResource(R.string.link_p95), if (hasLink) "${link.p95Ms}ms" else "—", Modifier.weight(1f))
            LinkCell(stringResource(R.string.link_signal), if (hasSignal) "${ui.share.signalDbm}dBm" else "—", Modifier.weight(1f))
        }
        Text(
            stringResource(R.string.link_quality_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LinkCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            value,
            style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
            maxLines = 1,
        )
    }
}

/** 实时日志预览(诊断段):最近 3 条(快照,进入段时加载),点卡或「查看全部」进日志详情。 */
@Composable
private fun LogPreviewCard(onOpenLogs: () -> Unit) {
    val logsVm: com.mzstd.hxmyproxy.ui.LogsViewModel = hiltViewModel()
    val entries by logsVm.entries.collectAsStateWithLifecycle()
    BentoCard(tier = CardTier.Sunken, contentPadding = 14.dp, spacing = 6.dp, onClick = onOpenLogs) {
        CardHeader(
            title = stringResource(R.string.log_preview_title),
            icon = painterResource(R.drawable.ic_b_bars),
            trailing = {
                Text(
                    stringResource(R.string.monitor_view_all_domains),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
        )
        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.monitor_no_logs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            entries.take(3).forEach { e ->
                Text(
                    "${e.timestamp} ${e.level}/${e.tag}: ${e.message}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = when (e.level) {
                        "E" -> MaterialTheme.colorScheme.error
                        "W" -> StatusColors.warn()
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
