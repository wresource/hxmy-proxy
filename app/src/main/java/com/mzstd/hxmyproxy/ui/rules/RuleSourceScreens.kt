package com.mzstd.hxmyproxy.ui.rules

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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.rules.*
import com.mzstd.hxmyproxy.data.repository.RuleLibraryRepository as Library
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
fun RuleManualScreen(main: MainViewModel, vm: RuleLibraryViewModel, onBack: () -> Unit,
    onImport: (String?) -> Unit, onSource: (String, String) -> Unit, initialAction: RuleAction? = null, overridesOnly: Boolean = false,
    initialQuery: String = "") {
    LaunchedEffect(vm) { vm.activate() }
    val all by vm.sources.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableIntStateOf(listOf(null, RuleAction.PROXY, RuleAction.DIRECT, RuleAction.REJECT).indexOf(initialAction)) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var fromHistory by remember { mutableStateOf<String?>(null) }
    val historyValues by main.domainHistory.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<Library.Item?>(null) }
    var editing by remember { mutableStateOf<Library.Item?>(null) }
    val action = listOf(null, RuleAction.PROXY, RuleAction.DIRECT, RuleAction.REJECT)[filter]
    val sources = remember(all, overridesOnly) { all.filter { it.isManual && (!overridesOnly || it.kind == Library.Kind.OVERRIDES || it.kind == Library.Kind.PAUSED_OVERRIDES) } }
    val result = rulePage(vm, sources, query, offset, action = action)
    RuleDetailLayout(stringResource(if (overridesOnly) R.string.ui_manual_overrides else R.string.rl_manual), onBack,
        actions = { IconButton(onClick = { history = true }) { Icon(painterResource(R.drawable.ic_b_history), stringResource(R.string.rules_add_from_history)) } },
        footer = { result.page?.let { RulePager(it, { offset = it }) } }) {
        LazyColumn(Modifier.fillMaxSize().testTag("manual_rules_page"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { AdaptiveActionPair(stringResource(R.string.ui_add_rule), stringResource(R.string.rl_import_file)) { button ->
                Button(onClick = { adding = true }, modifier = button, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.ui_add_rule)) }
                FilledTonalButton(onClick = { onImport(null) }, modifier = button, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.rl_import_file)) }
            } }
            item { RuleSearch(query, { query = it; offset = 0 }) }
            item { RuleTabs(listOf(stringResource(R.string.rl_all), stringResource(R.string.rl_proxy), stringResource(R.string.rl_direct), stringResource(R.string.rl_block)), filter, { filter = it; offset = 0 }) }
            if (notice != null) item { RuleMessage(vm) }
            when {
                result.error != null -> item { RuleQueryError(vm) }
                result.page == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                result.page.items.isEmpty() -> item { Text(stringResource(R.string.rl_empty)) }
                else -> items(result.page.items, key = { it.key }) { item -> RuleCard { RuleItemRow(item, { selected = item }, { enabled -> vm.command { vm.library.setItemEnabled(item, enabled) } }, !busy) } }
            }
        }
    }
    if (adding) DomainRuleEditor(main, initialAction = action ?: RuleAction.PROXY, initialScope = RuleScope.SINGLE, onDismiss = { adding = false })
    if (history) HistoryAddDialog(historyValues.sorted(), { fromHistory = it; history = false }, { history = false })
    fromHistory?.let { value -> DomainRuleEditor(main, value, action ?: RuleAction.DIRECT, RuleInput.parse(value)?.scope ?: RuleScope.SINGLE, { fromHistory = null }) }
    selected?.let { item -> RuleItemActions(item, vm, { selected = null }, { selected = null; editing = item }, { onSource(it, "") }) }
    editing?.let { item -> RuleEntryEditor(item.source, item, vm, main, { editing = null }) }
}

@Composable
fun RuleSourcesScreen(vm: RuleLibraryViewModel, onBack: () -> Unit, onSource: (String, String) -> Unit,
    onImport: (String?) -> Unit, initialMode: String = "builtin") {
    LaunchedEffect(vm) { vm.activate() }
    val all by vm.sources.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableIntStateOf(when (initialMode) { "mine" -> 0; "subscriptions" -> 2; else -> 1 }) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var batch by remember { mutableStateOf(false) }
    var choosingCategory by remember { mutableStateOf(false) }
    var queryError by remember { mutableStateOf(false) }
    val candidates = remember(all, mode, category) { all.filter { source -> when (mode) {
        0 -> source.kind == Library.Kind.USER; 2 -> source.kind == Library.Kind.SUBSCRIPTION
        else -> source.kind == Library.Kind.BUILTIN && (category == null || source.category?.name == category)
    } } }
    val names = candidates.associate { it.id to sourceName(it) }
    val retry by vm.queryRevision.collectAsStateWithLifecycle()
    val found by produceState<List<Library.Source>?>(null, candidates, query, names, retry) {
        value = null; queryError = false
        try { if (query.isNotBlank()) delay(120); value = vm.library.matchingSources(candidates, query, names) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { queryError = true; value = emptyList() }
    }
    val rows = found.orEmpty().drop(offset).take(50) // Only compact collection metadata; rule contents are queried separately.
    RuleDetailLayout(stringResource(R.string.ruleset_manager_title), onBack,
        actions = { IconButton(onClick = { batch = true }) { Icon(painterResource(R.drawable.ic_b_filter), stringResource(R.string.rl_batch)) } },
        footer = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { offset = (offset - 50).coerceAtLeast(0) }, enabled = offset > 0) { Text(stringResource(R.string.rl_previous)) }
                Text(stringResource(R.string.rl_page, if (rows.isEmpty()) 0 else offset + 1, offset + rows.size, found.orEmpty().size), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                TextButton(onClick = { offset += 50 }, enabled = offset + rows.size < found.orEmpty().size) { Text(stringResource(R.string.rl_next)) }
            }
        }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { AdaptiveActionPair(stringResource(if (mode == 2) R.string.rl_add_subscription else R.string.rl_new_set), stringResource(R.string.rl_import_file)) { button ->
                Button(onClick = { creating = true }, modifier = button, shape = MaterialTheme.shapes.medium) { Text(stringResource(if (mode == 2) R.string.rl_add_subscription else R.string.rl_new_set)) }
                FilledTonalButton(onClick = { onImport(null) }, modifier = button, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.rl_import_file)) }
            } }
            item { RuleSearch(query, { query = it; offset = 0 }, R.string.rl_search) }
            item { RuleTabs(listOf(stringResource(R.string.rl_mine), stringResource(R.string.rl_builtin), stringResource(R.string.rl_subscriptions)), mode, { mode = it; offset = 0 }) }
            if (mode == 1) item { TextButton(onClick = { choosingCategory = true }) { Text(category?.let { stringResource(RuleCategory.valueOf(it).titleRes) } ?: stringResource(R.string.rl_all)) } }
            if (notice != null) item { RuleMessage(vm) }
            when {
                queryError -> item { RuleQueryError(vm) }
                found == null || loading && candidates.isEmpty() -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                rows.isEmpty() -> item { Text(stringResource(R.string.rl_empty)) }
                else -> items(rows, key = { it.id }) { source -> RuleCard { RuleSourceRow(source) { onSource(source.id, query) } } }
            }
        }
    }
    if (creating) CreateRuleSetSheet(vm, mode == 2, { creating = false }) { id -> creating = false; onSource(id, "") }
    if (batch) BuiltinSwitches(vm, all.filter { it.kind == Library.Kind.BUILTIN }) { batch = false }
    if (choosingCategory) InteractionSheet(stringResource(R.string.rl_builtin), { choosingCategory = false }, scrollBody = false) {
        LazyColumn(Modifier.heightIn(max = 400.dp).fillMaxWidth()) {
            item { TextButton(onClick = { category = null; offset = 0; choosingCategory = false }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.rl_all)) } }
            items(RuleCategory.entries) { group -> TextButton(onClick = { category = group.name; offset = 0; choosingCategory = false }, Modifier.fillMaxWidth()) { Text(stringResource(group.titleRes)) } }
        }
    }
}

@Composable internal fun BuiltinSwitches(vm: RuleLibraryViewModel, sources: List<Library.Source>, onDismiss: () -> Unit) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    InteractionSheet(stringResource(R.string.rl_batch), onDismiss, scrollBody = false) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.rl_all_builtin), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(sources.isNotEmpty() && sources.all { it.enabled }, { enabled -> vm.command { vm.library.setBuiltins(sources.mapTo(hashSetOf()) { it.id.substringAfter(':') }, enabled) } }, enabled = !busy)
            } }
            items(RuleCategory.entries) { category ->
                val group = sources.filter { it.category == category }
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(category.titleRes), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(group.isNotEmpty() && group.all { it.enabled }, { enabled -> vm.command { vm.library.setBuiltins(group.mapTo(hashSetOf()) { it.id.substringAfter(':') }, enabled) } }, enabled = !busy && group.isNotEmpty())
                }
            }
        }
    }
}

@Composable
fun RuleSourceScreen(id: String, initialQuery: String, ui: MainUiState, main: MainViewModel, vm: RuleLibraryViewModel,
    onBack: () -> Unit, onImport: (String?) -> Unit, onSource: (String, String) -> Unit, onChanges: () -> Unit) {
    LaunchedEffect(vm) { vm.activate() }
    val all by vm.sources.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val importState by vm.importState.collectAsStateWithLifecycle()
    val pendingAdd by vm.pendingAddSource.collectAsStateWithLifecycle()
    val source = all.firstOrNull { it.id == id }
    var query by rememberSaveable(id) { mutableStateOf(initialQuery) }
    var offset by rememberSaveable(id) { mutableIntStateOf(0) }
    var type by rememberSaveable(id) { mutableIntStateOf(0) }
    var more by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Library.Item?>(null) }
    var editing by remember { mutableStateOf<Library.Item?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var copyMode by remember { mutableStateOf<String?>(null) }
    val resolver = LocalContext.current.applicationContext.contentResolver
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> if (uri != null && source != null) vm.export(source, uri, resolver) }
    LaunchedEffect(pendingAdd, source) { if (pendingAdd == id && source != null) { adding = true; vm.pendingAddSource.value = null } }
    val result = rulePage(vm, listOfNotNull(source), query, offset, type = listOf(null, 0, 32, 128)[type])
    val title = source?.let { sourceName(it) } ?: stringResource(R.string.rl_loading)
    val copyName = stringResource(R.string.rl_copy_name, title)
    RuleDetailLayout(title, onBack, actions = {
        IconButton(onClick = { more = true }, enabled = source != null) { Icon(painterResource(R.drawable.ic_ui_more), stringResource(R.string.ui_more_actions)) }
    }, footer = { result.page?.let { RulePager(it, { offset = it }) } }) {
        LazyColumn(Modifier.fillMaxSize().testTag("rule_source_page"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (source == null) item { Text(stringResource(if (loading || all.isEmpty()) R.string.rl_loading else R.string.rl_missing_set)) }
            else {
                item { RuleCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(pluralStringResource(R.plurals.rl_rules_count, source.count, source.count), style = MaterialTheme.typography.headlineSmall)
                            Text(stringResource(if (source.enabled) R.string.rl_enabled else R.string.rl_paused), style = MaterialTheme.typography.bodySmall)
                        }
                        if (source.kind != Library.Kind.OVERRIDES && source.kind != Library.Kind.PAUSED_OVERRIDES)
                            Switch(source.enabled, { enabled -> vm.command { vm.library.setEnabled(source, enabled) } }, enabled = !busy)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionBadge(source.action)
                        if (source.kind == Library.Kind.BUILTIN) Text(stringResource(if (source.editable) R.string.rl_editable else R.string.rl_readonly), style = MaterialTheme.typography.bodySmall)
                    }
                } }
                item { AdaptiveActionPair(stringResource(R.string.rl_add_entry), stringResource(R.string.rl_import_file)) { button ->
                    Button(onClick = { if (source.editable) adding = true else copyMode = "add" }, modifier = button, enabled = !busy, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.rl_add_entry)) }
                    FilledTonalButton(onClick = { if (source.editable) onImport(id) else copyMode = "import" }, modifier = button, enabled = !busy, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.rl_import_file)) }
                } }
                if (!source.editable) item { RuleNotice(stringResource(R.string.rl_readonly_hint)) }
                if (source.kind == Library.Kind.SUBSCRIPTION) item { RuleCard {
                    Text(source.url.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    val locale = LocalConfiguration.current.locales[0]
                    val formatter = remember(locale) { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, locale) }
                    Text(if (source.updatedAt == 0L) stringResource(R.string.rl_update_never) else stringResource(R.string.rl_updated, formatter.format(java.util.Date(source.updatedAt))), style = MaterialTheme.typography.bodySmall)
                    if (source.error != null) Text(stringResource(R.string.rl_update_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton(onClick = { vm.updateSubscription(source); onImport(id) }, Modifier.fillMaxWidth(), enabled = importState.step !in listOf(RuleLibraryViewModel.Step.READING, RuleLibraryViewModel.Step.SAVING), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.rl_check_update)) }
                } }
                if (notice != null) item { RuleMessage(vm) }
                item { RuleSearch(query, { query = it; offset = 0 }) }
                item { RuleTabs(listOf(stringResource(R.string.rl_all), stringResource(R.string.rules_type_domain), "IPv4", "IPv6"), type, { type = it; offset = 0 }, "rule_type_filter") }
                when {
                    result.error != null -> item { RuleQueryError(vm) }
                    result.page == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    result.page.items.isEmpty() -> item { Text(stringResource(R.string.rl_empty)) }
                    else -> items(result.page.items, key = { it.key }) { item -> RuleCard {
                        RuleItemRow(item, { selected = item }, if (source.isManual) ({ enabled -> vm.command { vm.library.setItemEnabled(item, enabled) } }) else null, !busy)
                    } }
                }
            }
        }
    }
    if (source == null) return
    if (adding) {
        if (source.isManual && source.kind != Library.Kind.PAUSED_OVERRIDES) DomainRuleEditor(main, initialAction = source.action, initialScope = RuleScope.SINGLE, onDismiss = { adding = false })
        else RuleEntryEditor(source, null, vm, main, { adding = false })
    }
    selected?.let { item -> RuleItemActions(item, vm, { selected = null }, { selected = null; editing = item }, { onSource(it, "") }) }
    editing?.let { item -> RuleEntryEditor(source, item, vm, main, { editing = null }) }
    if (more) InteractionSheet(title, { more = false }) {
        TextButton(onClick = { more = false; export.launch(title.replace(Regex("[/\\\\:*?\"<>|\\r\\n]"), "_").take(80) + ".txt") }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.rl_export_txt)) }
        if (!source.isManual) TextButton(onClick = { more = false; copyMode = "copy" }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.rl_copy_set)) }
        if (source.kind == Library.Kind.USER || source.kind == Library.Kind.SUBSCRIPTION || source.kind == Library.Kind.BUILTIN && RuleCatalog.byId(id.substringAfter(':'))?.kind == RuleGroupKind.DIRECT)
            TextButton(onClick = { more = false; renaming = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.rl_edit_metadata)) }
        if (source.kind == Library.Kind.BUILTIN && source.editable && source.overridden) TextButton(onClick = { more = false; restoring = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.rl_restore)) }
        TextButton(onClick = { more = false; onChanges() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_rule_changes)) }
        if (source.kind == Library.Kind.USER || source.kind == Library.Kind.SUBSCRIPTION) TextButton(onClick = { more = false; deleting = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.rl_delete_set), color = MaterialTheme.colorScheme.error) }
    }
    copyMode?.let { mode -> InteractionSheet(stringResource(R.string.rl_copy_set), { copyMode = null }, footer = {
        Button(onClick = {
            var newId = ""
            vm.command(onDone = { copyMode = null; if (mode == "import") onImport(newId) else { if (mode == "add") vm.pendingAddSource.value = newId; onSource(newId, "") } }) { newId = vm.library.copy(source, copyName) }
        }, Modifier.fillMaxWidth(), enabled = !busy, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.rl_copy_continue)) }
    }) { Text(copyName, style = MaterialTheme.typography.titleMedium); Text(stringResource(R.string.rl_readonly_hint), style = MaterialTheme.typography.bodyMedium) } }
    if (renaming) RenameRuleSetSheet(source, vm) { renaming = false }
    if (restoring) AppAlertDialog(onDismissRequest = { restoring = false }, title = { Text(stringResource(R.string.rl_restore)) },
        text = { Text(stringResource(R.string.rl_restore_confirm)) }, confirmButton = { TextButton(onClick = { vm.command(onDone = { restoring = false }) { vm.library.restoreBuiltin(source) } }, enabled = !busy) { Text(stringResource(R.string.rl_restore)) } },
        dismissButton = { TextButton(onClick = { restoring = false }, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
    if (deleting) AppAlertDialog(onDismissRequest = { deleting = false }, title = { Text(stringResource(R.string.rl_delete_set)) },
        text = { Text(stringResource(R.string.rl_delete_confirm, title)) }, confirmButton = { TextButton(onClick = { vm.command(onDone = { deleting = false; onBack() }) { vm.library.delete(source) } }, enabled = !busy) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { deleting = false }, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
}

@Composable private fun RenameRuleSetSheet(source: Library.Source, vm: RuleLibraryViewModel, onDismiss: () -> Unit) {
    var name by rememberSaveable(source.id) { mutableStateOf(source.name) }
    var action by rememberSaveable(source.id) { mutableStateOf(source.action) }
    var url by rememberSaveable(source.id) { mutableStateOf(source.url.orEmpty()) }
    val urlValid = source.kind != Library.Kind.SUBSCRIPTION || runCatching { vm.library.validateUrl(url.trim()) }.isSuccess
    val busy by vm.busy.collectAsStateWithLifecycle()
    InteractionSheet(stringResource(R.string.rl_edit_metadata), onDismiss, dismissEnabled = !busy,
        footer = { SheetActions(stringResource(R.string.save), { vm.command(onDone = onDismiss) { vm.library.rename(source, name, action, url.trim().takeIf { source.kind == Library.Kind.SUBSCRIPTION }) } },
            stringResource(R.string.cancel), onDismiss, enabled = !busy, primaryEnabled = name.isNotBlank() && name.length <= 200 && urlValid) }) {
        if (source.kind != Library.Kind.BUILTIN) OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.rl_name)) },
            singleLine = true, enabled = !busy, isError = name.length > 200, shape = MaterialTheme.shapes.medium)
        if (source.kind == Library.Kind.SUBSCRIPTION) OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.rl_subscription_url)) }, singleLine = true, enabled = !busy, isError = url.isNotEmpty() && !urlValid,
            shape = MaterialTheme.shapes.medium)
        RuleActionChoices(action, { action = it }, allowProxy = false, enabled = !busy)
        RuleMessage(vm)
    }
}
