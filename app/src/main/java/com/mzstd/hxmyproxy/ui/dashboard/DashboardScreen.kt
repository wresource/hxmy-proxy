package com.mzstd.hxmyproxy.ui.dashboard

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.model.InterfaceType
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.service.ProxyForegroundService
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.components.QrImage

/** 接口类型 → 本地化标签（随 InterfacesScreen 删除从该页迁来）。 */
private fun InterfaceType.labelRes(): Int = when (this) {
    InterfaceType.WIFI -> R.string.iface_wifi
    InterfaceType.HOTSPOT -> R.string.iface_hotspot
    InterfaceType.USB -> R.string.iface_usb
    InterfaceType.BLUETOOTH -> R.string.iface_bluetooth
    InterfaceType.ETHERNET -> R.string.iface_ethernet
    InterfaceType.UNKNOWN -> R.string.iface_unknown
}

@Composable
fun DashboardScreen(ui: MainUiState, viewModel: com.mzstd.hxmyproxy.ui.MainViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val share = ui.share
    var showQr by remember { mutableStateOf(false) }
    var interfacesExpanded by remember { mutableStateOf(false) }
    var entriesExpanded by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { ProxyForegroundService.start(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 顶部状态卡：品牌渐变“头部”（运行时偏 teal 容器、停止时偏中性），更有层次。
        val headerTop = if (share.running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(headerTop, MaterialTheme.colorScheme.surface)))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(if (share.running) R.string.status_running else R.string.status_stopped),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(stringResource(if (share.vpn.detected) R.string.vpn_detected else R.string.vpn_not_detected))
                Text(stringResource(R.string.active_conns, share.activeConnections))
                if (share.signalLevel >= 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.signal_label), style = MaterialTheme.typography.bodyMedium)
                        com.mzstd.hxmyproxy.ui.components.SignalBars(share.signalLevel)
                        Text("${share.signalDbm} dBm", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (share.running) {
                    Text(
                        stringResource(
                            R.string.rate_line,
                            com.mzstd.hxmyproxy.ui.formatRate(share.downloadRateBps),
                            com.mzstd.hxmyproxy.ui.formatRate(share.uploadRateBps),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.total_traffic, com.mzstd.hxmyproxy.ui.formatBytes(share.totalBytes)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // 端口占用告警：某协议 bind 失败（端口被占）→ 该代理未启动，明确提示用户换端口。
        if (share.portBindErrors.isNotEmpty()) {
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

        // ② 可分享入口：每个接口一个开关（复用 toggleInterface），默认 2 行、超出折叠。
        val interfaces = share.interfaces
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${stringResource(iface.type.labelRes())} · ${iface.name}",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    iface.cidr,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = iface.id in ui.settings.selectedInterfaceIds,
                                onCheckedChange = { viewModel.toggleInterface(iface.id, it) },
                            )
                        }
                    }
                    if (interfaces.size > 2) {
                        TextButton(
                            onClick = { interfacesExpanded = !interfacesExpanded },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (interfacesExpanded) stringResource(R.string.monitor_collapse)
                                else stringResource(R.string.monitor_expand, interfaces.size),
                            )
                        }
                    }
                }
            }
        }

        // ③ 入口配置：按「协议  IP:端口」列出全部入口，默认只显示首条（优先 HTTP），其余折叠。
        val primaryEntry = share.recommendedEntries.firstOrNull { it.protocol == ProxyProtocol.HTTP }
            ?: share.recommendedEntries.firstOrNull()
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.entry_config), style = MaterialTheme.typography.titleMedium)
                if (primaryEntry == null) {
                    Text(stringResource(R.string.no_entry))
                } else {
                    val allEntries = share.recommendedEntries
                    val shownEntries = if (entriesExpanded) allEntries else listOf(primaryEntry)
                    shownEntries.forEach { e ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${e.protocol.name}  ${e.displayEndpoint}", style = MaterialTheme.typography.bodyLarge)
                                e.mdnsEndpoint?.let {
                                    Text(
                                        "${e.protocol.name}  $it",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(e.copyValue))
                                Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                            }) { Text(stringResource(R.string.copy)) }
                        }
                    }
                    if (allEntries.size > 1) {
                        TextButton(
                            onClick = { entriesExpanded = !entriesExpanded },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (entriesExpanded) stringResource(R.string.monitor_collapse)
                                else stringResource(R.string.monitor_expand, allEntries.size),
                            )
                        }
                    }
                    OutlinedButton(onClick = { showQr = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.qr_setup))
                    }
                }
            }
        }

        if (showQr) {
            val setupUrl = primaryEntry?.let { "http://${it.host}:${ui.settings.pacPort}/" }
            AlertDialog(
                onDismissRequest = { showQr = false },
                confirmButton = { TextButton(onClick = { showQr = false }) { Text(stringResource(R.string.setup_close)) } },
                title = { Text(stringResource(R.string.qr_setup)) },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (!ui.settings.pacEnabled || setupUrl == null) {
                            Text(stringResource(R.string.qr_need_pac))
                        } else {
                            QrImage(setupUrl, sizeDp = 220)
                            Text(stringResource(R.string.qr_setup_hint), style = MaterialTheme.typography.bodyMedium)
                            Text(setupUrl, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
            )
        }

        Spacer(Modifier.height(8.dp))
        // 主操作：更大胶囊 + 按状态着色（运行时用 error 容器，读作“停止”）。
        Button(
            onClick = {
                if (share.running) {
                    ProxyForegroundService.stop(context)
                } else {
                    val perms = buildList {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                        if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
                    }
                    if (perms.isEmpty()) ProxyForegroundService.start(context)
                    else permLauncher.launch(perms.toTypedArray())
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = MaterialTheme.shapes.large,
            colors = if (share.running) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else {
                ButtonDefaults.buttonColors()
            },
        ) {
            Text(
                stringResource(if (share.running) R.string.stop_sharing else R.string.start_sharing),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
