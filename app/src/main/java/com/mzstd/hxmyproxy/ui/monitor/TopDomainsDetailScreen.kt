package com.mzstd.hxmyproxy.ui.monitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.components.DetailScaffold
import com.mzstd.hxmyproxy.ui.components.TopDomainRuleDialog

/**
 * 目标域名全量详情页：完整 top domains 列表 + 点任一域名弹三态救济(直连/拦截/代理)。
 * 与 rules「管理全部」/ protection「查看明细」一致的独立页体验（替代原页内展开）。
 */
@Composable
fun TopDomainsDetailScreen(
    ui: MainUiState,
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    // 默认「最近访问」，与首页那张卡同口径——从卡片点进来看到的顺序不该变。
    // 但明细页是用来排查的，所以允许切到「流量」找出谁在占带宽。
    var byTraffic by remember { mutableStateOf(false) }
    val domains = if (byTraffic) {
        ui.share.topDomains.sortedByDescending { it.uploadBytes + it.downloadBytes }
    } else {
        ui.share.topDomains.sortedByDescending { it.lastSeenAtEpochMs }
    }
    val ruleVersion by viewModel.ruleVersion.collectAsStateWithLifecycle()
    var editHost by remember { mutableStateOf<String?>(null) }

    DetailScaffold(title = stringResource(R.string.monitor_top_domains), onBack = onBack) { padding ->
        if (domains.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text(
                    stringResource(R.string.monitor_no_domains),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val maxBytes = domains.maxOf { it.uploadBytes + it.downloadBytes }.coerceAtLeast(1L)
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    androidx.compose.foundation.layout.Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.domains_sort_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        androidx.compose.material3.FilterChip(
                            selected = !byTraffic,
                            onClick = { byTraffic = false },
                            label = { Text(stringResource(R.string.domains_sort_recent)) },
                        )
                        androidx.compose.material3.FilterChip(
                            selected = byTraffic,
                            onClick = { byTraffic = true },
                            label = { Text(stringResource(R.string.domains_sort_traffic)) },
                        )
                    }
                }
                items(domains, key = { it.host }) { d ->
                    DomainRow(d, maxBytes, ruleVersion.let { viewModel.decideHost(d.host) }.takeIf { it != RuleAction.PROXY }, onEdit = { editHost = it })
                }
            }
        }
    }
    editHost?.let { host ->
        TopDomainRuleDialog(
            host = host,
            current = viewModel.decideHost(host).takeIf { it != RuleAction.PROXY },
            onDirect = { viewModel.setDomainDirect(host); editHost = null },
            onReject = { viewModel.setDomainReject(host); editHost = null },
            onClear = { viewModel.clearDomainRule(host); editHost = null },
            onDismiss = { editHost = null },
        )
    }
}
