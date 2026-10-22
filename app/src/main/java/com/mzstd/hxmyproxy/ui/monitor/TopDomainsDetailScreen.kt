package com.mzstd.hxmyproxy.ui.monitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.model.DomainTraffic
import com.mzstd.hxmyproxy.core.proxy.RequestActivityRecord
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.components.DetailScaffold
import com.mzstd.hxmyproxy.ui.components.DomainRuleEditor

/** Summary is capped at five rows; this independent screen owns the complete lazy list. */
@Composable
fun TopDomainsDetailScreen(ui: MainUiState, viewModel: MainViewModel, onBack: () -> Unit,
    onOpenLogs: (String) -> Unit = {}, onSettings: (String) -> Unit = {}, onChanges: () -> Unit = {}) {
    val snapshot by viewModel.activitySnapshot.collectAsStateWithLifecycle()
    val query by viewModel.activityQuery.collectAsStateWithLifecycle()
    val version by viewModel.ruleVersion.collectAsStateWithLifecycle()
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var editHost by rememberSaveable { mutableStateOf<String?>(null) }
    TopDomainsDetailContent(snapshot.domains, onBack, { host -> version.let { viewModel.decideHost(host) } }, { editHost = it },
        requests = snapshot.requests, externalQuery = query, onQuery = { viewModel.activityQuery.value = it },
        onAdd = { editHost = query.text }, onChanges = onChanges, onSelect = { selectedKey = it.key },
        detail = { row -> DomainActivityPanel(row, snapshot.paused, ui, viewModel,
            onEdit = { editHost = row.host }, onOpenLogs = onOpenLogs, onSettings = onSettings) })
    domainActivities(snapshot, query).firstOrNull { it.key == selectedKey }?.let { row ->
        DomainActivityDialog(row, snapshot.paused, ui, viewModel, { selectedKey = null },
            { selectedKey = null; editHost = row.host }, onOpenLogs, onSettings)
    }
    editHost?.let { DomainRuleEditor(viewModel, it, onDismiss = { editHost = null }) }
}

/** Shared with regression tests using real TrafficAccounting snapshots. */
@Composable
internal fun TopDomainsDetailContent(
    domains: List<DomainTraffic>, onBack: () -> Unit, actionForHost: (String) -> RuleAction?, onEdit: (String) -> Unit,
    requests: List<RequestActivityRecord> = emptyList(), externalQuery: ActivityQuery? = null,
    onQuery: (ActivityQuery) -> Unit = {}, onAdd: () -> Unit = {}, onChanges: () -> Unit = {},
    onSelect: ((DomainActivity) -> Unit)? = null, detail: (@Composable (DomainActivity) -> Unit)? = null,
) {
    var localText by rememberSaveable { mutableStateOf("") }
    var localSort by rememberSaveable { mutableStateOf(DomainSort.RECENT) }
    var localFilter by rememberSaveable { mutableStateOf(ActivityFilter.ALL) }
    var localRoute by rememberSaveable { mutableStateOf(ActivityRoute.ALL) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val query = externalQuery ?: ActivityQuery(localText, localSort, localFilter, localRoute)
    val update: (ActivityQuery) -> Unit = { if (externalQuery == null) { localText = it.text; localSort = it.sort; localFilter = it.filter; localRoute = it.route }; onQuery(it) }
    val rows = remember(domains, requests, query) { domainActivities(ActivitySnapshot(domains = domains, requests = requests), query) }
    val maxBytes = rows.maxOfOrNull { it.bytes ?: 0 }?.coerceAtLeast(1) ?: 1
    DetailScaffold(stringResource(R.string.monitor_top_domains), onBack) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val split = maxWidth >= 720.dp && detail != null
            Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.weight(1f).testTag("all_domain_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item("controls") { DomainMonitorControls(query, update, onAdd, onChanges) }
                    if (rows.isEmpty()) item("empty") { MonitorEmpty(stringResource(R.string.ui_no_domain_match)) }
                    // Protocol is part of identity, including the overflow aggregate bucket.
                    items(rows, key = { it.key }) { row -> DomainTrafficRow(row, maxBytes, actionForHost(row.host)) {
                        if (split) selectedKey = row.key else if (onSelect != null) onSelect(row) else onEdit(row.host)
                    } }
                }
                if (split) {
                    val selected = rows.firstOrNull { it.key == selectedKey } ?: rows.firstOrNull { !it.aggregate }
                    Surface(Modifier.weight(0.9f).padding(top = 16.dp, end = 16.dp, bottom = 16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (selected != null) { Text(selected.host, style = MaterialTheme.typography.titleMedium); detail!!(selected) }
                            else MonitorEmpty(stringResource(R.string.ui_no_domain_match))
                        }
                    }
                }
            }
        }
    }
}
