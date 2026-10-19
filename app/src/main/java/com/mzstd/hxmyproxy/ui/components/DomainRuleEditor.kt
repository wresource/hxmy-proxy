package com.mzstd.hxmyproxy.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.mzstd.hxmyproxy.ui.components.AppAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleInput
import com.mzstd.hxmyproxy.core.rules.RuleScope
import com.mzstd.hxmyproxy.ui.MainViewModel

@Composable
fun DomainRuleEditor(viewModel: MainViewModel, initial: String = "", initialAction: RuleAction = RuleAction.PROXY,
    initialScope: RuleScope = RuleScope.EXACT, onDismiss: () -> Unit, onSaved: (String) -> Unit = {}) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    var text by remember(initial) { mutableStateOf(RuleInput.parse(initial)?.host ?: initial) }
    var action by remember { mutableStateOf(initialAction) }
    var scope by remember { mutableStateOf(initialScope) }
    var attempted by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var confirmWide by remember { mutableStateOf(false) }
    val parsed = remember(text, scope) { RuleInput.forScope(text, scope) }
    fun save(chosenScope: RuleScope) {
        saving = true
        viewModel.applyDomainRule(text, action, chosenScope) { result ->
            saving = false
            result.onSuccess { onDismiss(); onSaved(it) }.onFailure { failed = true }
        }
    }
    if (confirmWide) {
        AlertDialog(onDismissRequest = { confirmWide = false }, title = { Text(stringResource(R.string.rules_scope_confirm_title)) },
            text = { Text(stringResource(R.string.rules_scope_confirm_body, parsed?.host.orEmpty(), parsed?.host.orEmpty(), parsed?.host.orEmpty())) },
            confirmButton = { TextButton(onClick = { confirmWide = false; save(RuleScope.SUFFIX) }) { Text(stringResource(R.string.rules_scope_suffix)) } },
            dismissButton = { TextButton(onClick = { confirmWide = false; scope = RuleScope.EXACT }) { Text(stringResource(R.string.rules_scope_exact)) } })
    } else InteractionSheet(
        title = stringResource(if (initial.isBlank()) R.string.ui_add_rule else R.string.ui_domain_rule),
        onDismiss = onDismiss, dismissEnabled = !saving,
        footer = {
            SheetActions(stringResource(R.string.save), {
                attempted = true
                if (parsed != null && !saving) {
                    if (!parsed.isIp && scope == RuleScope.SUFFIX) confirmWide = true else save(scope)
                }
            }, stringResource(R.string.cancel), onDismiss, enabled = !saving, primaryTag = "domain_rule_save")
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.ui_domain_input), style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(text, { text = it; failed = false }, Modifier.fillMaxWidth().testTag("domain_rule_input"),
                placeholder = { Text("example.com") }, isError = attempted && parsed == null,
                singleLine = true, textStyle = MaterialTheme.typography.bodyLarge,
                supportingText = { Text(stringResource(if (attempted && parsed == null) R.string.ui_invalid_domain else R.string.ui_domain_input_hint)) }, shape = MaterialTheme.shapes.medium)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ui_rule_action), style = MaterialTheme.typography.labelLarge)
            listOf(RuleAction.PROXY, RuleAction.DIRECT, RuleAction.REJECT).forEach { choice ->
                SheetChoiceRow(stringResource(when (choice) {
                    RuleAction.PROXY -> R.string.override_proxy; RuleAction.DIRECT -> R.string.override_direct; RuleAction.REJECT -> R.string.override_reject
                }), action == choice, { action = choice }, stringResource(when (choice) {
                    RuleAction.PROXY -> R.string.ui_proxy_choice_hint; RuleAction.DIRECT -> R.string.ui_direct_choice_hint; RuleAction.REJECT -> R.string.ui_reject_choice_hint
                }))
            }
        }
        if ((action == RuleAction.DIRECT && !ui.settings.userDirectEnabled) || (action == RuleAction.REJECT && !ui.settings.userRejectEnabled)) {
            Text(stringResource(R.string.ui_rule_list_disabled), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (parsed?.isIp != true) RuleScopePicker(scope) { scope = it }
        if (failed) Text(stringResource(R.string.ui_save_failed), color = MaterialTheme.colorScheme.error)
    }
}
