package com.mzstd.hxmyproxy.ui.rules

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.mzstd.hxmyproxy.ui.components.AppAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleScope
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.components.DetailScaffold
import com.mzstd.hxmyproxy.ui.components.DomainRuleEditor
import com.mzstd.hxmyproxy.ui.components.AdaptiveActionPair
import com.mzstd.hxmyproxy.ui.components.WorkbenchRow
import com.mzstd.hxmyproxy.ui.monitor.actionLabel
import com.mzstd.hxmyproxy.ui.monitor.activityTime

@Composable
fun RecentRuleChangesScreen(viewModel: MainViewModel, onBack: () -> Unit, onActivity: (String) -> Unit, onOverrides: () -> Unit,
    library: RuleLibraryViewModel? = null) {
    val changes by viewModel.recentRuleChanges.collectAsStateWithLifecycle()
    val unreadable by viewModel.ruleChangeHistoryUnreadable.collectAsStateWithLifecycle()
    val batches = library?.history?.collectAsStateWithLifecycle()?.value.orEmpty()
    val ordered = remember(changes, batches) {
        (changes.map { it.at to "host:${it.id}" } + batches.map { it.at to "batch:${it.id}" }).sortedByDescending { it.first }.distinctBy { it.second }
    }
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val timeFormat = remember(locale) { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, locale) }
    var clearing by rememberSaveable { mutableStateOf(false) }
    DetailScaffold(stringResource(R.string.ui_rule_changes), onBack, actions = {
        IconButton(onClick = { clearing = true }, enabled = changes.isNotEmpty() || batches.isNotEmpty() || unreadable,
            modifier = Modifier.testTag("clear_rule_history")) {
            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.ui_clear_rule_history))
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("rule_change_history"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.ui_rule_changes_hint), style = MaterialTheme.typography.bodySmall) }
            if (library != null) item { RuleMessage(library) }
            if (unreadable) item { Text(stringResource(R.string.ui_rule_history_unreadable), color = MaterialTheme.colorScheme.error) }
            else if (ordered.isEmpty()) item { Text(stringResource(R.string.ui_no_rule_changes)) }
            items(ordered, key = { it.second }) { (_, key) ->
              val batch = batches.firstOrNull { "batch:${it.id}" == key }
              if (batch != null && library != null) {
                RuleCard {
                    val verb = stringResource(when (batch.kind) {
                        "import" -> R.string.rl_history_import; "delete" -> R.string.rl_history_delete
                        "create" -> R.string.rl_history_create; "copy" -> R.string.rl_history_copy
                        "restore" -> R.string.rl_history_restore; "enable" -> R.string.rl_history_enable
                        "disable" -> R.string.rl_history_disable; else -> R.string.rl_history_edit
                    })
                    val group = if (batch.target.startsWith("builtin:")) com.mzstd.hxmyproxy.core.rules.RuleCatalog.byId(batch.target.substringAfter(':')) else null
                    val label = if (batch.kind != "import" && group != null) stringResource(group.titleRes) else batch.label
                    Text(if (label.isBlank()) verb else "$verb · $label", style = MaterialTheme.typography.titleMedium)
                    Text(timeFormat.format(java.util.Date(batch.at)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (batch.reverted || batch.superseded) Text(stringResource(if (batch.reverted) R.string.ui_reverted else R.string.ui_rule_changed_later), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { library.undo(batch.id) }, enabled = !batch.reverted && !batch.superseded,
                        modifier = Modifier.testTag("revert_batch_${batch.id}")) { Text(stringResource(R.string.rl_undo)) }
                }
              } else {
                val change = changes.firstOrNull { "host:${it.id}" == key } ?: return@items
                Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(change.rule, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
                        Text("${timeFormat.format(java.util.Date(change.at))} · ${stringResource(change.action?.let(::actionLabel) ?: R.string.top_domain_clear)}",
                            style = MaterialTheme.typography.bodyMedium)
                        if (change.reverted || change.superseded) Text(stringResource(if (change.reverted) R.string.ui_reverted else R.string.ui_rule_changed_later),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        AdaptiveActionPair(stringResource(R.string.ui_view_traffic), stringResource(R.string.ui_revert_rule), extraWidth = 24.dp) { button ->
                        TextButton(modifier = button, onClick = { onActivity(change.host) }) { Text(stringResource(R.string.ui_view_traffic)) }
                        TextButton(modifier = button.testTag("revert_rule_${change.id}"), enabled = !change.reverted && !change.superseded, onClick = {
                            viewModel.revertDomainChange(change.id) { result ->
                                Toast.makeText(context, if (result.getOrNull() == true) R.string.ui_reverted else R.string.ui_revert_conflict, Toast.LENGTH_LONG).show()
                            }
                        }) { Text(stringResource(R.string.ui_revert_rule)) }
                        }
                    }
                }
              }
            }
            if (batches.isNotEmpty()) item { Text(stringResource(R.string.rl_history_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { WorkbenchRow(stringResource(R.string.ui_manual_overrides), onClick = onOverrides) }
        }
    }
    if (clearing) AlertDialog(onDismissRequest = { clearing = false },
        title = { Text(stringResource(R.string.ui_clear_rule_history)) },
        text = { Text(stringResource(R.string.ui_clear_rule_history_confirm)) },
        confirmButton = { TextButton(modifier = Modifier.testTag("confirm_clear_rule_history"), onClick = {
            viewModel.clearRuleChangeHistory { result ->
                if (result.isSuccess) clearing = false
                else Toast.makeText(context, R.string.ui_rule_history_clear_failed, Toast.LENGTH_LONG).show()
            }
        }) { Text(stringResource(R.string.ui_clear_rule_history)) } },
        dismissButton = { TextButton(onClick = { clearing = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
fun ManualOverridesScreen(ui: MainUiState, viewModel: MainViewModel, onBack: () -> Unit, onChanges: () -> Unit) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    DetailScaffold(stringResource(R.string.ui_manual_overrides), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Button(onClick = { editing = "" }) { Text(stringResource(R.string.ui_add_rule)) }
                TextButton(onClick = onChanges) { Text(stringResource(R.string.ui_rule_changes)) }
            } }
            item { Text(stringResource(R.string.ui_override_hint), style = MaterialTheme.typography.bodySmall) }
            if (ui.settings.hostOverrides.isEmpty()) item { Text(stringResource(R.string.ui_no_overrides)) }
            items(ui.settings.hostOverrides.entries.toList(), key = { it.key }) { (rule, action) ->
                Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { editing = rule }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(rule, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(actionLabel(action)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(painterResource(R.drawable.ic_b_edit), stringResource(R.string.ui_change_domain_rule), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        TextButton(onClick = { removing = rule }) { Text(stringResource(R.string.ui_remove_override), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    editing?.let { rule -> DomainRuleEditor(viewModel, rule, ui.settings.hostOverrides[rule] ?: RuleAction.PROXY,
        if (rule.isBlank()) RuleScope.EXACT else RuleScope.parse(rule).first, onDismiss = { editing = null }) }
    removing?.let { rule -> AlertDialog(onDismissRequest = { removing = null },
        title = { Text(stringResource(R.string.ui_remove_override)) }, text = { Text(rule) },
        confirmButton = { TextButton(onClick = { viewModel.clearHostOverride(rule); removing = null }) { Text(stringResource(R.string.ui_remove_override)) } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } }) }
}
