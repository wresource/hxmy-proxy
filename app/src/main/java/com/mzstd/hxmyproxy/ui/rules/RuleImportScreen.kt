package com.mzstd.hxmyproxy.ui.rules

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.data.repository.RuleContentStore
import com.mzstd.hxmyproxy.data.repository.RuleImportRepository as Importer
import com.mzstd.hxmyproxy.data.repository.RuleLibraryRepository as Library
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.components.*
import com.mzstd.hxmyproxy.ui.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

@Composable
fun RuleImportScreen(ui: MainUiState, vm: RuleLibraryViewModel, onBack: () -> Unit,
    onSource: (String, String) -> Unit, onChanges: () -> Unit) {
    LaunchedEffect(vm) { vm.activate() }
    val state by vm.importState.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val recent by vm.recentImports.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val job = state.job
    val active = state.step == RuleLibraryViewModel.Step.READING || state.step == RuleLibraryViewModel.Step.SAVING
    var paste by remember { mutableStateOf(false) }
    var formats by remember { mutableStateOf(false) }
    var issues by remember { mutableStateOf(false) }
    var fullPreview by remember { mutableStateOf(false) }
    var mode by rememberSaveable(job?.id) { mutableIntStateOf(when (state.requestedTarget) {
        null -> 0; "direct", "reject", "overrides", "overrides-paused" -> 2; else -> 1
    }) }
    var targetId by rememberSaveable(job?.id) { mutableStateOf(state.requestedTarget) }
    var name by rememberSaveable(job?.id) { mutableStateOf(job?.destinationName.orEmpty()) }
    var nameChanged by rememberSaveable(job?.id) { mutableStateOf(job?.destinationName != null) }
    var action by rememberSaveable(job?.id) { mutableStateOf(state.action) }
    var enabled by rememberSaveable(job?.id) { mutableStateOf(job?.destinationEnabled ?: (state.requestedTarget != "overrides-paused")) }
    var replace by rememberSaveable(job?.id) { mutableStateOf(job?.destinationReplace ?: false) }
    var targetPicker by remember { mutableStateOf(false) }
    var typePicker by remember { mutableStateOf(false) }
    var replaceConfirm by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Library.Related?>(null) }
    var disabling by remember { mutableStateOf<Library.Related?>(null) }
    val manualId = when (action) { RuleAction.DIRECT -> "direct"; RuleAction.REJECT -> "reject"; RuleAction.PROXY -> if (enabled) "overrides" else "overrides-paused" }
    val target = if (mode == 0) null else sources.firstOrNull { it.id == if (mode == 2) manualId else targetId }
    val chosenAction = if (mode == 1) target?.action ?: action else action
    LaunchedEffect(job?.id, job?.name) { if (!nameChanged) name = job?.name.orEmpty().substringBeforeLast('.', job?.name.orEmpty()) }
    LaunchedEffect(state.requestedTarget, sources) {
        if (sources.firstOrNull { it.id == state.requestedTarget }?.kind == Library.Kind.SUBSCRIPTION) replace = true
    }
    LaunchedEffect(chosenAction, state.step) {
        if (job?.collection != null && state.step in listOf(RuleLibraryViewModel.Step.REVIEW, RuleLibraryViewModel.Step.TARGET) && chosenAction != state.action) vm.inspect(chosenAction)
    }
    val resolver = LocalContext.current.applicationContext.contentResolver
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::choose) }
    val report = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let { vm.exportIssues(it, resolver) } }
    fun back() { when {
        issues -> issues = false
        fullPreview -> fullPreview = false
        active -> vm.cancelImport()
        state.step == RuleLibraryViewModel.Step.TARGET -> vm.review()
        state.step == RuleLibraryViewModel.Step.REVIEW -> vm.beginImport(state.requestedTarget)
        else -> onBack()
    } }
    BackHandler(state.step != RuleLibraryViewModel.Step.SELECT || issues || fullPreview) { back() }
    val retry by vm.queryRevision.collectAsStateWithLifecycle()
    var refError by remember { mutableStateOf(false) }
    val ref by produceState<RuleContentStore.Ref?>(null, job?.collection, retry) {
        value = null; refError = false
        try { value = withContext(Dispatchers.IO) { job?.collection?.let { requireNotNull(vm.imports.store.ref(it)) } } }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { refError = true }
    }
    val previewSource = remember(ref, chosenAction, job?.name) { ref?.let { Library.Source("preview:${job?.id}", Library.Kind.USER, job?.name.orEmpty(), action = chosenAction, enabled = true, ref = it, editable = false) } }
    val preview = rulePage(vm, if (state.step == RuleLibraryViewModel.Step.REVIEW) listOfNotNull(previewSource) else emptyList(), "", limit = 5)
    if (issues && job != null) { ImportIssuesScreen(vm, job, { issues = false }, { report.launch("rule-import-problems.txt") }); return }
    if (fullPreview && previewSource != null) { ImportPreviewScreen(vm, previewSource!!, { fullPreview = false }); return }
    val title = stringResource(when (state.step) {
        RuleLibraryViewModel.Step.SELECT -> R.string.ruleset_import
        RuleLibraryViewModel.Step.READING -> R.string.rl_checking
        RuleLibraryViewModel.Step.REVIEW -> R.string.rl_review
        RuleLibraryViewModel.Step.TARGET -> R.string.rl_choose_target
        RuleLibraryViewModel.Step.SAVING -> R.string.rl_saving
        RuleLibraryViewModel.Step.RESULT -> R.string.rl_saved
        RuleLibraryViewModel.Step.FAILED -> R.string.rl_import_failed
    })
    fun save() = vm.save(Importer.Destination(target, name, chosenAction, enabled, mode != 0 && replace,
        state.skipInvalid, preserveTargetState = mode == 1))
    RuleDetailLayout(title, ::back, footer = {
        when (state.step) {
            RuleLibraryViewModel.Step.SELECT -> TextButton(onClick = onBack, Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
            RuleLibraryViewModel.Step.READING, RuleLibraryViewModel.Step.SAVING -> OutlinedButton(onClick = vm::cancelImport, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.rl_cancel_import)) }
            RuleLibraryViewModel.Step.REVIEW -> {
                if ((job?.invalid ?: 0) > 0) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(state.skipInvalid, vm::skipInvalid, Modifier.testTag("import_skip_invalid"))
                    Text(stringResource(R.string.rl_skip_issues), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = vm::target, Modifier.fillMaxWidth().testTag("import_next"), shape = MaterialTheme.shapes.medium,
                    enabled = ref != null && (job?.valid ?: 0) > 0 && ((job?.invalid ?: 0) == 0 || state.skipInvalid) && !state.checking && state.checkError == null) {
                    Text(stringResource(if (state.relations.any { it.relation != Library.Relation.DISABLED }) R.string.rl_keep_continue else R.string.rl_choose_target))
                }
            }
            RuleLibraryViewModel.Step.TARGET -> {
                Button(onClick = { if (replace && target != null && target.count > 0) replaceConfirm = true else save() }, Modifier.fillMaxWidth().testTag("import_save"), shape = MaterialTheme.shapes.medium,
                    enabled = ref != null && !state.checking && state.action == chosenAction && state.checkError == null && !busy && (mode == 0 && name.isNotBlank() && name.length <= 200 && chosenAction != RuleAction.PROXY || mode != 0 && target != null)) {
                    Text(stringResource(R.string.rl_save_confirm))
                }
            }
            RuleLibraryViewModel.Step.RESULT -> {
                Button(onClick = { job?.target?.let { onSource(it, "") } }, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, enabled = job?.target != null) { Text(stringResource(R.string.rl_view_imported)) }
                TextButton(onClick = { vm.beginImport() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.rl_continue_import)) }
            }
            RuleLibraryViewModel.Step.FAILED -> {
                Button(onClick = { if (job?.collection != null) vm.review() else vm.beginImport(state.requestedTarget) }, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.rl_retry)) }
                TextButton(onClick = onBack, Modifier.fillMaxWidth()) { Text(stringResource(R.string.back)) }
            }
        }
    }) {
        LazyColumn(Modifier.fillMaxSize().testTag("rule_import_page"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { com.mzstd.hxmyproxy.ui.components.RuleApplicationNotice(ui.share) }
            if (refError) item { RuleQueryError(vm) }
            if (job != null) item { Text(job.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            when (state.step) {
                RuleLibraryViewModel.Step.SELECT -> {
                    item { Text(stringResource(R.string.rl_choose_hint), style = MaterialTheme.typography.bodyLarge) }
                    item { RuleCard {
                        Icon(painterResource(R.drawable.ic_b_doc), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.rl_import_file), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.rl_format_limits), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { choose.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) }, Modifier.fillMaxWidth().testTag("choose_rule_file"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.rl_choose_file)) }
                    } }
                    item { AdaptiveActionPair(stringResource(R.string.rl_paste), stringResource(R.string.rl_formats)) { button ->
                        FilledTonalButton(onClick = { paste = true }, modifier = button.testTag("import_paste"), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.rl_paste)) }
                        OutlinedButton(onClick = { formats = true }, modifier = button, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.rl_formats)) }
                    } }
                    items(recent.filter { it.phase in listOf(Importer.Phase.READY, Importer.Phase.INTERRUPTED, Importer.Phase.FAILED) }.take(3), key = { it.id }) { old ->
                        RuleCard {
                            Text(stringResource(R.string.rl_unfinished), style = MaterialTheme.typography.labelLarge)
                            Text(old.name, style = MaterialTheme.typography.bodyLarge)
                            if (old.phase == Importer.Phase.INTERRUPTED) Text(stringResource(R.string.rl_interrupted), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { vm.resume(old) }) { Text(stringResource(R.string.rl_resume)) }
                        }
                    }
                }
                RuleLibraryViewModel.Step.READING, RuleLibraryViewModel.Step.SAVING -> item { RuleCard {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(if (state.step == RuleLibraryViewModel.Step.READING) R.string.rl_checking else R.string.rl_saving), style = MaterialTheme.typography.titleMedium)
                    Text(pluralStringResource(R.plurals.rl_read_progress, job?.lines ?: 0, job?.lines ?: 0, formatBytes(job?.bytes ?: 0)), style = MaterialTheme.typography.bodyMedium)
                } }
                RuleLibraryViewModel.Step.REVIEW -> {
                    item { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(pluralStringResource(R.plurals.rl_valid_count, job?.valid ?: 0, job?.valid ?: 0), style = MaterialTheme.typography.headlineMedium)
                        Text(pluralStringResource(R.plurals.rl_file_summary, job?.lines ?: 0, job?.lines ?: 0, formatBytes(job?.bytes ?: 0)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                    item { ImportStats(job?.valid ?: 0, job?.duplicates ?: 0, job?.invalid ?: 0) }
                    if ((job?.invalid ?: 0) > 0) item { RuleNotice(stringResource(R.string.rl_invalid), true) {
                        TextButton(onClick = { issues = true }, modifier = Modifier.testTag("import_issues")) { Text(stringResource(R.string.rl_view_issues)) }
                    } }
                    item { RuleCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.rl_review), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { fullPreview = true }, enabled = previewSource != null) { Text(stringResource(R.string.rl_view_all)) }
                        }
                        if (preview.error != null) RuleQueryError(vm)
                        else preview.page?.items?.forEach { entry -> Text(entry.row.value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace) }
                    } }
                    if (state.hasDomainAndIpRules) item { RuleNotice(stringResource(R.string.rl_batch_ip_hint)) }
                    item { RelatedRules(state.relations, state.checking, state.checkError, { viewing = it }, { relation -> if (relation.item.source.isManual) vm.disableRelated(relation) else disabling = relation }, vm::enableRelated, { vm.inspect() }, !busy) }
                    if (notice != null) item { RuleMessage(vm) }
                }
                RuleLibraryViewModel.Step.TARGET -> {
                    item { Text(pluralStringResource(R.plurals.rl_rules_count, job?.valid ?: 0, job?.valid ?: 0), style = MaterialTheme.typography.headlineSmall) }
                    item { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.rl_choose_target), style = MaterialTheme.typography.labelLarge)
                        OutlinedButton(onClick = { typePicker = true }, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                            Text(stringResource(listOf(R.string.rl_target_new, R.string.rl_target_existing, R.string.rl_target_manual)[mode]), Modifier.weight(1f)); Icon(painterResource(R.drawable.ic_b_chevron_down), null, Modifier.size(20.dp))
                        }
                    } }
                    if (mode == 0) item { OutlinedTextField(name, { name = it; nameChanged = true }, Modifier.fillMaxWidth().testTag("import_set_name"),
                        label = { Text(stringResource(R.string.rl_name)) }, singleLine = true, shape = MaterialTheme.shapes.medium) }
                    if (mode == 1) item { OutlinedButton(onClick = { targetPicker = true }, Modifier.fillMaxWidth().testTag("import_target_picker"), shape = MaterialTheme.shapes.medium) {
                        Text(target?.let { sourceName(it) } ?: stringResource(R.string.rl_target_existing), Modifier.weight(1f)); Icon(painterResource(R.drawable.ic_b_chevron_down), null, Modifier.size(20.dp))
                    } }
                    if (mode != 0) item { RuleTabs(listOf(stringResource(R.string.rl_append), stringResource(R.string.rl_replace)), if (replace) 1 else 0, { replace = it == 1 }, "import_merge") }
                    item { RuleActionChoices(chosenAction, { action = it; vm.inspect(it) }, allowProxy = mode == 2, enabled = mode != 1) }
                    if (chosenAction == RuleAction.DIRECT && ui.share.directEgressUnavailable) item { RuleNotice(stringResource(R.string.rl_direct_missing), true) }
                    item { RuleCard {
                        if (mode == 1) {
                            Text(stringResource(R.string.rl_keep_status), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(if (target?.enabled == true) R.string.rl_enabled else R.string.rl_paused), style = MaterialTheme.typography.titleMedium)
                        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.rl_enable_after), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Switch(enabled, { enabled = it }, modifier = Modifier.testTag("import_enable"))
                        }
                        if (mode != 1 && !enabled) Text(stringResource(R.string.rl_save_only), style = MaterialTheme.typography.bodySmall)
                    } }
                    if (state.hasDomainAndIpRules) item { RuleNotice(stringResource(R.string.rl_batch_ip_hint)) }
                    item { RelatedRules(state.relations, state.checking, state.checkError, { viewing = it }, { relation -> if (relation.item.source.isManual) vm.disableRelated(relation) else disabling = relation }, vm::enableRelated, { vm.inspect(chosenAction) }, !busy) }
                    item { Text(stringResource(R.string.rl_new_connections), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (notice != null) item { RuleMessage(vm) }
                }
                RuleLibraryViewModel.Step.RESULT -> {
                    item { RuleCard {
                        Icon(painterResource(R.drawable.ic_b_check), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.rl_saved), style = MaterialTheme.typography.headlineMedium)
                        Text(stringResource(R.string.rl_saved_count, job?.saved ?: 0, job?.skippedExisting ?: 0), style = MaterialTheme.typography.bodyLarge)
                        if (job != null) ImportStats(job.saved, job.duplicates, job.invalid)
                    } }
                    item { RuleNotice(stringResource(R.string.rl_new_connections)) }
                    if ((job?.invalid ?: 0) > 0) item { OutlinedButton(onClick = { report.launch("rule-import-problems.txt") }, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.rl_export_issues)) } }
                    item { TextButton(onClick = onChanges, Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_rule_changes)) } }
                }
                RuleLibraryViewModel.Step.FAILED -> {
                    item { RuleNotice(stringResource(R.string.rl_failed_hint), true) }
                    item { RuleCard { Text(stringResource(R.string.rl_details), style = MaterialTheme.typography.titleMedium); Text(job?.message ?: state.checkError.orEmpty(), style = MaterialTheme.typography.bodyMedium) } }
                }
            }
        }
    }
    if (paste) PasteRulesSheet({ paste = false }) { value -> paste = false; vm.paste(value) }
    if (formats) RuleFormats { formats = false }
    if (typePicker) InteractionSheet(stringResource(R.string.rl_choose_target), { typePicker = false }) {
        listOf(R.string.rl_target_new, R.string.rl_target_existing, R.string.rl_target_manual).forEachIndexed { index, label ->
            SheetChoiceRow(stringResource(label), mode == index, { mode = index; typePicker = false; if (mode != 2 && action == RuleAction.PROXY) action = RuleAction.DIRECT })
        }
    }
    if (targetPicker) InteractionSheet(stringResource(R.string.rl_target_existing), { targetPicker = false }, scrollBody = false) {
        val choices = sources.filter { !it.isManual && it.editable }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            if (choices.isEmpty()) item { Text(stringResource(R.string.rl_empty)) }
            items(choices, key = { it.id }) { source -> RuleSourceRow(source) { targetId = source.id; targetPicker = false; vm.inspect(source.action) } }
        }
    }
    if (replaceConfirm && target != null && job != null) AppAlertDialog(onDismissRequest = { replaceConfirm = false },
        title = { Text(stringResource(R.string.rl_replace)) }, text = { Text(stringResource(R.string.rl_replace_confirm, sourceName(target),
            pluralStringResource(R.plurals.rl_rules_count, target.count, target.count), pluralStringResource(R.plurals.rl_rules_count, job.valid, job.valid))) },
        confirmButton = { TextButton(onClick = { replaceConfirm = false; save() }, modifier = Modifier.testTag("confirm_rule_replace")) { Text(stringResource(R.string.rl_replace)) } },
        dismissButton = { TextButton(onClick = { replaceConfirm = false }) { Text(stringResource(R.string.cancel)) } })
    viewing?.let { RuleValueDetails(it.item, { viewing = null }) }
    disabling?.let { relation -> AppAlertDialog(onDismissRequest = { disabling = null }, title = { Text(stringResource(R.string.rl_pause_set)) },
        text = { Text(pluralStringResource(R.plurals.rl_pause_set_confirm, relation.item.source.count, sourceName(relation.item.source), relation.item.source.count)) },
        confirmButton = { TextButton(onClick = { disabling = null; vm.disableRelated(relation) }) { Text(stringResource(R.string.rl_pause_set)) } },
        dismissButton = { TextButton(onClick = { disabling = null }) { Text(stringResource(R.string.cancel)) } }) }
}

@Composable private fun ImportStats(valid: Int, duplicates: Int, invalid: Int) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 300.dp * fontScale) 3 else if (maxWidth >= 200.dp * fontScale) 2 else 1
        val values = listOf(R.string.rl_valid to valid, R.string.rl_duplicates to duplicates, R.string.rl_invalid to invalid)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            values.chunked(columns).forEach { group -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                group.forEach { (label, count) -> Surface(Modifier.weight(1f), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(count.toString(), style = MaterialTheme.typography.headlineSmall); Text(stringResource(label), style = MaterialTheme.typography.bodySmall) }
                } }
                repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
            } }
        }
    }
}

@Composable private fun PasteRulesSheet(onDismiss: () -> Unit, onCheck: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var text by rememberSaveable { mutableStateOf("") }
    var longText by remember { mutableStateOf<String?>(null) }
    InteractionSheet(stringResource(R.string.rl_paste), onDismiss, footer = {
        Button(onClick = { onCheck(longText ?: text) }, Modifier.fillMaxWidth().testTag("check_pasted_rules"), enabled = !(longText ?: text).isBlank(), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.rl_check_content)) }
    }) {
        OutlinedButton(onClick = { clipboard.getText()?.text?.takeIf(String::isNotBlank)?.let(onCheck) }, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.rl_read_clipboard)) }
        if (longText != null) RuleNotice(stringResource(R.string.rl_long_paste))
        OutlinedTextField(text, { if (it.length > 8192) longText = it else { text = it; longText = null } }, Modifier.fillMaxWidth().heightIn(max = 180.dp).testTag("paste_rules_input"),
            minLines = 3, maxLines = 6, placeholder = { Text("192.168.0.0/16\n1.2.3.4 1.2.3.255") }, shape = MaterialTheme.shapes.medium)
        Text(stringResource(R.string.rl_format_limits), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun ImportIssuesScreen(vm: RuleLibraryViewModel, job: Importer.Job, onBack: () -> Unit, onExport: () -> Unit) {
    var offset by rememberSaveable(job.id) { mutableIntStateOf(0) }
    val retry by vm.queryRevision.collectAsStateWithLifecycle()
    var failed by remember { mutableStateOf(false) }
    val rows by produceState<List<RuleContentStore.Issue>?>(null, job.id, offset, retry) {
        value = null; failed = false
        try { value = vm.imports.issues(job, offset) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { failed = true }
    }
    RuleDetailLayout(stringResource(R.string.rl_view_issues), onBack, footer = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { offset = (offset - 50).coerceAtLeast(0) }, enabled = offset > 0) { Text(stringResource(R.string.rl_previous)) }
            Text(stringResource(R.string.rl_page, if (job.invalid == 0) 0 else offset + 1, minOf(offset + (rows?.size ?: 0), job.invalid), job.invalid), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { offset += 50 }, enabled = offset + 50 < job.invalid) { Text(stringResource(R.string.rl_next)) }
        }
        OutlinedButton(onClick = onExport, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.rl_export_issues)) }
    }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (failed) item { RuleQueryError(vm) }
            else if (rows == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            items(rows.orEmpty(), key = { it.line }) { issue -> RuleCard {
                Text(stringResource(R.string.rl_line_number, issue.line), style = MaterialTheme.typography.labelLarge)
                Text(issue.value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                Text(stringResource(when (issue.reason) { "invalid_range" -> R.string.rl_invalid_range; "line_too_long" -> R.string.rl_line_too_long; else -> R.string.rl_invalid_format }),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            } }
        }
    }
}

@Composable private fun ImportPreviewScreen(vm: RuleLibraryViewModel, source: Library.Source, onBack: () -> Unit) {
    var offset by rememberSaveable(source.id) { mutableIntStateOf(0) }
    var query by rememberSaveable(source.id) { mutableStateOf("") }
    var selected by remember { mutableStateOf<Library.Item?>(null) }
    val page = rulePage(vm, listOf(source), query, offset)
    RuleDetailLayout(source.name, onBack, footer = { page.page?.let { RulePager(it, { offset = it }) } }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { RuleSearch(query, { query = it; offset = 0 }) }
            if (page.page == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            items(page.page?.items.orEmpty(), key = { it.key }) { item -> RuleCard { RuleItemRow(item, { selected = item }) } }
        }
    }
    selected?.let { RuleValueDetails(it, { selected = null }) }
}
