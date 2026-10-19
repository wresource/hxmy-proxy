package com.mzstd.hxmyproxy.ui.protection

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleCatalog
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.components.BannerLevel
import com.mzstd.hxmyproxy.ui.components.BentoCard
import com.mzstd.hxmyproxy.ui.components.CardTier
import com.mzstd.hxmyproxy.ui.components.HostOverrideDialog
import com.mzstd.hxmyproxy.ui.components.OverrideBadge
import com.mzstd.hxmyproxy.ui.components.RatioBar
import com.mzstd.hxmyproxy.ui.components.StatLabel
import com.mzstd.hxmyproxy.ui.components.WarnBanner
import com.mzstd.hxmyproxy.ui.components.WorkbenchFold
import com.mzstd.hxmyproxy.ui.components.stdSwitchColors
import java.text.NumberFormat

/**
 * 防护详情：会话计数、拦截排行、直连失败救济及 OISD 开关。
 * 沿用原数据与规则操作；低频说明折叠，详情返回由宿主提供。
 */
@Composable
fun ProtectionScreen(
    ui: MainUiState,
    viewModel: MainViewModel,
    onOpenBlockedDetail: () -> Unit,
    contentPadding: PaddingValues,
) {
    val adGroup = RuleCatalog.ADS_OISD
    val adBlockOn = adGroup.id in ui.settings.enabledRuleGroups
    val blocked = ui.share.blockedTotal
    var editHost by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BentoCard(Modifier.fillMaxWidth(), tier = CardTier.Primary, spacing = 8.dp) {
            StatLabel(stringResource(R.string.protection_hero_sub))
            Text(NumberFormat.getIntegerInstance().format(blocked) + " " + stringResource(R.string.protection_unit_times),
                style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.rule_ads_oisd), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.protection_adblock_sub), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = adBlockOn, onCheckedChange = { viewModel.toggleRuleGroup(adGroup.id, it) }, colors = stdSwitchColors())
            }
        }

        // 直连不通：仅在确有持续失败时出现。
        // 为什么必须给这张卡：DIRECT 是 fail-closed 的（连不上宁可断也不降级到 VPN），失败对用户
        // **完全静默**——只表现为「这个 app 有时候卡」，因为每次访问都在白等一个连接超时。
        // 真机日志实证过 237 次这样的超时，全是被设成「直连」的遥测域名；不导出日志根本发现不了。
        val directFails = ui.share.directFailures
        if (directFails.isNotEmpty()) {
            BentoCard(Modifier.fillMaxWidth(), tier = CardTier.Default, spacing = 10.dp) {
                WarnBanner(
                    text = pluralStringResource(R.plurals.direct_fail_banner, directFails.size, directFails.size),
                    level = BannerLevel.Warn,
                    icon = painterResource(R.drawable.ic_b_alert_circle),
                )
                Text(
                    stringResource(R.string.direct_fail_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    directFails.take(5).forEach { f ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    f.host,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // 原因走 ProxyError.code 映射到本地化文案：ProxyError.label 是硬编码中文
                                // （给日志用的），直接上界面会在英文 UI 里冒出「远程连接超时」。
                                val reason = when (f.lastError) {
                                    "timeout" -> stringResource(R.string.direct_fail_reason_timeout)
                                    "dns" -> stringResource(R.string.direct_fail_reason_dns)
                                    "refused" -> stringResource(R.string.direct_fail_reason_refused)
                                    "unreachable" -> stringResource(R.string.direct_fail_reason_unreachable)
                                    "denied" -> stringResource(R.string.direct_fail_reason_denied)
                                    else -> stringResource(R.string.direct_fail_reason_other)
                                }
                                Text(
                                    pluralStringResource(R.plurals.direct_fail_row_sub, f.fails, f.fails, reason),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // 一键改回：把覆盖从「直连」换成「走代理」（走 VPN，通常正是用户本意），
                            // 并立刻清掉计数让本行消失，不必等下一次连接成功。
                            DetailChip(stringResource(R.string.direct_fail_switch)) {
                                viewModel.switchToProxyEgress(f.host)
                            }
                        }
                    }
                }
            }
        }

        // 拦得最多：排名 + 等宽域名 + 覆盖徽章 + ×次数 + tune；粉 RatioBar 按次数归一（榜首满宽）。
        // 卡头 chip=拦截明细入口（替代旧全宽按钮），空态也保留卡与入口。
        BentoCard(Modifier.fillMaxWidth(), tier = CardTier.Default, spacing = 10.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatLabel(stringResource(R.string.protection_top_blocked))
                Spacer(Modifier.weight(1f))
                DetailChip(stringResource(R.string.protection_view_blocked), onOpenBlockedDetail)
            }
            val top = ui.share.topBlockedDomains.take(5)
            if (top.isEmpty()) {
                Text(
                    stringResource(R.string.monitor_no_blocked),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                )
            } else {
                val maxCount = top.maxOf { it.count }.coerceAtLeast(1L)
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    top.forEachIndexed { i, b ->
                        TopBlockedRow(
                            rank = i + 1,
                            host = b.host,
                            count = b.count,
                            fraction = b.count.toFloat() / maxCount,
                            ovr = ui.settings.hostOverrides[b.host],
                            highlight = i == 0,
                            onClick = { editHost = b.host },
                        )
                    }
                }
                // 脚注：其他来源计数 + 「点右侧调节可放行」的救济提示（HTML .tnote）。
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_b_info),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        stringResource(R.string.protection_top_note, (blocked - top.sumOf { it.count }).coerceAtLeast(0L)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        WorkbenchFold(stringResource(R.string.protection_how_title), "protection_help") {
            HowItem(R.drawable.ic_b_filter, R.string.protection_how_1)
            HowItem(R.drawable.ic_b_devices, R.string.protection_how_2)
            HowItem(R.drawable.ic_b_wifi, R.string.protection_how_3)
            HowItem(R.drawable.ic_b_undo, R.string.protection_how_4)
        }
    }
    // 三态救济弹窗（与拦截明细页同款）：点排行行/tune 触发。
    editHost?.let { host ->
        HostOverrideDialog(
            host = host,
            current = ui.settings.hostOverrides[host],
            onSet = { a -> viewModel.setHostOverride(host, a); editHost = null },
            onClear = { viewModel.clearHostOverride(host); editHost = null },
            onDismiss = { editHost = null },
            vpnActive = ui.share.vpn.detected,
        )
    }
}

/** Standard touch target for details and direct-failure recovery. */
@Composable
private fun DetailChip(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(text) }
}

/**
 * 拦得最多单行：排名 + 等宽域名（+覆盖徽章）+ ×次数 + tune 提示，下衬粉 RatioBar。
 * 行整体可点=三态救济入口；tune 图标是可见的「可调整」提示（用户反馈：不然谁知道要点）。
 */
@Composable
private fun TopBlockedRow(
    rank: Int,
    host: String,
    count: Long,
    fraction: Float,
    ovr: RuleAction?,
    highlight: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).heightIn(min = 56.dp).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "$rank",
                modifier = Modifier.width(16.dp),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
            )
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    host,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (ovr != null) OverrideBadge(ovr)
            }
            Text(
                "×$count",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                ),
                color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                painterResource(R.drawable.ic_tune),
                contentDescription = stringResource(R.string.override_adjust),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        // 比例条与域名列左对齐（缩进=排名宽 16 + 间距 8）。
        RatioBar(
            fraction = fraction,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 24.dp),
            height = 6.dp,
        )
    }
}

/** 「防护如何工作」微网格单元：小图标 + 短句（EN 较长，允许两行）。 */
@Composable
private fun HowItem(@DrawableRes icon: Int, @StringRes text: Int, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(14.dp),
        )
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
