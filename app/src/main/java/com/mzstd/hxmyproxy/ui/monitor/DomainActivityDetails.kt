package com.mzstd.hxmyproxy.ui.monitor

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.proxy.RequestActivityRecord
import com.mzstd.hxmyproxy.core.proxy.RequestOutcome
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleScope
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.components.*
import com.mzstd.hxmyproxy.ui.formatBytes

internal data class DomainDetailState(
    val row: DomainActivity, val action: RuleAction, val source: String,
    val logging: Boolean = true, val paused: Boolean = false, val canRestore: Boolean = false,
    val saving: Boolean = false, val saveError: Boolean = false,
)

/** One adapter owns actual rule writes; both the narrow sheet and wide detail reuse the same view. */
@Composable
private fun DomainDetailBinding(row: DomainActivity, paused: Boolean, ui: MainUiState, viewModel: MainViewModel,
    content: @Composable (DomainDetailState, (RuleAction?) -> Unit) -> Unit) {
    val version by viewModel.ruleVersion.collectAsStateWithLifecycle()
    val decision = version.let { viewModel.decideHostDetailed(row.host) }
    val context = LocalContext.current
    var saving by remember(row.key) { mutableStateOf(false) }
    var error by remember(row.key) { mutableStateOf(false) }
    content(DomainDetailState(row, decision.action, decision.src.name, ui.settings.logEnabled, paused,
        viewModel.hasDomainRule(row.host, ui.settings), saving, error)) { action ->
        if (!saving) {
            saving = true; error = false
            viewModel.applyDomainRule(row.host, action, RuleScope.EXACT) { result ->
                saving = false
                result.onSuccess { Toast.makeText(context, R.string.ui_quick_rule_saved, Toast.LENGTH_LONG).show() }
                    .onFailure { error = true }
            }
        }
    }
}

@Composable
internal fun DomainActivityDialog(row: DomainActivity, paused: Boolean, ui: MainUiState, viewModel: MainViewModel,
    onDismiss: () -> Unit, onEdit: () -> Unit, onOpenLogs: (String) -> Unit, onSettings: (String) -> Unit) {
    DomainDetailBinding(row, paused, ui, viewModel) { state, apply ->
        DomainDetailSheet(state, onDismiss, onEdit, { onDismiss(); onOpenLogs(row.host) },
            { onDismiss(); onSettings(it) }, apply)
    }
}

/** Stateless data boundary also used by visual/interaction checks with real accounting records. */
@Composable
internal fun DomainDetailSheet(state: DomainDetailState, onDismiss: () -> Unit, onEdit: () -> Unit,
    onLogs: () -> Unit, onSettings: (String) -> Unit, onApply: (RuleAction?) -> Unit) {
    var record by remember(state.row.key) { mutableStateOf<RequestActivityRecord?>(null) }
    val domainScroll = rememberScrollState()
    val recordScroll = rememberScrollState()
    val contentState = rememberSaveableStateHolder()
    LaunchedEffect(record?.key) { if (record != null) recordScroll.scrollTo(0) }
    InteractionSheet(
        title = if (record == null) state.row.host else stringResource(R.string.ui_connection_detail),
        onDismiss = onDismiss, onBack = if (record == null) null else ({ record = null }),
        dismissEnabled = !state.saving, bodyScrollState = if (record == null) domainScroll else recordScroll,
        footer = if (record != null) null else ({ DomainDetailActions(state, onEdit, onLogs, onSettings, onApply) }),
    ) {
        if (record == null) contentState.SaveableStateProvider(state.row.key) {
            DomainDetailContent(state, onEdit, onLogs, onApply) { record = it }
        }
        else ConnectionDetailContent(state.row.host, record!!)
    }
}

@Composable
internal fun DomainActivityPanel(row: DomainActivity, paused: Boolean, ui: MainUiState, viewModel: MainViewModel,
    onEdit: () -> Unit, onOpenLogs: (String) -> Unit, onSettings: (String) -> Unit) {
    DomainDetailBinding(row, paused, ui, viewModel) { state, apply ->
        var record by remember(row.key) { mutableStateOf<RequestActivityRecord?>(null) }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (record != null) {
                TextButton(onClick = { record = null }) {
                    Icon(painterResource(R.drawable.ic_arrow_back), null, Modifier.size(18.dp))
                    Text(stringResource(R.string.back), Modifier.padding(start = 8.dp))
                }
                ConnectionDetailContent(row.host, record!!)
            } else {
                DomainDetailContent(state, onEdit, { onOpenLogs(row.host) }, apply) { record = it }
                DomainDetailActions(state, onEdit, { onOpenLogs(row.host) }, onSettings, apply)
            }
        }
    }
}

@Composable
internal fun TrafficDetailSummary(title: String, total: Long?, down: Long?, up: Long?, active: String? = null) {
    Surface(Modifier.fillMaxWidth().testTag("detail_traffic_card"), color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(total?.let(::formatBytes) ?: "—", style = MaterialTheme.typography.titleMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("↓ ${down?.let(::formatBytes) ?: "—"}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text("↑ ${up?.let(::formatBytes) ?: "—"}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            if (active != null) Text(active, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConnectionStatus(record: RequestActivityRecord) {
    val cs = MaterialTheme.colorScheme
    val failed = record.outcome == RequestOutcome.FAILED || record.outcome == RequestOutcome.BLOCKED
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(color = if (failed) cs.errorContainer else cs.secondaryContainer,
            contentColor = if (failed) cs.onErrorContainer else cs.onSecondaryContainer, shape = MaterialTheme.shapes.extraSmall) {
            Text(stringResource(outcomeLabel(record.outcome)), Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.weight(1f))
        Text("${record.protocol} · ${activityTime(record.updatedAt)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun DomainDetailContent(state: DomainDetailState, onEdit: () -> Unit, onLogs: () -> Unit,
    onApply: (RuleAction?) -> Unit, onRecord: (RequestActivityRecord) -> Unit) {
    val row = state.row
    TrafficDetailSummary(stringResource(R.string.ui_cumulative_traffic), row.bytes, row.traffic?.downloadBytes, row.traffic?.uploadBytes,
        if (row.traffic == null) stringResource(R.string.ui_active_unknown) else stringResource(R.string.ui_active_connections, row.active.toInt()))
    if (row.traffic == null) Text(stringResource(R.string.ui_untracked_counter), style = MaterialTheme.typography.bodySmall)
    if (state.paused) Text(stringResource(R.string.ui_paused_snapshot), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    row.latest?.let { record ->
        ConnectionStatus(record)
        if (record.outcome in listOf(RequestOutcome.FAILED, RequestOutcome.BLOCKED)) {
            Text(stringResource(requestExplanation(record)), style = MaterialTheme.typography.bodyMedium)
        }
    } ?: Text(stringResource(if (state.logging) R.string.ui_no_request_evidence else R.string.ui_request_logging_off),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        DetailValueRow(stringResource(R.string.ui_rule_label), stringResource(actionLabel(state.action)), onClick = onEdit)
        Text(stringResource(ruleSourceLabel(state.source)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        row.latest?.takeIf { it.action != null && it.action != state.action.name }?.let {
            Text(stringResource(R.string.ui_recorded_rule, stringResource(recordedActionLabel(it.action))),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.canRestore) TextButton(onClick = { onApply(null) }, enabled = !state.saving,
            contentPadding = PaddingValues(vertical = 8.dp)) { Text(stringResource(R.string.top_domain_clear)) }
    }
    if (state.saveError) Text(stringResource(R.string.ui_save_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    if (row.requests.isNotEmpty()) WorkbenchFold(stringResource(R.string.ui_connection_records, row.requests.size), "requests-${row.key}") {
        Column(Modifier.fillMaxWidth()) {
            row.requests.forEach { record ->
                Row(Modifier.fillMaxWidth().clickable { onRecord(record) }.heightIn(min = 56.dp).padding(vertical = 8.dp)
                    .testTag("request_${record.key}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${activityTime(record.startedAt)} · ${stringResource(outcomeLabel(record.outcome))}", style = MaterialTheme.typography.bodyMedium)
                        record.uploadBytes?.let { Text("↓ ${formatBytes(record.downloadBytes ?: 0)} · ↑ ${formatBytes(it)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Icon(painterResource(R.drawable.ic_b_chevron_right), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (hasRecommendedFix(row.latest)) TextButton(onClick = onLogs, contentPadding = PaddingValues(vertical = 8.dp)) {
        Icon(painterResource(R.drawable.ic_b_doc), null, Modifier.size(18.dp))
        Text(stringResource(R.string.ui_related_logs), Modifier.padding(start = 8.dp))
    }
}

private fun hasRecommendedFix(record: RequestActivityRecord?) = record != null &&
    (record.outcome == RequestOutcome.BLOCKED || record.outcome == RequestOutcome.FAILED &&
        (record.errorCode == "dns" || record.action == "DIRECT" || record.errorCode in setOf("vpn", "sidelined")))

@Composable
private fun DomainDetailActions(state: DomainDetailState, onEdit: () -> Unit, onLogs: () -> Unit,
    onSettings: (String) -> Unit, onApply: (RuleAction?) -> Unit) {
    val record = state.row.latest
    val (label, action) = when {
        record?.outcome == RequestOutcome.BLOCKED -> R.string.ui_allow_proxy to { onApply(RuleAction.PROXY) }
        record?.outcome == RequestOutcome.FAILED && record.errorCode == "dns" -> R.string.ui_check_dns to { onSettings("dns") }
        record?.outcome == RequestOutcome.FAILED && record.action == "DIRECT" -> R.string.ui_check_direct to { onSettings("direct-egress") }
        record?.outcome == RequestOutcome.FAILED && record.errorCode in setOf("vpn", "sidelined") -> R.string.ui_check_egress to { onSettings("proxy-egress") }
        else -> R.string.ui_change_domain_rule to onEdit
    }
    val directFailure = record?.outcome == RequestOutcome.FAILED && record.action == "DIRECT" && record.errorCode != "dns"
    val secondary = if (directFailure) R.string.ui_use_proxy else if (hasRecommendedFix(record)) R.string.ui_change_domain_rule else R.string.ui_related_logs
    SheetActions(stringResource(label), action, stringResource(secondary),
        if (directFailure) ({ onApply(RuleAction.PROXY) }) else if (hasRecommendedFix(record)) onEdit else onLogs,
        enabled = !state.saving, primaryTag = "domain_primary_action")
}

@Composable
private fun ConnectionDetailContent(host: String, record: RequestActivityRecord) {
    SelectionContainer { Text(host, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace) }
    ConnectionStatus(record)
    TrafficDetailSummary(stringResource(R.string.ui_connection_traffic),
        record.uploadBytes?.let { it + (record.downloadBytes ?: 0) }, record.downloadBytes, record.uploadBytes)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        DetailValueRow(stringResource(R.string.ui_rule_label), stringResource(recordedActionLabel(record.action)))
        Text(stringResource(ruleSourceLabel(record.source)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(stringResource(requestExplanation(record)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    WorkbenchFold(stringResource(R.string.ui_diagnostic_details), "request-${record.key}") {
        DetailValueRow("ID", record.requestId.toString(), mono = true)
        DetailValueRow(stringResource(R.string.ui_stage), record.stage ?: "—")
        DetailValueRow("DNS", record.dnsSource ?: "—")
        DetailValueRow(stringResource(R.string.ui_peer), record.peer ?: "—", mono = true)
        if (!record.error.isNullOrBlank()) SelectionContainer { Text(record.error, style = MaterialTheme.typography.bodySmall) }
    }
}

internal fun ruleSourceLabel(source: String?) = when (source) {
    "OVERRIDE" -> R.string.rule_source_override; "USER_ALLOW" -> R.string.rule_source_allow
    "USER_BLOCK" -> R.string.rule_source_block; "BUILTIN_ADS" -> R.string.rule_source_ads
    "BUILTIN_APP", "BUILTIN_PROXY" -> R.string.rule_source_builtin
    "DEFAULT" -> R.string.rule_source_default
    else -> R.string.ui_unknown
}
internal fun recordedActionLabel(action: String?) = RuleAction.entries.firstOrNull { it.name == action }?.let(::actionLabel) ?: R.string.ui_unknown
internal fun requestExplanation(record: RequestActivityRecord): Int = when {
    record.outcome == RequestOutcome.BLOCKED -> R.string.ui_explain_blocked
    record.outcome == RequestOutcome.FAILED -> when (record.errorCode) {
        "dns" -> R.string.direct_fail_reason_dns
        "timeout" -> R.string.direct_fail_reason_timeout
        "refused" -> R.string.direct_fail_reason_refused
        "unreachable" -> R.string.direct_fail_reason_unreachable
        "denied" -> R.string.direct_fail_reason_denied
        "direct-egress-unavailable" -> R.string.ui_direct_missing
        "vpn" -> R.string.ui_vpn_missing
        "sidelined" -> R.string.ui_egress_sidelined
        else -> R.string.direct_fail_reason_other
    }
    record.outcome == RequestOutcome.RELAYED -> R.string.ui_explain_relayed
    record.outcome == RequestOutcome.CONNECTED -> R.string.ui_explain_connected
    else -> R.string.ui_explain_pending
}
