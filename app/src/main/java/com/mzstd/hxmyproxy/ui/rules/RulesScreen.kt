package com.mzstd.hxmyproxy.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
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
fun RulesScreen(ui: MainUiState, viewModel: MainViewModel, onManage: () -> Unit) {
    val s = ui.settings
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.rules_title), style = MaterialTheme.typography.titleLarge)
        if (ui.share.lockdownSuspected) {
            ElevatedCard(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Text(
                    stringResource(R.string.lockdown_warning),
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

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

        // —— ② App / 服务规则集 + 自建集（每集一个开关；管理入口可增删集/集内域名）——
        SectionCard(stringResource(R.string.rules_module_apps)) {
            Text(
                stringResource(R.string.rules_user_direct_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val descs = RuleCatalog.appGroups.map { g ->
                val (label, c) = groupVisual(g.id)
                RuleCellDesc(g.titleRes, null, label, c, g.id in s.enabledRuleGroups) {
                    viewModel.toggleRuleGroup(g.id, g.id !in s.enabledRuleGroups)
                }
            } + s.userRuleSets.map { set ->
                RuleCellDesc(null, set.name, set.name.take(1).ifEmpty { "·" }, null, set.enabled) {
                    viewModel.toggleRuleSet(set.id, !set.enabled)
                }
            }
            descs.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { d -> RuleSetGridCell(Modifier.weight(1f), d) }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            OutlinedButton(onClick = onManage, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.rules_manage))
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

/** 规则集网格单元描述（非 composable，避免在 map 里调 composable）。 */
private class RuleCellDesc(
    val titleRes: Int?, val titleStr: String?, val label: String,
    val colorLong: Long?, val enabled: Boolean, val onToggle: () -> Unit,
)

/** 内置 App 集的图标字 + 品牌色（首字占位、避免商标；真 logo 可后续接入）。 */
private fun groupVisual(id: String): Pair<String, Long?> = when (id) {
    "app-neteasemusic" -> "网" to 0xFFC20C0CL
    "app-bilibili" -> "B" to 0xFFFB7299L
    "app-wechat" -> "微" to 0xFF07C160L
    else -> (id.firstOrNull()?.uppercase() ?: "?") to null
}

/** 规则集圆形图标网格单元：圆形(开=品牌色 / 关=灰) + 名称；点击切换开关。 */
@Composable
private fun RuleSetGridCell(modifier: Modifier, d: RuleCellDesc) {
    val name = d.titleRes?.let { stringResource(it) } ?: d.titleStr ?: ""
    val brand = d.colorLong?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    Column(
        modifier.clickable { d.onToggle() }.padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .background(if (d.enabled) brand else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                d.label,
                color = if (d.enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.size(4.dp))
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
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
