package com.mzstd.hxmyproxy.ui.protection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.components.DetailScaffold

/**
 * 拦截明细页：本次共享会话被拦的域名/IP **完整**列表（按命中次数降序），供排查误封。
 * 点某一项 → [HostOverrideDialog] 设三态覆盖（走代理/直连/拦截，最高优先级），误封一键放行。
 */
@Composable
fun BlockedDetailScreen(ui: MainUiState, viewModel: MainViewModel, onBack: () -> Unit) {
    var editHost by remember { mutableStateOf<String?>(null) }
    DetailScaffold(title = stringResource(R.string.blocked_detail_title), onBack = onBack) { padding ->
        val list = ui.share.topBlockedDomains
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.monitor_no_blocked), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val ld = LocalLayoutDirection.current
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp + padding.calculateStartPadding(ld),
                    end = 16.dp + padding.calculateEndPadding(ld),
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding(),
                ),
            ) {
                items(list, key = { it.host }) { b ->
                    val ovr = ui.settings.hostOverrides[b.host]
                    Row(
                        Modifier.fillMaxWidth().clickable { editHost = b.host }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(b.host, style = MaterialTheme.typography.bodyLarge)
                            if (ovr != null) {
                                Text(
                                    stringResource(R.string.override_current, overrideLabel(ovr)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Text(
                            "×${b.count}",
                            style = MaterialTheme.typography.titleMedium,
                            color = com.mzstd.hxmyproxy.ui.theme.StatusColors.bad(),
                        )
                        // 可见「可调整」提示：行本就可点，图标让人知道能改（用户反馈：不然谁知道要点）。
                        Icon(
                            painterResource(R.drawable.ic_tune),
                            contentDescription = stringResource(R.string.override_adjust),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 12.dp).size(20.dp),
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    editHost?.let { host ->
        HostOverrideDialog(
            host = host,
            current = ui.settings.hostOverrides[host],
            onSet = { action -> viewModel.setHostOverride(host, action); editHost = null },
            onClear = { viewModel.clearHostOverride(host); editHost = null },
            onDismiss = { editHost = null },
        )
    }
}

/** 三态救济弹窗：把某 host 覆盖为「走代理 / 直连 / 拦截」（最高优先级，压过所有规则）。 */
@Composable
fun HostOverrideDialog(
    host: String,
    current: RuleAction?,
    onSet: (RuleAction) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var action by remember { mutableStateOf(current ?: RuleAction.PROXY) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSet(action) }) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = onClear) { Text(stringResource(R.string.override_clear)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
        title = { Text(host, maxLines = 1) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.override_desc), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = action == RuleAction.PROXY, onClick = { action = RuleAction.PROXY }, label = { Text(stringResource(R.string.override_proxy)) })
                    FilterChip(selected = action == RuleAction.DIRECT, onClick = { action = RuleAction.DIRECT }, label = { Text(stringResource(R.string.override_direct)) })
                    FilterChip(selected = action == RuleAction.REJECT, onClick = { action = RuleAction.REJECT }, label = { Text(stringResource(R.string.override_reject)) })
                }
            }
        },
    )
}

@Composable
private fun overrideLabel(action: RuleAction): String = stringResource(
    when (action) {
        RuleAction.PROXY -> R.string.override_proxy
        RuleAction.DIRECT -> R.string.override_direct
        RuleAction.REJECT -> R.string.override_reject
    },
)
