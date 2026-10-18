package com.mzstd.hxmyproxy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.ui.theme.StatusColors

/**
 * 三态救济弹窗：把某 host 覆盖为「走代理 / 直连 / 拦截」（最高优先级，压过所有规则）。
 * 共享组件——防护拦截明细页、监控 Top domains 复用（看到某 host 想改直连/拦截，两下点击即可）。
 */
@Composable
fun HostOverrideDialog(
    host: String,
    current: RuleAction?,
    onSet: (RuleAction) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    /**
     * 当前是否有 VPN 在位。决定「直连」那条说明的措辞与警示级别——
     * 有 VPN 时「直连」意味着**绕过它**，而用户点进来多半只是想「别拦这个域名」，
     * 两者在结果上常常相反：真机日志里 237 次直连超时全来自这个误解。
     */
    vpnActive: Boolean = false,
) {
    var action by remember { mutableStateOf(current ?: RuleAction.PROXY) }
    InteractionSheet(host, onDismiss,
        footer = { SheetActions(stringResource(R.string.save), { onSet(action) }, stringResource(R.string.cancel), onDismiss) }) {
        Text(stringResource(R.string.override_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(RuleAction.PROXY to R.string.override_proxy, RuleAction.DIRECT to R.string.override_direct,
                RuleAction.REJECT to R.string.override_reject).forEach { (value, label) ->
                SheetChoiceRow(stringResource(label), action == value, { action = value })
            }
        }
        val warn = action == RuleAction.DIRECT && vpnActive
        Text(when (action) {
            RuleAction.PROXY -> stringResource(R.string.override_hint_proxy)
            RuleAction.DIRECT -> stringResource(if (vpnActive) R.string.override_hint_direct_vpn else R.string.override_hint_direct)
            RuleAction.REJECT -> stringResource(R.string.override_hint_reject)
        }, style = MaterialTheme.typography.bodySmall, color = if (warn) StatusColors.warn() else MaterialTheme.colorScheme.onSurfaceVariant)
        if (current != null) TextButton(onClick = onClear) { Text(stringResource(R.string.override_clear)) }
    }
}
