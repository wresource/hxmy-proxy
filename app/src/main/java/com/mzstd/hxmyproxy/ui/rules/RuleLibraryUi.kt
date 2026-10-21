package com.mzstd.hxmyproxy.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.rules.IpRange
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.data.repository.RuleLibraryRepository as Library
import com.mzstd.hxmyproxy.ui.components.AdaptiveActionPair
import com.mzstd.hxmyproxy.ui.components.DetailScaffold
import com.mzstd.hxmyproxy.ui.components.InteractionSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable internal fun ruleActionName(action: RuleAction) = stringResource(when (action) {
    RuleAction.PROXY -> R.string.rl_proxy; RuleAction.DIRECT -> R.string.rl_direct; RuleAction.REJECT -> R.string.rl_block
})
@Composable internal fun sourceName(source: Library.Source): String = source.titleRes?.let { stringResource(it) } ?: when (source.kind) {
    Library.Kind.DIRECT -> stringResource(R.string.rl_direct)
    Library.Kind.REJECT -> stringResource(R.string.rl_block)
    Library.Kind.OVERRIDES, Library.Kind.PAUSED_OVERRIDES -> stringResource(R.string.ui_manual_overrides)
    else -> source.name
}

@Composable internal fun RuleCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable internal fun RuleNotice(text: String, error: Boolean = false, content: (@Composable () -> Unit)? = null) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(R.drawable.ic_b_info), null, Modifier.padding(top = 2.dp).size(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                content?.invoke()
            }
        }
    }
}
@Composable internal fun ActionBadge(action: RuleAction) {
    Surface(shape = MaterialTheme.shapes.extraSmall,
        color = if (action == RuleAction.REJECT) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (action == RuleAction.REJECT) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer) {
        Text(ruleActionName(action), Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}
@Composable internal fun RuleSearch(value: String, onChange: (String) -> Unit, label: Int = R.string.rl_search_entries) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth().testTag("rule_search"), singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        placeholder = { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_b_search), null) },
        trailingIcon = if (value.isNotEmpty()) ({ IconButton(onClick = { onChange("") }) {
            Icon(painterResource(R.drawable.ic_b_close), stringResource(R.string.rl_clear_search))
        } }) else null,
        textStyle = MaterialTheme.typography.bodyLarge, shape = MaterialTheme.shapes.medium)
}
@Composable internal fun RuleTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, tag: String = "rule_tabs") {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val minimum = with(density) { (labels.maxOfOrNull { measurer.measure(it, style).size.width } ?: 0).toDp() } + 24.dp
    @Composable fun tab(index: Int, label: String, modifier: Modifier) {
            TextButton(onClick = { onSelect(index) }, modifier.heightIn(min = 48.dp).semantics { this.selected = selected == index }, shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.textButtonColors(containerColor = if (selected == index) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = if (selected == index) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)) {
                Text(label, maxLines = 1, style = MaterialTheme.typography.labelLarge)
            }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerLow).testTag(tag)) {
        if (maxWidth >= minimum * labels.size + (8 + 4 * (labels.size - 1)).dp) Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            labels.forEachIndexed { index, label -> tab(index, label, Modifier.weight(1f)) }
        } else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            labels.forEachIndexed { index, label -> tab(index, label, Modifier) }
        }
    }
}
@Composable internal fun RuleActionChoices(action: RuleAction, onChoose: (RuleAction) -> Unit, allowProxy: Boolean = true, enabled: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.rl_action), style = MaterialTheme.typography.labelLarge)
        (if (allowProxy) listOf(RuleAction.PROXY, RuleAction.DIRECT, RuleAction.REJECT) else listOf(RuleAction.DIRECT, RuleAction.REJECT)).forEach { choice ->
            com.mzstd.hxmyproxy.ui.components.SheetChoiceRow(ruleActionName(choice), choice == action,
                { if (enabled) onChoose(choice) }, stringResource(when (choice) {
                    RuleAction.PROXY -> R.string.rl_proxy_hint; RuleAction.DIRECT -> R.string.rl_direct_hint; RuleAction.REJECT -> R.string.rl_block_hint
                }), enabled = enabled)
        }
    }
}

@Composable internal fun RuleItemRow(item: Library.Item, onOpen: () -> Unit, onToggle: ((Boolean) -> Unit)? = null, enabled: Boolean = true) {
    val toggleLabel = stringResource(R.string.rl_toggle_rule, item.row.value)
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClick = onOpen)
        .heightIn(min = 68.dp).padding(horizontal = 8.dp, vertical = 10.dp).testTag("rule_row"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.row.value, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val origin = sourceName(item.source)
            val actionName = ruleActionName(item.action)
            Text(actionName + (if (origin != actionName) " · $origin" else "") + (if (!item.source.enabled) " · ${stringResource(R.string.rl_list_paused)}" else ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (onToggle != null) Switch(checked = item.source.kind != Library.Kind.PAUSED_OVERRIDES && item.row.enabled,
            onCheckedChange = onToggle, enabled = enabled, modifier = Modifier.semantics { contentDescription = toggleLabel })
        else Icon(painterResource(R.drawable.ic_b_chevron_right), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable internal fun RuleSourceRow(source: Library.Source, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClick = onOpen)
        .heightIn(min = 76.dp).padding(horizontal = 6.dp, vertical = 10.dp).testTag("rule_source_${source.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(painterResource(R.drawable.ic_b_doc), null, Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(sourceName(source), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(pluralStringResource(R.plurals.rl_rules_count, source.count, source.count) + " · " + stringResource(if (source.enabled) R.string.rl_enabled else R.string.rl_paused),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ActionBadge(source.action)
    }
}

@Composable internal fun RulePager(page: Library.Page, onPage: (Int) -> Unit, limit: Int = 50) {
    Row(Modifier.fillMaxWidth().testTag("rule_pager"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { onPage((page.offset - limit).coerceAtLeast(0)) }, Modifier.testTag("rule_page_previous"), enabled = page.offset > 0) { Text(stringResource(R.string.rl_previous)) }
        Text(stringResource(R.string.rl_page, if (page.total == 0) 0 else page.offset + 1,
            minOf(page.offset + page.items.size, page.total), page.total), Modifier.weight(1f), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { onPage(page.offset + limit) }, Modifier.testTag("rule_page_next"), enabled = page.offset + page.items.size < page.total) { Text(stringResource(R.string.rl_next)) }
    }
}
internal data class RulePageState(val page: Library.Page? = null, val error: String? = null)
@Composable internal fun RuleQueryError(vm: RuleLibraryViewModel) {
    RuleNotice(stringResource(R.string.rl_operation_failed), true) { TextButton(onClick = vm::retryQuery) { Text(stringResource(R.string.rl_retry)) } }
}
@Composable internal fun rulePage(vm: RuleLibraryViewModel, sources: List<Library.Source>, query: String,
    offset: Int = 0, limit: Int = 50, type: Int? = null, action: RuleAction? = null): RulePageState {
    val retry by vm.queryRevision.collectAsStateWithLifecycle()
    val result by produceState(RulePageState(), sources, query, offset, limit, type, action, retry) {
        value = RulePageState()
        if (query.isNotBlank()) delay(120)
        try { value = RulePageState(vm.library.page(sources, query, offset, limit, type, action)) }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { value = RulePageState(error = error.message ?: error.javaClass.simpleName) }
    }
    return result
}

@Composable internal fun RuleDetailLayout(title: String, onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}, footer: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        DetailScaffold(title, onBack, actions, showTopBar = maxHeight >= 240.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                if (footer != null) Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { footer() }
                }
            }
        }
    }
}

@Composable internal fun RuleMessage(vm: RuleLibraryViewModel) {
    val notice by vm.notice.collectAsStateWithLifecycle()
    val message = notice ?: return
    RuleNotice(stringResource(when (message.kind) {
        RuleLibraryViewModel.MessageKind.SAVED -> R.string.rl_saved
        RuleLibraryViewModel.MessageKind.EXPORTED -> R.string.rl_exported
        RuleLibraryViewModel.MessageKind.REVERTED -> R.string.ui_reverted
        RuleLibraryViewModel.MessageKind.PROTECTED -> R.string.ui_revert_conflict
        RuleLibraryViewModel.MessageKind.ERROR -> R.string.rl_operation_failed
    }), error = message.kind == RuleLibraryViewModel.MessageKind.ERROR) {
        if (message.detail != null) Text(message.detail, style = MaterialTheme.typography.bodySmall)
        Row {
            if (message.undoId != null) TextButton(onClick = { vm.undo(message.undoId) }) { Text(stringResource(R.string.rl_undo)) }
            TextButton(onClick = { vm.dismissNotice(message.token) }) { Text(stringResource(R.string.ui_close)) }
        }
    }
}

@Composable internal fun RuleFormats(onDismiss: () -> Unit) {
    InteractionSheet(stringResource(R.string.rl_formats), onDismiss) {
        Text(stringResource(R.string.rl_format_body), style = MaterialTheme.typography.bodyMedium)
        RuleCard { Text("192.168.0.0/16\n2001:db8::/32\n1.2.3.4-1.2.3.255\n1.2.3.4 1.2.3.255", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
        Text(stringResource(R.string.rl_range_body), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.rl_ip_matching_hint), style = MaterialTheme.typography.bodyMedium)
        RuleNotice(stringResource(R.string.rl_format_limits))
    }
}

@Composable internal fun RelatedRules(related: List<Library.Related>, checking: Boolean, error: String?,
    onView: (Library.Related) -> Unit, onDisable: (Library.Related) -> Unit, onEnable: (Library.Related) -> Unit,
    onRetry: () -> Unit, enabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    var enabling by remember { mutableStateOf<Library.Related?>(null) }
    fun enable(relation: Library.Related) {
        val source = relation.item.source
        if (!source.isManual || (source.kind == Library.Kind.DIRECT || source.kind == Library.Kind.REJECT) && !source.enabled) enabling = relation
        else onEnable(relation)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.rl_relations), style = MaterialTheme.typography.titleMedium)
        when {
            checking -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.rl_relations_checking), style = MaterialTheme.typography.bodyMedium)
            }
            error != null -> RuleNotice(stringResource(R.string.rl_relations_failed), true) { TextButton(onClick = onRetry) { Text(stringResource(R.string.rl_retry)) } }
            related.isEmpty() -> Text(stringResource(R.string.rl_no_relations), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                Text(stringResource(R.string.rl_relations_limit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                related.take(3).forEach { relation ->
                    RelatedRuleCard(relation, { onView(relation) }, { onDisable(relation) }, { enable(relation) }, enabled)
                }
                if (!expanded && related.size > 3) TextButton(onClick = { expanded = true }) { Text(stringResource(R.string.rl_view_all)) }
            }
        }
    }
    if (expanded) InteractionSheet(stringResource(R.string.rl_relations), { expanded = false }, scrollBody = false) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(related, key = { "${it.relation}:${it.item.key}:${it.peer}" }) { relation ->
                RelatedRuleCard(relation, { expanded = false; onView(relation) }, { expanded = false; onDisable(relation) }, { expanded = false; enable(relation) }, enabled)
            }
        }
    }
    enabling?.let { relation -> com.mzstd.hxmyproxy.ui.components.AppAlertDialog(onDismissRequest = { enabling = null },
        title = { Text(stringResource(if (relation.item.source.isManual) R.string.rl_enable_list else R.string.rl_enable_set)) },
        text = { Text(pluralStringResource(R.plurals.rl_enable_group_confirm, relation.item.source.count, sourceName(relation.item.source), relation.item.source.count)) },
        confirmButton = { TextButton(onClick = { enabling = null; onEnable(relation) }, enabled = enabled) { Text(stringResource(R.string.rl_history_enable)) } },
        dismissButton = { TextButton(onClick = { enabling = null }) { Text(stringResource(R.string.cancel)) } }) }
}
@Composable private fun RelatedRuleCard(relation: Library.Related, onView: () -> Unit, onDisable: () -> Unit, onEnable: () -> Unit, enabled: Boolean) {
    val row = relation.item
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = remember(locale) { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, locale) }
    RuleCard(Modifier.testTag("related_${row.source.id}_${row.row.value}")) {
        Text(stringResource(when (relation.relation) {
            Library.Relation.DUPLICATE -> R.string.rl_relation_duplicate; Library.Relation.COVERED -> R.string.rl_relation_covered
            Library.Relation.OVERLAP -> R.string.rl_relation_overlap; Library.Relation.CONFLICT -> R.string.rl_relation_conflict
            Library.Relation.RESOLVED_IP -> R.string.rl_relation_peer; Library.Relation.DISABLED -> R.string.rl_relation_disabled
        }), style = MaterialTheme.typography.labelLarge, color = if (relation.relation == Library.Relation.CONFLICT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Text(row.row.value, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionBadge(row.action); Text(if (row.source.kind in listOf(Library.Kind.DIRECT, Library.Kind.REJECT)) stringResource(R.string.rl_manual) else sourceName(row.source), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
        if (relation.peer != null) Text(stringResource(R.string.rl_peer_hint, relation.peer,
            dateFormat.format(java.util.Date(relation.observedAt ?: 0))), style = MaterialTheme.typography.bodySmall)
        val pause = stringResource(if (row.source.isManual) R.string.rl_pause_rule else R.string.rl_pause_set)
        val enableLabel = stringResource(if (!row.source.isManual) R.string.rl_enable_set else if ((row.source.kind == Library.Kind.DIRECT || row.source.kind == Library.Kind.REJECT) && !row.source.enabled) R.string.rl_enable_list else R.string.rl_enable_rule)
        AdaptiveActionPair(stringResource(R.string.rl_view_rule), if (relation.relation == Library.Relation.DISABLED) enableLabel else pause) { button ->
            TextButton(onClick = onView, modifier = button) { Text(stringResource(R.string.rl_view_rule)) }
            TextButton(onClick = if (relation.relation == Library.Relation.DISABLED) onEnable else onDisable, enabled = enabled, modifier = button.testTag("related_toggle_${row.source.id}_${row.row.value}")) {
                Text(if (relation.relation == Library.Relation.DISABLED) enableLabel else pause, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable internal fun RuleValueDetails(item: Library.Item, onDismiss: () -> Unit, actions: (@Composable () -> Unit)? = null) {
    val clipboard = LocalClipboardManager.current
    InteractionSheet(stringResource(R.string.rl_view_rule), onDismiss, footer = actions) {
        SelectionContainer { Text(item.row.value, style = MaterialTheme.typography.bodyLarge) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ActionBadge(item.action)
            Text(if (item.source.kind in listOf(Library.Kind.DIRECT, Library.Kind.REJECT)) stringResource(R.string.rl_manual) else sourceName(item.source), Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = { clipboard.setText(AnnotatedString(item.row.value)) }) { Icon(painterResource(R.drawable.ic_b_copy), stringResource(R.string.copy)) }
        }
        val range = remember(item.row.value) { IpRange.parse(item.row.value) }
        if (range != null) {
            val cidrs = remember(range) { range.cidrs() }
            RuleNotice(stringResource(R.string.rl_range_summary,
                pluralStringResource(R.plurals.rl_addresses, if (range.addressCount == java.math.BigInteger.ONE) 1 else 2, range.addressCount.toString()),
                pluralStringResource(R.plurals.rl_cidr_blocks, cidrs.size, cidrs.size)))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                items(cidrs) { Text(it, Modifier.fillMaxWidth().padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}
