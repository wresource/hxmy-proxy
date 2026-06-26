package com.mzstd.hxmyproxy.ui.rules

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleCatalog
import com.mzstd.hxmyproxy.core.rules.RuleGroup
import com.mzstd.hxmyproxy.core.rules.UserRuleSet
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 规则集管理：我的规则集（自建、可增删集 + 集内域名，支持泛域名）+ 内置规则集（只读可查看域名 + 开关）。
 */
@Composable
fun RuleSetManagerScreen(ui: MainUiState, viewModel: MainViewModel, onBack: () -> Unit) {
    var showNew by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                }
                Text(stringResource(R.string.ruleset_manager_title), style = MaterialTheme.typography.titleLarge)
            }
        }

        // —— 我的规则集 ——
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ruleset_my), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { showNew = true }) { Text(stringResource(R.string.ruleset_new)) }
            }
        }
        if (ui.settings.userRuleSets.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.ruleset_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(ui.settings.userRuleSets, key = { it.id }) { set -> UserRuleSetCard(set, viewModel) }
        }

        // —— 内置规则集 ——
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text(stringResource(R.string.ruleset_builtin), style = MaterialTheme.typography.titleMedium) }
        items(RuleCatalog.all, key = { it.id }) { group ->
            BuiltinGroupCard(group, group.id in ui.settings.enabledRuleGroups, viewModel)
        }
    }

    if (showNew) {
        NewRuleSetDialog(
            onCreate = { name, action -> viewModel.addRuleSet(name, action); showNew = false },
            onDismiss = { showNew = false },
        )
    }
}

@Composable
private fun UserRuleSetCard(set: UserRuleSet, viewModel: MainViewModel) {
    var expanded by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(set.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        "${actionLabel(set.action)} · ${stringResource(R.string.ruleset_domains_count, set.domains.size)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = set.enabled, onCheckedChange = { viewModel.toggleRuleSet(set.id, it) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(if (expanded) R.string.monitor_collapse else R.string.ruleset_edit))
                }
                TextButton(onClick = { viewModel.deleteRuleSet(set.id) }) { Text(stringResource(R.string.delete)) }
            }
            if (expanded) {
                set.domains.sorted().forEach { d ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(d, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { viewModel.removeDomainFromSet(set.id, d) }) { Text(stringResource(R.string.rules_remove)) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = input, onValueChange = { input = it }, singleLine = true,
                        label = { Text(stringResource(R.string.rules_add_domain)) }, modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { if (input.isNotBlank()) { viewModel.addDomainToSet(set.id, input); input = "" } }) {
                        Text(stringResource(R.string.rules_add))
                    }
                }
            }
        }
    }
}

@Composable
private fun BuiltinGroupCard(group: RuleGroup, enabled: Boolean, viewModel: MainViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(group.titleRes), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(group.sourceRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = { viewModel.toggleRuleGroup(group.id, it) })
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.monitor_collapse else R.string.ruleset_view_domains))
            }
            if (expanded) {
                val preview by produceState<Pair<Int, List<String>>?>(initialValue = null, group.id) {
                    value = loadPreview(context, group.assetPath)
                }
                val p = preview
                if (p == null) {
                    Text("…", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        stringResource(R.string.ruleset_total, p.first),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    p.second.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (p.first > p.second.size) Text("…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun NewRuleSetDialog(onCreate: (String, RuleAction) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var action by remember { mutableStateOf(RuleAction.DIRECT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name, action) }) { Text(stringResource(R.string.create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        title = { Text(stringResource(R.string.ruleset_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.ruleset_name)) })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = action == RuleAction.DIRECT, onClick = { action = RuleAction.DIRECT }, label = { Text(stringResource(R.string.ruleset_action_direct)) })
                    FilterChip(selected = action == RuleAction.REJECT, onClick = { action = RuleAction.REJECT }, label = { Text(stringResource(R.string.ruleset_action_reject)) })
                }
            }
        },
    )
}

@Composable
private fun actionLabel(action: RuleAction): String = stringResource(
    when (action) {
        RuleAction.REJECT -> R.string.ruleset_action_reject
        else -> R.string.ruleset_action_direct
    },
)

/** 读 assets 清单预览（纯 .txt；返回总条数 + 前 [limit] 条）。 */
private suspend fun loadPreview(context: Context, assetPath: String, limit: Int = 30): Pair<Int, List<String>> =
    withContext(Dispatchers.IO) {
        var total = 0
        val sample = ArrayList<String>(limit)
        runCatching {
            context.assets.open(assetPath).bufferedReader().forEachLine { line ->
                val d = line.trim()
                if (d.isNotEmpty() && d[0] != '#') {
                    total++
                    if (sample.size < limit) sample.add(d)
                }
            }
        }
        total to sample
    }
