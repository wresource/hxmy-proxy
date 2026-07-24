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
    val domains = ui.share.topDomains.sortedByDescending { it.lastSeenAtEpochMs }
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
