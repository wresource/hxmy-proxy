package com.mzstd.hxmyproxy.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.model.AppLanguage
import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.model.PerformancePreset
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.core.model.ThemeMode
import com.mzstd.hxmyproxy.core.model.VpnDownStrategy
import com.mzstd.hxmyproxy.data.repository.CredentialStore
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.theme.LocalDarkTheme

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

private fun VpnDownStrategy.labelRes() = when (this) {
    VpnDownStrategy.CONTINUE -> R.string.vpn_continue
    VpnDownStrategy.BLOCK -> R.string.vpn_block
    VpnDownStrategy.WARN -> R.string.vpn_warn
}

@Composable
fun SettingsScreen(
    ui: MainUiState,
    viewModel: MainViewModel,
    onOpenHelp: () -> Unit = {},
    onReplayOnboarding: () -> Unit = {},
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 分组圆角容器（Pixel 系统设置样式）：设置页与主页/规则页统一「卡片分组」语言。
        // 语言+外观内容同构（都是三选 chips），合成一张「通用」卡两小节，chips 左缘对齐。
        SettingsGroup(stringResource(R.string.settings_general)) {
            Text(
                stringResource(R.string.settings_language),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChipRow(AppLanguage.entries, s.language, { stringResource(it.labelRes()) }, viewModel::setLanguage, evenWidth = true)
            Text(
                stringResource(R.string.settings_appearance),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChipRow(ThemeMode.entries, s.themeMode, { stringResource(it.labelRes()) }, viewModel::setThemeMode, evenWidth = true)
        }

        SettingsGroup(stringResource(R.string.settings_preset)) {
            ChipRow(PerformancePreset.entries, s.preset, { stringResource(it.labelRes()) }, viewModel::setPreset)
            if (s.preset == PerformancePreset.CUSTOM) {
                CustomLimits(s.limits, viewModel)
            }
        }

        SettingsGroup(stringResource(R.string.settings_protocols)) {
            SwitchRow(stringResource(R.string.proto_http), s.httpEnabled, viewModel::setHttpEnabled, stringResource(R.string.proto_http_sub))
            SwitchRow(stringResource(R.string.proto_socks), s.socksEnabled, viewModel::setSocksEnabled, stringResource(R.string.proto_socks_sub))
            SwitchRow(stringResource(R.string.proto_pac), s.pacEnabled, viewModel::setPacEnabled, stringResource(R.string.proto_pac_sub))
        }

        SettingsGroup(stringResource(R.string.settings_ports)) {
            val bindErrors = ui.share.portBindErrors
            // 重复校验包含未启用协议的端口：端口值持久化，启用后仍会撞端口，故配置期就该拦下。
            PortField(
                stringResource(R.string.port_http), s.httpPort,
                otherPorts = setOf(s.socksPort, s.pacPort),
                bindError = ProxyProtocol.HTTP in bindErrors, onCommit = viewModel::setHttpPort,
            )
            PortField(
                stringResource(R.string.port_socks), s.socksPort,
                otherPorts = setOf(s.httpPort, s.pacPort),
                bindError = ProxyProtocol.SOCKS5 in bindErrors, onCommit = viewModel::setSocksPort,
            )
            PortField(
                stringResource(R.string.port_pac), s.pacPort,
                otherPorts = setOf(s.httpPort, s.socksPort),
                bindError = ProxyProtocol.PAC in bindErrors, onCommit = viewModel::setPacPort,
            )
        }

        SettingsGroup(stringResource(R.string.settings_auth)) {
            SwitchRow(stringResource(R.string.auth_enable), s.authEnabled, viewModel::setAuthEnabled)
            if (s.authEnabled) {
                AuthCredentials(ui.credentials, viewModel::setCredentials)
            } else {
                Text(
                    stringResource(R.string.auth_warning),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        SettingsGroup(stringResource(R.string.settings_vpn_strategy)) {
            ChipRow(VpnDownStrategy.entries, s.vpnDownStrategy, { stringResource(it.labelRes()) }, viewModel::setVpnStrategy)
        }

        SettingsGroup(stringResource(R.string.settings_privacy)) {
            SwitchRow(
                stringResource(R.string.settings_block_private), s.blockPrivateLanEgress,
                viewModel::setBlockPrivateLan, stringResource(R.string.block_private_sub),
            )
        }

        SettingsGroup(stringResource(R.string.settings_nav)) {
            NavBarEditor(hiddenTabs = s.hiddenTabs, onSetHidden = viewModel::setTabHidden)
        }

        // 帮助/引导合成一张无标题导航卡(与监控页同 NavRow 组件),替代两个裸 TextButton——样式全 app 闭环。
        com.mzstd.hxmyproxy.ui.components.GroupCard(title = null) {
            com.mzstd.hxmyproxy.ui.components.NavRow(stringResource(R.string.help_open), onOpenHelp)
            com.mzstd.hxmyproxy.ui.components.NavRow(stringResource(R.string.replay_onboarding), onReplayOnboarding)
        }
    }
}

/** 分组圆角容器（委托公共 [com.mzstd.hxmyproxy.ui.components.GroupCard]，与监控页共用）。 */
@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) =
    com.mzstd.hxmyproxy.ui.components.GroupCard(title, content = content)

@Composable
private fun CustomLimits(limits: ConnectionLimits, viewModel: MainViewModel) {
    SectionTitle(stringResource(R.string.settings_limits))
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
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            // 副标题说明：一行讲清这项是干嘛的（审计:原来只有一行字,新手看不懂每项含义）。
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * 选项 chips 一行。[evenWidth]=true 时**等宽平分整行**（Row + weight，label 居中）——用于选项少且
 * 定长的语言/外观(各 3 选),消除「chip 随文字长短不齐」的观感；false 用 FlowRow 自适应换行（选项多/长
 * 如性能预设 4 选,等宽会挤,交回自适应）。
 */
@Composable
private fun <T> ChipRow(
    options: Iterable<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    evenWidth: Boolean = false,
) {
    // 选中态复用「按钮/滑块」那套亮蓝(强调色全 app 一套):浅色=浅蓝底深字、深色=亮蓝底深字。
    val cs = MaterialTheme.colorScheme
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = if (LocalDarkTheme.current) cs.primary else cs.primaryContainer,
        selectedLabelColor = if (LocalDarkTheme.current) cs.onPrimary else cs.onPrimaryContainer,
    )
    if (evenWidth) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    modifier = Modifier.weight(1f),
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    colors = chipColors,
                    label = {
                        Text(
                            label(option),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
            }
        }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    colors = chipColors,
                    label = { Text(label(option)) },
                )
            }
        }
    }
}

/**
 * 端口输入：仅在「完成/失焦」时提交有效值（避免边打字边热重启监听到中间无效端口），
 * 校验范围(1024–65535)与跨协议重复；后台 bind 失败（端口被占用）也回显红字。
 */
@Composable
private fun PortField(
    label: String,
    value: Int,
    otherPorts: Set<Int>,
    bindError: Boolean,
    onCommit: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    val rangeError = parsed == null || parsed !in 1024..65535
    val dupError = !rangeError && parsed in otherPorts
    val invalid = rangeError || dupError
    val valid: Int? = if (invalid) null else parsed
    val focusManager = LocalFocusManager.current

    fun commitIfValid() {
        if (valid != null && valid != value) onCommit(valid)
    }

    OutlinedTextField(
        value = text,
        onValueChange = { new -> text = new.filter(Char::isDigit).take(5) },
        label = { Text(label) },
        singleLine = true,
        isError = invalid || bindError,
        supportingText = {
            val msg = when {
                rangeError -> stringResource(R.string.port_error_range)
                dupError -> stringResource(R.string.port_error_duplicate)
                bindError -> stringResource(R.string.port_error_in_use)
                else -> null
            }
            if (msg != null) Text(msg, color = MaterialTheme.colorScheme.error)
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commitIfValid(); focusManager.clearFocus() }),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focus ->
                // 失焦：有效则提交，无效则回退到上次有效端口（避免悬而未决的脏输入）。
                if (!focus.isFocused) {
                    if (invalid) text = value.toString() else commitIfValid()
                }
            },
    )
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

@Composable
private fun LimitSlider(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth()) {
        Text("$label: ${v.toInt()}", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = v,
            onValueChange = { v = it },
            onValueChangeFinished = { onChange(v.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

/**
 * 导航栏自定义（支付宝「我的应用」编辑模式）：上排=当前显示的 tab，可隐藏项右上角红「−」徽标；
 * 下方「已隐藏」区，项右上角绿「+」徽标；点击整项（≥48dp 热区）在两区间移动，即时生效。
 * 主页/设置为固定项不显示徽标（不加锁不置灰，噪音最小）。徽标内含 −/+ 符号而非纯色点——
 * WCAG 1.4.1 禁止仅靠颜色传达含义（红绿色盲无法区分红/绿），纯红点还会被误读为未读通知。
 */
@Composable
private fun NavBarEditor(hiddenTabs: Set<String>, onSetHidden: (String, Boolean) -> Unit) {
    val visible = com.mzstd.hxmyproxy.ui.NavTab.visible(hiddenTabs)
    val hidden = com.mzstd.hxmyproxy.ui.NavTab.entries.filter { !it.fixed && it.route in hiddenTabs }

    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        visible.forEach { tab ->
            NavTabCell(
                tab = tab,
                badge = if (tab.fixed) null else NavBadge.REMOVE,
                onClick = if (tab.fixed) null else ({ onSetHidden(tab.route, true) }),
                modifier = Modifier.weight(1f),
            )
        }
        // 少于 4 个时补空位，保持格宽稳定不跳动。
        repeat(4 - visible.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
    }

    Text(
        stringResource(R.string.nav_hidden_section),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (hidden.isEmpty()) {
        Text(
            stringResource(R.string.nav_hidden_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            hidden.forEach { tab ->
                NavTabCell(
                    tab = tab,
                    badge = NavBadge.ADD,
                    onClick = { onSetHidden(tab.route, false) },
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(4 - hidden.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
        }
    }
    Text(
        stringResource(R.string.nav_customize_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private enum class NavBadge { REMOVE, ADD }

/** 导航项格子：图标+名称，右上角可选 −/+ 徽标；整格可点（≥48dp 热区），徽标只是视觉提示。 */
@Composable
private fun NavTabCell(
    tab: com.mzstd.hxmyproxy.ui.NavTab,
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
    val clickMod = if (onClick != null) {
        Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = a11y, onClick = onClick)
    } else {
        Modifier
    }
    // 外层 Box 不裁剪、留出顶部空间——徽标挂在外层,不会被点击区的圆角 clip 切掉(修「徽标被遮挡」)。
    Box(modifier.padding(top = 6.dp)) {
        Column(
            Modifier.fillMaxWidth().then(clickMod).padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            androidx.compose.material3.Icon(
                androidx.compose.ui.res.painterResource(tab.icon),
                contentDescription = a11y,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        // 徽标：红−/绿+，含符号（不只靠颜色）。悬于外层右上角（上移 6dp 进预留区），完整显示不被裁。
        if (badge != null) {
            val (bg, sym) = when (badge) {
                NavBadge.REMOVE -> MaterialTheme.colorScheme.error to "−"
                NavBadge.ADD -> com.mzstd.hxmyproxy.ui.theme.StatusColors.good() to "+"
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = (-6).dp)
                    .size(16.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(bg),
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
