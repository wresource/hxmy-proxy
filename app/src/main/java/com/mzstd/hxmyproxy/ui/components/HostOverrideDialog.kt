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
                // 选中项的**后果**说明。三个 chip 的字面（走代理/直连/拦截）只说了「做什么」，
                // 没说「会怎样」——而「直连」在 VPN 在位时是绕过 VPN，跟用户想要的「别拦它」
                // 常常相反。把后果直接写在选择旁边，比事后在帮助页解释有效。
                val warn = action == RuleAction.DIRECT && vpnActive
                Text(
                    text = when (action) {
                        RuleAction.PROXY -> stringResource(R.string.override_hint_proxy)
                        RuleAction.DIRECT ->
                            if (vpnActive) stringResource(R.string.override_hint_direct_vpn)
                            else stringResource(R.string.override_hint_direct)
                        RuleAction.REJECT -> stringResource(R.string.override_hint_reject)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (warn) StatusColors.warn() else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
