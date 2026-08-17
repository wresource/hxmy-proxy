package com.mzstd.hxmyproxy.ui.dashboard

import android.Manifest
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.model.DirectEgressChoice
import com.mzstd.hxmyproxy.core.model.EgressNetworkChoice
import com.mzstd.hxmyproxy.core.model.LinkStats
import com.mzstd.hxmyproxy.core.model.InterfaceType
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.data.repository.ManualResetPhase
import com.mzstd.hxmyproxy.service.ProxyForegroundService
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.components.BannerLevel
import com.mzstd.hxmyproxy.ui.components.BentoCard
import com.mzstd.hxmyproxy.ui.components.BigStat
import com.mzstd.hxmyproxy.ui.components.IconDisc
import com.mzstd.hxmyproxy.ui.components.CardTier
import com.mzstd.hxmyproxy.ui.components.CountBadge
import com.mzstd.hxmyproxy.ui.components.ExpandCollapseButton
import com.mzstd.hxmyproxy.ui.components.PageHeader
import com.mzstd.hxmyproxy.ui.components.ProtoBadge
import com.mzstd.hxmyproxy.ui.components.QrImage
import com.mzstd.hxmyproxy.ui.components.Sparkline
import com.mzstd.hxmyproxy.ui.components.StatLabel
import com.mzstd.hxmyproxy.ui.components.StatusDot
import com.mzstd.hxmyproxy.ui.components.WarnBanner
import com.mzstd.hxmyproxy.ui.components.stdFilterChipColors
import com.mzstd.hxmyproxy.ui.components.stdSwitchColors
import com.mzstd.hxmyproxy.ui.theme.LocalDarkTheme
import com.mzstd.hxmyproxy.ui.theme.StatusColors
import java.util.Locale

/** 接口类型 → 本地化标签（随 InterfacesScreen 删除从该页迁来）。 */
private fun InterfaceType.labelRes(): Int = when (this) {
    InterfaceType.WIFI -> R.string.iface_wifi
    InterfaceType.HOTSPOT -> R.string.iface_hotspot
    InterfaceType.USB -> R.string.iface_usb
    InterfaceType.BLUETOOTH -> R.string.iface_bluetooth
    InterfaceType.ETHERNET -> R.string.iface_ethernet
    InterfaceType.UNKNOWN -> R.string.iface_unknown
}

/** 接口类型 → Bento 线性图标（以太网与 USB 同属有线入口共用一枚）。 */
private fun InterfaceType.iconRes(): Int = when (this) {
    InterfaceType.WIFI -> R.drawable.ic_b_wifi
    InterfaceType.HOTSPOT -> R.drawable.ic_b_hotspot
    InterfaceType.USB, InterfaceType.ETHERNET -> R.drawable.ic_b_usb
    InterfaceType.BLUETOOTH -> R.drawable.ic_b_devices
    InterfaceType.UNKNOWN -> R.drawable.ic_b_globe
}

/** 共享 hero 三态：共享中 / 已停止 / 未就绪（服务在跑但谁也连不进——空转）。 */
private enum class HeroState { Running, Stopped, NotReady }

/**
 * hero 态判定（与旧 StatusTile 同一套规则原样保留）：
 * 「共享中」是效果承诺而非进程状态——只有真的具备共享能力才配这三个字；
 * 运行中但零网段 / 零协议 / 端口全没起 → 未就绪 + 对应黄警示文案。
 */
private fun heroStateOf(ui: MainUiState): Pair<HeroState, Int?> {
    val share = ui.share
    val noProto = !ui.settings.httpEnabled && !ui.settings.socksEnabled && !ui.settings.pacEnabled
    val d = share.diagnostics
    val anyPortUp = d.httpPortUp || d.socksPortUp || d.pacPortUp
    val warnRes = when {
        !share.running -> null
        share.admissionEmpty -> R.string.warn_no_iface
        noProto -> R.string.warn_no_proto
        !anyPortUp -> R.string.warn_no_port_up
        else -> null
    }
    val state = when {
        !share.running -> HeroState.Stopped
        warnRes != null -> HeroState.NotReady
        else -> HeroState.Running
    }
    return state to warnRes
}

/**
 * 主页（Bento 重设计，规格=images/html/01-dashboard.html）：
 * 行1 共享 hero（状态色渐变底 + display 级状态词 + 88dp 启停圆钮）+ 防护竖卡（2:1）；
 * 行2 速率宽卡 + 统计竖条（仅共享中）；行3 入口配置；行4 可分享入口 + 出口网络。
 * 启停按钮进 hero 圆钮（竖屏不再悬浮底部）；横屏保持原结构（rail 侧竖长条按钮 + 滚动列）。
 */
@Composable
fun DashboardScreen(
    ui: MainUiState,
    viewModel: MainViewModel,
    onOpenProtection: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val context = LocalContext.current

    // pending-result 防重投：ActivityResultRegistry 会把未消费的权限结果存进 savedInstanceState，
    // 进程死亡后重开 app、Compose 一 register 就原样重投——旧回调无条件 start()，表现为
    // 「关掉 app 再打开，共享自己开起来了」（用户 7-26 现场观察的「自动续上」路径之一）。
    // 用 remember（非 Saveable）做进程内标志：重投递到达时它必为 false，直接忽略。
    // 代价：权限框开着时 Activity 重建（旋转/折叠）需再点一次开始——可接受。
    var startRequested by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (startRequested) {
            startRequested = false
            ProxyForegroundService.start(context)
        }
    }

    val onStart = {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
        }
        if (perms.isEmpty()) {
            ProxyForegroundService.start(context)
        } else {
            startRequested = true
            permLauncher.launch(perms.toTypedArray())
        }
    }
    // 手动刷新服务的结果呈现：轻结果用 Toast；「仍不可达」用对话框引导拉系统网络面板
    //（app 无权开关 WiFi——Android 10 起 setWifiEnabled 已失效,面板是 app 能做的极限）。
    val resetPhase by viewModel.manualResetState.collectAsStateWithLifecycle()
    LaunchedEffect(resetPhase) {
        when (resetPhase) {
            ManualResetPhase.DONE_OK -> {
                Toast.makeText(context, R.string.refresh_done_ok, Toast.LENGTH_SHORT).show()
                viewModel.ackManualReset()
            }
            ManualResetPhase.DONE_NO_CLIENT -> {
                Toast.makeText(context, R.string.refresh_done, Toast.LENGTH_SHORT).show()
                viewModel.ackManualReset()
            }
            else -> {}
        }
    }
    if (resetPhase == ManualResetPhase.DONE_LINK_DEAD) {
        AlertDialog(
            onDismissRequest = { viewModel.ackManualReset() },
            title = { Text(stringResource(R.string.refresh_dead_title)) },
            text = { Text(stringResource(R.string.refresh_dead_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.ackManualReset()
                    runCatching {
                        context.startActivity(Intent(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
                    }
                }) { Text(stringResource(R.string.refresh_open_panel)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.ackManualReset() }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    val conf = androidx.compose.ui.platform.LocalConfiguration.current
    val landscape = conf.screenWidthDp > conf.screenHeightDp

    if (landscape) {
        // 横屏：竖向长条主按钮贴在导航 rail 右侧、内容列左侧——填满高度不留空,
        // 且不在屏幕边缘/内容滚动区,避免误触(用户设计)。hero 内不再重复放圆钮。
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
                DashboardContent(ui, viewModel, onStart, onOpenProtection, showHeroButton = false)
            }
        }
    } else {
        // 竖屏：单列滚动 bento 网格；启停圆钮在 hero 内（首屏即达,无需悬浮按钮）。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DashboardContent(ui, viewModel, onStart, onOpenProtection, showHeroButton = true)
        }
    }
}

/** 页面内容（竖/横屏共用）：页头 + 四行 bento。 */
@Composable
private fun DashboardContent(
    ui: MainUiState,
    viewModel: MainViewModel,
    onStart: () -> Unit,
    onOpenProtection: () -> Unit,
    showHeroButton: Boolean,
) {
    val (heroState, warnRes) = heroStateOf(ui)
    PageHeader(
        title = stringResource(R.string.app_name),
        icon = painterResource(R.drawable.ic_b_arrow_right),
        trailing = {
            Text(
                stringResource(R.string.dash_brand_mode),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
    HeroRow(ui, viewModel, heroState, warnRes, onStart, onOpenProtection, showHeroButton)
    // 行2 仅「真·共享中」显示（含端口部分被占的 porterror 态）；停止/未就绪整体移除。
    if (heroState == HeroState.Running) RateRow(ui, viewModel)
    PortBindBanner(ui)
    LinkLossBanner(ui)
    EntryCard(ui)
    FlowDiagram()
    InterfacesCard(ui, viewModel, Modifier.fillMaxWidth())
    EgressCard(ui, viewModel, Modifier.fillMaxWidth())
    DirectEgressCard(ui, viewModel, Modifier.fillMaxWidth())
}

// ══════════ 行1：共享 hero + 防护竖卡 ══════════

/** 行1 bento：共享 hero(2) + 防护竖卡(1)，等高对齐。 */
@Composable
private fun HeroRow(
    ui: MainUiState,
    viewModel: MainViewModel,
    state: HeroState,
    warnRes: Int?,
    onStart: () -> Unit,
    onOpenProtection: () -> Unit,
    showButton: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeroCard(ui, viewModel, state, warnRes, onStart, showButton, Modifier.weight(2f).fillMaxHeight())
        GuardCard(ui, onOpenProtection, Modifier.weight(1f).fillMaxHeight())
    }
}

/** hero 渐变底（一次性形态,色值来自 HTML 稿 --hero-*-bg,按明暗分）。 */
@Composable
private fun heroBrush(state: HeroState): Brush {
    val dark = LocalDarkTheme.current
    val (top, bottom) = when (state) {
        HeroState.Running -> if (dark) Color(0xFF22392A) to Color(0xFF182B1E) else Color(0xFFF0FAF1) to Color(0xFFD2EEDA)
        HeroState.Stopped -> if (dark) Color(0xFF2B2D34) to Color(0xFF23262E) else Color(0xFFFFFFFF) to Color(0xFFF3F7FF)
        HeroState.NotReady -> if (dark) Color(0xFF392B17) to Color(0xFF2B2013) else Color(0xFFFDF3E4) to Color(0xFFF9E7CF)
    }
    return Brush.verticalGradient(listOf(top, bottom))
}

/** hero display 状态词颜色（绿/中性/暖黄,与渐变底同族）。 */
@Composable
private fun heroWordColor(state: HeroState): Color {
    val dark = LocalDarkTheme.current
    return when (state) {
        HeroState.Running -> if (dark) Color(0xFFB9EDBD) else Color(0xFF0C4A16)
        HeroState.Stopped -> MaterialTheme.colorScheme.onSurface
        HeroState.NotReady -> if (dark) Color(0xFFFFCFA5) else Color(0xFF6F3600)
    }
}

/** 状态圆点 + 同色光晕（HTML .dot 的 box-shadow 光环形态）。 */
@Composable
private fun GlowDot(color: Color) {
    Box(
        Modifier.size(16.dp).clip(CircleShape).background(color.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        StatusDot(color, size = 8.dp)
    }
}

/**
 * 共享 hero：状态点 + display 级状态词 + 条件黄警示行 + 88dp 启停圆钮 + 模式脚注。
 * 渐变底盖满整卡：BentoCard 透明底 + 内层 Column 自绘 Brush（卡壳仍走组件库,Surface 裁剪圆角）。
 */
@Composable
private fun HeroCard(
    ui: MainUiState,
    viewModel: MainViewModel,
    state: HeroState,
    warnRes: Int?,
    onStart: () -> Unit,
    showButton: Boolean,
    modifier: Modifier = Modifier,
) {
    val share = ui.share
    val resetPhase by viewModel.manualResetState.collectAsStateWithLifecycle()
    val dotColor = when (state) {
        HeroState.Running -> StatusColors.runningDot()
        HeroState.Stopped -> StatusColors.stoppedDot()
        HeroState.NotReady -> StatusColors.warn()
    }
    BentoCard(modifier, container = Color.Transparent, contentPadding = 0.dp) {
        Column(
            Modifier.fillMaxSize().background(heroBrush(state)).padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlowDot(dotColor)
                StatLabel(stringResource(R.string.status_share))
                Spacer(Modifier.weight(1f))
                // 手动刷新服务（仅共享中）：客户端连不上时的第一步自救——app 全量重置 + 主动探测
                // 刷沿路表项；仍不通再由对话框引导开系统网络面板。RUNNING 期间转圈防重触。
                if (share.running) {
                    if (resetPhase == ManualResetPhase.RUNNING) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        IconButton(onClick = { viewModel.manualReset() }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                painterResource(R.drawable.ic_b_refresh),
                                contentDescription = stringResource(R.string.refresh_service),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                }
            }
            Text(
                stringResource(
                    when (state) {
                        HeroState.Running -> R.string.status_running
                        HeroState.Stopped -> R.string.status_stopped
                        HeroState.NotReady -> R.string.status_idle
                    },
                ),
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 36.sp, lineHeight = 40.sp),
                color = heroWordColor(state),
                maxLines = 1,
            )
            if (warnRes != null) {
                Row(
                    Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_b_alert),
                        contentDescription = null,
                        tint = StatusColors.warn(),
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        stringResource(warnRes),
                        style = MaterialTheme.typography.labelMedium,
                        color = StatusColors.warn(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                if (showButton) HeroRoundButton(share.running, onStart)
            }
            // 脚注：运行且检测到 VPN → 「VPN 已接入 · 共享出口」；否则过滤网关叙事（防护仍生效）。
            val vpnFoot = share.running && share.vpn.detected
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(
                    painterResource(if (vpnFoot) R.drawable.ic_b_key else R.drawable.ic_b_shield),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    stringResource(if (vpnFoot) R.string.mode_vpn_egress else R.string.dash_gateway_foot),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 88dp 启停圆钮（M3 双编码）：运行=tonal errorContainer 红（读作「停止」）、
 * 停止=filled primary 蓝（邀请开始）；色彩动效过渡。危险/停止一律 error,不用粉。
 */
@Composable
private fun HeroRoundButton(running: Boolean, onStart: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (running) cs.errorContainer else cs.primary,
        label = "heroBtnBg",
    )
    val content by animateColorAsState(
        targetValue = if (running) cs.onErrorContainer else cs.onPrimary,
        label = "heroBtnFg",
    )
    Button(
        onClick = { if (running) ProxyForegroundService.stop(context) else onStart() },
        modifier = Modifier.size(88.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(
                painterResource(if (running) R.drawable.ic_stop else R.drawable.ic_play),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                stringResource(if (running) R.string.stop_short else R.string.start_short),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 防护竖卡：中性底 + 1.5dp 粉描边（ElevatedCard 无 border 参,用 Modifier.border 自绘）,
 * 粉只落在盾图标与大数字上（≤10% 点睛纪律）。点击进防护页（右上尖角提示可点）。
 */
@Composable
private fun GuardCard(ui: MainUiState, onOpenProtection: () -> Unit, modifier: Modifier = Modifier) {
    val share = ui.share
    val adBlockOn = com.mzstd.hxmyproxy.core.rules.RuleCatalog.adGroups.any { it.id in ui.settings.enabledRuleGroups }
    val dark = LocalDarkTheme.current
    val edge = MaterialTheme.colorScheme.tertiary.copy(alpha = if (dark) 0.32f else 0.30f)
    BentoCard(
        modifier = modifier.border(1.5.dp, edge, MaterialTheme.shapes.large),
        tier = CardTier.Primary,
        onClick = onOpenProtection,
        contentPadding = 14.dp,
        spacing = 5.dp,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlowDot(if (adBlockOn) StatusColors.runningDot() else StatusColors.stoppedDot())
            Spacer(Modifier.width(5.dp))
            StatLabel(stringResource(R.string.protection_title), Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            Icon(
                painterResource(R.drawable.ic_b_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(14.dp),
            )
        }
        Icon(
            painterResource(R.drawable.ic_b_shield_check),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(top = 6.dp).size(22.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(
            String.format(Locale.US, "%,d", share.blockedTotal),
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 26.sp, lineHeight = 30.sp),
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
        )
        Text(
            stringResource(if (adBlockOn) R.string.monitor_blocked else R.string.protect_off),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(R.string.protect_works_always),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ══════════ 行2：速率宽卡 + 统计竖条（仅共享中） ══════════

/** 行2 bento：实时速率(2) + 统计竖条(1)，与行1 同比例对齐。 */
@Composable
private fun RateRow(ui: MainUiState, viewModel: MainViewModel) {
    val hist by viewModel.rateHistory.collectAsStateWithLifecycle()
    val dark = LocalDarkTheme.current
    // 上行专用灰蓝（HTML --up）：下行=primary 主角、上行退居配角,一眼分主次。
    val upColor = if (dark) Color(0xFF93A5C4) else Color(0xFF7C8DA6)
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BentoCard(Modifier.weight(2f).fillMaxHeight(), tier = CardTier.Primary, contentPadding = 14.dp, spacing = 8.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StatLabel(stringResource(R.string.monitor_realtime))
                Spacer(Modifier.weight(1f))
                StatLabel(stringResource(R.string.monitor_last_60s))
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                RateColumn(
                    Modifier.weight(1f),
                    label = stringResource(R.string.monitor_down),
                    iconRes = R.drawable.ic_b_arrow_down,
                    color = MaterialTheme.colorScheme.primary,
                    chipBg = if (dark) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.primaryContainer,
                    rate = ui.share.downloadRateBps,
                    history = hist.down,
                )
                VerticalDivider(
                    Modifier.padding(horizontal = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
                RateColumn(
                    Modifier.weight(1f),
                    label = stringResource(R.string.monitor_up),
                    iconRes = R.drawable.ic_b_arrow_up,
                    color = upColor,
                    chipBg = if (dark) upColor.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainer,
                    rate = ui.share.uploadRateBps,
                    history = hist.up,
                )
            }
        }
        StatColumn(ui, Modifier.weight(1f).fillMaxHeight())
    }
}

/** 速率单列：方向角标 + 标签 + BigStat 大数字 + 60s Sparkline。 */
@Composable
private fun RateColumn(
    modifier: Modifier,
    label: String,
    iconRes: Int,
    color: Color,
    chipBg: Color,
    rate: Long,
    history: List<Float>,
) {
    // formatRate 恒为「数字 空格 单位」,拆开喂 BigStat 的值/单位槽。
    val txt = com.mzstd.hxmyproxy.ui.formatRate(rate)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier.size(20.dp).clip(RoundedCornerShape(7.dp)).background(chipBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(iconRes), contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
            }
            StatLabel(label)
        }
        BigStat(value = txt.substringBefore(' '), unit = txt.substringAfter(' '), valueSize = 26)
        Sparkline(history, color = color)
    }
}

/** 统计竖条（outlined=次要信息）：连接 / 信号 / 累计；信号无值时收缩为两格均分。 */
@Composable
private fun StatColumn(ui: MainUiState, modifier: Modifier = Modifier) {
    val share = ui.share
    val hairline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    Column(
        modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(horizontal = 13.dp, vertical = 4.dp),
    ) {
        StatCell(
            Modifier.weight(1f),
            stringResource(R.string.stat_conns),
            "${share.activeConnections}",
            stringResource(R.string.stat_unit_devices),
        )
        if (share.signalLevel >= 0) {
            HorizontalDivider(color = hairline)
            StatCell(Modifier.weight(1f), stringResource(R.string.stat_signal), "${share.signalDbm}", "dBm")
        }
        // 段①（客户端 → 本机）链路时延：手机当代理时，这一段最先劣化，而此前 UI 上完全看不到
        // ——监控页的「服务延迟」测的是本机→互联网（段②），所以「手机自己正常、客户端却卡死」时毫无线索。
        val ls = share.linkStats
        if (ls.samples > 0) {
            HorizontalDivider(color = hairline)
            StatCell(
                Modifier.weight(1f),
                stringResource(R.string.stat_link),
                "${ls.p50Ms}",
                "ms",
                valueColor = when {
                    ls.p50Ms < LinkStats.GOOD_MS -> StatusColors.good()
                    ls.p50Ms < LinkStats.WARN_MS -> StatusColors.warn()
                    else -> MaterialTheme.colorScheme.error
                },
                extra = "p95 ${ls.p95Ms}",
            )
        }
        // 丢包率：与时延**并列但独立**的一格。时延窗口只收成功样本，所以「p50 很漂亮但一半包丢了」
        // 在上面那格里完全看不出来——8-01 真机日志正是这个形状（p50 <20ms 占 34.8%，
        // 而自愈突发每 10 发只回 3~5 发）。这条链路真正的病是丢包，不是延迟。
        if (ls.lossSamples > 0) {
            HorizontalDivider(color = hairline)
            StatCell(
                Modifier.weight(1f),
                stringResource(R.string.stat_loss),
                "${ls.lossPct}",
                "%",
                valueColor = when {
                    ls.lossPct < LinkStats.LOSS_WARN_PCT -> StatusColors.good()
                    ls.lossPct < LinkStats.LOSS_BAD_PCT -> StatusColors.warn()
                    else -> MaterialTheme.colorScheme.error
                },
            )
        }
        HorizontalDivider(color = hairline)
        val total = com.mzstd.hxmyproxy.ui.formatBytes(share.totalBytes)
        StatCell(
            Modifier.weight(1f),
            stringResource(R.string.stat_traffic),
            total.substringBefore(' '),
            total.substringAfter(' '),
        )
    }
}

/** 统计竖条单格：小标签 + tnum 值 + 单位。 */
@Composable
private fun StatCell(
    modifier: Modifier,
    label: String,
    value: String,
    unit: String,
    valueColor: Color? = null,
    extra: String? = null,
) {
    Column(modifier.padding(vertical = 3.dp), verticalArrangement = Arrangement.Center) {
        StatLabel(label)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Bold),
                color = valueColor ?: MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                unit,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp),
                maxLines = 1,
            )
            // 次要数字（如 p95）：窄列里优先保证主数字完整，放不下就省略。
            if (extra != null) {
                Text(
                    extra,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = 2.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ══════════ 端口占用告警 + 入口配置 ══════════

/**
 * 链路丢包告警 + **物理层**建议。
 *
 * 为什么给的是"挪设备"而不是软件设置：这一段是空口质量问题，软件层修不了。客户端与手机连同一 AP 时，
 * 一个下行字节要穿越弱链路 4 次（≈p⁴），而手机自己只穿 2 次（≈p²）——p=0.85 时 0.52 vs 0.72，
 * 这就是「手机自己上网正常、连它的设备却卡」的算术必然（8-01 日志实测每 10 发只回 3~5 发）。
 * 建议按收益排序：客户端直连手机热点（消掉 AP 中继，质变）> 手机挪近路由器 > 客户端插网线。
 */
@Composable
private fun LinkLossBanner(ui: MainUiState) {
    val ls = ui.share.linkStats
    // 要有足够样本才报，否则刚连上的一两次超时就会吓人一跳。
    if (!ui.share.running || ls.lossSamples < 8 || ls.lossPct < LinkStats.LOSS_WARN_PCT) return
    WarnBanner(
        text = stringResource(R.string.link_loss_banner, ls.lossPct),
        level = if (ls.lossPct >= LinkStats.LOSS_BAD_PCT) BannerLevel.Error else BannerLevel.Warn,
        icon = painterResource(R.drawable.ic_b_alert_circle),
    )
}

/** 端口占用告警：某协议 bind 失败（端口被占）→ 该代理未启动，全宽 errorContainer 行。 */
@Composable
private fun PortBindBanner(ui: MainUiState) {
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
    WarnBanner(
        text = stringResource(R.string.port_bind_failed_banner, portList),
        level = BannerLevel.Error,
        icon = painterResource(R.drawable.ic_b_alert_circle),
    )
}

/**
 * 入口配置卡：三协议 ProtoBadge + 等宽地址 + 复制钮；「扫码配置」上移卡头成常驻 chip
 * （无入口时降透明度,点开弹层自会给引导）；折叠逻辑与 QR bottom sheet 原样保留。
 */
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
    val hairline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)

    BentoCard(Modifier.fillMaxWidth(), tier = CardTier.Default, contentPadding = 14.dp, spacing = 6.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.entry_config),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            // 扫码 chip：primaryContainer 小胶囊;停止/未就绪降透明度但仍可点(弹层内给缺 PAC 引导)。
            Row(
                Modifier
                    .alpha(if (primaryEntry != null) 1f else 0.45f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable { showQr = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_b_qr),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    stringResource(R.string.qr_setup),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        if (primaryEntry == null) {
            // 三种空态分开引导（原样保留）：运行中无入口 → 黄警示明说；没选接口 → 提示选接口；
            // 选了但没开始 → 引导开始共享。
            if (share.running) {
                WarnBanner(
                    text = stringResource(R.string.entry_none_running),
                    icon = painterResource(R.drawable.ic_b_alert),
                )
            } else {
                Row(
                    Modifier.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_b_info),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        stringResource(
                            if (ui.settings.selectedInterfaceIds.isEmpty()) R.string.no_entry
                            else R.string.start_to_show_entries,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            val allEntries = share.recommendedEntries
            // 折叠态除首选(HTTP)外,常显 PAC 那条——完整 http://ip:port/proxy.pac 是系统「自动配置」要的。
            val pacEntry = allEntries.firstOrNull { it.protocol == ProxyProtocol.PAC && it != primaryEntry }
            val collapsedEntries = listOfNotNull(primaryEntry, pacEntry)
            val shownEntries = if (entriesExpanded) allEntries else collapsedEntries
            shownEntries.forEachIndexed { i, e ->
                if (i > 0) HorizontalDivider(color = hairline)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ProtoBadge(e.protocol)
                    // 等宽字体：地址是要抄写/核对的内容，等宽更易读、更「技术可信」。
                    Text(
                        e.displayEndpoint,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(e.copyValue))
                            Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_b_copy),
                            contentDescription = stringResource(R.string.copy),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            // PAC 已开但 HTTP/SOCKS 全关 → 生成的 pac 退化成 return "DIRECT"(能拉取但不代理),明确告警。
            if (ui.settings.pacEnabled && !ui.settings.httpEnabled && !ui.settings.socksEnabled) {
                WarnBanner(
                    text = stringResource(R.string.pac_needs_backend),
                    icon = painterResource(R.drawable.ic_b_alert),
                )
            }
            // 仅当有被折叠隐藏的入口才显示展开按钮（避免已全显时出现无效「展开」）。
            if (allEntries.size > collapsedEntries.size) {
                ExpandCollapseButton(entriesExpanded, allEntries.size) { entriesExpanded = !entriesExpanded }
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
                    Surface(shape = MaterialTheme.shapes.large, color = Color.White) {
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
                        color = Color.White,
                    ) {
                        Column(Modifier.padding(18.dp)) { QrImage(setupUrl, sizeDp = 216) }
                    }
                }
            }
        }
    }
}

// ══════════ 行4：可分享入口 + 出口网络 ══════════

/**
 * 数据流示意图：设备 ─接入→ 本机 ─出口→ 网络。帮用户理清「入口 vs 出口」——
 * 下面 DuoRow 的两张卡（接入网络 / 出口网络）正好对应流向图的左右两端。
 */
@Composable
private fun FlowDiagram() {
    BentoCard(tier = CardTier.Sunken, contentPadding = 12.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FlowNode(R.drawable.ic_b_devices, R.string.flow_device, Modifier.weight(1f))
            FlowArrow(R.string.flow_in)
            FlowNode(R.drawable.ic_b_phone, R.string.flow_phone, Modifier.weight(1f))
            FlowArrow(R.string.flow_out)
            FlowNode(R.drawable.ic_b_globe, R.string.flow_net, Modifier.weight(1f))
        }
    }
}

/** 流向节点：图标圆盘 + 名称（设备 / 本机 / 网络）。 */
@Composable
private fun FlowNode(iconRes: Int, labelRes: Int, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        IconDisc(painterResource(iconRes), size = 34.dp, iconSize = 18.dp)
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 流向箭头段：上方小标签（接入 / 出口，primary 色）+ 箭头图标。 */
@Composable
private fun FlowArrow(labelRes: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
        Icon(
            painterResource(R.drawable.ic_b_arrow_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 2.dp).size(18.dp),
        )
    }
}

/** 可分享入口卡：逐接口开关直嵌 + 「已选 n/m」徽章；默认 2 行、超出折叠；空态双文案。 */
@Composable
private fun InterfacesCard(ui: MainUiState, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val share = ui.share
    var interfacesExpanded by remember { mutableStateOf(false) }
    // 受「显示 IPv6」偏好过滤：默认只列 v4，v6 地址太长、抄写困难。
    // 隐藏的 v6 接口仍在准入集里生效（见 MainUiState.visibleInterfaces）。
    val interfaces = ui.visibleInterfaces
    val hairline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    BentoCard(modifier, tier = CardTier.Sunken, contentPadding = 13.dp, spacing = 4.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_b_wifi),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            StatLabel(stringResource(R.string.shareable_interfaces), Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            if (interfaces.isNotEmpty()) {
                val selected = interfaces.count { it.id in ui.settings.selectedInterfaceIds }
                // 准入空集且运行中=fail-closed 全拒,徽章转黄警示色(与 hero 未就绪呼应)。
                val warn = share.running && share.admissionEmpty
                CountBadge(
                    stringResource(R.string.dash_selected_count, selected, interfaces.size),
                    fg = if (warn) StatusColors.warn() else MaterialTheme.colorScheme.onPrimaryContainer,
                    bg = if (warn) StatusColors.warnContainer() else MaterialTheme.colorScheme.primaryContainer,
                )
            }
        }
        if (interfaces.isEmpty()) {
            Row(
                Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_b_wifi_off),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    stringResource(R.string.no_interfaces),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 走蜂窝上网时没有局域网可共享,追加引导开个人热点。
            if (share.needsHotspotHint) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = hairline)
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_b_hotspot),
                        contentDescription = null,
                        tint = StatusColors.warn(),
                        modifier = Modifier.padding(top = 2.dp).size(13.dp),
                    )
                    Text(
                        stringResource(R.string.hint_enable_hotspot),
                        style = MaterialTheme.typography.labelMedium,
                        color = StatusColors.warn(),
                    )
                }
            }
        } else {
            val shownIfaces = if (interfacesExpanded) interfaces else interfaces.take(2)
            shownIfaces.forEachIndexed { i, iface ->
                if (i > 0) HorizontalDivider(color = hairline)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painterResource(iface.type.iconRes()),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${stringResource(iface.type.labelRes())} · ${iface.name}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            iface.cidr,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = iface.id in ui.settings.selectedInterfaceIds,
                        onCheckedChange = { viewModel.toggleInterface(iface.id, it) },
                        colors = stdSwitchColors(),
                    )
                }
            }
            if (interfaces.size > 2) {
                ExpandCollapseButton(interfacesExpanded, interfaces.size) { interfacesExpanded = !interfacesExpanded }
            }
        }
        // **被隐藏的 IPv6 必须留一行痕迹。**
        // 第一版藏得一点提示都没有,用户的第一反应是「IPv6 代理被取消了」——
        // 找不到的功能和删掉没有区别。这一行既解释了「为什么少了几个地址」,
        // 又是就地打开的入口(不必翻到设置页去找那个开关)。
        IPv6HintRow(ui, viewModel)
    }
}

/**
 * 「另有 N 个 IPv6 地址」/「收起 IPv6」——隐藏状态的可见痕迹 + 就地切换。
 *
 * 点它直接改 `showIpv6` 设置,而不是只在本卡内临时展开:两处状态分离会造出
 * 「卡上展开了、设置里却是关的」这种自相矛盾,而入口卡/通知/PAC 跟的是设置。
 */
@Composable
private fun IPv6HintRow(ui: MainUiState, viewModel: MainViewModel) {
    val hidden = ui.hiddenIpv6Count
    val showing = ui.settings.showIpv6
    // 没有 v6 可显示时两个分支都不出现——别为不存在的东西留提示。
    if (hidden == 0 && !(showing && ui.share.interfaces.any { it.isIpv6 })) return
    TextButton(
        onClick = { viewModel.setShowIpv6(!showing) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (showing) stringResource(R.string.ipv6_collapse)
            else stringResource(R.string.ipv6_hidden_hint, hidden),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** 出口网络卡：5 FilterChip 页内直选；离线物理网络置灰，VPN 冲突黄警示（行为原样保留）。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EgressCard(ui: MainUiState, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val choice = ui.settings.egressChoice
    val st = ui.share.egressStatus
    val vpnActive = ui.share.vpn.detected
    var showCellularConfirm by remember { mutableStateOf(false) }
    BentoCard(modifier, tier = CardTier.Sunken, contentPadding = 13.dp, spacing = 6.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                painterResource(R.drawable.ic_b_egress),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            StatLabel(stringResource(R.string.egress_title))
        }
        Text(
            stringResource(R.string.egress_sub),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val opts = listOf(
            Triple(EgressNetworkChoice.AUTO, R.string.egress_auto, true),
            Triple(EgressNetworkChoice.VPN, R.string.egress_vpn, st.vpn),
            Triple(EgressNetworkChoice.WIFI, R.string.egress_wifi, st.wifi),
            Triple(EgressNetworkChoice.CELLULAR, R.string.egress_cellular, st.cellularCapable),
            Triple(EgressNetworkChoice.ETHERNET, R.string.egress_ethernet, st.ethernetCapable),
        )
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            opts.forEach { (c, labelRes, online) ->
                val isAuto = c == EgressNetworkChoice.AUTO
                // 离线的物理/ VPN 出口置灰不可选；已选中项即便离线仍可点（供切走）。
                FilterChip(
                    selected = choice == c,
                    onClick = {
                        if (c == EgressNetworkChoice.CELLULAR && !ui.settings.cellularEgressConfirmed) showCellularConfirm = true
                        else viewModel.setEgressChoice(c)
                    },
                    enabled = online || isAuto || c == choice,
                    colors = stdFilterChipColors(),
                    leadingIcon = if (choice == c) {
                        {
                            Icon(
                                painterResource(R.drawable.ic_b_check),
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else null,
                    label = {
                        // 离线不再拼「· offline」文字（英文超窄卡宽把 chip 撑爆/裁字）——靠 enabled=false 置灰表达。
                        Text(
                            stringResource(labelRes),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
        if (choice == EgressNetworkChoice.AUTO) {
            Text(
                stringResource(R.string.egress_auto_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (choice == EgressNetworkChoice.CELLULAR) {
            Text(
                stringResource(R.string.egress_cellular_metered),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 「指定出口走不通时怎么办」——只在真的指定了出口时才有意义（AUTO 没有「原本该走哪」）。
        // 必须可见：STRICT 断开对用户表现为「某个 App 连不上」，看不到这个开关就无从判断原因。
        if (choice != EgressNetworkChoice.AUTO) {
            Text(
                stringResource(R.string.egress_fallback_title),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                stringResource(R.string.egress_fallback_sub),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    com.mzstd.hxmyproxy.core.model.EgressFallback.STRICT to R.string.egress_fallback_strict,
                    com.mzstd.hxmyproxy.core.model.EgressFallback.DEGRADE to R.string.egress_fallback_degrade,
                ).forEach { (v, labelRes) ->
                    val sel = ui.settings.egressFallback == v
                    FilterChip(
                        selected = sel,
                        onClick = { viewModel.setEgressFallback(v) },
                        colors = stdFilterChipColors(),
                        leadingIcon = if (sel) {
                            {
                                Icon(
                                    painterResource(R.drawable.ic_b_check),
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            }
                        } else null,
                        label = { Text(stringResource(labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            Text(
                stringResource(
                    if (ui.settings.egressFallback == com.mzstd.hxmyproxy.core.model.EgressFallback.STRICT)
                        R.string.egress_fallback_strict_desc else R.string.egress_fallback_degrade_desc,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val boundPhysical = choice == EgressNetworkChoice.WIFI || choice == EgressNetworkChoice.CELLULAR || choice == EgressNetworkChoice.ETHERNET
        if (vpnActive && boundPhysical) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    painterResource(R.drawable.ic_b_alert),
                    contentDescription = null,
                    tint = StatusColors.warn(),
                    modifier = Modifier.padding(top = 2.dp).size(13.dp),
                )
                Text(
                    stringResource(R.string.egress_vpn_warn),
                    style = MaterialTheme.typography.labelMedium,
                    color = StatusColors.warn(),
                )
            }
        }
    }
    if (showCellularConfirm) CellularConfirmDialog(
        onConfirm = {
            viewModel.confirmCellularEgress()
            viewModel.setEgressChoice(EgressNetworkChoice.CELLULAR)
            showCellularConfirm = false
        },
        onDismiss = { showCellularConfirm = false },
    )
}

/** 直连出口卡：DIRECT(bypass) 流量走哪张物理网。AUTO=以太网/USB→WiFi→蜂窝→fail-closed；可手动指定。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DirectEgressCard(ui: MainUiState, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val choice = ui.settings.directEgressChoice
    val st = ui.share.egressStatus
    var showCellularConfirm by remember { mutableStateOf(false) }
    BentoCard(modifier, tier = CardTier.Sunken, contentPadding = 13.dp, spacing = 6.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                painterResource(R.drawable.ic_b_egress),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            StatLabel(stringResource(R.string.direct_egress_title))
        }
        Text(
            stringResource(R.string.direct_egress_sub),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val opts = listOf(
            Triple(DirectEgressChoice.AUTO, R.string.egress_auto, true),
            Triple(DirectEgressChoice.ETHERNET, R.string.egress_ethernet, st.ethernetCapable),
            Triple(DirectEgressChoice.WIFI, R.string.egress_wifi, st.wifi),
            Triple(DirectEgressChoice.CELLULAR, R.string.egress_cellular, st.cellularCapable),
        )
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            opts.forEach { (c, labelRes, online) ->
                val isAuto = c == DirectEgressChoice.AUTO
                FilterChip(
                    selected = choice == c,
                    onClick = {
                        if (c == DirectEgressChoice.CELLULAR && !ui.settings.cellularEgressConfirmed) showCellularConfirm = true
                        else viewModel.setDirectEgressChoice(c)
                    },
                    enabled = online || isAuto || c == choice,
                    colors = stdFilterChipColors(),
                    leadingIcon = if (choice == c) {
                        {
                            Icon(
                                painterResource(R.drawable.ic_b_check),
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else null,
                    label = {
                        Text(stringResource(labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                )
            }
        }
        if (choice == DirectEgressChoice.AUTO) {
            Text(
                stringResource(R.string.direct_egress_auto_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (choice == DirectEgressChoice.CELLULAR) {
            Text(
                stringResource(R.string.egress_cellular_metered),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showCellularConfirm) CellularConfirmDialog(
        onConfirm = {
            viewModel.confirmCellularEgress()
            viewModel.setDirectEgressChoice(DirectEgressChoice.CELLULAR)
            showCellularConfirm = false
        },
        onDismiss = { showCellularConfirm = false },
    )
}

/** 蜂窝出口首次确认弹窗：提示会消耗移动数据/资费（首次选蜂窝出口时弹一次）。 */
@Composable
private fun CellularConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cellular_confirm_title)) },
        text = { Text(stringResource(R.string.cellular_confirm_msg)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.cellular_confirm_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

// ══════════ 横屏启停（保持原结构） ══════════

/**
 * 横屏竖向主按钮：窄长条填满高度，贴导航 rail 右侧不占内容区、不在滚动区内避免误触。
 * 内容用 ▶/■ 图标而非文字——逐字竖排只适合中文，英文 "Stop sharing" 逐字母竖排不可读
 * （双语检查原则，用户决策改图标）。颜色状态与 hero 圆钮一致（蓝=开始 ↔ 浅红=停止,色彩过渡）。
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
        contentPadding = PaddingValues(4.dp),
    ) {
        Icon(
            painterResource(if (running) R.drawable.ic_stop else R.drawable.ic_play),
            contentDescription = stringResource(if (running) R.string.stop_sharing else R.string.start_sharing),
            modifier = Modifier.size(28.dp),
        )
    }
}

/**
 * 横屏启停按钮配色：两态都「亮/浅底 + 深字」。浅色用 container（浅蓝/浅红底），
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
