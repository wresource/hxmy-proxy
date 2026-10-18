package com.mzstd.hxmyproxy.ui.settings

import com.mzstd.hxmyproxy.core.model.DohEgressChoice
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.model.AppLanguage
import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.model.PerformancePreset
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.core.model.ThemeMode
import com.mzstd.hxmyproxy.data.repository.CredentialStore
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.NavTab
import com.mzstd.hxmyproxy.ui.components.SegTabs
import com.mzstd.hxmyproxy.ui.dashboard.DirectEgressCard
import com.mzstd.hxmyproxy.ui.dashboard.EgressCard
import com.mzstd.hxmyproxy.ui.dashboard.InterfacesCard
import com.mzstd.hxmyproxy.ui.components.BannerLevel
import com.mzstd.hxmyproxy.ui.components.BentoCard
import com.mzstd.hxmyproxy.ui.components.CardHeader
import com.mzstd.hxmyproxy.ui.components.CardTier
import com.mzstd.hxmyproxy.ui.components.CountBadge
import com.mzstd.hxmyproxy.ui.components.InfoDot
import com.mzstd.hxmyproxy.ui.components.LabeledSwitchRow
import com.mzstd.hxmyproxy.ui.components.PageHeader
import com.mzstd.hxmyproxy.ui.components.ProtoBadge
import com.mzstd.hxmyproxy.ui.components.StatLabel
import com.mzstd.hxmyproxy.ui.components.WarnBanner
import com.mzstd.hxmyproxy.ui.components.stdFilterChipColors
import com.mzstd.hxmyproxy.ui.components.stdSwitchColors
import com.mzstd.hxmyproxy.ui.theme.AvatarBgDark
import com.mzstd.hxmyproxy.ui.theme.AvatarBgLight
import com.mzstd.hxmyproxy.ui.theme.AvatarFgDark
import com.mzstd.hxmyproxy.ui.theme.AvatarFgLight
import com.mzstd.hxmyproxy.ui.theme.LocalDarkTheme
import com.mzstd.hxmyproxy.ui.theme.StatusColors

private fun AppLanguage.labelRes() = when (this) {
    AppLanguage.SYSTEM -> R.string.lang_system
    AppLanguage.ENGLISH -> R.string.lang_english
    AppLanguage.CHINESE -> R.string.lang_chinese
}

private fun ThemeMode.labelRes() = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

private fun PerformancePreset.labelRes() = when (this) {
    PerformancePreset.BATTERY -> R.string.preset_battery
    PerformancePreset.BALANCED -> R.string.preset_balanced
    PerformancePreset.HIGH_THROUGHPUT -> R.string.preset_high
    PerformancePreset.CUSTOM -> R.string.preset_custom
}

/** 粉彩头像板取色（明暗自适应）：0=天蓝 / 1=青 / 2=草绿 / 4=薰衣草——设置页各卡图标身份色。 */
@Composable
private fun avatarColors(i: Int): Pair<Color, Color> {
    val dark = LocalDarkTheme.current
    return (if (dark) AvatarBgDark else AvatarBgLight)[i] to (if (dark) AvatarFgDark else AvatarFgLight)[i]
}

/** 沉底小面色（端口输入框底 / 徽标描边）——与 BentoUi Sunken 档同源取色。 */
@Composable
private fun sunkenColor(): Color =
    if (LocalDarkTheme.current) MaterialTheme.colorScheme.surfaceContainerLow
    else MaterialTheme.colorScheme.surfaceContainer

/** Use the height left above the keyboard; a large keyboard can also crowd a portrait window. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun compactSettingsEditing(): Boolean {
    val density = LocalDensity.current
    val keyboardHeight = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    return WindowInsets.isImeVisible && LocalConfiguration.current.screenHeightDp.dp - keyboardHeight < 400.dp
}

/** Inbound and ports are frequent controls; only the other sections use a settings index. */
@Composable
fun SettingsScreen(
    ui: MainUiState, viewModel: MainViewModel,
    onOpenHelp: () -> Unit = {}, onReplayOnboarding: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp), onOpenPage: (String) -> Unit = {},
) {
    var seg by rememberSaveable { mutableIntStateOf(0) }
    val compactEditing = compactSettingsEditing()
    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        if (!compactEditing) {
            com.mzstd.hxmyproxy.ui.components.WorkbenchHeader(stringResource(R.string.nav_settings))
            SegTabs(listOf(stringResource(R.string.seg_set_in), stringResource(R.string.seg_set_out),
                stringResource(R.string.seg_set_privacy), stringResource(R.string.seg_set_general)), seg, { seg = it }, Modifier.padding(horizontal = 16.dp))
        }
        Column(Modifier.weight(1f).imePadding().testTag("settings_body").verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(top = 12.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (seg == 0) {
                InterfacesCard(ui, viewModel)
                ProtoPortsCard(ui, viewModel)
                com.mzstd.hxmyproxy.ui.components.WorkbenchRow(
                    stringResource(R.string.ui_authentication),
                    stringResource(if (ui.settings.authEnabled) R.string.ui_enabled else R.string.ui_disabled),
                    R.drawable.ic_b_shield_check, tag = "settings_auth", onClick = { onOpenPage("auth") })
            } else SettingsIndex(ui, seg, onOpenPage, onOpenHelp, onReplayOnboarding)
        }
    }
}

@Composable
private fun SettingsIndex(ui: MainUiState, section: Int, open: (String) -> Unit, help: () -> Unit, onboarding: () -> Unit) {
    val s = ui.settings
    val context = LocalContext.current
    @Composable fun entry(page: String, subtitle: String? = null, icon: Int = R.drawable.ic_nav_settings) {
        com.mzstd.hxmyproxy.ui.components.WorkbenchRow(stringResource(settingsTitle(page)), subtitle, icon,
            tag = "settings_$page", onClick = { open(page) })
    }
    when (section) {
        0 -> {
            entry("interfaces", stringResource(R.string.ui_selected_networks, ui.share.interfaces.count { it.id in s.selectedInterfaceIds }), R.drawable.ic_b_wifi)
            entry("ports", stringResource(R.string.ui_protocol_summary, s.httpPort, s.socksPort, s.pacPort), R.drawable.ic_b_swap)
            entry("auth", stringResource(if (s.authEnabled) R.string.ui_enabled else R.string.ui_disabled), R.drawable.ic_b_shield_check)
        }
        1 -> {
            entry("proxy-egress", stringResource(egressChoiceLabel(s.egressChoice.name)), R.drawable.ic_b_arrow_right)
            entry("direct-egress", stringResource(egressChoiceLabel(s.directEgressChoice.name)), R.drawable.ic_b_arrow_right)
            entry("priority", s.egressPriority.joinToString(" → ") { context.getString(egressChoiceLabel(it.name)) })
            entry("dns", stringResource(if (s.backupDnsEnabled) R.string.ui_enabled else R.string.ui_disabled))
        }
        2 -> { entry("privacy"); entry("backup") }
        else -> {
            entry("appearance", stringResource(s.language.labelRes()) + " · " + stringResource(s.themeMode.labelRes()))
            entry("performance", stringResource(s.preset.labelRes()))
            entry("navigation")
            com.mzstd.hxmyproxy.ui.components.WorkbenchRow(stringResource(R.string.help_open), icon = R.drawable.ic_b_help, onClick = help)
            com.mzstd.hxmyproxy.ui.components.WorkbenchRow(stringResource(R.string.replay_onboarding), icon = R.drawable.ic_b_replay, onClick = onboarding)
            entry("about", remember(context) { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }.getOrDefault("") })
        }
    }
}

internal fun settingsTitle(page: String): Int = when (page) {
    "interfaces" -> R.string.shareable_interfaces
    "ports" -> R.string.settings_proto_ports
    "auth" -> R.string.ui_authentication
    "proxy-egress" -> R.string.ui_proxy_egress
    "direct-egress" -> R.string.ui_direct_egress
    "priority" -> R.string.ui_network_priority
    "dns" -> R.string.ui_fallback_dns
    "privacy" -> R.string.ui_privacy_security
    "backup" -> R.string.ui_settings_backup
    "appearance" -> R.string.ui_appearance
    "performance" -> R.string.ui_performance
    "navigation" -> R.string.ui_navigation
    "about" -> R.string.ui_about
    else -> R.string.nav_settings
}
private fun egressChoiceLabel(name: String) = when (name) {
    "VPN" -> R.string.egress_vpn; "WIFI" -> R.string.egress_wifi; "CELLULAR" -> R.string.egress_cellular
    "ETHERNET" -> R.string.egress_ethernet; else -> R.string.egress_auto
}

@Composable
fun SettingsDetailScreen(page: String, ui: MainUiState, viewModel: MainViewModel, onBack: () -> Unit,
    onOpenPage: (String) -> Unit, onOpenHelp: () -> Unit, onReplayOnboarding: () -> Unit) {
    val section = when (page) { "interfaces", "ports", "auth" -> 0; "proxy-egress", "direct-egress", "priority", "dns" -> 1; "privacy", "backup" -> 2; else -> 3 }
    com.mzstd.hxmyproxy.ui.components.DetailScaffold(
        stringResource(if (page == "ports") R.string.ui_edit_ports else settingsTitle(page)), onBack,
        actions = { if (page == "ports") InfoDot(stringResource(R.string.settings_proto_ports), stringResource(R.string.ui_ports_help)) },
        showTopBar = !compactSettingsEditing(),
    ) { padding ->
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().padding(padding).imePadding()) {
            val wide = maxWidth >= 720.dp
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (wide) Column(Modifier.weight(0.8f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsIndex(ui, section, onOpenPage, onOpenHelp, onReplayOnboarding)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (page) {
                        "interfaces" -> { InterfacesCard(ui, viewModel, showIpv6Hint = false); Ipv6Card(ui.settings.showIpv6, viewModel) }
                        "ports" -> ProtoPortsCard(ui, viewModel, showHeading = false)
                        "auth" -> AuthCard(ui, viewModel)
                        "proxy-egress" -> EgressCard(ui, viewModel)
                        "direct-egress" -> DirectEgressCard(ui, viewModel, showPriority = false)
                        "priority" -> com.mzstd.hxmyproxy.ui.dashboard.EgressPriorityList(ui, viewModel)
                        "dns" -> DohCard(ui, viewModel)
                        "privacy" -> PrivacyCard(ui, viewModel)
                        "backup" -> BackupCard(viewModel)
                        "appearance" -> GeneralCard(ui.settings.language, ui.settings.themeMode, viewModel, Modifier.fillMaxWidth())
                        "performance" -> PresetCard(ui.settings.preset, ui.settings.limits, viewModel, Modifier.fillMaxWidth())
                        "navigation" -> NavEditCard(ui.settings.hiddenTabs, viewModel::setTabHidden)
                        "about" -> AboutCard()
                    }
                }
            }
        }
    }
}

// ---------- 设置备份（导出/导入 JSON） ----------

/**
 * 设置备份卡：导出/导入全部配置（含规则、接口选择、出口偏好等），用于换机与重装迁移。
 *
 * **为何必需**：日志与设置目录已从系统云备份中排除（隐私政策承诺「完全本地、不上云」），
 * 因此云端恢复这条路被主动关掉了，手动导出是保住配置的唯一途径。
 * 代理凭据单独加密存储，**不进这份明文备份**。
 */
@Composable
private fun BackupCard(viewModel: MainViewModel) {
    val context = LocalContext.current
    var pendingJson by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = pendingJson
        pendingJson = null
        if (uri == null || json == null) return@rememberLauncherForActivityResult
        message = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
        }.fold(
            { context.getString(R.string.settings_backup_exported) },
            { context.getString(R.string.settings_backup_failed) },
        )
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { r -> r.readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            message = context.getString(R.string.settings_backup_failed)
            return@rememberLauncherForActivityResult
        }
        viewModel.importSettings(text) { r ->
            message = r.fold(
                { context.getString(R.string.settings_backup_imported, it) },
                { context.getString(R.string.settings_backup_failed) },
            )
        }
    }

    BentoCard(tier = CardTier.Primary, contentPadding = 13.dp, spacing = 8.dp) {
        CardHeader(
            title = stringResource(R.string.settings_backup),
            icon = painterResource(R.drawable.ic_b_layers),
        )
        Text(
            stringResource(R.string.settings_backup_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LinkTile(
                R.drawable.ic_b_arrow_up,
                stringResource(R.string.settings_backup_export),
                {
                    viewModel.exportSettings { r ->
                        r.onSuccess { json ->
                            pendingJson = json
                            runCatching { exportLauncher.launch("hxmy-settings.json") }
                                .onFailure { message = context.getString(R.string.settings_backup_failed) }
                        }.onFailure { message = context.getString(R.string.settings_backup_failed) }
                    }
                },
                Modifier.weight(1f),
            )
            LinkTile(
                R.drawable.ic_b_arrow_down,
                stringResource(R.string.settings_backup_import),
                {
                    runCatching { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
                        .onFailure { message = context.getString(R.string.settings_backup_failed) }
                },
                Modifier.weight(1f),
            )
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ---------- 协议与端口 ----------

/**
 * 协议、端口与开关就地编辑；独立页面复用相同控件，不重复标题。
 * 端口重复校验包含未启用协议的端口：端口值持久化，启用后仍会撞端口，故配置期就该拦下。
 */
@Composable
private fun ProtoPortsCard(ui: MainUiState, viewModel: MainViewModel, showHeading: Boolean = true) {
    val s = ui.settings
    val bindErrors = ui.share.portBindErrors
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
      Column(Modifier.fillMaxWidth().padding(16.dp)) {
        if (showHeading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_b_swap), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.settings_proto_ports), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            InfoDot(stringResource(R.string.settings_proto_ports), stringResource(R.string.ui_ports_help))
        }
        ProtoPortRow(
            badge = { ProtoBadge(ProxyProtocol.HTTP) },
            name = "HTTP",
            tag = "HTTP",
            sub = stringResource(R.string.proto_http_sub),
            checked = s.httpEnabled,
            onToggle = viewModel::setHttpEnabled,
            port = s.httpPort,
            otherPorts = setOf(s.socksPort, s.pacPort),
            bindError = ProxyProtocol.HTTP in bindErrors,
            portLabel = stringResource(R.string.port_http),
            onCommitPort = viewModel::setHttpPort,
        )
        ProtoPortRow(
            badge = { ProtoBadge(ProxyProtocol.SOCKS5) },
            name = stringResource(R.string.proto_socks),
            tag = "SOCKS5",
            sub = stringResource(R.string.proto_socks_sub),
            checked = s.socksEnabled,
            onToggle = viewModel::setSocksEnabled,
            port = s.socksPort,
            otherPorts = setOf(s.httpPort, s.pacPort),
            bindError = ProxyProtocol.SOCKS5 in bindErrors,
            portLabel = stringResource(R.string.port_socks),
            onCommitPort = viewModel::setSocksPort,
        )
        ProtoPortRow(
            badge = { ProtoBadge(ProxyProtocol.PAC) },
            name = "PAC",
            tag = "PAC",
            sub = stringResource(R.string.proto_pac_sub),
            checked = s.pacEnabled,
            onToggle = viewModel::setPacEnabled,
            port = s.pacPort,
            otherPorts = setOf(s.httpPort, s.socksPort),
            bindError = ProxyProtocol.PAC in bindErrors,
            portLabel = stringResource(R.string.port_pac),
            onCommitPort = viewModel::setPacPort,
        )
        Text(stringResource(R.string.ui_ports_client_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
      }
    }
}

/**
 * 备用 DNS(DoH) 卡——设置/出口段。从 ProtoPortsCard 拆出（工作台设计稿：DoH 是
 * 解析失败后的**出口救济**，归出口段；混在协议端口里会被读成「又一个协议」）。
 */
@Composable
private fun DohCard(ui: MainUiState, viewModel: MainViewModel) {
    val s = ui.settings
    BentoCard(tier = CardTier.Default, contentPadding = 12.dp, spacing = 8.dp) {
        ProtoPortRow(
            badge = { DohBadge() },
            name = stringResource(R.string.backup_dns),
            sub = stringResource(R.string.backup_dns_sub),
            checked = s.backupDnsEnabled,
            onToggle = viewModel::setBackupDnsEnabled,
        )
        // 出口选择只在 DoH 开着时才有意义，关掉就不占版面（避免堆一堵无效旋钮的墙）。
        if (s.backupDnsEnabled) DohEgressPicker(s.dohEgressChoice, viewModel::setDohEgressChoice)
    }
}

/**
 * 备用 DNS 走哪张网。
 *
 * 为什么这个旋钮必须存在：DoH 是「系统解析已经失败」之后的救济手段，而它此前跟随**进程默认路由**
 * ——egress=VPN 时那正是刚刚失败的那条 VPN，于是它与要救的业务同路同死（实测救援成功率仅 6.5%）。
 * 默认 FOLLOW_DIRECT（跟随「直连出口」），语义与 DIRECT 一致：绕开可能坏掉的 VPN 走物理网。
 */
@Composable
private fun DohEgressPicker(
    choice: DohEgressChoice,
    onPick: (DohEgressChoice) -> Unit,
) {
    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)) {
        Text(
            stringResource(R.string.doh_egress_title),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            stringResource(R.string.doh_egress_sub),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            listOf(
                DohEgressChoice.FOLLOW_DIRECT to R.string.doh_egress_follow_direct,
                DohEgressChoice.DEFAULT to R.string.doh_egress_default,
                DohEgressChoice.WIFI to R.string.egress_wifi,
                DohEgressChoice.CELLULAR to R.string.egress_cellular,
                DohEgressChoice.ETHERNET to R.string.egress_ethernet,
            ).forEach { (c, labelRes) ->
                FilterChip(
                    selected = choice == c,
                    onClick = { onPick(c) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
        // 说明收进 ⓘ(工作台纪律:常驻解释句不占版面)。
        InfoDot(stringResource(R.string.doh_egress_title), stringResource(R.string.doh_egress_hint))
    }
}

/** 行间分隔:M3 列表不用 divider(2026-08 工作台对齐),行距由 Row 自身撑起——保留空实现少动调用点。 */
@Composable
private fun RowDivider() {
}

/** DoH 绿徽章（一次性形态）：与 [ProtoBadge] 同构，取头像板草绿档。 */
@Composable
private fun DohBadge() {
    val (bg, fg) = avatarColors(2)
    Text(
        "DoH",
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
        color = fg,
        modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(bg).padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/**
 * 协议、端口、开关同行；大字体下改为两行，保持五位端口完整。无端口的 DoH 保留原说明行。
 * 端口仅在「完成/失焦」时提交有效值（避免边打字边热重启监听到中间无效端口）；
 * 校验范围(1024–65535)/跨协议重复，后台 bind 失败（端口被占用）也回显——三种错误红字在行下展开。
 */
@Composable
private fun ProtoPortRow(
    badge: @Composable () -> Unit,
    name: String,
    sub: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    port: Int? = null,
    otherPorts: Set<Int> = emptySet(),
    bindError: Boolean = false,
    portLabel: String? = null,
    onCommitPort: ((Int) -> Unit)? = null,
    tag: String = name,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var text by remember(port) { mutableStateOf(port?.toString().orEmpty()) }
        val parsed = text.toIntOrNull()
        val rangeError = port != null && (parsed == null || parsed !in 1024..65535)
        val dupError = port != null && !rangeError && parsed in otherPorts
        val invalid = rangeError || dupError
        val focusManager = LocalFocusManager.current

        fun commitIfValid() {
            val valid = if (invalid) null else parsed
            if (valid != null && port != null && valid != port) onCommitPort?.invoke(valid)
        }

        if (port == null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) { badge() }
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onToggle, colors = stdSwitchColors())
        }
        if (port != null) {
            var focused by remember { mutableStateOf(false) }
            @Composable fun portField(modifier: Modifier) { OutlinedTextField(
                value = text, onValueChange = { text = it.filter(Char::isDigit).take(5) },
                label = { Text(stringResource(R.string.ui_port)) }, singleLine = true, isError = invalid || bindError,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commitIfValid(); focusManager.clearFocus() }),
                modifier = modifier.testTag("port_input_$tag").semantics { if (portLabel != null) contentDescription = portLabel }
                    .onFocusChanged { focus ->
                        // Preserve the old Done/blur commit boundary; never rebind on each keystroke.
                        if (focused && !focus.isFocused) {
                            if (invalid) text = port.toString() else commitIfValid()
                        }
                        focused = focus.isFocused
                    },
            ) }
            @Composable fun toggle() {
                Switch(checked = checked, onCheckedChange = onToggle, colors = stdSwitchColors(),
                    modifier = Modifier.testTag("protocol_enabled_$tag").semantics { contentDescription = name })
            }
            val density = LocalDensity.current
            val measure = rememberTextMeasurer()
            val valueStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)
            val fieldWidth = with(density) { measure.measure("65535", valueStyle).size.width.toDp() } + 40.dp
            // Keep all three rows in the same layout, including at large font sizes.
            val nameWidth = with(density) { measure.measure("SOCKS5", MaterialTheme.typography.titleSmall).size.width.toDp() }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= nameWidth + fieldWidth + 76.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        portField(Modifier.width(fieldWidth))
                        toggle()
                    }
                } else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        toggle()
                    }
                    portField(Modifier.fillMaxWidth())
                }
            }
        }
        // 三种错误红字（原逻辑迁移）：范围/重复只在编辑中出现（失焦即回退），bind 失败常显。
        val msg = when {
            rangeError -> stringResource(R.string.port_error_range)
            dupError -> stringResource(R.string.port_error_duplicate)
            bindError -> stringResource(R.string.port_error_in_use)
            else -> null
        }
        if (msg != null) {
            Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

// ---------- 认证 ----------

/** 认证卡：卡头（挂锁盘 + 未开启时「有风险」红徽章）+ 开关；未开启=红警示横幅，开启=凭据输入。 */
@Composable
private fun AuthCard(ui: MainUiState, viewModel: MainViewModel) {
    val s = ui.settings
    BentoCard {
        CardHeader(
            title = stringResource(R.string.settings_auth),
            icon = painterResource(R.drawable.ic_b_lock),
            iconBg = StatusColors.warnContainer(),
            iconTint = StatusColors.warn(),
            trailing = {
                if (!s.authEnabled) {
                    CountBadge(
                        stringResource(R.string.settings_risk_tag),
                        fg = MaterialTheme.colorScheme.onErrorContainer,
                        bg = MaterialTheme.colorScheme.errorContainer,
                    )
                }
            },
        )
        LabeledSwitchRow(
            title = stringResource(R.string.auth_enable),
            subtitle = stringResource(R.string.auth_enable_sub),
            checked = s.authEnabled,
            onCheckedChange = viewModel::setAuthEnabled,
        )
        if (s.authEnabled) {
            AuthCredentials(ui.credentials, viewModel::setCredentials)
        } else {
            WarnBanner(
                stringResource(R.string.auth_warning),
                level = BannerLevel.Error,
                icon = painterResource(R.drawable.ic_b_shield_alert),
            )
        }
    }
}

/**
 * 认证凭据输入（开启认证时）。密码经 Keystore 加密持久化（[CredentialStore]）；
 * 在「完成/失焦」时提交，避免每键写盘。两项均非空才算有效。
 */
@Composable
private fun AuthCredentials(
    credentials: CredentialStore.Credentials,
    onCommit: (String, String) -> Unit,
) {
    var username by remember(credentials.username) { mutableStateOf(credentials.username) }
    var password by remember(credentials.password) { mutableStateOf(credentials.password) }
    var visible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    fun commit() {
        // 空密码且原本已设置 → 视为"未改密码"，保留原密文：防止编辑中临时清空 + 失焦把已存凭据误删。
        val effective = if (password.isBlank() && credentials.password.isNotBlank()) credentials.password else password
        onCommit(username, effective)
    }

    OutlinedTextField(
        value = username,
        onValueChange = { username = it },
        label = { Text(stringResource(R.string.auth_username)) },
        singleLine = true,
        shape = MaterialTheme.shapes.large,
        isError = username.isBlank(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.isFocused) commit() },
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(stringResource(R.string.auth_password)) },
        singleLine = true,
        shape = MaterialTheme.shapes.large,
        isError = password.isBlank(),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit(); focusManager.clearFocus() }),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) {
                Text(stringResource(if (visible) R.string.auth_hide else R.string.auth_show))
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.isFocused) commit() },
    )
    if (username.isBlank() || password.isBlank()) {
        Text(
            stringResource(R.string.auth_incomplete),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ---------- 隐私与安全 ----------

/** 隐私与安全（反 SSRF 开关）：轻量 strip 感 → Sunken 档卡。 */
@Composable
private fun PrivacyCard(ui: MainUiState, viewModel: MainViewModel) {
    val (iconBg, iconFg) = avatarColors(1)
    BentoCard(tier = CardTier.Sunken) {
        CardHeader(
            title = stringResource(R.string.settings_privacy),
            icon = painterResource(R.drawable.ic_b_shield_check),
            iconBg = iconBg,
            iconTint = iconFg,
        )
        LabeledSwitchRow(
            title = stringResource(R.string.settings_block_private),
            subtitle = stringResource(R.string.block_private_sub),
            checked = ui.settings.blockPrivateLanEgress,
            onCheckedChange = viewModel::setBlockPrivateLan,
        )
        LabeledSwitchRow(
            title = stringResource(R.string.settings_log_enabled),
            subtitle = stringResource(R.string.settings_log_enabled_sub),
            checked = ui.settings.logEnabled,
            onCheckedChange = viewModel::setLogEnabled,
        )
    }
}

// ---------- 性能预设 | 通用（双列 bento） ----------

/** 性能预设卡：2×2 预设 chips + 当前档参数小字；「自定义」选中时展开滑块（逻辑原样）。 */
@Composable
private fun PresetCard(
    preset: PerformancePreset,
    limits: ConnectionLimits,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val (iconBg, iconFg) = avatarColors(4)
    // 折叠形态(用户拍板):平时收起只显当前档摘要,点开才是档位选择与滑块。
    var expanded by rememberSaveable { mutableStateOf(false) }
    BentoCard(modifier, contentPadding = 12.dp, spacing = 8.dp, onClick = { expanded = !expanded }) {
        CardHeader(
            title = stringResource(R.string.settings_preset),
            icon = painterResource(R.drawable.ic_b_gauge),
            iconBg = iconBg,
            iconTint = iconFg,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CountBadge(
                        stringResource(preset.labelRes()),
                        fg = MaterialTheme.colorScheme.onPrimaryContainer,
                        bg = MaterialTheme.colorScheme.primaryContainer,
                    )
                    Icon(
                        painterResource(if (expanded) R.drawable.ic_b_chevron_up else R.drawable.ic_b_chevron_down),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            },
        )
        if (!expanded) {
            // 收起态摘要:当前生效参数一行小字。
            Text(
                stringResource(R.string.limit_global) + " ${limits.maxGlobalConnections} · " +
                    stringResource(R.string.limit_per_client) + " ${limits.maxPerClientConnections} · " +
                    stringResource(R.string.limit_idle) + " ${limits.idleTimeoutSeconds}s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            return@BentoCard
        }
        // 2×2 预设 chips（选中带对勾，HTML chip.sel）。
        PerformancePreset.entries.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pair.forEach { p ->
                    FilterChip(
                        modifier = Modifier.weight(1f),
                        selected = p == preset,
                        onClick = { viewModel.setPreset(p) },
                        colors = stdFilterChipColors(),
                        leadingIcon = if (p == preset) {
                            { Icon(painterResource(R.drawable.ic_b_check), contentDescription = null, Modifier.size(14.dp)) }
                        } else null,
                        label = {
                            Text(
                                stringResource(p.labelRes()),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                    )
                }
            }
        }
        // 当前档参数小字（2×2）：任何预设都能一眼看到实际生效值。
        val spec = listOf(
            stringResource(R.string.limit_global) to limits.maxGlobalConnections.toString(),
            stringResource(R.string.limit_per_client) to limits.maxPerClientConnections.toString(),
            stringResource(R.string.limit_buffer) to (limits.relayBufferBytes / 1024).toString(),
            stringResource(R.string.limit_idle) to limits.idleTimeoutSeconds.toString(),
        )
        spec.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (k, v) -> SpecItem(k, v, Modifier.weight(1f)) }
            }
        }
        if (preset == PerformancePreset.CUSTOM) {
            CustomLimits(limits, viewModel)
        }
    }
}

/** 参数小项：label 灰小字 + tnum 加粗值。 */
@Composable
private fun SpecItem(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(value, style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"))
    }
}

@Composable
private fun CustomLimits(limits: ConnectionLimits, viewModel: MainViewModel) {
    StatLabel(stringResource(R.string.settings_limits))
    LimitSlider(stringResource(R.string.limit_global), limits.maxGlobalConnections, ConnectionLimits.RANGE_GLOBAL) {
        viewModel.setCustomLimits(limits.copy(maxGlobalConnections = it))
    }
    LimitSlider(stringResource(R.string.limit_per_client), limits.maxPerClientConnections, ConnectionLimits.RANGE_PER_CLIENT) {
        viewModel.setCustomLimits(limits.copy(maxPerClientConnections = it))
    }
    // 「转发并行度」滑块已移除：NIO relay 用 selector（按 CPU 核数自动拉满），不再是「每隧道 2 线程」模型，
    // 该参数对 HTTPS/SOCKS 主流量无效。buffer 上限对齐 128KiB（RANGE_BUFFER_BYTES）。
    LimitSlider(stringResource(R.string.limit_buffer), limits.relayBufferBytes / 1024, 8..128) {
        viewModel.setCustomLimits(limits.copy(relayBufferBytes = it * 1024))
    }
    LimitSlider(stringResource(R.string.limit_idle), limits.idleTimeoutSeconds, ConnectionLimits.RANGE_IDLE_SECONDS) {
        viewModel.setCustomLimits(limits.copy(idleTimeoutSeconds = it))
    }
}

@Composable
private fun LimitSlider(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth()) {
        Text(
            "$label: ${v.toInt()}",
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
        )
        Slider(
            value = v,
            onValueChange = { v = it },
            onValueChangeFinished = { onChange(v.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

/** 通用卡：语言 / 外观 各一小节三段 chips（FlowRow 自适应——中文「跟随系统」在窄列可换行不截断）+ 显示 IPv6 开关。 */
@Composable
private fun GeneralCard(
    language: AppLanguage,
    themeMode: ThemeMode,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    BentoCard(modifier, contentPadding = 12.dp, spacing = 6.dp) {
        CardHeader(
            title = stringResource(R.string.settings_general),
            icon = painterResource(R.drawable.ic_tune),
            iconBg = MaterialTheme.colorScheme.surfaceContainerHighest,
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.settings_language),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OptionChipFlow(AppLanguage.entries, language, { stringResource(it.labelRes()) }, viewModel::setLanguage)
        Text(
            stringResource(R.string.settings_appearance),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OptionChipFlow(ThemeMode.entries, themeMode, { stringResource(it.labelRes()) }, viewModel::setThemeMode)
    }
}

/**
 * 「显示 IPv6 地址」卡——设置/入口段（工作台设计稿：它决定入口列表**列出什么**，
 * 与准入网段同属一件事）。副标题写明只影响显示、代理本身仍双栈——防「功能被删」误读。
 */
@Composable
private fun Ipv6Card(showIpv6: Boolean, viewModel: MainViewModel) {
    BentoCard(tier = CardTier.Default, contentPadding = 12.dp, spacing = 0.dp) {
        LabeledSwitchRow(
            title = stringResource(R.string.settings_show_ipv6),
            subtitle = stringResource(R.string.settings_show_ipv6_sub),
            checked = showIpv6,
            onCheckedChange = viewModel::setShowIpv6,
        )
    }
}

/** 选项 chips（FlowRow 自适应换行）：窄列 bento 里保证双语都不截断。 */
@Composable
private fun <T> OptionChipFlow(
    options: Iterable<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    val chipColors = stdFilterChipColors()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                colors = chipColors,
                label = { Text(label(option), style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

// ---------- 导航栏编辑 ----------

/** 导航栏编辑卡（Sunken 底 + 白格子）：卡头带「点击格子移动」小注，格子/徽标逻辑原样。 */
@Composable
private fun NavEditCard(hiddenTabs: Set<String>, onSetHidden: (String, Boolean) -> Unit) {
    val (iconBg, iconFg) = avatarColors(0)
    BentoCard(tier = CardTier.Sunken) {
        CardHeader(
            title = stringResource(R.string.settings_nav),
            icon = painterResource(R.drawable.ic_b_navbar),
            iconBg = iconBg,
            iconTint = iconFg,
            trailing = { StatLabel(stringResource(R.string.settings_nav_tap_hint)) },
        )
        NavBarEditor(hiddenTabs = hiddenTabs, onSetHidden = onSetHidden)
    }
}

/**
 * 导航栏自定义（支付宝「我的应用」编辑模式）：上排=当前显示的 tab，可隐藏项右上角红「−」徽标；
 * 下方「已隐藏」区，项右上角绿「+」徽标；点击整项（≥48dp 热区）在两区间移动，即时生效。
 * 主页/设置为固定项（HTML 稿降透明度示意「不可动」）。徽标内含 −/+ 符号而非纯色点——
 * WCAG 1.4.1 禁止仅靠颜色传达含义（红绿色盲无法区分红/绿），纯红点还会被误读为未读通知。
 */
@Composable
private fun NavBarEditor(hiddenTabs: Set<String>, onSetHidden: (String, Boolean) -> Unit) {
    val visible = NavTab.visible(hiddenTabs)
    val hidden = NavTab.entries.filter { !it.fixed && it.route in hiddenTabs }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        visible.forEach { tab ->
            NavTabCell(
                tab = tab,
                badge = if (tab.fixed) null else NavBadge.REMOVE,
                onClick = if (tab.fixed) null else ({ onSetHidden(tab.route, true) }),
                modifier = Modifier.weight(1f),
            )
        }
        // 少于总数时补空位，保持格宽稳定不跳动（按全部 tab 数，随 NavTab 增减自适应）。
        repeat(NavTab.entries.size - visible.size) { Spacer(Modifier.weight(1f)) }
    }

    if (hidden.isEmpty()) {
        // 空态：label + 虚线框占位（HTML hiddenbox）。
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatLabel(stringResource(R.string.nav_hidden_section))
            val outline = MaterialTheme.colorScheme.outline
            Box(
                Modifier
                    .weight(1f)
                    .height(28.dp)
                    .alpha(0.75f)
                    .drawBehind {
                        drawRoundRect(
                            color = outline,
                            cornerRadius = CornerRadius(10.dp.toPx()),
                            style = Stroke(
                                width = 1.2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                            ),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.nav_hidden_empty),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        StatLabel(stringResource(R.string.nav_hidden_section))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            hidden.forEach { tab ->
                NavTabCell(
                    tab = tab,
                    badge = NavBadge.ADD,
                    onClick = { onSetHidden(tab.route, false) },
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(NavTab.entries.size - hidden.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    Text(
        stringResource(R.string.nav_customize_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private enum class NavBadge { REMOVE, ADD }

/** 导航项格子：白底圆角小卡 + 图标 + 名称，右上角可选 −/+ 徽标；整格可点，徽标只是视觉提示。 */
@Composable
private fun NavTabCell(
    tab: NavTab,
    badge: NavBadge?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(tab.label)
    val a11y = when (badge) {
        NavBadge.REMOVE -> stringResource(R.string.nav_hide_a11y, label)
        NavBadge.ADD -> stringResource(R.string.nav_restore_a11y, label)
        null -> label
    }
    val dark = LocalDarkTheme.current
    // 格底 = Primary 档面色（Sunken 卡上浮出的白格，HTML --cell-bg）。
    val cellBg = if (dark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest
    val cellShape = RoundedCornerShape(12.dp)
    val edge = sunkenColor()
    val clickMod = if (onClick != null) Modifier.clickable(onClickLabel = a11y, onClick = onClick) else Modifier
    // 外层 Box 不裁剪、留出顶部空间——徽标挂在外层,不会被点击区的圆角 clip 切掉(修「徽标被遮挡」)。
    Box(modifier.padding(top = 6.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .alpha(if (tab.fixed) 0.55f else 1f)
                .clip(cellShape)
                .background(cellBg)
                .then(clickMod)
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                painterResource(tab.icon),
                contentDescription = a11y,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 徽标：红−/绿+，含符号（不只靠颜色）。悬于外层右上角（上移 6dp 进预留区），描 Sunken 底色边浮出。
        if (badge != null) {
            val (bg, sym) = when (badge) {
                NavBadge.REMOVE -> StatusColors.bad() to "−"
                NavBadge.ADD -> StatusColors.good() to "+"
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = (-6).dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(bg)
                    .border(1.5.dp, edge, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    sym,
                    color = MaterialTheme.colorScheme.surface,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

// ---------- 帮助双联 ----------

/** 帮助/引导入口小卡：图标 + 文案 + 右尖角，整卡可点。 */
@Composable
private fun LinkTile(icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    BentoCard(modifier, tier = CardTier.Sunken, onClick = onClick, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painterResource(R.drawable.ic_b_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** 关于卡(通用段尾):版本号 + 开源地址。版本此前全 app 无处可看,排查沟通都靠猜。 */
@Composable
private fun AboutCard() {
    val context = LocalContext.current
    BentoCard(tier = CardTier.Sunken, contentPadding = 12.dp, spacing = 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.about_version),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // BuildConfig 未启用(项目关掉了 buildFeatures.buildConfig),运行时从 PackageManager 取。
            val pkg = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
            }
            Text(
                (pkg?.versionName ?: "?") + " (" + (pkg?.longVersionCode ?: 0) + ")",
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/wresource/hxmy-proxy"),
                        ),
                    )
                }
            },
        ) {
            Text(
                stringResource(R.string.about_source),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
