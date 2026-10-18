package com.mzstd.hxmyproxy.ui.monitor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import com.mzstd.hxmyproxy.ui.components.AppDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.model.ClientSession
import com.mzstd.hxmyproxy.core.proxy.RequestOutcome
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.components.DetailScaffold
import com.mzstd.hxmyproxy.ui.components.DomainRuleEditor
import com.mzstd.hxmyproxy.ui.components.RatioBar
import com.mzstd.hxmyproxy.ui.components.WorkbenchRow
import com.mzstd.hxmyproxy.ui.formatBytes
import com.mzstd.hxmyproxy.ui.formatRate
import com.mzstd.hxmyproxy.ui.theme.StatusColors
import java.text.DateFormat
import java.util.Date

@Composable
fun ActivityScreen(ui: MainUiState, viewModel: MainViewModel, onOpenDomains: () -> Unit,
    onOpenClients: () -> Unit, onOpenLogs: (String) -> Unit, onSettings: (String) -> Unit,
    onChanges: () -> Unit, contentPadding: PaddingValues = PaddingValues()) {
    val snapshot by viewModel.activitySnapshot.collectAsStateWithLifecycle()
    val query by viewModel.activityQuery.collectAsStateWithLifecycle()
    val ruleVersion by viewModel.ruleVersion.collectAsStateWithLifecycle()
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var editHost by rememberSaveable { mutableStateOf<String?>(null) }
    var clientIp by rememberSaveable { mutableStateOf<String?>(null) }
    val all = remember(snapshot) { domainActivities(snapshot) }
    ActivityContent(snapshot, query, ui.settings.logEnabled, contentPadding,
        onQuery = { viewModel.activityQuery.value = it }, onPause = viewModel::toggleActivityPaused,
        onAllDomains = onOpenDomains, onAllClients = onOpenClients, onAddRule = { editHost = query.text },
        onChanges = onChanges, onDomain = { selectedKey = it.key }, onClient = { clientIp = it.clientIp.hostAddress },
        actionForHost = { host -> ruleVersion.let { viewModel.decideHost(host) } })
    all.firstOrNull { it.key == selectedKey }?.let { selected ->
        DomainActivityDialog(selected, snapshot.paused, ui, viewModel, onDismiss = { selectedKey = null },
            onEdit = { selectedKey = null; editHost = selected.host }, onOpenLogs = onOpenLogs, onSettings = onSettings)
    }
    snapshot.clients.firstOrNull { it.clientIp.hostAddress == clientIp }?.let { client ->
        ClientActivityDialog(client, snapshot.paused, onDismiss = { clientIp = null })
    }
    editHost?.let { host -> DomainRuleEditor(viewModel, host, onDismiss = { editHost = null }) }
}

@Composable
internal fun ActivityContent(snapshot: ActivitySnapshot, query: ActivityQuery, logging: Boolean,
    contentPadding: PaddingValues = PaddingValues(), onQuery: (ActivityQuery) -> Unit,
    onPause: () -> Unit, onAllDomains: () -> Unit, onAllClients: () -> Unit, onAddRule: () -> Unit,
    onChanges: () -> Unit, onDomain: (DomainActivity) -> Unit, onClient: (ClientSession) -> Unit,
    actionForHost: (String) -> RuleAction) {
    val rows = remember(snapshot, query) { domainActivities(snapshot, query) }
    val maxBytes = rows.maxOfOrNull { it.bytes ?: 0 }?.coerceAtLeast(1) ?: 1
    LazyColumn(Modifier.fillMaxSize().testTag("activity_summary"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp + contentPadding.calculateTopPadding(),
            bottom = 16.dp + contentPadding.calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("devices-heading") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ui_devices_count, snapshot.clients.size), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onAllClients, modifier = Modifier.testTag("all_clients")) { Text(stringResource(R.string.ui_all_devices)) }
                IconButton(onClick = onPause, modifier = Modifier.testTag("activity_pause")) {
                    Icon(painterResource(if (snapshot.paused) R.drawable.ic_play else R.drawable.ic_ui_pause), stringResource(if (snapshot.paused) R.string.ui_resume else R.string.ui_pause))
                }
            }
        }
        if (snapshot.clients.isEmpty()) item("no-clients") { MonitorEmpty(stringResource(R.string.monitor_no_clients)) }
        items(snapshot.clients.take(CLIENT_PREVIEW_LIMIT), key = { "client:${it.clientIp.hostAddress}" }) { client ->
            ClientTrafficRow(client, snapshot.paused) { onClient(client) }
        }
        item("session-usage") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.ui_session_usage, formatBytes(snapshot.totalBytes)), style = MaterialTheme.typography.bodySmall)
                Text(if (snapshot.paused) stringResource(R.string.ui_paused_snapshot) else "↓ ${formatRate(snapshot.downRate)} · ↑ ${formatRate(snapshot.upRate)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item("domain-controls") {
            DomainMonitorControls(query, onQuery, onAddRule, onChanges)
        }
        if (rows.isEmpty()) item("no-domain") { MonitorEmpty(stringResource(if (query.text.isBlank()) R.string.monitor_no_domains else R.string.ui_no_domain_match)) }
        items(rows.take(DOMAIN_PREVIEW_LIMIT), key = { "domain:${it.key}" }) { row ->
            DomainTrafficRow(row, maxBytes, actionForHost(row.host), onClick = { onDomain(row) })
        }
        item("all-domains") {
            WorkbenchRow(stringResource(R.string.ui_view_all, rows.size), subtitle = stringResource(R.string.ui_full_domain_hint),
                tag = "all_domains", onClick = onAllDomains)
        }
        if (!logging) item("no-evidence") { Text(stringResource(R.string.ui_request_logging_off), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun DomainMonitorControls(query: ActivityQuery, onQuery: (ActivityQuery) -> Unit, onAddRule: () -> Unit, onChanges: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ui_domains), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onAddRule, modifier = Modifier.testTag("activity_add_rule")) {
                Icon(painterResource(R.drawable.ic_b_plus), null, Modifier.size(18.dp)); Text(stringResource(R.string.ui_add_rule), Modifier.padding(start = 6.dp))
            }
            IconButton(onClick = onChanges, modifier = Modifier.testTag("activity_rule_changes")) { Icon(painterResource(R.drawable.ic_b_history), stringResource(R.string.ui_rule_changes)) }
        }
        OutlinedTextField(query.text, { onQuery(query.copy(text = it)) }, Modifier.fillMaxWidth().testTag("domain_search"),
            singleLine = true, placeholder = { Text(stringResource(R.string.ui_domain_search)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_b_search), null) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonitorChoice(ActivityFilter.entries, query.filter, ::activityFilterLabel, Modifier.weight(1f).testTag("activity_filter")) { onQuery(query.copy(filter = it)) }
            MonitorChoice(DomainSort.entries, query.sort, ::domainSortLabel, Modifier.weight(1f).testTag("activity_sort")) { onQuery(query.copy(sort = it)) }
        }
    }
}

@Composable
internal fun <T> MonitorChoice(choices: List<T>, selected: T, label: (T) -> Int, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
            Text(stringResource(label(selected)), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Icon(painterResource(R.drawable.ic_b_chevron_down), null, Modifier.size(16.dp))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { value -> DropdownMenuItem(text = { Text(stringResource(label(value))) }, onClick = { onSelect(value); expanded = false }) }
        }
    }
}
internal fun domainSortLabel(value: DomainSort) = when (value) {
    DomainSort.ACTIVE -> R.string.ui_active_first; DomainSort.RECENT -> R.string.domains_sort_recent; DomainSort.TRAFFIC -> R.string.domains_sort_traffic
}
internal fun activityFilterLabel(value: ActivityFilter) = when (value) {
    ActivityFilter.ALL -> R.string.ui_all_activity; ActivityFilter.ACTIVE -> R.string.ui_active_only
    ActivityFilter.BLOCKED -> R.string.ui_rule_blocked; ActivityFilter.FAILED -> R.string.ui_connection_failed
    ActivityFilter.RELAYED -> R.string.ui_relayed
}
internal fun outcomeLabel(value: RequestOutcome) = when (value) {
    RequestOutcome.PENDING -> R.string.ui_request_pending; RequestOutcome.CONNECTED -> R.string.ui_request_connected
    RequestOutcome.RELAYED -> R.string.ui_relayed; RequestOutcome.BLOCKED -> R.string.ui_rule_blocked
    RequestOutcome.FAILED -> R.string.ui_connection_failed; RequestOutcome.CLOSED -> R.string.ui_request_closed
}
internal fun actionLabel(value: RuleAction) = when (value) {
    RuleAction.PROXY -> R.string.ui_proxy; RuleAction.DIRECT -> R.string.override_direct; RuleAction.REJECT -> R.string.override_reject
}

@Composable
internal fun ClientTrafficRow(client: ClientSession, paused: Boolean, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 72.dp)
            .padding(12.dp).testTag("client_${client.clientIp.hostAddress}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Text(client.clientIp.hostAddress.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                Text(formatBytes(client.uploadBytes + client.downloadBytes), style = MaterialTheme.typography.titleSmall)
            }
            Text("↓ ${formatBytes(client.downloadBytes)} · ↑ ${formatBytes(client.uploadBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (paused) stringResource(R.string.ui_paused_snapshot) else stringResource(R.string.ui_active_connections, client.activeConnections),
                style = MaterialTheme.typography.bodySmall, color = if (client.activeConnections > 0 && !paused) StatusColors.good() else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun DomainTrafficRow(row: DomainActivity, maxBytes: Long, action: RuleAction?, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().testTag("domain_${row.key}")
            .then(if (!row.aggregate) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 88.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Text(if (row.aggregate) stringResource(R.string.monitor_others) else row.host, Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(row.bytes?.let(::formatBytes) ?: "—", style = MaterialTheme.typography.titleSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(row.protocol.name, style = MaterialTheme.typography.labelMedium)
                Text(if (row.traffic == null) stringResource(R.string.ui_active_unknown) else stringResource(R.string.ui_active_connections, row.active.toInt()), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = if (row.active > 0) StatusColors.good() else MaterialTheme.colorScheme.onSurfaceVariant)
                if (!row.aggregate && action != null) Text(stringResource(actionLabel(action)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Text("↓ ${row.traffic?.downloadBytes?.let(::formatBytes) ?: "—"} · ↑ ${row.traffic?.uploadBytes?.let(::formatBytes) ?: "—"}",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (row.latest?.outcome in listOf(RequestOutcome.BLOCKED, RequestOutcome.FAILED)) Text(stringResource(outcomeLabel(row.latest!!.outcome)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else Text(activityTime(row.lastSeen), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RatioBar(fraction = ((row.bytes ?: 0).toFloat() / maxBytes.coerceAtLeast(1)).coerceIn(0f, 1f),
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth(), height = 3.dp)
        }
    }
}

internal fun activityTime(at: Long): String = if (at <= 0) "—" else java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(Date(at))

@Composable
internal fun MonitorEmpty(message: String) { Text(message, Modifier.fillMaxWidth().padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable
internal fun ClientActivityDialog(client: ClientSession, paused: Boolean, onDismiss: () -> Unit) {
    com.mzstd.hxmyproxy.ui.components.InteractionSheet(client.clientIp.hostAddress.orEmpty(), onDismiss) {
        TrafficDetailSummary(stringResource(R.string.ui_traffic), client.uploadBytes + client.downloadBytes,
            client.downloadBytes, client.uploadBytes, stringResource(R.string.ui_active_connections, client.activeConnections))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            com.mzstd.hxmyproxy.ui.components.DetailValueRow(stringResource(R.string.ui_download_rate), formatRate(client.downloadRateBps))
            com.mzstd.hxmyproxy.ui.components.DetailValueRow(stringResource(R.string.ui_upload_rate), formatRate(client.uploadRateBps))
            com.mzstd.hxmyproxy.ui.components.DetailValueRow(stringResource(R.string.ui_last_activity), activityTime(client.lastSeenAtEpochMs))
        }
        if (paused) Text(stringResource(R.string.ui_paused_snapshot), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ClientsDetailScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val snapshot by viewModel.activitySnapshot.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    DetailScaffold(stringResource(R.string.ui_all_devices), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (snapshot.clients.isEmpty()) item { MonitorEmpty(stringResource(R.string.monitor_no_clients)) }
            items(snapshot.clients, key = { it.clientIp.hostAddress.orEmpty() }) { client -> ClientTrafficRow(client, snapshot.paused) { selected = client.clientIp.hostAddress } }
        }
    }
    snapshot.clients.firstOrNull { it.clientIp.hostAddress == selected }?.let { ClientActivityDialog(it, snapshot.paused) { selected = null } }
}
