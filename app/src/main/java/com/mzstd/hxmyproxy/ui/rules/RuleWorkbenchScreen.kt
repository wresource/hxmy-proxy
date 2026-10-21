package com.mzstd.hxmyproxy.ui.rules

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleInput
import com.mzstd.hxmyproxy.core.rules.RuleScope
import com.mzstd.hxmyproxy.data.repository.RuleLibraryRepository as Library
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
fun RuleWorkbenchScreen(ui: MainUiState, main: MainViewModel, vm: RuleLibraryViewModel,
    onImport: (String?) -> Unit, onSource: (String, String) -> Unit, onManual: (String, RuleAction?) -> Unit,
    onManager: (String) -> Unit, onChanges: () -> Unit, contentPadding: PaddingValues) {
    LaunchedEffect(vm) { vm.activate() }
    val sources by vm.sources.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var adding by rememberSaveable { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var switches by remember { mutableStateOf(false) }
    var formats by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf<Int?>(null) }
    var selected by remember { mutableStateOf<Library.Item?>(null) }
    var editing by remember { mutableStateOf<Library.Item?>(null) }
    val action = listOf(null, RuleAction.PROXY, RuleAction.DIRECT, RuleAction.REJECT)[filter]
    val manual = remember(sources) { sources.filter { it.isManual } }
    val preview = rulePage(vm, if (tab == 0) manual else emptyList(), query, limit = 5, action = action)
    val named = remember(sources, tab) { sources.filter { if (tab == 2) it.kind == Library.Kind.SUBSCRIPTION else !it.isManual && it.kind != Library.Kind.SUBSCRIPTION } }
    var searchError by remember { mutableStateOf(false) }
    var searchRetry by remember { mutableIntStateOf(0) }
    val displayNames = named.associate { it.id to sourceName(it) }
    val found by produceState<List<Library.Source>?>(null, named, query, displayNames, searchRetry) {
        value = null
        searchError = false
        if (query.isNotBlank()) delay(120)
        try { value = vm.library.matchingSources(named, query, displayNames) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { searchError = true; value = emptyList() }
    }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        WorkbenchHeader(stringResource(R.string.nav_rules)) {
            IconButton(onClick = { more = true }) { Icon(painterResource(R.drawable.ic_ui_more), stringResource(R.string.ui_more_actions)) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().imePadding().testTag("rule_workbench"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                RuleCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.rl_list_status), style = MaterialTheme.typography.titleMedium)
                            Text("${stringResource(R.string.rl_block)}: ${stringResource(if (ui.settings.userRejectEnabled) R.string.rl_enabled else R.string.rl_paused)} · ${stringResource(R.string.rl_direct)}: ${stringResource(if (ui.settings.userDirectEnabled) R.string.rl_enabled else R.string.rl_paused)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { switches = true }) { Text(stringResource(R.string.rl_list_switches)) }
                    }
                }
            }
            item {
                AdaptiveActionPair(stringResource(R.string.ui_add_rule), stringResource(R.string.rl_import_file), extraWidth = 64.dp) { button ->
                    Button(onClick = { adding = true }, modifier = button.testTag("rule_add"), shape = MaterialTheme.shapes.medium) {
                        Icon(painterResource(R.drawable.ic_b_plus), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.ui_add_rule))
                    }
                    FilledTonalButton(onClick = { onImport(null) }, modifier = button.testTag("rule_import"), shape = MaterialTheme.shapes.medium) {
                        Icon(painterResource(R.drawable.ic_b_doc), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.rl_import_file))
                    }
                }
            }
            item { RuleSearch(query, { query = it }, R.string.rl_search) }
            item { RuleTabs(listOf(stringResource(R.string.rl_manual), stringResource(R.string.rl_sets), stringResource(R.string.rl_subscriptions)), tab, { tab = it }) }
            if (notice != null) item { RuleMessage(vm) }
            if (error != null) item { RuleNotice(stringResource(R.string.rl_operation_failed), true) { TextButton(onClick = vm::reload) { Text(stringResource(R.string.rl_retry)) } } }
            if (tab == 0) {
                item { RuleTabs(listOf(stringResource(R.string.rl_all), stringResource(R.string.rl_proxy), stringResource(R.string.rl_direct), stringResource(R.string.rl_block)), filter, { filter = it }, "rule_action_filter") }
                item {
                    RuleCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.rl_manual), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { onManual(query, action) }, modifier = Modifier.testTag("rule_manual_all")) { Text(stringResource(R.string.rl_view_all)) }
                        }
                        val page = preview.page
                        when {
                            preview.error != null -> Text(stringResource(R.string.rl_operation_failed), color = MaterialTheme.colorScheme.error)
                            page == null || loading && manual.isEmpty() -> LinearProgressIndicator(Modifier.fillMaxWidth())
                            page.items.isEmpty() -> Text(stringResource(R.string.rl_empty), style = MaterialTheme.typography.bodyMedium)
                            else -> {
                                Text(pluralStringResource(R.plurals.rl_rules_count, page.total, page.total), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                page.items.forEach { item -> RuleItemRow(item, { selected = item }, { enabled -> vm.command { vm.library.setItemEnabled(item, enabled) } }, !busy) }
                            }
                        }
                    }
                }
            } else {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(if (tab == 2) R.string.rl_subscriptions else R.string.rl_mine), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { creating = if (tab == 2) 2 else 1 }) { Text(stringResource(if (tab == 2) R.string.rl_add_subscription else R.string.rl_new_set)) }
                    }
                }
                if (searchError) item { RuleNotice(stringResource(R.string.rl_operation_failed), true) { TextButton(onClick = { searchRetry++ }) { Text(stringResource(R.string.rl_retry)) } } }
                else if (found == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                else {
                    val mine = found.orEmpty().filter { it.kind != Library.Kind.BUILTIN }
                    if (mine.isEmpty()) item { Text(stringResource(R.string.rl_empty), style = MaterialTheme.typography.bodyMedium) }
                    items(mine.take(5), key = { it.id }) { source -> RuleCard { RuleSourceRow(source) { onSource(source.id, query) } } }
                    if (mine.size > 5) item { TextButton(onClick = { onManager(if (tab == 2) "subscriptions" else "mine") }) { Text(stringResource(R.string.rl_view_all)) } }
                    if (tab == 1) {
                        item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.rl_builtin), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                TextButton(onClick = { onManager("builtin") }) { Text(stringResource(R.string.rl_view_all)) }
                            }
                        }
                        items(found.orEmpty().filter { it.kind == Library.Kind.BUILTIN }.take(5), key = { it.id }) { source -> RuleCard { RuleSourceRow(source) { onSource(source.id, query) } } }
                        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    }
                }
            }
            item {
                AdaptiveActionPair(stringResource(R.string.ui_rule_changes), stringResource(R.string.rl_formats)) { button ->
                    TextButton(onClick = onChanges, modifier = button) { Text(stringResource(R.string.ui_rule_changes)) }
                    TextButton(onClick = { formats = true }, modifier = button) { Text(stringResource(R.string.rl_formats)) }
                }
            }
        }
    }
    if (adding) DomainRuleEditor(main, initialAction = action ?: RuleAction.PROXY, initialScope = RuleScope.SINGLE,
        onDismiss = { adding = false })
    if (formats) RuleFormats { formats = false }
    if (switches) RuleListSwitches(vm, sources) { switches = false }
    if (more) InteractionSheet(stringResource(R.string.ui_more_actions), { more = false }) {
        TextButton(onClick = { more = false; onChanges() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_rule_changes)) }
        TextButton(onClick = { more = false; switches = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.rl_list_switches)) }
        TextButton(onClick = { more = false; formats = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.rl_formats)) }
        TextButton(onClick = { more = false; onManager("builtin") }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.rl_builtin)) }
    }
    creating?.let { kind -> CreateRuleSetSheet(vm, kind == 2, { creating = null }) { id -> creating = null; onSource(id, "") } }
    selected?.let { item -> RuleItemActions(item, vm, { selected = null }, { selected = null; editing = item }, { onSource(it, "") }) }
    editing?.let { item -> RuleEntryEditor(item.source, item, vm, main, { editing = null }) }
}

@Composable internal fun RuleListSwitches(vm: RuleLibraryViewModel, sources: List<Library.Source>, onDismiss: () -> Unit) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    InteractionSheet(stringResource(R.string.rl_list_switches), onDismiss) {
        sources.filter { it.kind == Library.Kind.DIRECT || it.kind == Library.Kind.REJECT }.forEach { source ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) { Text(sourceName(source), style = MaterialTheme.typography.titleMedium); Text(pluralStringResource(R.plurals.rl_rules_count, source.count, source.count), style = MaterialTheme.typography.bodySmall) }
                Switch(source.enabled, { enabled -> vm.command { vm.library.setEnabled(source, enabled) } }, enabled = !busy)
            }
        }
    }
}

@Composable internal fun CreateRuleSetSheet(vm: RuleLibraryViewModel, subscription: Boolean, onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var action by rememberSaveable { mutableStateOf(RuleAction.DIRECT) }
    var attempted by remember { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val validUrl = !subscription || runCatching { vm.library.validateUrl(url.trim()) }.isSuccess
    InteractionSheet(stringResource(if (subscription) R.string.rl_add_subscription else R.string.rl_new_set), onDismiss, dismissEnabled = !busy,
        footer = { SheetActions(stringResource(R.string.create), {
            attempted = true
            if (name.isNotBlank() && validUrl) {
                var created = ""
                vm.command(onDone = { onCreated(created) }) { created = vm.library.create(name, action, if (subscription) url.trim() else null) }
            }
        }, stringResource(R.string.cancel), onDismiss, enabled = !busy, primaryTag = "create_rule_set") }) {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("rule_set_name"), label = { Text(stringResource(R.string.rl_name)) }, enabled = !busy,
            singleLine = true, isError = attempted && name.isBlank(), shape = MaterialTheme.shapes.medium)
        if (subscription) OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth().testTag("rule_subscription_url"),
            label = { Text(stringResource(R.string.rl_subscription_url)) }, placeholder = { Text("https://example.org/rules.txt") },
            singleLine = true, isError = attempted && !validUrl, enabled = !busy, shape = MaterialTheme.shapes.medium)
        RuleActionChoices(action, { action = it }, allowProxy = false, enabled = !busy)
        if (subscription) RuleNotice(stringResource(R.string.rl_subscription_hint))
        RuleMessage(vm)
    }
}

@Composable internal fun RuleItemActions(item: Library.Item, vm: RuleLibraryViewModel, onDismiss: () -> Unit,
    onEdit: () -> Unit, onSource: (String) -> Unit) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val copyName = stringResource(R.string.rl_copy_name, sourceName(item.source))
    RuleValueDetails(item, onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.source.editable) {
                Button(onClick = onEdit, Modifier.fillMaxWidth(), enabled = !busy, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.ruleset_edit)) }
                if (item.source.isManual) OutlinedButton(onClick = { vm.command(onDone = onDismiss) { vm.library.setItemEnabled(item, !(item.source.kind != Library.Kind.PAUSED_OVERRIDES && item.row.enabled)) } }, Modifier.fillMaxWidth(), enabled = !busy, shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(if (item.source.kind == Library.Kind.PAUSED_OVERRIDES || !item.row.enabled) R.string.rl_enable_rule else R.string.rl_pause_rule))
                }
                TextButton(onClick = { vm.command(onDone = onDismiss) { vm.library.deleteItem(item) } }, Modifier.fillMaxWidth(), enabled = !busy) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            } else Button(onClick = {
                var id = ""
                vm.command(onDone = { onDismiss(); onSource(id) }) { id = vm.library.copy(item.source, copyName) }
            }, Modifier.fillMaxWidth(), enabled = !busy, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.rl_copy_set)) }
        }
    }
}

@Composable internal fun RuleEntryEditor(source: Library.Source, item: Library.Item?, vm: RuleLibraryViewModel,
    main: MainViewModel, onDismiss: () -> Unit) {
    val original = remember(item?.row?.value) { item?.row?.value?.let(RuleInput::parse) }
    var value by rememberSaveable(item?.key) { mutableStateOf(original?.host ?: item?.row?.value?.takeIf { it.length <= 2048 }.orEmpty()) }
    var tooLong by remember { mutableStateOf(false) }
    var scope by rememberSaveable(item?.key) { mutableStateOf(original?.scope ?: RuleScope.SINGLE) }
    var action by rememberSaveable(item?.key) { mutableStateOf(item?.action ?: source.action) }
    var attempted by remember { mutableStateOf(false) }
    var wide by remember { mutableStateOf(false) }
    var relationships by remember { mutableStateOf<List<Library.Related>>(emptyList()) }
    var checking by remember { mutableStateOf(false) }
    var checkError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var checkedRefresh by remember { mutableIntStateOf(-1) }
    var checkedKey by remember { mutableStateOf<Pair<String, RuleAction>?>(null) }
    var noIpEvidence by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Library.Related?>(null) }
    var disabling by remember { mutableStateOf<Library.Related?>(null) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val parsed = remember(value, scope) { RuleInput.forScope(value, scope) }
    val sources by vm.sources.collectAsStateWithLifecycle()
    val key = parsed?.value?.let { it to action }
    val latestKey by rememberUpdatedState(key)
    LaunchedEffect(key, refresh, sources) {
        relationships = emptyList(); checkError = null; checkedKey = null; noIpEvidence = false
        val request = key ?: return@LaunchedEffect
        val requestRefresh = refresh
        checking = true
        try {
            delay(180)
            val inspection = main.inspectRule(request.first, request.second)
            if (latestKey == request && refresh == requestRefresh) {
                relationships = inspection.related.filterNot { it.item.source.id == source.id && it.item.row.value == item?.row?.value }
                noIpEvidence = inspection.ipEvidenceMissing; checkedKey = request; checkedRefresh = requestRefresh
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { if (latestKey == request && refresh == requestRefresh) checkError = error.message ?: "check failed" }
        finally { if (latestKey == request && refresh == requestRefresh) checking = false }
    }
    fun save() {
        attempted = true
        val rule = parsed ?: return
        vm.command(onDone = onDismiss) {
            if (source.isManual && item != null) vm.library.editManual(item, value, action, scope)
            else vm.library.editItem(source, item?.row?.value, rule.value)
        }
    }
    if (viewing != null) {
        RuleValueDetails(viewing!!.item, { viewing = null })
    } else InteractionSheet(stringResource(if (item == null) R.string.rl_add_entry else R.string.ruleset_edit), onDismiss,
        dismissEnabled = !busy, footer = { SheetActions(stringResource(R.string.save), {
            if (parsed?.isIp == false && scope == RuleScope.SUFFIX && original?.scope != RuleScope.SUFFIX) wide = true else save()
        }, stringResource(R.string.cancel), onDismiss, enabled = !busy, primaryEnabled = parsed != null && !tooLong && checkedKey == key && checkedRefresh == refresh && !checking && checkError == null,
            primaryTag = "rule_entry_save") }) {
        OutlinedTextField(value, { proposed -> val accepted = if (proposed.length <= 2048) proposed else RuleInput.parse(proposed)?.value?.takeIf { it.length <= 2048 }; tooLong = accepted == null; if (accepted != null) value = accepted }, Modifier.fillMaxWidth().testTag("rule_entry_input"), singleLine = true,
            label = { Text(stringResource(R.string.ui_domain_input)) }, enabled = !busy, isError = attempted && parsed == null, shape = MaterialTheme.shapes.medium)
        if (source.isManual && item != null) RuleActionChoices(action, { action = it }, enabled = !busy)
        else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { ActionBadge(source.action); Text(sourceName(source), style = MaterialTheme.typography.bodyMedium) }
        if (parsed?.isIp != true) RuleScopePicker(scope, enabled = !busy) { scope = it }
        if (tooLong) Text(stringResource(R.string.rl_single_too_long), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        if (noIpEvidence) RuleNotice(stringResource(R.string.rl_no_ip_evidence))
        RelatedRules(relationships, checking, checkError, { viewing = it }, { relation ->
            if (relation.item.source.isManual) vm.command(onDone = { refresh++ }) { vm.library.disableRelated(relation) } else disabling = relation
        }, { relation -> vm.command(onDone = { refresh++ }) { vm.library.enableRelated(relation) } }, { refresh++ }, !busy)
        RuleMessage(vm)
    }
    if (wide) AppAlertDialog(onDismissRequest = { wide = false }, title = { Text(stringResource(R.string.rules_scope_confirm_title)) },
        text = { Text(stringResource(R.string.rules_scope_confirm_body, parsed?.host.orEmpty(), parsed?.host.orEmpty(), parsed?.host.orEmpty())) },
        confirmButton = { TextButton(onClick = { wide = false; save() }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { wide = false }) { Text(stringResource(R.string.cancel)) } })
    disabling?.let { relation -> AppAlertDialog(onDismissRequest = { disabling = null }, title = { Text(stringResource(R.string.rl_pause_set)) },
        text = { Text(pluralStringResource(R.plurals.rl_pause_set_confirm, relation.item.source.count, sourceName(relation.item.source), relation.item.source.count)) },
        confirmButton = { TextButton(onClick = { disabling = null; vm.command(onDone = { refresh++ }) { vm.library.disableRelated(relation) } }) { Text(stringResource(R.string.rl_pause_set)) } },
        dismissButton = { TextButton(onClick = { disabling = null }) { Text(stringResource(R.string.cancel)) } }) }
}
