package com.mzstd.hxmyproxy.ui.run

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.mzstd.hxmyproxy.ui.components.AppDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.components.DetailScaffold
import com.mzstd.hxmyproxy.ui.components.WorkbenchRow
import com.mzstd.hxmyproxy.ui.dashboard.QrSheet

@Composable
internal fun AddressCard(
    ui: MainUiState,
    viewModel: MainViewModel,
    onOpenAddresses: () -> Unit = {},
    onOpenSettings: (String) -> Unit = {},
    onStart: () -> Unit = {},
) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var showQr by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copy: (String) -> Unit = {
        clipboard.setText(AnnotatedString(it))
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
    }
    ConnectionCard(ui, selected, { selected = it }, copy, { showQr = true }, onOpenAddresses, onOpenSettings, onStart,
        onShowIpv6 = { viewModel.setShowIpv6(true) })
    if (showQr) QrSheet(ui, { showQr = false }, selectedConnectionHost(ui, selected)?.host,
        onEnablePac = { viewModel.setPacEnabled(true) })
}

@Composable
internal fun ConnectionCard(
    ui: MainUiState, selected: String?, onSelect: (String) -> Unit, onCopy: (String) -> Unit,
    onQr: () -> Unit, onAddresses: () -> Unit, onSettings: (String) -> Unit, onStart: () -> Unit,
    onShowIpv6: () -> Unit = {},
) {
    val hosts = connectionHosts(ui)
    val host = selectedConnectionHost(ui, selected)
    var menu by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val numberStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace)
    val density = LocalDensity.current
    val measure = rememberTextMeasurer()
    val widestPort = listOf(ui.settings.httpPort, ui.settings.socksPort, ui.settings.pacPort).maxBy { it.toString().length }.toString()
    val minTile = with(density) { measure.measure(widestPort, numberStyle).size.width.toDp() } + 12.dp
    Surface(color = cs.primaryContainer, contentColor = cs.onPrimaryContainer,
        shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth().testTag("connection_card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ui_proxy_ip_ports), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (hosts.size > 1) {
                    Column {
                        TextButton(onClick = { menu = true }, modifier = Modifier.testTag("connection_host_picker")) {
                            Text(host?.label.orEmpty(), style = MaterialTheme.typography.labelMedium)
                            Icon(painterResource(R.drawable.ic_b_chevron_down), null, Modifier.size(16.dp))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            hosts.forEach { h -> DropdownMenuItem(text = {
                                Column { Text(h.label); Text(h.host, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                            }, onClick = { onSelect(h.host); menu = false }) }
                        }
                    }
                } else Text(host?.label.orEmpty(), style = MaterialTheme.typography.bodySmall)
            }
            if (host != null) {
                val copyEnabled = ProxyProtocol.entries.any { listenerPresentation(ui, it, host) == ListenerPresentation.LISTENING }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(host.host, Modifier.weight(1f).testTag("connection_ip"),
                        style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 26.sp))
                    IconButton(onClick = { onCopy(host.host) }, enabled = copyEnabled, modifier = Modifier.testTag("copy_ip")) {
                        Icon(painterResource(R.drawable.ic_b_copy), stringResource(R.string.ui_copy_ip))
                    }
                }
            } else Text(stringResource(R.string.ui_no_selected_network), style = MaterialTheme.typography.bodyMedium)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = if (maxWidth >= minTile * 3 + 12.dp) 3 else if (maxWidth >= minTile * 2 + 6.dp) 2 else 1
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProxyProtocol.entries.chunked(columns).forEach { protocols ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            protocols.forEach { protocol ->
                                val state = listenerPresentation(ui, protocol, host)
                                val value = host?.let { connectionEntry(ui, it, protocol) }
                                val port = value?.port ?: when (protocol) {
                                    ProxyProtocol.HTTP -> ui.settings.httpPort
                                    ProxyProtocol.SOCKS5 -> ui.settings.socksPort
                                    ProxyProtocol.PAC -> ui.settings.pacPort
                                }
                                val busy = state == ListenerPresentation.PORT_BUSY
                                val ready = state == ListenerPresentation.LISTENING
                                Surface(Modifier.weight(1f), color = if (busy) cs.errorContainer else cs.surfaceContainerLow,
                                    contentColor = if (busy) cs.onErrorContainer else if (ready) cs.onSurface else cs.onSurfaceVariant,
                                    shape = MaterialTheme.shapes.medium) {
                                    Column(Modifier.fillMaxWidth().testTag("endpoint_${protocol.name}")
                                        .clickable(enabled = ready) { value?.let { onCopy(it.copyValue) } }
                                        .heightIn(min = 80.dp).padding(horizontal = 4.dp, vertical = 8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(protocol.name, style = MaterialTheme.typography.labelMedium)
                                        Text(port.toString(), modifier = Modifier.testTag("port_value_${protocol.name}"), style = numberStyle, maxLines = 1)
                                        Text(stringResource(listenerLabel(state, protocol)), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            repeat(columns - protocols.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onSettings("interfaces") },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("home_inbound"),
                    contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Icon(painterResource(R.drawable.ic_b_wifi), null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.shareable_interfaces))
                }
                OutlinedButton(onClick = { onSettings("ports") },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("home_ports"),
                    contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Icon(painterResource(R.drawable.ic_b_swap), null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.ui_edit_ports))
                }
            }
            if (!ui.share.running && !ui.share.starting) Text(stringResource(R.string.ui_saved_ports), style = MaterialTheme.typography.bodySmall)
            if (ui.settings.authEnabled) Text(stringResource(R.string.ui_client_auth_hint), style = MaterialTheme.typography.bodySmall)
            val hiddenV6 = com.mzstd.hxmyproxy.core.model.effectiveShareInterfaces(ui.share.interfaces, ui.settings.selectedInterfaceIds).count { it.isIpv6 } - hosts.count { ':' in it.host }
            if (hiddenV6 > 0) TextButton(onClick = onShowIpv6) {
                Text(stringResource(R.string.ipv6_hidden_hint, hiddenV6), style = MaterialTheme.typography.bodySmall)
            }
            if (hosts.isNotEmpty() && ui.settings.selectedInterfaceIds.isNotEmpty() &&
                ui.share.interfaces.none { it.id in ui.settings.selectedInterfaceIds }) {
                TextButton(onClick = { onSettings("interfaces") }) {
                    Text(stringResource(R.string.ui_network_fallback), style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(onClick = { when { host == null -> onSettings("interfaces"); !ui.share.running -> onStart(); else -> onQr() } },
                    enabled = !ui.share.starting, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(if (host == null) R.string.ui_choose_network else if (!ui.share.running) R.string.start_sharing else R.string.qr_setup),
                        style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = onAddresses, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.ui_all_addresses), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

internal fun listenerLabel(state: ListenerPresentation, protocol: ProxyProtocol): Int = when (state) {
    ListenerPresentation.OFF -> R.string.diag_disabled
    ListenerPresentation.STOPPED -> R.string.status_stopped
    ListenerPresentation.STARTING -> R.string.notif_starting
    ListenerPresentation.NO_ADDRESS -> R.string.ui_no_address
    ListenerPresentation.PORT_BUSY -> R.string.ui_port_busy
    ListenerPresentation.UPDATING -> R.string.ui_updating
    ListenerPresentation.UNAVAILABLE -> R.string.ui_listener_pending
    ListenerPresentation.LISTENING -> if (protocol == ProxyProtocol.PAC) R.string.ui_copy_url else R.string.ui_copy_address
}

@Composable
fun ConnectionAddressesScreen(ui: MainUiState, onBack: () -> Unit, onSettings: (String) -> Unit, onShowIpv6: () -> Unit = {}) {
    val clipboard = LocalClipboardManager.current
    val hosts = connectionHosts(ui)
    DetailScaffold(stringResource(R.string.ui_all_addresses), onBack) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (hosts.isEmpty()) item { Text(stringResource(R.string.ui_no_selected_network)) }
            items(hosts, key = { it.interfaceId }) { host ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(host.label, style = MaterialTheme.typography.titleMedium)
                        Text(host.host, fontFamily = FontFamily.Monospace)
                        ProxyProtocol.entries.forEach { protocol ->
                            val entry = connectionEntry(ui, host, protocol)
                            val state = listenerPresentation(ui, protocol, host)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${protocol.name} · ${stringResource(listenerLabel(state, protocol))}", style = MaterialTheme.typography.labelMedium)
                                    Text(entry.copyValue, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                                }
                                IconButton(onClick = { clipboard.setText(AnnotatedString(entry.copyValue)) }, enabled = state == ListenerPresentation.LISTENING) {
                                    Icon(painterResource(R.drawable.ic_b_copy), stringResource(R.string.copy))
                                }
                            }
                        }
                    }
                }
            }
            item { WorkbenchRow(stringResource(R.string.settings_proto_ports), onClick = { onSettings("ports") }) }
            if (!ui.settings.showIpv6) item { TextButton(onClick = onShowIpv6) { Text(stringResource(R.string.ui_show_ipv6)) } }
            item { WorkbenchRow(stringResource(R.string.shareable_interfaces), onClick = { onSettings("interfaces") }) }
        }
    }
}
