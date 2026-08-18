package com.mzstd.hxmyproxy.ui.run

import android.Manifest
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.service.ProxyForegroundService
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.data.repository.ManualResetPhase
import com.mzstd.hxmyproxy.ui.components.InfoDot
import com.mzstd.hxmyproxy.ui.components.SegTabs
import com.mzstd.hxmyproxy.ui.formatRate
import com.mzstd.hxmyproxy.ui.monitor.MonitorScreen
import com.mzstd.hxmyproxy.ui.monitor.MonitorSection
import com.mzstd.hxmyproxy.ui.monitor.TrafficStatsScreen
import com.mzstd.hxmyproxy.ui.theme.StatusColors

/**
 * 「运行」tab（工作台重构 2026-08，规格=google-play/prototypes/ 的定稿设计）：
 * 常驻状态条（点 + RUN/STOP + 实时速率 + 刷新 + 启停开关）+ 分段（概览/监控/流量）。
 *
 * 分段策略（过渡期）：概览=本文件新实现；监控=原 MonitorScreen 整页（设备+诊断，
 * 下一批拆成两段）；流量=原 TrafficStatsScreen。旧 dashboard 路由保留为
 * 「入口与出口设置」详情页，概览四格的入口/出口格点进去——功能零丢失。
 */
@Composable
fun RunScreen(
    ui: MainUiState,
    viewModel: MainViewModel,
    onOpenConfig: () -> Unit,
    onOpenProtection: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenDomains: () -> Unit,
    onOpenTrafficStats: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    var seg by rememberSaveable { mutableIntStateOf(0) }
    val ld = LocalLayoutDirection.current
    val context = LocalContext.current
    // 与旧 DashboardScreen 相同的 pending-result 防重投(详见那边的注释)。
    var startRequested by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (startRequested) {
            startRequested = false
            ProxyForegroundService.start(context)
        }
    }
    val onStart = {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
        }
        if (perms.isEmpty()) {
            ProxyForegroundService.start(context)
        } else {
            startRequested = true
            permLauncher.launch(perms.toTypedArray())
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(
                start = contentPadding.calculateStartPadding(ld),
                end = contentPadding.calculateEndPadding(ld),
                top = contentPadding.calculateTopPadding(),
            ),
    ) {
        StatusStrip(ui, viewModel, onStart)
        SegTabs(
            labels = listOf(
                stringResource(R.string.seg_overview),
                stringResource(R.string.seg_devices),
                stringResource(R.string.seg_traffic),
                stringResource(R.string.monitor_diagnostics),
            ),
            selected = seg,
            onSelect = { seg = it },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val innerPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())
        when (seg) {
            0 -> OverviewSegment(ui, viewModel, onStart, onOpenConfig, onOpenProtection, { seg = 1 }, innerPadding)
            1 -> MonitorScreen(
                ui, viewModel,
                onOpenHistory = onOpenHistory,
                onOpenLogs = onOpenLogs,
                onOpenDomains = onOpenDomains,
                onOpenTrafficStats = onOpenTrafficStats,
                contentPadding = innerPadding,
                section = MonitorSection.DEVICES,
            )
            2 -> TrafficStatsScreen(onBack = { seg = 0 }, embedded = true, contentPadding = innerPadding)
            else -> MonitorScreen(
                ui, viewModel,
                onOpenHistory = onOpenHistory,
                onOpenLogs = onOpenLogs,
                onOpenDomains = onOpenDomains,
                onOpenTrafficStats = onOpenTrafficStats,
                contentPadding = innerPadding,
                section = MonitorSection.HEALTH,
            )
        }
    }
}

/**
 * 常驻状态条：任何分段下都看得见运行态与速率。
 * 启停开关走与旧 hero 圆钮完全相同的权限流程（POST_NOTIFICATIONS / ACCESS_LOCAL_NETWORK）。
 */
@Composable
private fun StatusStrip(ui: MainUiState, viewModel: MainViewModel, onStart: () -> Unit) {
    val context = LocalContext.current
    val share = ui.share
    val running = share.running

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

    val cs = MaterialTheme.colorScheme
    val dotColor = if (running) StatusColors.runningDot() else StatusColors.stoppedDot()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(9.dp).background(dotColor, CircleShape))
        Text(
            if (running) "RUN" else "STOP",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = cs.onSurface,
        )
        Text(
            if (running) {
                "↓${formatRate(share.downloadRateBps)} ↑${formatRate(share.uploadRateBps)}"
            } else {
                "—"
            },
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        if (running) {
            IconButton(onClick = { viewModel.manualReset() }, modifier = Modifier.size(40.dp)) {
                Icon(
                    painterResource(R.drawable.ic_b_refresh),
                    contentDescription = stringResource(R.string.refresh_service),
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Switch(
            checked = running,
            onCheckedChange = { want ->
                if (want) onStart() else ProxyForegroundService.stop(context)
            },
        )
    }
}

/** 概览段：地址卡 → 速率+四格 → 四格摘要 → 启停大按钮（设计稿 Main 屏）。 */
@Composable
private fun OverviewSegment(
    ui: MainUiState,
    viewModel: MainViewModel,
    onStart: () -> Unit,
    onOpenConfig: () -> Unit,
    onOpenProtection: () -> Unit,
    onOpenMonitor: () -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val share = ui.share
    val running = share.running
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        WarnBanners(ui)
        AddressCard(ui, viewModel)
        RateStatsCard(ui, viewModel)
        SummaryGrid(ui, onOpenConfig, onOpenProtection, onOpenMonitor)
        // 启停大按钮：与状态条开关同一套动作；停止态是页面的首要行动点。
        StartStopBar(running) {
            if (running) ProxyForegroundService.stop(context) else Unit
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun StartStopBar(running: Boolean, onStart: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    if (running) {
        Button(
            onClick = { ProxyForegroundService.stop(context) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = cs.errorContainer,
                contentColor = cs.onErrorContainer,
            ),
        ) { Text(stringResource(R.string.stop_sharing), fontWeight = FontWeight.SemiBold) }
    } else {
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = CircleShape,
        ) { Text(stringResource(R.string.start_sharing), fontWeight = FontWeight.SemiBold) }
    }
}
