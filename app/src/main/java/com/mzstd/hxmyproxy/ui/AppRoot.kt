package com.mzstd.hxmyproxy.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.ui.dashboard.DashboardScreen
import com.mzstd.hxmyproxy.ui.diagnostics.DiagnosticsScreen
import com.mzstd.hxmyproxy.ui.interfaces.InterfacesScreen
import com.mzstd.hxmyproxy.ui.settings.SettingsScreen

private sealed class Dest(val route: String, val label: Int, val glyph: String) {
    data object Dashboard : Dest("dashboard", R.string.nav_dashboard, "⌂")
    data object Interfaces : Dest("interfaces", R.string.nav_interfaces, "⇄")
    data object Diagnostics : Dest("diagnostics", R.string.nav_diagnostics, "✓")
    data object Settings : Dest("settings", R.string.nav_settings, "⚙")
}

@Composable
fun AppRoot(viewModel: MainViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val destinations = listOf(Dest.Dashboard, Dest.Interfaces, Dest.Diagnostics, Dest.Settings)

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val current = backStackEntry?.destination?.route
                destinations.forEach { dest ->
                    NavigationBarItem(
                        selected = current == dest.route,
                        onClick = {
                            navController.navigate(dest.route) {
                                launchSingleTop = true
                                popUpTo(Dest.Dashboard.route)
                            }
                        },
                        icon = { Text(dest.glyph) },
                        label = { Text(stringResource(dest.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Dest.Dashboard.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Dest.Dashboard.route) { DashboardScreen(ui, viewModel) }
            composable(Dest.Interfaces.route) { InterfacesScreen(ui, viewModel) }
            composable(Dest.Diagnostics.route) { DiagnosticsScreen(ui) }
            composable(Dest.Settings.route) { SettingsScreen(ui, viewModel) }
        }
    }
}
