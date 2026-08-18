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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
internal fun WarnBanners(ui: MainUiState) {
    val share = ui.share
    if (!share.running) return
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
    if (share.portBindErrors.isNotEmpty()) {
        WarnBanner(
            stringResource(
                R.string.port_bind_failed_banner,
                share.portBindErrors.joinToString(" / ") { it.name },
            ),
        )
    }
}

/**
 * 代理地址卡：多入口切换 chips（勾了多个网段才出现）→ 大号 IP → 协议 chips（点即复制，
 * PAC 复制完整 URL）→ IPv6 痕迹。这是整个 app 唯一不可替代的产出，永远排第一。
 */
@Composable
internal fun AddressCard(ui: MainUiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val share = ui.share
    val entries = share.recommendedEntries
    val hosts = remember(entries) { entries.map { it.host }.distinct() }
    var pickedHost by rememberSaveable(hosts) { mutableStateOf(hosts.firstOrNull() ?: "") }
    val copy: (String) -> Unit = {
        clipboard.setText(AnnotatedString(it))
        Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }

    BentoCard(tier = CardTier.Primary, contentPadding = 16.dp, spacing = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatLabel(stringResource(R.string.entry_config))
            InfoDot(
                title = stringResource(R.string.entry_config),
                body = stringResource(R.string.overview_addr_info),
            )
            Spacer(Modifier.weight(1f))
        }
        when {
            !share.running -> Text(
                stringResource(R.string.start_to_show_entries),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entries.isEmpty() -> Text(
                stringResource(R.string.entry_none_running),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                if (hosts.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        hosts.forEach { h ->
                            val on = h == pickedHost
                            AssistChip(
                                onClick = { pickedHost = h },
                                label = {
                                    Text(
                                        h,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                                    containerColor = if (on) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLowest
                                    },
                                ),
                            )
                        }
                    }
                }
                val host = pickedHost.ifEmpty { hosts.first() }
                Text(
                    host,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.5).sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { copy(host) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    entries.filter { it.host == host }.forEach { e ->
                        AssistChip(
                            onClick = { copy(e.copyValue) },
                            label = {
                                Text(
                                    "${protoLabel(e.protocol)} ${e.port}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    painterResource(R.drawable.ic_b_copy),
                                    contentDescription = stringResource(R.string.copied),
                                    modifier = Modifier.size(13.dp),
                                )
                            },
                        )
                    }
                }
                if (ui.hiddenIpv6Count > 0) {
                    Text(
                        stringResource(R.string.ipv6_hidden_hint, ui.hiddenIpv6Count),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { viewModel.setShowIpv6(true) },
                    )
                }
            }
        }
    }
}

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
    }
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
private fun SummaryCell(
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
            .background(
                if (tint) cs.tertiaryContainer else cs.surfaceContainer,
                RoundedCornerShape(12.dp),
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
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
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
