package com.mzstd.hxmyproxy.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.model.AppLanguage
import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.model.PerformancePreset
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.core.model.VpnDownStrategy
import com.mzstd.hxmyproxy.data.repository.CredentialStore
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel

private fun AppLanguage.labelRes() = when (this) {
    AppLanguage.SYSTEM -> R.string.lang_system
    AppLanguage.ENGLISH -> R.string.lang_english
    AppLanguage.CHINESE -> R.string.lang_chinese
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
) {
    val s = ui.settings
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle(stringResource(R.string.settings_language))
        ChipRow(AppLanguage.entries, s.language, { stringResource(it.labelRes()) }, viewModel::setLanguage)

        HorizontalDivider()
        SectionTitle(stringResource(R.string.settings_preset))
        ChipRow(PerformancePreset.entries, s.preset, { stringResource(it.labelRes()) }, viewModel::setPreset)
        if (s.preset == PerformancePreset.CUSTOM) {
            CustomLimits(s.limits, viewModel)
        }

        HorizontalDivider()
        SectionTitle(stringResource(R.string.settings_protocols))
        SwitchRow(stringResource(R.string.proto_http), s.httpEnabled, viewModel::setHttpEnabled)
        SwitchRow(stringResource(R.string.proto_socks), s.socksEnabled, viewModel::setSocksEnabled)
        SwitchRow(stringResource(R.string.proto_pac), s.pacEnabled, viewModel::setPacEnabled)

        HorizontalDivider()
        SectionTitle(stringResource(R.string.settings_ports))
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

        HorizontalDivider()
        SectionTitle(stringResource(R.string.settings_auth))
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

        HorizontalDivider()
        SectionTitle(stringResource(R.string.settings_vpn_strategy))
        ChipRow(VpnDownStrategy.entries, s.vpnDownStrategy, { stringResource(it.labelRes()) }, viewModel::setVpnStrategy)

        HorizontalDivider()
        SwitchRow(stringResource(R.string.settings_mdns), s.mdnsEnabled, viewModel::setMdnsEnabled)
        SwitchRow(stringResource(R.string.settings_block_private), s.blockPrivateLanEgress, viewModel::setBlockPrivateLan)

        HorizontalDivider()
        TextButton(onClick = onOpenHelp) { Text(stringResource(R.string.help_open)) }
        TextButton(onClick = onReplayOnboarding) { Text(stringResource(R.string.replay_onboarding)) }
    }
}

@Composable
private fun CustomLimits(limits: ConnectionLimits, viewModel: MainViewModel) {
    SectionTitle(stringResource(R.string.settings_limits))
    LimitSlider(stringResource(R.string.limit_global), limits.maxGlobalConnections, ConnectionLimits.RANGE_GLOBAL) {
        viewModel.setCustomLimits(limits.copy(maxGlobalConnections = it))
    }
    LimitSlider(stringResource(R.string.limit_per_client), limits.maxPerClientConnections, ConnectionLimits.RANGE_PER_CLIENT) {
        viewModel.setCustomLimits(limits.copy(maxPerClientConnections = it))
    }
    LimitSlider(stringResource(R.string.limit_parallelism), limits.relayParallelism, ConnectionLimits.RANGE_PARALLELISM) {
        viewModel.setCustomLimits(limits.copy(relayParallelism = it))
    }
    LimitSlider(stringResource(R.string.limit_buffer), limits.relayBufferBytes / 1024, 8..256) {
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
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> ChipRow(options: Iterable<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
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
