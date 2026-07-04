package com.mzstd.hxmyproxy.ui.dashboard

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.model.InterfaceType
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.service.ProxyForegroundService
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.components.ExpandCollapseButton
import com.mzstd.hxmyproxy.ui.components.LabeledSwitchRow
import com.mzstd.hxmyproxy.ui.components.QrImage
import com.mzstd.hxmyproxy.ui.components.cardContainerColor
import com.mzstd.hxmyproxy.ui.theme.LocalDarkTheme
import com.mzstd.hxmyproxy.ui.theme.StatusColors

/** 接口类型 → 本地化标签（随 InterfacesScreen 删除从该页迁来）。 */
private fun InterfaceType.labelRes(): Int = when (this) {
    InterfaceType.WIFI -> R.string.iface_wifi
    InterfaceType.HOTSPOT -> R.string.iface_hotspot
    InterfaceType.USB -> R.string.iface_usb
    InterfaceType.BLUETOOTH -> R.string.iface_bluetooth
    InterfaceType.ETHERNET -> R.string.iface_ethernet
    InterfaceType.UNKNOWN -> R.string.iface_unknown
}

/**
 * 主页（hero 重构）：状态大字 + 地址卡钉在最前 + 统计小格，
 * 「开始/停止」主按钮**固定在底部不随内容滚动**（全 app 最高频操作不该被滚走）。
 * 主按钮用「形状+颜色」双编码状态（学 Pixel VPN）：停止=蓝填充全圆 pill，
 * 运行=错误容器色、圆角收紧；切换以弹簧动效过渡（Expressive 方向）。
 */
@Composable
fun DashboardScreen(
    ui: MainUiState,
    viewModel: com.mzstd.hxmyproxy.ui.MainViewModel,
    onOpenProtection: () -> Unit = {},
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
) {
    val context = LocalContext.current
    val share = ui.share

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { ProxyForegroundService.start(context) }

    val onStart = {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
        }
        if (perms.isEmpty()) ProxyForegroundService.start(context)
        else permLauncher.launch(perms.toTypedArray())
    }
    val conf = androidx.compose.ui.platform.LocalConfiguration.current
    val landscape = conf.screenWidthDp > conf.screenHeightDp

    if (landscape) {
        // 横屏：竖向长条主按钮贴在导航 rail 右侧、内容列左侧——填满高度不留空,
        // 且不在屏幕边缘/内容滚动区,避免误触(用户设计)。
        Row(Modifier.fillMaxSize().consumeWindowInsets(contentPadding)) {
            VerticalStartStopButton(
                ui, onStart,
                Modifier
                    .padding(contentPadding)
                    .padding(start = 4.dp, top = 12.dp, bottom = 12.dp)
                    .fillMaxHeight(),
            )
            Column(
                // 沉浸式:inset padding 放 verticalScroll 之后,内容可滚入系统栏后方。
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HeroDuo(ui, onOpenProtection)
                EntryCard(ui)
                PortBindErrorCard(ui)
                if (share.running) StatRow(ui)
                InterfacesCard(ui, viewModel)
            }
        }
    } else {
        // 竖屏：悬浮按钮布局——滚动内容全屏（底部预留按钮高度），按钮悬浮其上、
        // 背后「透明→surface」柔和渐变,内容从按钮下穿过时逐渐淡出。
        val surface = MaterialTheme.colorScheme.surface
        Box(Modifier.fillMaxSize().consumeWindowInsets(contentPadding)) {
            Column(
                // 沉浸式:inset padding 放 verticalScroll 之后,内容滚动时穿入状态栏后方。
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HeroDuo(ui, onOpenProtection)
                EntryCard(ui)
                PortBindErrorCard(ui)
                if (share.running) StatRow(ui)
                InterfacesCard(ui, viewModel)
                // 预留悬浮按钮区：最后一张卡能完整滚出、不被按钮遮挡。
                Spacer(Modifier.height(76.dp))
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = contentPadding.calculateBottomPadding())
                    .background(
                        Brush.verticalGradient(
                            0f to surface.copy(alpha = 0f),
                            0.55f to surface,
                        ),
                    ),
            ) {
                StartStopButton(ui, onStart)
            }
        }
    }
}

/**
 * Hero 第一排：「共享 | 防护」双状态立体 tile（替代原大字标题——第一排即状态，用户设计）。
 * 模式叙事/连接数/速率并入左（共享）tile 小字，不再独立成行（用户反馈）；右 tile 小字=「有没有 VPN 都生效」。
 */
@Composable
private fun HeroDuo(ui: MainUiState, onOpenProtection: () -> Unit) {
    val share = ui.share
    val adBlockOn = com.mzstd.hxmyproxy.core.rules.RuleCatalog.adGroups.any { it.id in ui.settings.enabledRuleGroups }
    val shareSubs = buildList {
        add(
            if (share.vpn.detected) stringResource(R.string.mode_vpn_egress)
            else stringResource(R.string.mode_gateway_plain),
        )
        add(stringResource(R.string.active_conns, share.activeConnections))
        if (share.running) {
            add(
                stringResource(
                    R.string.rate_line,
                    com.mzstd.hxmyproxy.ui.formatRate(share.downloadRateBps),
                    com.mzstd.hxmyproxy.ui.formatRate(share.uploadRateBps),
                ),
            )
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp).height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusTile(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            on = share.running,
            label = stringResource(R.string.status_share),
            value = stringResource(if (share.running) R.string.status_running else R.string.status_stopped),
            subs = shareSubs,
        )
        StatusTile(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            on = adBlockOn,
            label = stringResource(R.string.protection_title),
            value = if (adBlockOn) stringResource(R.string.protect_blocked, share.blockedTotal)
            else stringResource(R.string.protect_off),
            subs = listOf(stringResource(R.string.protect_works_always)),
            onClick = onOpenProtection,
        )
    }
}

/** 入口地址卡（学 Tailscale 钉在最前）：主地址等宽大字 + 一键复制；PAC 常显；停止态给引导文案。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EntryCard(ui: MainUiState) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val share = ui.share
    var entriesExpanded by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }

    val primaryEntry = share.recommendedEntries.firstOrNull { it.protocol == ProxyProtocol.HTTP }
        ?: share.recommendedEntries.firstOrNull()

    ElevatedCard(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = cardContainerColor()),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.entry_config), style = MaterialTheme.typography.titleMedium)
            if (primaryEntry == null) {
                // 两种空态分开引导：没选接口 → 提示选接口；选了但没开始 → 提示开始共享（原来混为一谈误导用户）。
                Text(
                    stringResource(
                        if (ui.settings.selectedInterfaceIds.isEmpty()) R.string.no_entry
                        else R.string.start_to_show_entries,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val allEntries = share.recommendedEntries
                // 折叠态除首选(HTTP)外,常显 PAC 那条——完整 http://ip:port/proxy.pac 是系统「自动配置」要的。
                val pacEntry = allEntries.firstOrNull { it.protocol == ProxyProtocol.PAC && it != primaryEntry }
                val collapsedEntries = listOfNotNull(primaryEntry, pacEntry)
                val shownEntries = if (entriesExpanded) allEntries else collapsedEntries
                shownEntries.forEach { e ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                e.protocol.name,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            // 等宽字体：地址是要抄写/核对的内容，等宽更易读、更「技术可信」。
                            Text(
                                e.displayEndpoint,
                                style = MaterialTheme.typography.bodyLarge,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(e.copyValue))
                            Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                        }) { Text(stringResource(R.string.copy)) }
                    }
                }
                // PAC 已开但 HTTP/SOCKS 全关 → 生成的 pac 退化成 return "DIRECT"(能拉取但不代理),明确告警。
                if (ui.settings.pacEnabled && !ui.settings.httpEnabled && !ui.settings.socksEnabled) {
                    Text(
                        stringResource(R.string.pac_needs_backend),
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.warn(),
                    )
                }
                // 仅当有被折叠隐藏的入口才显示展开按钮（避免已全显时出现无效「展开」）。
                if (allEntries.size > collapsedEntries.size) {
                    ExpandCollapseButton(entriesExpanded, allEntries.size) { entriesExpanded = !entriesExpanded }
                }
                OutlinedButton(onClick = { showQr = true }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                    Text(stringResource(R.string.qr_setup))
                }
            }
        }
    }

    // 扫码配置底部弹层：一开即完整展开(skipPartiallyExpanded,不遮内容);文字全部在上、
    // 二维码垫底;白底 QR 容器保深色下扫码可靠;文案精简 + 「点击空白处关闭」提示。
    if (showQr) {
        val setupUrl = primaryEntry?.let { "http://${it.host}:${ui.settings.pacPort}/" }
        val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val conf = androidx.compose.ui.platform.LocalConfiguration.current
        val landscape = conf.screenWidthDp > conf.screenHeightDp
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { showQr = false },
            sheetState = sheetState,
        ) {
            if (!ui.settings.pacEnabled || setupUrl == null) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.qr_need_pac), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (landscape) {
                // 横屏：左文右码并排——竖排在横屏高度里放不下,二维码会被底边截断(用户实测「失效」)。
                Row(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(stringResource(R.string.qr_setup), style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(R.string.qr_sheet_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(setupUrl, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(setupUrl))
                                Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                            }) { Text(stringResource(R.string.copy)) }
                        }
                        Text(
                            stringResource(R.string.qr_dismiss_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Surface(shape = MaterialTheme.shapes.large, color = androidx.compose.ui.graphics.Color.White) {
                        Column(Modifier.padding(14.dp)) { QrImage(setupUrl, sizeDp = 180) }
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.qr_setup), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.qr_sheet_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            setupUrl,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(setupUrl))
                            Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                        }) { Text(stringResource(R.string.copy)) }
                    }
                    Text(
                        stringResource(R.string.qr_dismiss_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = androidx.compose.ui.graphics.Color.White,
                    ) {
                        Column(Modifier.padding(18.dp)) { QrImage(setupUrl, sizeDp = 216) }
                    }
                }
            }
        }
    }
}

/** 端口占用告警：某协议 bind 失败（端口被占）→ 该代理未启动，明确提示用户换端口。 */
@Composable
private fun PortBindErrorCard(ui: MainUiState) {
    val share = ui.share
    if (share.portBindErrors.isEmpty()) return
    val portOf: (ProxyProtocol) -> Int = {
        when (it) {
            ProxyProtocol.HTTP -> ui.settings.httpPort
            ProxyProtocol.SOCKS5 -> ui.settings.socksPort
            ProxyProtocol.PAC -> ui.settings.pacPort
        }
    }
    val portList = share.portBindErrors.sortedBy { it.name }
        .joinToString("、") { "${it.name} :${portOf(it)}" }
    ElevatedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            stringResource(R.string.port_bind_failed_banner, portList),
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/** 统计小格 ×3：连接 / 信号 / 累计流量。大数字（tnum 等宽，刷新不抖），底 surfaceContainer 分层。 */
@Composable
private fun StatRow(ui: MainUiState) {
    val share = ui.share
    // height(IntrinsicSize.Min) + 每格 fillMaxHeight → 三格等高（取最高者）；label 换行也不再高低不齐。
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatCell(Modifier.weight(1f).fillMaxHeight(), "${share.activeConnections}", stringResource(R.string.stat_conns))
        if (share.signalLevel >= 0) {
            StatCell(Modifier.weight(1f).fillMaxHeight(), "${share.signalDbm}", stringResource(R.string.stat_signal) + " dBm")
        }
        StatCell(Modifier.weight(1f).fillMaxHeight(), com.mzstd.hxmyproxy.ui.formatBytes(share.totalBytes), stringResource(R.string.stat_traffic))
    }
}

@Composable
private fun StatCell(modifier: Modifier, value: String, label: String) {
    com.mzstd.hxmyproxy.ui.components.TileCard(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        spacing = 2.dp,
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 可分享接口卡：每个接口一个开关，默认 2 行、超出折叠（配置项，层级下沉）。 */
@Composable
private fun InterfacesCard(ui: MainUiState, viewModel: com.mzstd.hxmyproxy.ui.MainViewModel) {
    val share = ui.share
    var interfacesExpanded by remember { mutableStateOf(false) }
    val interfaces = share.interfaces
    ElevatedCard(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = cardContainerColor()),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.shareable_interfaces), style = MaterialTheme.typography.titleMedium)
            if (interfaces.isEmpty()) {
                // 走蜂窝上网时没有局域网可共享,引导用户开个人热点;否则给通用「无接口」提示。
                Text(
                    stringResource(
                        if (share.needsHotspotHint) R.string.hint_enable_hotspot
                        else R.string.no_interfaces,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val shownIfaces = if (interfacesExpanded) interfaces else interfaces.take(2)
                shownIfaces.forEach { iface ->
                    LabeledSwitchRow(
                        title = "${stringResource(iface.type.labelRes())} · ${iface.name}",
                        subtitle = iface.cidr,
                        checked = iface.id in ui.settings.selectedInterfaceIds,
                        onCheckedChange = { viewModel.toggleInterface(iface.id, it) },
                    )
                }
                if (interfaces.size > 2) {
                    ExpandCollapseButton(interfacesExpanded, interfaces.size) { interfacesExpanded = !interfacesExpanded }
                }
            }
        }
    }
}

/** 状态 tile：点(红绿) + 标签 + 值。统一走 [com.mzstd.hxmyproxy.ui.components.TileCard] 立体外壳。 */
@Composable
private fun StatusTile(
    modifier: Modifier,
    on: Boolean,
    label: String,
    value: String,
    subs: List<String> = emptyList(),
    onClick: (() -> Unit)? = null,
) {
    com.mzstd.hxmyproxy.ui.components.TileCard(modifier, onClick = onClick, spacing = 6.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(Modifier.size(12.dp), shape = CircleShape, color = if (on) StatusColors.good() else StatusColors.stoppedDot()) {}
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
        Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        subs.forEach {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 横屏竖向主按钮：窄长条填满高度，贴导航 rail 右侧不占内容区、不在滚动区内避免误触。
 * 内容用 ▶/■ 图标而非文字——逐字竖排只适合中文，英文 "Stop sharing" 逐字母竖排不可读
 * （双语检查原则，用户决策改图标）。颜色状态与水平版一致（蓝=开始 ↔ 浅红=停止,弹簧过渡）。
 */
@Composable
private fun VerticalStartStopButton(ui: MainUiState, onStart: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val running = ui.share.running
    val (bg, fg) = startStopColors(running)
    val container by animateColorAsState(targetValue = bg, label = "vBtnColor")
    val content by animateColorAsState(targetValue = fg, label = "vBtnContent")
    Button(
        onClick = { if (running) ProxyForegroundService.stop(context) else onStart() },
        modifier = modifier.width(64.dp),
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
    ) {
        androidx.compose.material3.Icon(
            androidx.compose.ui.res.painterResource(if (running) R.drawable.ic_stop else R.drawable.ic_play),
            contentDescription = stringResource(if (running) R.string.stop_sharing else R.string.start_sharing),
            modifier = Modifier.size(28.dp),
        )
    }
}

/**
 * 固定底部主按钮：形状+颜色双编码（学 Pixel VPN）——停止=蓝填充全圆 pill（邀请开始），
 * 运行=错误容器色+圆角收紧（读作「停止」）；弹簧动效过渡（Expressive 方向，稳定 API 实现）。
 */
@Composable
private fun StartStopButton(ui: MainUiState, onStart: () -> Unit) {
    val context = LocalContext.current
    val running = ui.share.running
    // 两态都是「亮/浅底 + 深字」青春 tonal（对比 7:1）：开始=蓝、停止=红。
    // 浅色用 container(浅蓝/浅红底);深色用亮实色 primary/error(亮蓝/亮红底)——深色下 container 会变闷深块,故切亮实色。
    val (bg, fg) = startStopColors(running)
    val container by animateColorAsState(targetValue = bg, label = "btnColor")
    val content by animateColorAsState(targetValue = fg, label = "btnContent")
    Button(
        onClick = { if (running) ProxyForegroundService.stop(context) else onStart() },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .height(56.dp),
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        Text(
            stringResource(if (running) R.string.stop_sharing else R.string.start_sharing),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/**
 * 启停按钮配色：两态都「亮/浅底 + 深字」。浅色用 container（浅蓝/浅红底），
 * 深色用亮实色 primary/error（亮蓝/亮红底）——深色下 container 是 tone30 深块,发闷发脏,故切亮实色。
 */
@Composable
private fun startStopColors(running: Boolean): Pair<Color, Color> {
    val dark = LocalDarkTheme.current
    val cs = MaterialTheme.colorScheme
    return when {
        running && dark -> cs.error to cs.onError
        running -> cs.errorContainer to cs.onErrorContainer
        dark -> cs.primary to cs.onPrimary
        else -> cs.primaryContainer to cs.onPrimaryContainer
    }
}
