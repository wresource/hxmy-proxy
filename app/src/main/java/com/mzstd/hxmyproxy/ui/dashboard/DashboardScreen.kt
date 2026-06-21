package com.mzstd.hxmyproxy.ui.dashboard

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.service.ProxyForegroundService
import com.mzstd.hxmyproxy.ui.MainUiState

@Composable
fun DashboardScreen(ui: MainUiState, viewModel: com.mzstd.hxmyproxy.ui.MainViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val share = ui.share

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { ProxyForegroundService.start(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(if (share.running) R.string.status_running else R.string.status_stopped),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(stringResource(if (share.vpn.detected) R.string.vpn_detected else R.string.vpn_not_detected))
                Text(stringResource(R.string.clients_count, share.clients.size))
            }
        }

        val entry = share.recommendedEntries.firstOrNull { it.protocol == ProxyProtocol.SOCKS5 }
            ?: share.recommendedEntries.firstOrNull()
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.recommended_entry), style = MaterialTheme.typography.titleMedium)
                if (entry == null) {
                    Text(stringResource(R.string.no_entry))
                } else {
                    entry.mdnsEndpoint?.let { Text("SOCKS5 $it") }
                    Text("SOCKS5 ${entry.ipEndpoint}", style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = {
                        clipboard.setText(AnnotatedString(entry.mdnsEndpoint ?: entry.ipEndpoint))
                        Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.copy)) }
                }
            }
        }

        Text(stringResource(R.string.shareable_interfaces), style = MaterialTheme.typography.titleMedium)
        if (share.interfaces.isEmpty()) {
            Text(stringResource(R.string.no_interfaces))
        } else {
            share.interfaces.forEach { iface ->
                val selected = iface.id in ui.settings.selectedInterfaceIds
                Text("${if (selected) "✓ " else "  "}${iface.name}   ${iface.cidr}")
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (share.running) {
                    ProxyForegroundService.stop(context)
                } else {
                    val perms = buildList {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                        if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
                    }
                    if (perms.isEmpty()) ProxyForegroundService.start(context)
                    else permLauncher.launch(perms.toTypedArray())
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (share.running) R.string.stop_sharing else R.string.start_sharing))
        }
    }
}
