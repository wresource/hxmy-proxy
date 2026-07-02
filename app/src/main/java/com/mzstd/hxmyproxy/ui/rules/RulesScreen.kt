package com.mzstd.hxmyproxy.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.painterResource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val history by viewModel.domainHistory.collectAsStateWithLifecycle()
    var showHistory by remember { mutableStateOf(false) }
    var listExpanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 页内大标题已删：底栏已标「规则」,页内再写「规则分流」冗余(用户反馈)。
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

        // —— ① IP / 域名白名单（直连，出口分流绕过共享 VPN；整体开关 + 从历史添加 + 超 2 条折叠）——
        SectionCard(
            stringResource(R.string.rules_module_list),
            trailing = {
                Switch(checked = s.userDirectEnabled, onCheckedChange = { viewModel.toggleUserDirectEnabled(it) })
            },
        ) {
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
                }, shape = MaterialTheme.shapes.large) { Text(stringResource(R.string.rules_add)) }
            }
            OutlinedButton(onClick = { showHistory = true }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                Text(stringResource(R.string.rules_add_from_history))
            }
            val rules = s.userDirectRules.sorted()
            val shownRules = if (listExpanded) rules else rules.take(2)
            shownRules.forEach { domain ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(domain, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { viewModel.removeUserDirectRule(domain) }, shape = MaterialTheme.shapes.large) {
                        Text(stringResource(R.string.rules_remove))
                    }
                }
            }
            if (rules.size > 2) {
                TextButton(onClick = { listExpanded = !listExpanded }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (listExpanded) stringResource(R.string.monitor_collapse)
                        else stringResource(R.string.monitor_expand, rules.size),
                    )
                }
            }
        }

        // —— ② App / 服务规则集 + 自建集（每集一个开关；管理入口可增删集/集内域名）——
        SectionCard(stringResource(R.string.rules_module_apps)) {
            Text(
                // 专属提示：原来错误复用了白名单的 rules_user_direct_hint（审计发现的文案 bug）。
                stringResource(R.string.rules_app_sets_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val descs = RuleCatalog.appGroups.map { g ->
                RuleCellDesc(g.titleRes, null, groupIcon(g.id), g.id in s.enabledRuleGroups) {
                    viewModel.toggleRuleGroup(g.id, g.id !in s.enabledRuleGroups)
                }
            } + s.userRuleSets.map { set ->
                RuleCellDesc(null, set.name, R.drawable.ic_rule_label, set.enabled) {
                    viewModel.toggleRuleSet(set.id, !set.enabled)
                }
            }
            descs.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { d -> RuleSetGridCell(Modifier.weight(1f), d) }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            OutlinedButton(onClick = onManage, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                Text(stringResource(R.string.rules_manage))
            }
        }

        // —— ③ 广告拦截（每表开关 + 用户白名单覆盖）——
        SectionCard(stringResource(R.string.rules_module_ads)) {
            RuleCatalog.adGroups.forEach { group ->
                GroupSwitchRow(group, s.enabledRuleGroups, viewModel)
            }
        }

        // 底部手势条穿透：无底栏时(横屏 rail)本 Spacer=手势条高,内容能完整滚出;有底栏时 inset 已被消费=0。
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))

        if (showHistory) {
            HistoryAddDialog(
                history = history.filter { it !in s.userDirectRules }.sorted(),
                onAdd = { viewModel.addUserDirectRule(it) },
                onDismiss = { showHistory = false },
            )
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
    val titleRes: Int?, val titleStr: String?, val iconRes: Int,
    val enabled: Boolean, val onToggle: () -> Unit,
)

/** 内置 App 集的语义图标（Material 开源图标）。避免商标：用音乐/视频/聊天等通用图标。 */
private fun groupIcon(id: String): Int = when (id) {
    "app-neteasemusic" -> R.drawable.ic_rule_music
    "app-bilibili" -> R.drawable.ic_rule_video
    "app-wechat" -> R.drawable.ic_rule_chat
    else -> R.drawable.ic_rule_label
}

/** 规则集圆形图标网格单元：圆形(开=主题 primary / 关=灰) + 名称；点击切换开关。
 *  开关状态只用主题色表达——原先的第三方品牌色(网易红/B站粉/微信绿)不随主题、与蓝粉打架,已收敛。 */
@Composable
private fun RuleSetGridCell(modifier: Modifier, d: RuleCellDesc) {
    val name = d.titleRes?.let { stringResource(it) } ?: d.titleStr ?: ""
    Column(
        modifier.clickable { d.onToggle() }.padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .background(if (d.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(d.iconRes),
                contentDescription = name,
                tint = if (d.enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.size(4.dp))
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SectionCard(title: String, trailing: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                trailing()
            }
            content()
        }
    }
}

/** 「从历史添加」对话框：列出访问过的域名(已在白名单的排除)，点一条加入白名单，可连点多条。 */
@Composable
private fun HistoryAddDialog(history: List<String>, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.setup_close)) } },
        title = { Text(stringResource(R.string.rules_add_from_history)) },
        text = {
            if (history.isEmpty()) {
                Text(stringResource(R.string.rules_history_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(history) { h ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onAdd(h) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(h, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text("+", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
    )
}
