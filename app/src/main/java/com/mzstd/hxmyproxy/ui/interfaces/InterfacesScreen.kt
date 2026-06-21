package com.mzstd.hxmyproxy.ui.interfaces

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.model.InterfaceType
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel

fun InterfaceType.labelRes(): Int = when (this) {
    InterfaceType.WIFI -> R.string.iface_wifi
    InterfaceType.HOTSPOT -> R.string.iface_hotspot
    InterfaceType.USB -> R.string.iface_usb
    InterfaceType.BLUETOOTH -> R.string.iface_bluetooth
    InterfaceType.ETHERNET -> R.string.iface_ethernet
    InterfaceType.UNKNOWN -> R.string.iface_unknown
}

@Composable
fun InterfacesScreen(ui: MainUiState, viewModel: MainViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.interfaces_hint), style = MaterialTheme.typography.bodyMedium)
        if (ui.share.interfaces.isEmpty()) {
            Text(stringResource(R.string.no_interfaces))
        }
        ui.share.interfaces.forEach { iface ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${stringResource(iface.type.labelRes())} · ${iface.name}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(iface.cidr, style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(
                        checked = iface.isSelected,
                        onCheckedChange = { viewModel.toggleInterface(iface.id, it) },
                    )
                }
            }
        }
    }
}
