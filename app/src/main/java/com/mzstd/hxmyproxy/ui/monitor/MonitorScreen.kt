package com.mzstd.hxmyproxy.ui.monitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Row
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MonitorViewModel

private val GREEN = Color(0xFF2E7D32)
private val AMBER = Color(0xFFF9A825)
private val RED = Color(0xFFC62828)

internal fun latencyColor(millis: Long?): Color = when {
    millis == null -> RED
    millis <= 250 -> GREEN
    millis <= 500 -> AMBER
    else -> RED
}

@Composable
fun MonitorScreen(
    ui: MainUiState,
    onOpenHistory: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val vm: MonitorViewModel = hiltViewModel()
    val latency by vm.latency.collectAsStateWithLifecycle()
    val measuring by vm.measuring.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.monitor_latency), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (measuring) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = vm::refreshLatency) { Text(stringResource(R.string.refresh)) }
                }
            }
        }
        if (!ui.share.vpn.detected) {
            item {
                Text(
                    stringResource(R.string.monitor_novpn_hint),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(latency, key = { it.service.host }) { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.service.name, modifier = Modifier.weight(1f))
                Text(
                    if (r.millis == null) stringResource(R.string.latency_timeout) else "${r.millis} ms",
                    color = latencyColor(r.millis),
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        item { HorizontalDivider(Modifier.padding(vertical = 10.dp)) }
        item {
            OutlinedButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
                Text("${stringResource(R.string.monitor_open_history)} (${ui.history.size})")
            }
        }
        item { Spacer(Modifier.size(8.dp)) }
        item {
            OutlinedButton(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.error_logs))
            }
        }
    }
}
