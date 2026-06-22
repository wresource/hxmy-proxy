package com.mzstd.hxmyproxy.ui.diagnostics

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.log.FileLog
import com.mzstd.hxmyproxy.core.log.LogExport
import com.mzstd.hxmyproxy.ui.MainUiState

@Composable
fun DiagnosticsScreen(ui: MainUiState) {
    val context = LocalContext.current
    val d = ui.share.diagnostics
    val rows = listOf(
        R.string.diag_local_net_perm to d.localNetworkPermissionGranted,
        R.string.diag_vpn to d.vpnDetected,
        R.string.diag_http_port to d.httpPortUp,
        R.string.diag_socks_port to d.socksPortUp,
        R.string.diag_pac_port to d.pacPortUp,
        R.string.diag_mdns to d.mdnsPublished,
    )
    val okColor = Color(0xFF2E7D32)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        rows.forEachIndexed { index, (label, ok) ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text(stringResource(label), modifier = Modifier.weight(1f))
                Text(
                    stringResource(if (ok) R.string.diag_ok else R.string.diag_fail),
                    color = if (ok) okColor else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (index < rows.lastIndex) HorizontalDivider()
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Text(
            stringResource(R.string.diag_logs_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            stringResource(R.string.diag_logs_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    runCatching {
                        val uri = LogExport.buildShareUri(context)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(
                            Intent.createChooser(send, context.getString(R.string.export_logs)),
                        )
                    }.onFailure {
                        Toast.makeText(context, it.message ?: "export failed", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.export_logs)) }
            OutlinedButton(
                onClick = {
                    FileLog.clear()
                    Toast.makeText(context, context.getString(R.string.logs_cleared), Toast.LENGTH_SHORT).show()
                },
            ) { Text(stringResource(R.string.clear_logs)) }
        }
    }
}
