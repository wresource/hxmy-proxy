package com.mzstd.hxmyproxy.ui.monitor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel

@Composable
internal fun ClientRouteControl(ip: String, ui: MainUiState, viewModel: MainViewModel) {
    var saving by remember(ip) { mutableStateOf(false) }
    var failed by remember(ip) { mutableStateOf(false) }
    val selected = ip in ui.settings.directClientIps
    val applying = saving || (ui.share.running && selected != (ip in ui.share.directClientIps))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.client_all_direct), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(selected, { enabled ->
                saving = true; failed = false
                viewModel.setClientAllDirect(ip, enabled) { result -> saving = false; failed = result.isFailure }
            }, enabled = !applying, modifier = Modifier.testTag("client_direct_$ip"))
        }
        Text(stringResource(when {
            failed -> R.string.ui_save_failed
            applying -> R.string.client_route_applying
            !ui.share.running -> R.string.client_route_next_session
            selected -> R.string.client_route_physical
            else -> R.string.client_route_rules
        }), style = MaterialTheme.typography.bodySmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
