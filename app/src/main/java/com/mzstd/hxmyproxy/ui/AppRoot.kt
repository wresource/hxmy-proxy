package com.mzstd.hxmyproxy.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.ui.dashboard.DashboardScreen
import com.mzstd.hxmyproxy.ui.diagnostics.DiagnosticsScreen
import com.mzstd.hxmyproxy.ui.interfaces.InterfacesScreen
import com.mzstd.hxmyproxy.ui.monitor.HistoryDetailScreen
import com.mzstd.hxmyproxy.ui.monitor.LogsDetailScreen
import com.mzstd.hxmyproxy.ui.monitor.MonitorScreen
import com.mzstd.hxmyproxy.ui.settings.SettingsScreen

private sealed class Dest(val route: String, val label: Int, val icon: Int) {
    data object Dashboard : Dest("dashboard", R.string.nav_dashboard, R.drawable.ic_nav_dashboard)
    data object Interfaces : Dest("interfaces", R.string.nav_interfaces, R.drawable.ic_nav_interfaces)
    data object Diagnostics : Dest("diagnostics", R.string.nav_diagnostics, R.drawable.ic_nav_diagnostics)
    data object Monitor : Dest("monitor", R.string.nav_monitor, R.drawable.ic_nav_monitor)
    data object Settings : Dest("settings", R.string.nav_settings, R.drawable.ic_nav_settings)
}

@Composable
fun AppRoot(viewModel: MainViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val destinations = listOf(Dest.Dashboard, Dest.Interfaces, Dest.Diagnostics, Dest.Monitor, Dest.Settings)

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val current = backStackEntry?.destination?.route
                destinations.forEach { dest ->
                    NavigationBarItem(
                        modifier = Modifier.testTag("nav_${dest.route}"),
                        selected = current == dest.route,
                        onClick = {
                            navController.navigate(dest.route) {
                                launchSingleTop = true
                                popUpTo(Dest.Dashboard.route)
                            }
                        },
                        icon = { Icon(painterResource(dest.icon), contentDescription = stringResource(dest.label)) },
                        label = { Text(stringResource(dest.label)) },
                    )
                }
            }
        },
        // safeDrawing 让 innerPadding 携带 IME 插入：键盘弹出时内容区收缩，端口输入框不被遮挡。
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        // 大屏（平板/折叠屏）把内容限宽并居中，避免行宽过长；手机（<640dp）无影响。
        Box(
            Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            NavHost(
                navController = navController,
                startDestination = Dest.Dashboard.route,
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
            ) {
            composable(Dest.Dashboard.route) { DashboardScreen(ui, viewModel) }
            composable(Dest.Interfaces.route) { InterfacesScreen(ui, viewModel) }
            composable(Dest.Diagnostics.route) { DiagnosticsScreen(ui) }
            composable(Dest.Monitor.route) {
                MonitorScreen(
                    ui,
                    onOpenHistory = { navController.navigate("history_detail") },
                    onOpenLogs = { navController.navigate("logs_detail") },
                )
            }
            composable("history_detail") {
                HistoryDetailScreen(ui, viewModel, onBack = { navController.popBackStack() })
            }
            composable("logs_detail") {
                LogsDetailScreen(onBack = { navController.popBackStack() })
            }
            composable(Dest.Settings.route) { SettingsScreen(ui, viewModel) }
            }
        }
    }
}
