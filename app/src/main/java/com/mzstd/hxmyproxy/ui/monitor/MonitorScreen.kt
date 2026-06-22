package com.mzstd.hxmyproxy.ui.monitor

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.log.LogExport
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.MonitorViewModel

private val GREEN = Color(0xFF2E7D32)
private val AMBER = Color(0xFFF9A825)
private val RED = Color(0xFFC62828)

private fun latencyColor(millis: Long?): Color = when {
    millis == null -> RED
    millis <= 250 -> GREEN
    millis <= 500 -> AMBER
    else -> RED
}

@Composable
fun MonitorScreen(ui: MainUiState, mainViewModel: MainViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val vm: MonitorViewModel = hiltViewModel()
    val latency by vm.latency.collectAsStateWithLifecycle()
    val measuring by vm.measuring.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ---- 服务延迟 ----
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

        // ---- 历史入口 ----
        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.history_title), style = MaterialTheme.typography.titleMedium)
        }
        if (ui.history.isEmpty()) {
            item { Text(stringResource(R.string.monitor_no_history), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        }
        items(ui.history, key = { "${it.entry.protocol}|${it.entry.ip}|${it.entry.port}" }) { v ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${v.entry.protocol} ${v.entry.endpoint}")
                    if (!v.available) {
                        Text(
                            stringResource(R.string.history_unavailable),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(v.entry.endpoint))
                    Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.copy)) }
                TextButton(onClick = { mainViewModel.removeHistoryEndpoint(v.entry) }) {
                    Text(stringResource(R.string.delete))
                }
            }
        }

        // ---- 错误日志 ----
        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.error_logs), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    runCatching {
                        val uri = LogExport.buildShareUri(context)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, context.getString(R.string.export_logs)))
                    }.onFailure {
                        Toast.makeText(context, it.message ?: "export failed", Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.export_logs)) }
                TextButton(onClick = {
                    vm.clearLogs()
                    Toast.makeText(context, context.getString(R.string.logs_cleared), Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.clear_logs)) }
            }
        }
        if (logs.isEmpty()) {
            item { Text(stringResource(R.string.monitor_no_logs), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        }
        items(logs) { line ->
            Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        item { Spacer(Modifier.size(8.dp)) }
    }
}
