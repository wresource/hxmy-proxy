package com.mzstd.hxmyproxy.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.ui.dashboard.DashboardScreen
import com.mzstd.hxmyproxy.ui.help.HelpScreen
import com.mzstd.hxmyproxy.ui.monitor.HistoryDetailScreen
import com.mzstd.hxmyproxy.ui.monitor.LogsDetailScreen
import com.mzstd.hxmyproxy.ui.monitor.MonitorScreen
import com.mzstd.hxmyproxy.ui.rules.RuleSetEditScreen
import com.mzstd.hxmyproxy.ui.rules.RuleSetManagerScreen
import com.mzstd.hxmyproxy.ui.rules.RulesScreen
import com.mzstd.hxmyproxy.ui.settings.SettingsScreen

private sealed class Dest(val route: String, val label: Int, val icon: Int) {
    data object Dashboard : Dest("dashboard", R.string.nav_dashboard, R.drawable.ic_nav_dashboard)
    data object Rules : Dest("rules", R.string.nav_rules, R.drawable.ic_nav_rules)
    data object Monitor : Dest("monitor", R.string.nav_monitor, R.drawable.ic_nav_monitor)
    data object Settings : Dest("settings", R.string.nav_settings, R.drawable.ic_nav_settings)
}

// 大屏断点（Material：compact < 600dp 用底栏，medium/expanded 用侧边 nav rail）。
private const val EXPANDED_WIDTH_DP = 600

@Composable
fun AppRoot(viewModel: MainViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val destinations = listOf(Dest.Dashboard, Dest.Monitor, Dest.Rules, Dest.Settings)

    // 自适应：平板/折叠屏（≥600dp）把底部导航换成边缘 nav rail（adaptive skill Step 2），手机仍用底栏。
    val wide = LocalConfiguration.current.screenWidthDp >= EXPANDED_WIDTH_DP

    Scaffold(
        bottomBar = { if (!wide) BottomNavBar(navController, destinations) },
        // 系统栏/刘海由 Scaffold 处理；IME 交给各内容页自己的 imePadding（在 verticalScroll 外层）：
        // 这样键盘弹出时滚动视口收缩、自动把聚焦的输入框滚到键盘上方。
        // （若这里 safeDrawing 含 IME，innerPadding 会 consume 掉 IME，页内 imePadding 就失效。）
        contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime),
    ) { padding ->
        // padding 只施于内容侧：让 NavigationRail 占满全高、由其自身 insets 绘制到屏幕边缘（edge-to-edge）。
        Row(Modifier.fillMaxSize()) {
            if (wide) SideNavRail(navController, destinations)
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
                    composable(Dest.Rules.route) {
                        RulesScreen(ui, viewModel, onManage = { navController.navigate("ruleset_manager") })
                    }
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
                    composable(Dest.Settings.route) {
                        SettingsScreen(
                            ui, viewModel,
                            onOpenHelp = { navController.navigate("help") },
                            onReplayOnboarding = viewModel::replayOnboarding,
                        )
                    }
                    composable("help") { HelpScreen(onBack = { navController.popBackStack() }) }
                    composable("ruleset_manager") {
                        RuleSetManagerScreen(
                            ui, viewModel,
                            onBack = { navController.popBackStack() },
                            onEdit = { kind, id -> navController.navigate("ruleset_edit/$kind/$id") },
                        )
                    }
                    composable("ruleset_edit/{kind}/{id}") { entry ->
                        RuleSetEditScreen(
                            kind = entry.arguments?.getString("kind") ?: "user",
                            id = entry.arguments?.getString("id") ?: "",
                            ui = ui, viewModel = viewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}

/** 当前选中的目的地路由（顶层 tab 高亮）。 */
@Composable
private fun NavController.currentRoute(): String? =
    currentBackStackEntryAsState().value?.destination?.route

private fun NavController.switchTo(dest: Dest) = navigate(dest.route) {
    launchSingleTop = true
    popUpTo(Dest.Dashboard.route)
}

@Composable
private fun BottomNavBar(navController: NavController, destinations: List<Dest>) {
    NavigationBar {
        val current = navController.currentRoute()
        destinations.forEach { dest ->
            NavigationBarItem(
                modifier = Modifier.testTag("nav_${dest.route}"),
                selected = current == dest.route,
                onClick = { navController.switchTo(dest) },
                icon = { Icon(painterResource(dest.icon), contentDescription = stringResource(dest.label)) },
                label = { Text(stringResource(dest.label)) },
            )
        }
    }
}

@Composable
private fun SideNavRail(navController: NavController, destinations: List<Dest>) {
    NavigationRail {
        val current = navController.currentRoute()
        destinations.forEach { dest ->
            NavigationRailItem(
                modifier = Modifier.testTag("nav_${dest.route}"),
                selected = current == dest.route,
                onClick = { navController.switchTo(dest) },
                icon = { Icon(painterResource(dest.icon), contentDescription = stringResource(dest.label)) },
                label = { Text(stringResource(dest.label)) },
            )
        }
    }
}
