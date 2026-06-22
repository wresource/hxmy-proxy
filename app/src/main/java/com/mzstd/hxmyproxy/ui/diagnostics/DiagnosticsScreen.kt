package com.mzstd.hxmyproxy.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.ui.MainUiState

@Composable
fun DiagnosticsScreen(ui: MainUiState) {
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
    }
}
