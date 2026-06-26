package com.mzstd.hxmyproxy.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.rules.RuleCatalog
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel

/**
 * 规则页:三模块（澄清 C 的分级开关）。
 * 1. IP / 域名白名单：用户增删，整组走直连（出口分流绕过共享 VPN）。
 * 2. App / 服务规则集：每服务一个开关（即将上线）。
 * 3. 广告拦截：每个表一个开关 + 用户白名单覆盖（OISD small 默认关）。
 */
@Composable
fun RulesScreen(ui: MainUiState, viewModel: MainViewModel) {
    val s = ui.settings
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.rules_title), style = MaterialTheme.typography.titleLarge)

        // —— ① IP / 域名白名单（直连，出口分流绕过共享 VPN）——
        SectionCard(stringResource(R.string.rules_module_list)) {
            Text(
                stringResource(R.string.rules_user_direct_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var input by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.rules_add_domain)) },
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = {
                    if (input.isNotBlank()) { viewModel.addUserDirectRule(input); input = "" }
                }) { Text(stringResource(R.string.rules_add)) }
            }
            s.userDirectRules.sorted().forEach { domain ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(domain, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { viewModel.removeUserDirectRule(domain) }) {
                        Text(stringResource(R.string.rules_remove))
                    }
                }
            }
        }

        // —— ② App / 服务规则集（每服务一个开关，DIRECT 出口分流：绕过共享 VPN）——
        SectionCard(stringResource(R.string.rules_module_apps)) {
            Text(
                stringResource(R.string.rules_user_direct_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RuleCatalog.appGroups.forEach { group ->
                GroupSwitchRow(group, s.enabledRuleGroups, viewModel)
            }
        }

        // —— ③ 广告拦截（每表开关 + 用户白名单覆盖）——
        SectionCard(stringResource(R.string.rules_module_ads)) {
            RuleCatalog.adGroups.forEach { group ->
                GroupSwitchRow(group, s.enabledRuleGroups, viewModel)
            }
        }
    }
}

/** 一个规则组的开关行：名称 + 来源/License + Switch。 */
@Composable
private fun GroupSwitchRow(
    group: com.mzstd.hxmyproxy.core.rules.RuleGroup,
    enabled: Set<String>,
    viewModel: MainViewModel,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(group.titleRes), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(group.sourceRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = group.id in enabled,
            onCheckedChange = { viewModel.toggleRuleGroup(group.id, it) },
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
