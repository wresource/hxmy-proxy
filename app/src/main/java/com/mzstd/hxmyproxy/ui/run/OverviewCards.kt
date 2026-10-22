package com.mzstd.hxmyproxy.ui.run

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.dashboard.QrSheet
import com.mzstd.hxmyproxy.ui.components.BentoCard
import com.mzstd.hxmyproxy.ui.components.CardTier
import com.mzstd.hxmyproxy.ui.components.InfoDot
import com.mzstd.hxmyproxy.ui.components.Sparkline
import com.mzstd.hxmyproxy.ui.components.StatLabel
import com.mzstd.hxmyproxy.ui.components.WarnBanner
import com.mzstd.hxmyproxy.ui.formatBytes
import com.mzstd.hxmyproxy.ui.formatRate
import com.mzstd.hxmyproxy.ui.theme.LocalDarkTheme

/**
 * 概览段的卡片群（设计稿 Main 屏）。层级取舍：概览只放「产出」——
 * 地址、速率、四个数字、去处入口；配置本体全部在详情页/设置。
 */

/** 告警条：未就绪三态 + 端口占用。文案与判定沿用旧 hero 的同一套规则。 */
@Composable
internal fun WarnBanners(ui: MainUiState, onSettings: ((String) -> Unit)? = null) {
    val share = ui.share
    val actions = linkedMapOf<String, Int>()
    if (share.startupFailed && !share.ruleApplication.stoppedForFailure) WarnBanner(stringResource(R.string.startup_failed))
    if (!share.running) return
    if (share.directEgressUnavailable) {
        val label = when (ui.settings.directEgressChoice) {
            com.mzstd.hxmyproxy.core.model.DirectEgressChoice.WIFI -> R.string.egress_wifi
            com.mzstd.hxmyproxy.core.model.DirectEgressChoice.CELLULAR -> R.string.egress_cellular
            com.mzstd.hxmyproxy.core.model.DirectEgressChoice.ETHERNET -> R.string.egress_ethernet
            com.mzstd.hxmyproxy.core.model.DirectEgressChoice.AUTO -> R.string.egress_auto
        }
        WarnBanner(stringResource(R.string.direct_egress_missing, stringResource(label)))
        actions["direct-egress"] = R.string.ui_direct_egress
    }
    if (ui.settings.pacEnabled && !ui.settings.httpEnabled && !ui.settings.socksEnabled) {
        WarnBanner(stringResource(R.string.ui_pac_only))
        actions["ports"] = R.string.ui_edit_ports
    }
    val noProto = !ui.settings.httpEnabled && !ui.settings.socksEnabled && !ui.settings.pacEnabled
    val d = share.diagnostics
    val anyPortUp = d.httpPortUp || d.socksPortUp || d.pacPortUp
    val warnRes = when {
        share.admissionEmpty -> R.string.warn_no_iface
        noProto -> R.string.warn_no_proto
        !anyPortUp -> R.string.warn_no_port_up
        else -> null
    }
    if (warnRes != null) WarnBanner(stringResource(warnRes))
    if (share.admissionEmpty) actions["interfaces"] = R.string.shareable_interfaces
    if (noProto || !anyPortUp) actions["ports"] = R.string.ui_edit_ports
    if (share.portBindErrors.isNotEmpty()) {
        WarnBanner(
            stringResource(
                R.string.port_bind_failed_banner,
                share.portBindErrors.joinToString(" / ") { it.name },
            ),
        )
        actions["ports"] = R.string.ui_edit_ports
    }
    if (onSettings != null) actions.forEach { (page, label) ->
        TextButton(onClick = { onSettings(page) }) { Text(stringResource(label)) }
    }
}

/**
 * 代理地址卡：多入口切换 chips（勾了多个网段才出现）→ 大号 IP → 协议 chips（点即复制，
 * PAC 复制完整 URL）→ IPv6 痕迹。这是整个 app 唯一不可替代的产出，永远排第一。
 */
private fun protoLabel(p: ProxyProtocol): String = when (p) {
    ProxyProtocol.HTTP -> "HTTP"
    ProxyProtocol.SOCKS5 -> "SOCKS5"
    ProxyProtocol.PAC -> "PAC"
}

/** 实时速率卡：↓↑ 大数字 + 最近 60s sparkline（下行主角、上行配角，与旧监控同一分色）。 */
@Composable
internal fun RateStatsCard(ui: MainUiState, viewModel: MainViewModel) {
    val hist by viewModel.rateHistory.collectAsStateWithLifecycle()
    val dark = LocalDarkTheme.current
    val upColor = if (dark) androidx.compose.ui.graphics.Color(0xFF93A5C4) else androidx.compose.ui.graphics.Color(0xFF7C8DA6)
    val share = ui.share
    BentoCard(contentPadding = 14.dp, spacing = 8.dp) {
        StatLabel(stringResource(R.string.monitor_realtime))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "↓${formatRate(share.downloadRateBps)}",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                Text(
                    "↑${formatRate(share.uploadRateBps)}",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = upColor,
                    maxLines = 1,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Sparkline(hist.down, color = MaterialTheme.colorScheme.primary)
                Sparkline(hist.up, color = upColor)
            }
        }
        // 数字四格(设计稿):今日(流量 DAO)/拦截/连接/已运行。已运行搭 rateHistory 每秒
        // 重组的便车刷新,不另起 ticker。
        val monitorVm: com.mzstd.hxmyproxy.ui.MonitorViewModel = androidx.hilt.navigation.compose.hiltViewModel()
        val traffic by monitorVm.trafficSummary.collectAsStateWithLifecycle()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniStat(stringResource(R.string.traffic_period_today), formatBytes(traffic.todayBytes), Modifier.weight(1f))
            MiniStat(stringResource(R.string.monitor_blocked), "%,d".format(share.blockedTotal), Modifier.weight(1f))
            MiniStat(stringResource(R.string.overview_conns), "${share.activeConnections}", Modifier.weight(1f))
            MiniStat(stringResource(R.string.overview_uptime), uptimeText(share.sessionStartedAtMs), Modifier.weight(1f))
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
            .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 已运行时长:h:mm 或 m 分;未运行 —。 */
private fun uptimeText(startedAtMs: Long): String {
    if (startedAtMs <= 0) return "—"
    val sec = (System.currentTimeMillis() - startedAtMs) / 1000
    val h = sec / 3600; val m = (sec % 3600) / 60
    return if (h > 0) "%d:%02d".format(h, m) else "${m}m"
}

/**
 * 四格摘要：客户端 / 入口 / 防护 / 本次流量——每格都是入口。
 * 出口与准入的配置本体在「入口与出口设置」详情页（onOpenConfig）。
 */
@Composable
internal fun SummaryGrid(
    ui: MainUiState,
    onOpenConfig: () -> Unit,
    onOpenProtection: () -> Unit,
    onOpenMonitor: () -> Unit,
) {
    val share = ui.share
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SummaryCell(
            label = stringResource(R.string.overview_clients),
            value = "${share.clients.size}",
            onClick = onOpenMonitor,
            modifier = Modifier.weight(1f),
        )
        SummaryCell(
            label = stringResource(R.string.overview_inbound),
            value = "${ui.visibleInterfaces.count { it.isSelected }}/${ui.visibleInterfaces.size}",
            onClick = onOpenConfig,
            modifier = Modifier.weight(1f),
        )
        SummaryCell(
            label = stringResource(R.string.overview_egress),
            value = egressValueText(ui),
            onClick = onOpenConfig,
            modifier = Modifier.weight(1f),
        )
        SummaryCell(
            label = stringResource(R.string.protection_title),
            value = "%,d".format(share.blockedTotal),
            tint = true,
            onClick = onOpenProtection,
            modifier = Modifier.weight(1f),
        )
    }
    BentoCard(
        tier = CardTier.Sunken,
        contentPadding = 14.dp,
        spacing = 0.dp,
        onClick = onOpenConfig,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.overview_open_config),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painterResource(R.drawable.ic_b_arrow_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
internal fun SummaryCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String = "",
    tint: Boolean = false,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (tint) cs.tertiaryContainer else cs.surfaceContainer,
                MaterialTheme.shapes.medium,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (tint) cs.onTertiaryContainer else cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row {
            Text(
                value,
                modifier = Modifier.alignByBaseline(),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                ),
                color = if (tint) cs.onTertiaryContainer else cs.onSurface,
                maxLines = 1,
            )
            if (unit.isNotEmpty()) {
                Text(
                    " $unit",
                    modifier = Modifier.alignByBaseline(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tint) cs.onTertiaryContainer else cs.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 出口格显示值:诚实口径——显示**设置值**(自动/VPN/Wi-Fi/…),VPN 已检测到时自动档
 * 标注 VPN(自动=跟随系统默认,系统 VPN 开着流量就走它)。app 目前没有「实际在用哪张网」
 * 的实况字段,不编造实况。
 */
@Composable
private fun egressValueText(ui: MainUiState): String {
    val choice = ui.settings.egressChoice
    val res = when (choice) {
        com.mzstd.hxmyproxy.core.model.EgressNetworkChoice.AUTO ->
            if (ui.share.vpn.detected) R.string.egress_vpn else R.string.egress_auto
        com.mzstd.hxmyproxy.core.model.EgressNetworkChoice.VPN -> R.string.egress_vpn
        com.mzstd.hxmyproxy.core.model.EgressNetworkChoice.WIFI -> R.string.egress_wifi
        com.mzstd.hxmyproxy.core.model.EgressNetworkChoice.CELLULAR -> R.string.egress_cellular
        com.mzstd.hxmyproxy.core.model.EgressNetworkChoice.ETHERNET -> R.string.egress_ethernet
    }
    return stringResource(res)
}
