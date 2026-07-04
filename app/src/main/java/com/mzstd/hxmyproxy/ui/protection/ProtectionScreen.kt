package com.mzstd.hxmyproxy.ui.protection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.rules.RuleCatalog
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.components.ExpandCollapseButton
import com.mzstd.hxmyproxy.ui.theme.StatusColors

/**
 * 防护 tab（独立导航）：把"拦截"这条线做成完整体验——实时拦截数 + 广告拦截总开关 +
 * 拦截明细入口 + "有没有 VPN 都生效"的说明。让用户明确看到"它真的在拦、没 VPN 也能用"。
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
    var topExpanded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Hero：防护状态点 + 拦截数大字 + "有没有 VPN 都生效"。
        Column(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    Modifier.size(14.dp),
                    shape = CircleShape,
                    color = if (adBlockOn) StatusColors.good() else StatusColors.stoppedDot(),
                ) {}
                Text(stringResource(R.string.protection_title), style = MaterialTheme.typography.headlineSmall)
            }
            Text(
                "$blocked",
                style = MaterialTheme.typography.displayMedium,
                color = if (adBlockOn && blocked > 0) StatusColors.good() else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.protection_hero_sub),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        // 广告拦截总开关。
        ElevatedCard(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.elevatedCardColors(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.rule_ads_oisd), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.protection_adblock_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = adBlockOn,
                    onCheckedChange = { viewModel.toggleRuleGroup(adGroup.id, it) },
                    colors = com.mzstd.hxmyproxy.ui.components.stdSwitchColors(),
                )
            }
        }

        // 拦截 Top 预览：日常 3 条，可展开到 5 条，更多去明细。每行 tune 按钮=可调整的可见入口
        // （用户反馈：没有按钮谁知道这里能点）。点行或点按钮都弹三态救济弹窗。
        if (ui.share.topBlockedDomains.isNotEmpty()) {
            ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.elevatedCardColors()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.protection_top_blocked), style = MaterialTheme.typography.titleMedium)
                    val list = ui.share.topBlockedDomains
                    val shown = if (topExpanded) list.take(5) else list.take(3)
                    shown.forEach { b ->
                        Row(
                            Modifier.fillMaxWidth().clickable { editHost = b.host },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(b.host, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                            Text("×${b.count}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            IconButton(onClick = { editHost = b.host }) {
                                Icon(
                                    painterResource(R.drawable.ic_tune),
                                    contentDescription = stringResource(R.string.override_adjust),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    if (list.size > 3) {
                        ExpandCollapseButton(topExpanded, list.size.coerceAtMost(5)) { topExpanded = !topExpanded }
                    }
                }
            }
        }
        Button(onClick = onOpenBlockedDetail, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Text(stringResource(R.string.protection_view_blocked))
        }

        // 说明卡：防护如何工作（代理层拦截、全设备生效、有无 VPN 都行）。
        ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.elevatedCardColors()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.protection_how_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.protection_how_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    // 三态救济弹窗（与拦截明细页同款）：tune 按钮 / 点行触发。
    editHost?.let { host ->
        HostOverrideDialog(
            host = host,
            current = ui.settings.hostOverrides[host],
            onSet = { a -> viewModel.setHostOverride(host, a); editHost = null },
            onClear = { viewModel.clearHostOverride(host); editHost = null },
            onDismiss = { editHost = null },
        )
    }
}
