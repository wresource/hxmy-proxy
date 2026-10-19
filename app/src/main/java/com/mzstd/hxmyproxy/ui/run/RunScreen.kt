package com.mzstd.hxmyproxy.ui.run

import androidx.core.net.toUri
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.mzstd.hxmyproxy.ui.components.AppAlertDialog as AlertDialog
import androidx.compose.material3.Button
import com.mzstd.hxmyproxy.ui.components.AppDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.data.repository.ManualResetPhase
import com.mzstd.hxmyproxy.service.ProxyForegroundService
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.components.DomainRuleEditor
import com.mzstd.hxmyproxy.ui.components.AdaptiveActionPair
import com.mzstd.hxmyproxy.ui.components.SegTabs
import com.mzstd.hxmyproxy.ui.components.WorkbenchFold
import com.mzstd.hxmyproxy.ui.components.WorkbenchHeader
import com.mzstd.hxmyproxy.ui.components.WorkbenchRow
import com.mzstd.hxmyproxy.ui.components.WarnBanner
import com.mzstd.hxmyproxy.ui.monitor.ActivityQuery
import com.mzstd.hxmyproxy.ui.monitor.ActivityScreen
import com.mzstd.hxmyproxy.ui.monitor.DomainActivityDialog
import com.mzstd.hxmyproxy.ui.monitor.DomainSort
import com.mzstd.hxmyproxy.ui.monitor.DomainTrafficRow
import com.mzstd.hxmyproxy.ui.monitor.TrafficStatsScreen
import com.mzstd.hxmyproxy.ui.monitor.domainActivities

@Composable
fun RunScreen(ui: MainUiState, viewModel: MainViewModel, onOpenConfig: () -> Unit,
    onOpenProtection: () -> Unit, onOpenHistory: () -> Unit, onOpenLogs: () -> Unit,
    onOpenDomains: () -> Unit, onOpenTrafficStats: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(), onOpenAddresses: () -> Unit = {},
    onOpenClients: () -> Unit = {}, onSettings: (String) -> Unit = {},
    onRelatedLogs: (String) -> Unit = {}, onChanges: () -> Unit = {}, onHealthDetail: (String) -> Unit = {},
) {
    var seg by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var stopConfirm by rememberSaveable { mutableStateOf(false) }
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDomain by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    fun hasLocalAccess() = Build.VERSION.SDK_INT < 37 || ContextCompat.checkSelfPermission(context,
        "android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED
    var localAccess by remember { mutableStateOf(hasLocalAccess()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) localAccess = hasLocalAccess() }
        lifecycleOwner.lifecycle.addObserver(observer)
        localAccess = hasLocalAccess()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val snapshot by viewModel.activitySnapshot.collectAsStateWithLifecycle()
    val version by viewModel.ruleVersion.collectAsStateWithLifecycle()
    val recent = remember(snapshot) { domainActivities(snapshot, ActivityQuery(sort = DomainSort.RECENT)) }
    // Retain the pending-result guard: a permission result must not start a new sharing session twice.
    var startRequested by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        localAccess = hasLocalAccess()
        if (startRequested) { startRequested = false; ProxyForegroundService.start(context) }
    }
    val start: () -> Unit = {
        if (!startRequested && !ui.share.starting) {
            val requested = buildList {
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
            }
            if (requested.isEmpty()) ProxyForegroundService.start(context)
            else { startRequested = true; permissions.launch(requested.toTypedArray()) }
        }
    }
    RefreshFeedback(viewModel)
    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        WorkbenchHeader("hxmy proxy", brand = true) {
            val sharingAction = stringResource(if (ui.share.starting) R.string.cancel else if (ui.share.running) R.string.stop_sharing else R.string.start_sharing)
            val sharingState = stringResource(if (ui.share.starting) R.string.notif_starting else if (ui.share.running) R.string.status_running else R.string.status_stopped)
            Switch(checked = ui.share.running || ui.share.starting, onCheckedChange = {
                when { ui.share.starting -> ProxyForegroundService.stop(context); ui.share.running -> stopConfirm = true; else -> start() }
            }, modifier = Modifier.testTag("sharing_primary_action").semantics {
                contentDescription = sharingAction
                stateDescription = sharingState
            })
            Box {
                IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_ui_more), stringResource(R.string.ui_more_actions)) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    listOf(R.string.refresh_service to { viewModel.manualReset() }, R.string.ui_all_addresses to onOpenAddresses,
                        R.string.monitor_open_history to onOpenHistory, R.string.error_logs to onOpenLogs,
                        R.string.ui_rule_changes to onChanges, R.string.nav_settings to onOpenConfig).forEach { (label, action) ->
                        DropdownMenuItem(modifier = Modifier.clip(MaterialTheme.shapes.small), text = { Text(stringResource(label)) }, enabled = label != R.string.refresh_service || ui.share.running, onClick = { menu = false; action() })
                    }
                }
            }
        }
        SegTabs(listOf(stringResource(R.string.seg_overview), stringResource(R.string.ui_activity), stringResource(R.string.seg_traffic), stringResource(R.string.ui_health)),
            seg, { seg = it }, Modifier.padding(horizontal = 16.dp))
        val innerPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())
        when (seg) {
            0 -> BoxWithConstraints(Modifier.weight(1f)) {
                val wide = maxWidth >= 720.dp
                @Composable fun connection() {
                    Text(stringResource(if (ui.share.starting) R.string.notif_starting else if (ui.share.running) R.string.status_running else R.string.status_stopped),
                        Modifier.testTag("sharing_status"), style = MaterialTheme.typography.titleLarge)
                    if (!localAccess) {
                        WarnBanner(stringResource(R.string.ui_local_access_required))
                        TextButton(onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                            modifier = Modifier.testTag("local_network_permission_settings")) { Text(stringResource(R.string.ui_app_permissions)) }
                    }
                    WarnBanners(ui, onSettings)
                    AddressCard(ui, viewModel, onOpenAddresses, onSettings, start)
                    AdaptiveActionPair(stringResource(R.string.ui_activity), stringResource(R.string.ui_add_rule), extraWidth = 74.dp) { button ->
                        Button(onClick = { seg = 1 }, modifier = button) { Icon(painterResource(R.drawable.ic_b_search), null, Modifier.size(18.dp)); Text(stringResource(R.string.ui_activity), Modifier.padding(start = 8.dp)) }
                        FilledTonalButton(onClick = { editor = "" }, modifier = button.testTag("home_add_rule")) { Icon(painterResource(R.drawable.ic_b_plus), null, Modifier.size(18.dp)); Text(stringResource(R.string.ui_add_rule), Modifier.padding(start = 8.dp)) }
                    }
                }
                @Composable fun details() {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.ui_recent_activity), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { seg = 1 }) { Text(stringResource(R.string.ui_activity)) }
                    }
                    if (recent.isEmpty()) Text(stringResource(R.string.monitor_no_domains), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val max = recent.maxOfOrNull { it.bytes ?: 0 }?.coerceAtLeast(1) ?: 1
                    recent.take(if (wide) 2 else 1).forEach { row -> DomainTrafficRow(row, max, version.let { viewModel.decideHost(row.host) }) { selectedDomain = row.key } }
                    WorkbenchFold(stringResource(R.string.ui_runtime_details), "runtime") {
                        RateStatsCard(ui, viewModel)
                        SummaryGrid(ui, onOpenConfig, onOpenProtection) { seg = 1 }
                        WorkbenchRow(stringResource(R.string.settings_proto_ports), onClick = { onSettings("ports") })
                        WorkbenchRow(stringResource(R.string.ui_all_devices), onClick = onOpenClients)
                    }
                }
                if (wide) Row(Modifier.fillMaxSize().padding(bottom = innerPadding.calculateBottomPadding()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { connection() }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { details() }
                } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
                    .padding(top = 12.dp, bottom = innerPadding.calculateBottomPadding() + 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { connection(); details() }
            }
            1 -> Box(Modifier.weight(1f)) { ActivityScreen(ui, viewModel, onOpenDomains, onOpenClients, onRelatedLogs, onSettings, onChanges, innerPadding) }
            2 -> Box(Modifier.weight(1f)) { TrafficStatsScreen(onBack = { seg = 0 }, embedded = true, contentPadding = innerPadding) }
            else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = innerPadding.calculateBottomPadding() + 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WorkbenchRow(stringResource(R.string.ui_connection_checks), icon = R.drawable.ic_b_shield_check, onClick = { onHealthDetail("checks") })
                WorkbenchRow(stringResource(R.string.monitor_latency), icon = R.drawable.ic_b_timer, onClick = { onHealthDetail("latency") })
                WorkbenchRow(stringResource(R.string.ui_link_quality), icon = R.drawable.ic_b_activity, onClick = { onHealthDetail("link") })
                WorkbenchRow(stringResource(R.string.error_logs), icon = R.drawable.ic_b_doc, onClick = onOpenLogs)
                WorkbenchRow(stringResource(R.string.ui_protection), icon = R.drawable.ic_b_shield, onClick = onOpenProtection)
                TextButton(onClick = viewModel::manualReset, enabled = ui.share.running, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.refresh_service)) }
            }
        }
    }
    if (stopConfirm) AlertDialog(onDismissRequest = { stopConfirm = false }, title = { Text(stringResource(R.string.ui_stop_title)) },
        text = { Text(stringResource(R.string.ui_stop_body)) },
        confirmButton = { TextButton(onClick = { stopConfirm = false; ProxyForegroundService.stop(context) }, modifier = Modifier.testTag("confirm_stop_sharing")) { Text(stringResource(R.string.stop_sharing)) } },
        dismissButton = { TextButton(onClick = { stopConfirm = false }) { Text(stringResource(R.string.cancel)) } })
    editor?.let { DomainRuleEditor(viewModel, it, onDismiss = { editor = null }, onSaved = { seg = 1 }) }
    recent.firstOrNull { it.key == selectedDomain }?.let { row ->
        DomainActivityDialog(row, snapshot.paused, ui, viewModel, { selectedDomain = null },
            { selectedDomain = null; editor = row.host }, onRelatedLogs, onSettings)
    }
}

@Composable
private fun RefreshFeedback(viewModel: MainViewModel) {
    val context = LocalContext.current
    // 手动刷新的结果呈现（与旧 Dashboard 同一套；服务刷新是排障第一步）。
    val resetPhase by viewModel.manualResetState.collectAsStateWithLifecycle()
    LaunchedEffect(resetPhase) {
        when (resetPhase) {
            ManualResetPhase.DONE_OK -> {
                Toast.makeText(context, R.string.refresh_done_ok, Toast.LENGTH_SHORT).show()
                viewModel.ackManualReset()
            }
            ManualResetPhase.DONE_NO_CLIENT -> {
                Toast.makeText(context, R.string.refresh_done, Toast.LENGTH_SHORT).show()
                viewModel.ackManualReset()
            }
            else -> {}
        }
    }
    if (resetPhase == ManualResetPhase.DONE_LINK_DEAD) {
        AlertDialog(
            onDismissRequest = { viewModel.ackManualReset() },
            title = { Text(stringResource(R.string.refresh_dead_title)) },
            text = { Text(stringResource(R.string.refresh_dead_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.ackManualReset()
                    runCatching {
                        context.startActivity(Intent(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
                    }
                }) { Text(stringResource(R.string.refresh_open_panel)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.ackManualReset() }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

}
