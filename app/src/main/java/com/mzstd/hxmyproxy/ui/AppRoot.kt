package com.mzstd.hxmyproxy.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
import com.mzstd.hxmyproxy.ui.monitor.TopDomainsDetailScreen
import com.mzstd.hxmyproxy.ui.monitor.TrafficStatsScreen
import com.mzstd.hxmyproxy.ui.protection.BlockedDetailScreen
import com.mzstd.hxmyproxy.ui.protection.ProtectionScreen
import com.mzstd.hxmyproxy.ui.rules.RuleLibraryViewModel
import com.mzstd.hxmyproxy.ui.rules.RuleWorkbenchScreen
import com.mzstd.hxmyproxy.ui.rules.RuleManualScreen
import com.mzstd.hxmyproxy.ui.rules.RuleSourceScreen
import com.mzstd.hxmyproxy.ui.rules.RuleSourcesScreen
import com.mzstd.hxmyproxy.ui.rules.RuleImportScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.navArgument
import com.mzstd.hxmyproxy.ui.run.RunScreen
import com.mzstd.hxmyproxy.ui.settings.SettingsScreen

// 大屏断点（Material：compact < 600dp 用底栏，medium/expanded 用侧边 nav rail）。
private const val EXPANDED_WIDTH_DP = 600

// M3 motion token 实值（MotionTokens.kt）：入场用 EmphasizedDecelerate、出场用 EmphasizedAccelerate。
private val EmphasizedDecel = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccel = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

private fun tabIdx(route: String?) = NavTab.entries.indexOfFirst { it.route == route }


@Composable
fun AppRoot(viewModel: MainViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val ruleLibrary: RuleLibraryViewModel = hiltViewModel()
    val openRuleImport: (String?) -> Unit = { target -> ruleLibrary.beginImport(target); navController.navigate("rule_import") }
    val openRuleSource: (String, String) -> Unit = { id, query ->
        navController.navigate("rule_source/${android.net.Uri.encode(id)}?query=${android.net.Uri.encode(query)}")
    }
    val logHost by viewModel.logHostFilter.collectAsStateWithLifecycle()
    val openLogs: (String) -> Unit = { host -> viewModel.logHostFilter.value = host; navController.navigate("logs_detail") }
    val openSettings: (String) -> Unit = { page -> navController.navigate("settings_detail/$page") }
    val openChanges: () -> Unit = { navController.navigate("rule_changes") }

    // 可见 tab 由设置过滤（主页/设置强制保留）；NavHost 仍注册**全部**路由——
    // 隐藏只影响导航栏显示，任何遗留导航都落在已注册 route 上，不存在「未注册 route」崩溃面。
    val destinations = NavTab.visible(ui.settings.hiddenTabs)

    // 自适应：平板/折叠屏（≥600dp）把底部导航换成边缘 nav rail（adaptive skill Step 2），手机仍用底栏。
    val wide = LocalConfiguration.current.screenWidthDp >= EXPANDED_WIDTH_DP
    // 顶层 tab 才显示导航栏；详情页（history/logs/help/ruleset_*）全屏沉浸、由 DetailScaffold 提供返回。
    // current=null（NavHost 初始化瞬间）按顶层处理，避免启动时底栏闪没。topLevel 用全量 NavTab 判断
    // （而非 visible）：防御场景下站在被隐藏 tab 上时仍按顶层布局，等回退生效。
    val currentRoute = navController.currentRoute()
    val topLevel = currentRoute == null || NavTab.entries.any { it.route == currentRoute }

    // 防御性回退：若当前顶层 route 恰好被隐藏（正常流程不可能——隐藏操作只发生在设置页；
    // 该分支兜住 restoreState/深层链接等间接路径），自动回主页而非停留在无高亮的“幽灵页”。
    LaunchedEffect(currentRoute, ui.settings.hiddenTabs) {
        val cur = NavTab.entries.firstOrNull { it.route == currentRoute }
        if (cur != null && !cur.fixed && cur.route in ui.settings.hiddenTabs) {
            navController.switchTo(NavTab.RUN)
        }
    }

    Scaffold(
        bottomBar = { if (!wide && topLevel) BottomNavBar(navController, destinations) },
        // 背景继续 edge-to-edge；顶层内容的可操作视口必须止于导航栏上方。
        // 仅把底栏高度加在滚动内容末尾，会使 BringIntoView/无障碍把控件滚到导航栏后面。
        // 顶部仍交给页面，IME 交给页内 imePadding；详情保留自身的 TopAppBar insets。
        contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime),
    ) { padding ->
      val bottomViewportInset = if (topLevel) padding.calculateBottomPadding() else 0.dp
      val pagePadding = if (topLevel) androidx.compose.foundation.layout.PaddingValues(
          top = padding.calculateTopPadding()
      ) else padding
      Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            if (wide && topLevel) SideNavRail(navController, destinations)
            // 大屏（平板/折叠屏）把内容限宽并居中，避免行宽过长；手机无影响。
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                NavHost(
                    navController = navController,
                    startDestination = NavTab.RUN.route,
                    // 单列内容上限 840dp（M3 布局指南 pane 上限）：Fold 展开/横屏(~800dp)内容全宽,
                    // 消除「视觉边界与滑动边界差很多」的两侧空带;真正的大平板才触发限宽。
                    modifier = Modifier.widthIn(max = 840.dp).fillMaxSize()
                        .padding(bottom = bottomViewportInset)
                        .consumeWindowInsets(androidx.compose.foundation.layout.PaddingValues(bottom = bottomViewportInset)),
                    // 双轨转场——**只用 fade/scale,不用 slideIntoContainer**：
                    //   实测(1.5.6 诊断版)证明:此前的容器相对 slideIntoContainer/slideOutOfContainer 会**杀掉预测性
                    //   返回的跟手预览**(详情页返回只走 slide 分支,去掉全部转场后预览恢复 → slide 即元凶)。官方
                    //   predictive-back 示例正是 `scaleOut(0.9f)`——scale/fade 才是被 seek 的安全形态。故:
                    // • tab↔tab = Material **Fade Through**(顶级目的地无方向关系):出场快淡出、入场淡入+微放大。
                    // • tab↔详情 = Material **Shared Axis Z**(父子层级=前后景缩放):进入子页放大淡入、父页微放大淡出;
                    //   返回反向——子页缩小淡出(这段即预测性返回跟手预览)、父页从略大缩回。全程 scale+fade,跟手可 seek。
                    enterTransition = {
                        val from = tabIdx(initialState.destination.route)
                        val to = tabIdx(targetState.destination.route)
                        if (from >= 0 && to >= 0) {
                            fadeIn(tween(210, delayMillis = 90, easing = EmphasizedDecel)) +
                                scaleIn(tween(210, delayMillis = 90, easing = EmphasizedDecel), initialScale = 0.92f)
                        } else {
                            fadeIn(tween(300, easing = EmphasizedDecel)) +
                                scaleIn(tween(300, easing = EmphasizedDecel), initialScale = 0.80f)
                        }
                    },
                    exitTransition = {
                        val from = tabIdx(initialState.destination.route)
                        val to = tabIdx(targetState.destination.route)
                        if (from >= 0 && to >= 0) {
                            fadeOut(tween(90, easing = EmphasizedAccel))
                        } else {
                            fadeOut(tween(90, easing = EmphasizedAccel)) +
                                scaleOut(tween(300, easing = EmphasizedDecel), targetScale = 1.10f)
                        }
                    },
                    popEnterTransition = {
                        val from = tabIdx(initialState.destination.route)
                        val to = tabIdx(targetState.destination.route)
                        if (from >= 0 && to >= 0) {
                            fadeIn(tween(210, delayMillis = 90, easing = EmphasizedDecel)) +
                                scaleIn(tween(210, delayMillis = 90, easing = EmphasizedDecel), initialScale = 0.92f)
                        } else {
                            fadeIn(tween(300, easing = EmphasizedDecel)) +
                                scaleIn(tween(300, easing = EmphasizedDecel), initialScale = 1.10f)
                        }
                    },
                    popExitTransition = {
                        val from = tabIdx(initialState.destination.route)
                        val to = tabIdx(targetState.destination.route)
                        if (from >= 0 && to >= 0) {
                            fadeOut(tween(90, easing = EmphasizedAccel))
                        } else {
                            // 详情页退出——预测性返回跟手预览的主体：子页缩小淡出露出父页。
                            fadeOut(tween(300, easing = EmphasizedAccel)) +
                                scaleOut(tween(300, easing = EmphasizedAccel), targetScale = 0.80f)
                        }
                    },
                ) {
                    composable(NavTab.RUN.route) {
                        RunScreen(
                            ui, viewModel,
                            // 收编:入口/出口的配置本体已在设置 tab,概览直接切过去(dashboard 详情页仅保留给深链)。
                            onOpenConfig = { navController.switchTo(NavTab.SETTINGS) },
                            onOpenProtection = { navController.navigate("protection") },
                            onOpenHistory = { navController.navigate("history_detail") },
                            onOpenLogs = { openLogs("") },
                            onOpenDomains = { navController.navigate("top_domains") },
                            onOpenTrafficStats = { navController.navigate("traffic_stats") },
                            contentPadding = pagePadding,
                            onOpenAddresses = { navController.navigate("connection_addresses") },
                            onOpenClients = { navController.navigate("clients_detail") },
                            onSettings = openSettings, onRelatedLogs = openLogs, onChanges = openChanges,
                            onHealthDetail = { navController.navigate("health_detail/$it") },
                        )
                    }
                    // 旧 5-tab 时代的顶层页,工作台重构后降级为详情页(入口与出口设置 / 防护)。
                    // 保留注册:概览四格与 restoreState/深层链接都可能落到这里。
                    composable("dashboard") {
                        DashboardScreen(
                            ui, viewModel,
                            onOpenProtection = { navController.navigate("protection") },
                            contentPadding = pagePadding,
                        )
                    }
                    composable("protection") {
                        com.mzstd.hxmyproxy.ui.components.DetailScaffold(stringResource(R.string.protection_title), { navController.popBackStack() }) { inner ->
                            ProtectionScreen(ui, viewModel,
                                onOpenBlockedDetail = { navController.navigate("blocked_detail") },
                                contentPadding = inner)
                        }
                    }
                    composable("blocked_detail") {
                        BlockedDetailScreen(ui, viewModel, onBack = { navController.popBackStack() })
                    }
                    composable(NavTab.RULES.route) {
                        RuleWorkbenchScreen(ui, viewModel, ruleLibrary, openRuleImport, openRuleSource,
                            onManual = { query, action -> navController.navigate("rule_manual?query=${android.net.Uri.encode(query)}&action=${action?.name.orEmpty()}") },
                            onManager = { mode -> navController.navigate("rule_sources/$mode") }, onChanges = openChanges, contentPadding = pagePadding,
                            onOpenRun = { navController.switchTo(NavTab.RUN) }, onLogs = { openLogs("") })
                    }
                    composable("quick_rules_reject") {
                        RuleManualScreen(viewModel, ruleLibrary, { navController.popBackStack() }, openRuleImport, openRuleSource, initialAction = com.mzstd.hxmyproxy.core.rules.RuleAction.REJECT)
                    }
                    composable("quick_rules_direct") {
                        RuleManualScreen(viewModel, ruleLibrary, { navController.popBackStack() }, openRuleImport, openRuleSource, initialAction = com.mzstd.hxmyproxy.core.rules.RuleAction.DIRECT)
                    }
                    composable("monitor") {
                        MonitorScreen(
                            ui,
                            viewModel,
                            onOpenHistory = { navController.navigate("history_detail") },
                            onOpenLogs = { openLogs("") },
                            onOpenDomains = { navController.navigate("top_domains") },
                            onOpenTrafficStats = { navController.navigate("traffic_stats") },
                            contentPadding = pagePadding,
                        )
                    }
                    composable("traffic_stats") {
                        TrafficStatsScreen(onBack = { navController.popBackStack() })
                    }
                    composable("top_domains") {
                        TopDomainsDetailScreen(ui, viewModel, onBack = { navController.popBackStack() }, onOpenLogs = openLogs, onSettings = openSettings, onChanges = openChanges)
                    }
                    composable("history_detail") {
                        HistoryDetailScreen(ui, viewModel, onBack = { navController.popBackStack() })
                    }
                    composable("logs_detail") {
                        LogsDetailScreen(onBack = { navController.popBackStack() }, initialHost = logHost)
                    }
                    composable(NavTab.SETTINGS.route) {
                        SettingsScreen(
                            ui, viewModel,
                            onOpenHelp = { navController.navigate("help") },
                            onReplayOnboarding = viewModel::replayOnboarding,
                            onOpenPage = openSettings,
                            contentPadding = pagePadding,
                        )
                    }
                    composable("connection_addresses") {
                        com.mzstd.hxmyproxy.ui.run.ConnectionAddressesScreen(ui, { navController.popBackStack() }, openSettings, { viewModel.setShowIpv6(true) })
                    }
                    composable("clients_detail") {
                        com.mzstd.hxmyproxy.ui.monitor.ClientsDetailScreen(viewModel) { navController.popBackStack() }
                    }
                    composable("settings_detail/{page}") { entry ->
                        com.mzstd.hxmyproxy.ui.settings.SettingsDetailScreen(entry.arguments?.getString("page") ?: "interfaces", ui, viewModel,
                            { navController.popBackStack() }, openSettings, { navController.navigate("help") }, viewModel::replayOnboarding)
                    }
                    composable("rule_changes") {
                        com.mzstd.hxmyproxy.ui.rules.RecentRuleChangesScreen(viewModel, { navController.popBackStack() },
                            { host -> viewModel.activityQuery.value = com.mzstd.hxmyproxy.ui.monitor.ActivityQuery(text = host); navController.navigate("top_domains") },
                            { navController.navigate("manual_overrides") }, ruleLibrary)
                    }
                    composable("manual_overrides") {
                        RuleManualScreen(viewModel, ruleLibrary, { navController.popBackStack() }, openRuleImport, openRuleSource, overridesOnly = true)
                    }
                    composable("health_detail/{page}") { entry ->
                        val page = entry.arguments?.getString("page")
                        val section = when (page) { "latency" -> com.mzstd.hxmyproxy.ui.monitor.MonitorSection.LATENCY; "link" -> com.mzstd.hxmyproxy.ui.monitor.MonitorSection.LINK; else -> com.mzstd.hxmyproxy.ui.monitor.MonitorSection.CHECKS }
                        val title = when (page) { "latency" -> R.string.monitor_latency; "link" -> R.string.ui_link_quality; else -> R.string.ui_connection_checks }
                        com.mzstd.hxmyproxy.ui.components.DetailScaffold(stringResource(title), { navController.popBackStack() }) { inner ->
                            MonitorScreen(ui, viewModel, { navController.navigate("history_detail") }, { openLogs("") },
                                { navController.navigate("top_domains") }, { navController.navigate("traffic_stats") }, inner, section)
                        }
                    }
                    composable("help") { HelpScreen(onBack = { navController.popBackStack() }) }
                    composable("ruleset_manager") {
                        RuleSourcesScreen(ruleLibrary, { navController.popBackStack() }, openRuleSource, openRuleImport,
                            share = ui.share, onRetryRules = viewModel::retryRules)
                    }
                    composable("ruleset_edit/{kind}/{id}") { entry ->
                        val id = "${entry.arguments?.getString("kind") ?: "user"}:${entry.arguments?.getString("id").orEmpty()}"
                        RuleSourceScreen(id, "", ui, viewModel, ruleLibrary, { navController.popBackStack() }, openRuleImport, openRuleSource, openChanges)
                    }
                    composable("rule_source/{id}?query={query}", arguments = listOf(navArgument("query") { defaultValue = "" })) { entry ->
                        RuleSourceScreen(entry.arguments?.getString("id").orEmpty(), entry.arguments?.getString("query").orEmpty(),
                            ui, viewModel, ruleLibrary, { navController.popBackStack() }, openRuleImport, openRuleSource, openChanges)
                    }
                    composable("rule_sources/{mode}") { entry ->
                        RuleSourcesScreen(ruleLibrary, { navController.popBackStack() }, openRuleSource, openRuleImport, entry.arguments?.getString("mode") ?: "builtin",
                            share = ui.share, onRetryRules = viewModel::retryRules)
                    }
                    composable("rule_manual?query={query}&action={action}", arguments = listOf(navArgument("query") { defaultValue = "" }, navArgument("action") { defaultValue = "" })) { entry ->
                        RuleManualScreen(viewModel, ruleLibrary, { navController.popBackStack() }, openRuleImport, openRuleSource,
                            initialAction = runCatching { com.mzstd.hxmyproxy.core.rules.RuleAction.valueOf(entry.arguments?.getString("action").orEmpty()) }.getOrNull(),
                            initialQuery = entry.arguments?.getString("query").orEmpty())
                    }
                    composable("rule_import") {
                        RuleImportScreen(ui, ruleLibrary, { navController.popBackStack() }, openRuleSource, openChanges)
                    }
                }
            }
        }
        // A(状态栏渐变保护)：仅顶层 tab 页(无 TopAppBar)——内容上滑穿入状态栏后方时,顶部
        // 铺一层「背景色→透明」的薄渐变,时间/电量/信号图标始终有底色托着、清晰不糊(用户报告的
        // 「遮挡」根因)。详情页不叠加——其 DetailScaffold 的 TopAppBar 自带保护(官方:勿叠两层)。
        if (topLevel) StatusBarProtection(Modifier.align(Alignment.TopCenter))
      }
    }
}

/**
 * 状态栏渐变保护（官方 edge-to-edge「system bar protection」标准做法）：一条与页面背景同色、
 * 由不透明渐隐到透明的薄条,盖在状态栏高度区。内容滚到其后方时图标仍清晰;首屏(内容未滚入)时
 * 与背景同色故隐形。不消费触摸。
 */
@Composable
private fun StatusBarProtection(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.surface
    val density = LocalDensity.current
    val heightPx = WindowInsets.statusBars.getTop(density)
    Spacer(
        modifier
            .fillMaxWidth()
            .height(with(density) { (heightPx * 1.2f).toDp() })
            .background(
                Brush.verticalGradient(
                    0f to color,
                    0.7f to color.copy(alpha = 0.8f),
                    1f to Color.Transparent,
                ),
            ),
    )
}

/** 当前选中的目的地路由（顶层 tab 高亮）。 */
@Composable
private fun NavController.currentRoute(): String? =
    currentBackStackEntryAsState().value?.destination?.route

// 官方 bottom-nav 模式：saveState/restoreState 保住各 tab 的滚动位置与展开状态，切 tab 不再丢。
private fun NavController.switchTo(dest: NavTab) = navigate(dest.route) {
    launchSingleTop = true
    restoreState = true
    popUpTo(NavTab.RUN.route) { saveState = true }
}

@Composable
private fun BottomNavBar(navController: NavController, destinations: List<NavTab>) {
    NavigationBar {
        val current = navController.currentRoute()
        destinations.forEach { dest ->
            NavigationBarItem(
                modifier = Modifier.testTag("nav_${dest.route}"),
                selected = current == dest.route,
                onClick = { navController.switchTo(dest) },
                icon = { Icon(painterResource(dest.icon), contentDescription = stringResource(dest.label)) },
                label = { Text(stringResource(dest.label)) },
                // Bento 设计稿(01-dashboard .tab.on/.pill)：选中态 = primaryContainer 蓝 pill 指示 +
                // primary 蓝图标与字标签(替代 M3 默认的 secondaryContainer/onSecondaryContainer)。
                // 未选中沿用默认 onSurfaceVariant。浅/深两套 scheme 的 primary/primaryContainer 均已定义。
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}

@Composable
private fun SideNavRail(navController: NavController, destinations: List<NavTab>) {
    NavigationRail {
        val current = navController.currentRoute()
        // 均匀分散：两端 + 项间都用弹性空隙（近似 SpaceEvenly），横屏/展开态不再挤成一坨。
        Spacer(Modifier.height(16.dp))
        destinations.forEachIndexed { i, dest ->
            if (i > 0) Spacer(Modifier.height(8.dp))
            NavigationRailItem(
                modifier = Modifier.testTag("nav_${dest.route}"),
                selected = current == dest.route,
                onClick = { navController.switchTo(dest) },
                icon = { Icon(painterResource(dest.icon), contentDescription = stringResource(dest.label)) },
                label = { Text(stringResource(dest.label)) },
                // 与底栏同步：选中态 primaryContainer pill + primary 图标/字标签。
                colors = NavigationRailItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
        Spacer(Modifier.weight(1f))
    }
}
