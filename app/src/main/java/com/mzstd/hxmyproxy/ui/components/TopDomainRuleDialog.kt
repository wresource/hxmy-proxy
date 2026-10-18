package com.mzstd.hxmyproxy.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.rules.RuleAction

/**
 * top domain 便捷设规则弹窗：直连 / 拦截 / 清除——统一**写入 rules 的 block/allow 列表**（免手输域名），
 * 保留可见名单快捷操作；显式走代理与恢复默认是两个不同动作。互斥由 ViewModel 保证。
 */
@Composable
fun TopDomainRuleDialog(
    host: String,
    current: RuleAction?,     // 该域名当前在 block/allow 的动作：DIRECT / REJECT / null
    source: com.mzstd.hxmyproxy.core.rules.RuleSrc,
    canClear: Boolean,
    onDirect: () -> Unit,
    onReject: () -> Unit,
    onProxy: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    InteractionSheet(host, onDismiss) {
        Text(stringResource(R.string.top_domain_rule_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val sourceRes = when (source) {
                    com.mzstd.hxmyproxy.core.rules.RuleSrc.OVERRIDE -> R.string.rule_source_override
                    com.mzstd.hxmyproxy.core.rules.RuleSrc.USER_ALLOW -> R.string.rule_source_allow
                    com.mzstd.hxmyproxy.core.rules.RuleSrc.USER_BLOCK -> R.string.rule_source_block
                    com.mzstd.hxmyproxy.core.rules.RuleSrc.BUILTIN_ADS -> R.string.rule_source_ads
                    com.mzstd.hxmyproxy.core.rules.RuleSrc.BUILTIN_APP -> R.string.rule_source_builtin
                    com.mzstd.hxmyproxy.core.rules.RuleSrc.BUILTIN_PROXY -> R.string.rule_source_builtin
                    com.mzstd.hxmyproxy.core.rules.RuleSrc.DEFAULT -> R.string.rule_source_default
                }
        Text(stringResource(R.string.rule_source_current, stringResource(sourceRes)), style = MaterialTheme.typography.bodySmall)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetChoiceRow(stringResource(R.string.top_domain_proxy), current == RuleAction.PROXY, onProxy)
            SheetChoiceRow(stringResource(R.string.top_domain_direct), current == RuleAction.DIRECT, onDirect)
            SheetChoiceRow(stringResource(R.string.top_domain_reject), current == RuleAction.REJECT, onReject)
        }
        if (canClear) TextButton(onClick = onClear) { Text(stringResource(R.string.top_domain_clear)) }
    }
}
