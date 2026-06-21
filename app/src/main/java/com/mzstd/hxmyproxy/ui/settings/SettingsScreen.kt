package com.mzstd.hxmyproxy.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.model.AppLanguage
import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.model.PerformancePreset
import com.mzstd.hxmyproxy.core.model.VpnDownStrategy
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
fun SettingsScreen(ui: MainUiState, viewModel: MainViewModel) {
    val s = ui.settings
    Column(
        modifier = Modifier
            .fillMaxSize()
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
        PortField(stringResource(R.string.port_http), s.httpPort, viewModel::setHttpPort)
        PortField(stringResource(R.string.port_socks), s.socksPort, viewModel::setSocksPort)
        PortField(stringResource(R.string.port_pac), s.pacPort, viewModel::setPacPort)

        HorizontalDivider()
        SectionTitle(stringResource(R.string.settings_auth))
        SwitchRow(stringResource(R.string.auth_enable), s.authEnabled, viewModel::setAuthEnabled)
        if (!s.authEnabled) {
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

@Composable
private fun PortField(label: String, value: Int, onCommit: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new
            new.toIntOrNull()?.let(onCommit)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
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
