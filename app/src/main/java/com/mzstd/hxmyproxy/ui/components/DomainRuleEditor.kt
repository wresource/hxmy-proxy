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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleInput
import com.mzstd.hxmyproxy.core.rules.RuleScope
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.data.repository.RuleLibraryRepository
import com.mzstd.hxmyproxy.ui.rules.RelatedRules
import com.mzstd.hxmyproxy.ui.rules.RuleNotice
import com.mzstd.hxmyproxy.ui.rules.RuleValueDetails
import com.mzstd.hxmyproxy.ui.rules.sourceName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
fun DomainRuleEditor(viewModel: MainViewModel, initial: String = "", initialAction: RuleAction = RuleAction.PROXY,
    initialScope: RuleScope = RuleScope.EXACT, onDismiss: () -> Unit, onSaved: (String) -> Unit = {}) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    var text by rememberSaveable(initial) { mutableStateOf(RuleInput.parse(initial)?.host ?: initial.takeIf { it.length <= 2048 }.orEmpty()) }
    var action by rememberSaveable { mutableStateOf(initialAction) }
    var scope by rememberSaveable { mutableStateOf(initialScope) }
    var inputTooLong by rememberSaveable { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var confirmWide by remember { mutableStateOf(false) }
    var related by remember { mutableStateOf<List<RuleLibraryRepository.Related>>(emptyList()) }
    var checking by remember { mutableStateOf(false) }
    var checkError by remember { mutableStateOf<String?>(null) }
    var noIpEvidence by remember { mutableStateOf(false) }
    var changingRelated by remember { mutableStateOf(false) }
    var undoId by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var viewing by remember { mutableStateOf<RuleLibraryRepository.Related?>(null) }
    var disabling by remember { mutableStateOf<RuleLibraryRepository.Related?>(null) }
    val parsed = remember(text, scope) { RuleInput.forScope(text, scope) }
    val key = parsed?.value?.let { it to action }
    val latestKey by rememberUpdatedState(key)
    var checkedKey by remember { mutableStateOf<Pair<String, RuleAction>?>(null) }
    var checkedRefresh by remember { mutableIntStateOf(-1) }
    LaunchedEffect(key, refresh, ui.settings) {
        checkedKey = null; checkError = null; related = emptyList(); noIpEvidence = false
        val request = key ?: return@LaunchedEffect
        val requestRefresh = refresh
        checking = true
        try {
            delay(180)
            val inspection = viewModel.inspectRule(request.first, request.second)
            if (latestKey == request && refresh == requestRefresh) { related = inspection.related; noIpEvidence = inspection.ipEvidenceMissing; checkedKey = request; checkedRefresh = requestRefresh }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { if (latestKey == request && refresh == requestRefresh) checkError = error.message ?: "Rule check failed" }
        finally { if (latestKey == request && refresh == requestRefresh) checking = false }
    }
    fun relatedAction(relation: RuleLibraryRepository.Related, enable: Boolean) {
        changingRelated = true
        val done: (Result<String?>) -> Unit = { result ->
            changingRelated = false
            result.onSuccess { undoId = it; refresh++ }.onFailure { checkError = it.message; failed = true }
        }
        if (enable) viewModel.enableRelatedRule(relation, done) else viewModel.disableRelatedRule(relation, done)
    }
    fun save(chosenScope: RuleScope) {
        saving = true
        viewModel.applyDomainRule(text, action, chosenScope) { result ->
            saving = false
            result.onSuccess { onDismiss(); onSaved(it) }.onFailure { failed = true }
        }
    }
    if (viewing != null) {
        RuleValueDetails(viewing!!.item, { viewing = null })
    } else if (confirmWide) {
        AlertDialog(onDismissRequest = { confirmWide = false }, title = { Text(stringResource(R.string.rules_scope_confirm_title)) },
            text = { Text(stringResource(R.string.rules_scope_confirm_body, parsed?.host.orEmpty(), parsed?.host.orEmpty(), parsed?.host.orEmpty())) },
            confirmButton = { TextButton(onClick = { confirmWide = false; save(RuleScope.SUFFIX) }) { Text(stringResource(R.string.rules_scope_suffix)) } },
            dismissButton = { TextButton(onClick = { confirmWide = false; scope = RuleScope.EXACT }) { Text(stringResource(R.string.rules_scope_exact)) } })
    } else InteractionSheet(
        title = stringResource(if (initial.isBlank()) R.string.ui_add_rule else R.string.ui_domain_rule),
        onDismiss = onDismiss, dismissEnabled = !saving && !changingRelated,
        footer = {
            SheetActions(stringResource(R.string.save), {
                attempted = true
                if (parsed != null && !saving && !changingRelated && !inputTooLong && checkedKey == key && checkedRefresh == refresh && checkError == null) {
                    if (!parsed.isIp && scope == RuleScope.SUFFIX) confirmWide = true else save(scope)
                }
            }, stringResource(R.string.cancel), onDismiss, enabled = !saving && !changingRelated,
                primaryEnabled = parsed != null && !inputTooLong && checkedKey == key && checkedRefresh == refresh && !checking && checkError == null, primaryTag = "domain_rule_save")
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.ui_domain_input), style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(text, { proposed ->
                val accepted = if (proposed.length <= 2048) proposed else RuleInput.parse(proposed)?.value?.takeIf { it.length <= 2048 }
                inputTooLong = accepted == null
                if (accepted != null) text = accepted
                failed = false
            }, Modifier.fillMaxWidth().testTag("domain_rule_input"), enabled = !saving && !changingRelated,
                placeholder = { Text("example.com") }, isError = inputTooLong || attempted && parsed == null,
                singleLine = true, textStyle = MaterialTheme.typography.bodyLarge,
                supportingText = { Text(stringResource(if (inputTooLong) R.string.rl_single_too_long else if (attempted && parsed == null) R.string.ui_invalid_domain else R.string.ui_domain_input_hint)) }, shape = MaterialTheme.shapes.medium)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ui_rule_action), style = MaterialTheme.typography.labelLarge)
            listOf(RuleAction.PROXY, RuleAction.DIRECT, RuleAction.REJECT).forEach { choice ->
                SheetChoiceRow(stringResource(when (choice) {
                    RuleAction.PROXY -> R.string.override_proxy; RuleAction.DIRECT -> R.string.override_direct; RuleAction.REJECT -> R.string.override_reject
                }), action == choice, { action = choice }, stringResource(when (choice) {
                    RuleAction.PROXY -> R.string.ui_proxy_choice_hint; RuleAction.DIRECT -> R.string.ui_direct_choice_hint; RuleAction.REJECT -> R.string.ui_reject_choice_hint
                }), enabled = !saving && !changingRelated)
            }
        }
        if ((action == RuleAction.DIRECT && !ui.settings.userDirectEnabled) || (action == RuleAction.REJECT && !ui.settings.userRejectEnabled)) {
            Text(stringResource(R.string.ui_rule_list_disabled), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (parsed?.isIp != true) RuleScopePicker(scope, enabled = !saving && !changingRelated) { scope = it }
        if (noIpEvidence) RuleNotice(stringResource(R.string.rl_no_ip_evidence))
        if (related.any { it.item.source.isManual && RuleInput.parse(it.item.row.value)?.host == parsed?.host }) RuleNotice(stringResource(R.string.rl_replaces_manual))
        RelatedRules(related, checking, checkError, { viewing = it }, { relation ->
            if (relation.item.source.isManual) relatedAction(relation, false) else disabling = relation
        }, { relatedAction(it, true) }, { refresh++ }, !saving && !changingRelated)
        if (undoId != null) RuleNotice(stringResource(R.string.rl_saved)) {
            TextButton(onClick = { val id = undoId ?: return@TextButton; changingRelated = true; viewModel.revertLibraryRuleChange(id) { result ->
                changingRelated = false; if (result.getOrNull() == true) { undoId = null; refresh++ } else failed = true
            } }, Modifier.testTag("related_undo"), enabled = !changingRelated && !saving) { Text(stringResource(R.string.rl_undo)) }
        }
        if (failed) Text(stringResource(R.string.ui_save_failed), color = MaterialTheme.colorScheme.error)
    }
    disabling?.let { relation -> AlertDialog(onDismissRequest = { disabling = null },
        title = { Text(stringResource(R.string.rl_pause_set)) },
        text = { Text(pluralStringResource(R.plurals.rl_pause_set_confirm, relation.item.source.count, sourceName(relation.item.source), relation.item.source.count)) },
        confirmButton = { TextButton(onClick = { disabling = null; relatedAction(relation, false) }) { Text(stringResource(R.string.rl_pause_set)) } },
        dismissButton = { TextButton(onClick = { disabling = null }) { Text(stringResource(R.string.cancel)) } }) }
}
