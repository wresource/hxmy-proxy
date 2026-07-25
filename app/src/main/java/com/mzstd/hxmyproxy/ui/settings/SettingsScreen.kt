package com.mzstd.hxmyproxy.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
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
import com.mzstd.hxmyproxy.ui.components.BannerLevel
import com.mzstd.hxmyproxy.ui.components.BentoCard
import com.mzstd.hxmyproxy.ui.components.CardHeader
import com.mzstd.hxmyproxy.ui.components.CardTier
import com.mzstd.hxmyproxy.ui.components.CountBadge
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

/**
 * 设置页（Bento 版，规格=images/html/05-settings.html）：
 * 页头 → 协议与端口 hero（四行合一）→ 认证 → 隐私与安全 → 性能预设|通用双列 → 导航栏编辑 → 帮助双联。
 * 所有改动即时生效（无保存按钮），行为与旧版完全一致，只换视觉骨架。
 */
@Composable
fun SettingsScreen(
    ui: MainUiState,
    viewModel: MainViewModel,
    onOpenHelp: () -> Unit = {},
    onReplayOnboarding: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val s = ui.settings
    Column(
        // 沉浸式:inset padding 放 verticalScroll **之后**(属于被滚动内容,可随滚动穿入系统栏后方)。
        modifier = Modifier
            .fillMaxSize()
            .consumeWindowInsets(contentPadding)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 页头：品牌 mark 圆盘 + 标题 + 「改动即时生效」小注。
        PageHeader(
            title = stringResource(R.string.nav_settings),
            icon = painterResource(R.drawable.ic_b_arrow_right),
            trailing = { StatLabel(stringResource(R.string.settings_mode_line)) },
        )

        // 卡片按理论使用频率高→低排列：核心代理配置 → 日常外观/语言 → 安全（认证/隐私）→
        // 高级调优（性能）→ 个性化（导航栏）→ 帮助。性能/通用不再并排双列（窄卡挤字），各自全宽。
        ProtoPortsCard(ui, viewModel)
        GeneralCard(s.language, s.themeMode, viewModel, Modifier.fillMaxWidth())
        AuthCard(ui, viewModel)
        PrivacyCard(ui, viewModel)
        PresetCard(s.preset, s.limits, viewModel, Modifier.fillMaxWidth())
        NavEditCard(hiddenTabs = s.hiddenTabs, onSetHidden = viewModel::setTabHidden)
        BackupCard(viewModel)

        // 帮助 / 重看引导 双联入口卡。
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LinkTile(R.drawable.ic_b_help, stringResource(R.string.help_open), onOpenHelp, Modifier.weight(1f))
            LinkTile(R.drawable.ic_b_replay, stringResource(R.string.replay_onboarding), onReplayOnboarding, Modifier.weight(1f))
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

// ---------- 协议与端口（hero 合并卡） ----------

/**
 * 协议与端口合一 hero：每行 = 协议徽章 + 名称/副文案 + 端口小输入框 + 开关；DoH 行绿徽章无端口。
 * 端口重复校验包含未启用协议的端口：端口值持久化，启用后仍会撞端口，故配置期就该拦下。
 */
@Composable
private fun ProtoPortsCard(ui: MainUiState, viewModel: MainViewModel) {
    val s = ui.settings
    val bindErrors = ui.share.portBindErrors
    // meta 小注：当前入口 IP（优先已选接口，其次推荐入口）；没有就不显示。
    val host = ui.share.interfaces.firstOrNull { it.isSelected }?.address?.hostAddress
        ?: ui.share.recommendedEntries.firstOrNull()?.host
    val (iconBg, iconFg) = avatarColors(0)

    BentoCard(tier = CardTier.Primary, spacing = 0.dp) {
        CardHeader(
            title = stringResource(R.string.settings_proto_ports),
            icon = painterResource(R.drawable.ic_b_swap),
            iconBg = iconBg,
            iconTint = iconFg,
            trailing = {
                if (host != null) {
                    Text(
                        host,
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            },
        )
        Spacer(Modifier.height(2.dp))
        ProtoPortRow(
            badge = { ProtoBadge(ProxyProtocol.HTTP) },
            name = stringResource(R.string.proto_http),
            sub = stringResource(R.string.proto_http_sub),
            checked = s.httpEnabled,
            onToggle = viewModel::setHttpEnabled,
            port = s.httpPort,
            otherPorts = setOf(s.socksPort, s.pacPort),
            bindError = ProxyProtocol.HTTP in bindErrors,
            portLabel = stringResource(R.string.port_http),
            onCommitPort = viewModel::setHttpPort,
        )
        RowDivider()
        ProtoPortRow(
            badge = { ProtoBadge(ProxyProtocol.SOCKS5) },
            name = stringResource(R.string.proto_socks),
            sub = stringResource(R.string.proto_socks_sub),
            checked = s.socksEnabled,
            onToggle = viewModel::setSocksEnabled,
            port = s.socksPort,
            otherPorts = setOf(s.httpPort, s.pacPort),
            bindError = ProxyProtocol.SOCKS5 in bindErrors,
            portLabel = stringResource(R.string.port_socks),
            onCommitPort = viewModel::setSocksPort,
        )
        RowDivider()
        ProtoPortRow(
            badge = { ProtoBadge(ProxyProtocol.PAC) },
            name = stringResource(R.string.proto_pac_full),
            sub = stringResource(R.string.proto_pac_sub),
            checked = s.pacEnabled,
            onToggle = viewModel::setPacEnabled,
            port = s.pacPort,
            otherPorts = setOf(s.httpPort, s.socksPort),
            bindError = ProxyProtocol.PAC in bindErrors,
            portLabel = stringResource(R.string.port_pac),
            onCommitPort = viewModel::setPacPort,
        )
        RowDivider()
        // DoH 行：绿徽章、无端口（固定上游 8.8.8.8 / 1.1.1.1）。
        ProtoPortRow(
            badge = { DohBadge() },
            name = stringResource(R.string.backup_dns),
            sub = stringResource(R.string.backup_dns_sub),
            checked = s.backupDnsEnabled,
            onToggle = viewModel::setBackupDnsEnabled,
        )
    }
}

/** hero 卡内的行间发丝分隔线。 */
@Composable
private fun RowDivider() =
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

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
 * 协议行：徽章（56dp 定宽列对齐）+ 名称/副文案 + 端口小输入框（[port]=null 则无）+ 开关。
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

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // 定宽徽章列：HTTP/SOCKS5/PAC/DoH 徽章宽度不一，定宽居中让名称列左缘对齐。
            Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) { badge() }
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // 副文案允许换行：中文短(1 行)，英文即便精简仍偏长——被端口框挤窄的列里换 2 行显示全，不损信息。
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (port != null) {
                var focused by remember { mutableStateOf(false) }
                val borderColor = when {
                    invalid || bindError -> MaterialTheme.colorScheme.error
                    focused -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outlineVariant
                }
                val boxShape = RoundedCornerShape(9.dp)
                Row(
                    Modifier
                        .clip(boxShape)
                        .background(sunkenColor())
                        .border(1.dp, borderColor, boxShape)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        ":",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.outline,
                    )
                    BasicTextField(
                        value = text,
                        onValueChange = { new -> text = new.filter(Char::isDigit).take(5) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = if (invalid || bindError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { commitIfValid(); focusManager.clearFocus() }),
                        modifier = Modifier
                            .width(44.dp)
                            .semantics { if (portLabel != null) contentDescription = portLabel }
                            .onFocusChanged { focus ->
                                focused = focus.isFocused
                                // 失焦：有效则提交，无效则回退到上次有效端口（避免悬而未决的脏输入）。
                                if (!focus.isFocused) {
                                    if (invalid) text = port.toString() else commitIfValid()
                                }
                            },
                    )
                }
            }
            Switch(checked = checked, onCheckedChange = onToggle, colors = stdSwitchColors())
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
    BentoCard(modifier, contentPadding = 12.dp, spacing = 8.dp) {
        CardHeader(
            title = stringResource(R.string.settings_preset),
            icon = painterResource(R.drawable.ic_b_gauge),
            iconBg = iconBg,
            iconTint = iconFg,
        )
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
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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

/** 通用卡：语言 / 外观 各一小节三段 chips（FlowRow 自适应——中文「跟随系统」在窄列可换行不截断）。 */
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
