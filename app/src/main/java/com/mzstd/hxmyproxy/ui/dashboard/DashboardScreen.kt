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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

/**
 * 主页（hero 重构）：状态大字 + 地址卡钉在最前 + 统计小格，
 * 「开始/停止」主按钮**固定在底部不随内容滚动**（全 app 最高频操作不该被滚走）。
 * 主按钮用「形状+颜色」双编码状态（学 Pixel VPN）：停止=蓝填充全圆 pill，
 * 运行=错误容器色、圆角收紧；切换以弹簧动效过渡（Expressive 方向）。
 */
@Composable
fun DashboardScreen(ui: MainUiState, viewModel: com.mzstd.hxmyproxy.ui.MainViewModel) {
    val context = LocalContext.current
    val share = ui.share

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { ProxyForegroundService.start(context) }

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeroStatus(ui)
            EntryCard(ui)
            PortBindErrorCard(ui)
            if (share.running) StatRow(ui)
            InterfacesCard(ui, viewModel)
            Spacer(Modifier.height(4.dp))
        }
        // 主按钮固定底部（滚动区之外），单手拇指可达、永不被内容推走。
        StartStopButton(ui, onStart = {
            val perms = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
            }
            if (perms.isEmpty()) ProxyForegroundService.start(context)
            else permLauncher.launch(perms.toTypedArray())
        })
    }
}

/** Hero 状态区：大字状态 + 运行指示点（粉色点睛）+ 一行辅助信息。直接画在 surface 上，不套卡片。 */
@Composable
private fun HeroStatus(ui: MainUiState) {
    val share = ui.share
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (share.running) {
                // 运行指示点：粉色 tertiary 点睛（全页唯一的粉，≤10% 纪律）。
                Surface(
                    Modifier.size(12.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.tertiary,
                ) {}
            }
            Text(
                stringResource(if (share.running) R.string.status_running else R.string.status_stopped),
                style = MaterialTheme.typography.displaySmall,
            )
        }
        // 辅助行：VPN 状态 · 活跃连接
        Text(
            stringResource(if (share.vpn.detected) R.string.vpn_detected else R.string.vpn_not_detected) +
                "  ·  " + stringResource(R.string.active_conns, share.activeConnections),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (share.running) {
            Text(
                stringResource(
                    R.string.rate_line,
                    com.mzstd.hxmyproxy.ui.formatRate(share.downloadRateBps),
                    com.mzstd.hxmyproxy.ui.formatRate(share.uploadRateBps),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 入口地址卡（学 Tailscale 钉在最前）：主地址等宽大字 + 一键复制；PAC 常显；停止态给引导文案。 */
@Composable
private fun EntryCard(ui: MainUiState) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val share = ui.share
    var entriesExpanded by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }

    val primaryEntry = share.recommendedEntries.firstOrNull { it.protocol == ProxyProtocol.HTTP }
        ?: share.recommendedEntries.firstOrNull()

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                // 仅当有被折叠隐藏的入口才显示展开按钮（避免已全显时出现无效「展开」）。
                if (allEntries.size > collapsedEntries.size) {
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
                        // 扫码落地页配的是 HTTP 代理;SOCKS5/PAC 请在入口卡复制地址手动配置,故此处注明适用范围。
                        Text(
                            stringResource(R.string.qr_http_only),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        )
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCell(Modifier.weight(1f), "${share.activeConnections}", stringResource(R.string.stat_conns))
        if (share.signalLevel >= 0) {
            StatCell(Modifier.weight(1f), "${share.signalDbm}", stringResource(R.string.stat_signal) + " dBm")
        }
        StatCell(Modifier.weight(1f), com.mzstd.hxmyproxy.ui.formatBytes(share.totalBytes), stringResource(R.string.stat_traffic))
    }
}

@Composable
private fun StatCell(modifier: Modifier, value: String, label: String) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
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
}

/**
 * 固定底部主按钮：形状+颜色双编码（学 Pixel VPN）——停止=蓝填充全圆 pill（邀请开始），
 * 运行=错误容器色+圆角收紧（读作「停止」）；弹簧动效过渡（Expressive 方向，稳定 API 实现）。
 */
@Composable
private fun StartStopButton(ui: MainUiState, onStart: () -> Unit) {
    val context = LocalContext.current
    val running = ui.share.running
    val corner by animateDpAsState(
        targetValue = if (running) 16.dp else 28.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "btnCorner",
    )
    val container by animateColorAsState(
        targetValue = if (running) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primary,
        label = "btnColor",
    )
    val content by animateColorAsState(
        targetValue = if (running) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimary,
        label = "btnContent",
    )
    Button(
        onClick = { if (running) ProxyForegroundService.stop(context) else onStart() },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .height(56.dp),
        shape = RoundedCornerShape(corner),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        Text(
            stringResource(if (running) R.string.stop_sharing else R.string.start_sharing),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
