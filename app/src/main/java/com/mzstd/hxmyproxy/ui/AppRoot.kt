package com.mzstd.hxmyproxy.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
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
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
import com.mzstd.hxmyproxy.ui.rules.RuleSetEditScreen
import com.mzstd.hxmyproxy.ui.rules.RuleSetManagerScreen
import com.mzstd.hxmyproxy.ui.rules.RulesScreen
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
            navController.switchTo(NavTab.DASHBOARD)
        }
    }

    Scaffold(
        bottomBar = { if (!wide && topLevel) BottomNavBar(navController, destinations) },
        // 沉浸式（官方 edge-to-edge 完整形态）：inset **不在这里变成硬 padding**——
        // padding 原样传给各 tab 页作滚动容器的 contentPadding：首屏不被状态栏/底栏遮挡,
        // 滚动时内容穿入状态栏/手势条**后方**（内容与系统栏融合,而非被切断）。
        // IME 仍排除,交给页内 imePadding。详情页不接收 padding——DetailScaffold 自带
        // TopAppBar insets,外层不再消费后其背景自动延伸进状态栏（顶栏融合）。
        contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime),
    ) { padding ->
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
                    startDestination = NavTab.DASHBOARD.route,
                    // 单列内容上限 840dp（M3 布局指南 pane 上限）：Fold 展开/横屏(~800dp)内容全宽,
                    // 消除「视觉边界与滑动边界差很多」的两侧空带;真正的大平板才触发限宽。
                    modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
                    // 双轨转场：
                    // tab↔tab = fade through（官方规范,顶级目的地不建立方向关系）;
                    // tab→详情 = 整屏 push/pop（系统 Activity 转场同款）：新页**不透明**从右整屏滑入
                    //   盖住旧页,旧页左移 1/4 视差;返回反向。全程无透明度动画——之前的
                    //   「30dp 微滑+渐变」在整页场景会出现旧页已淡完、新页未显形的空白闪帧(用户实测糟糕)。
                    //   AnimatedContent 默认把 target 绘制在上层:push=详情盖入,pop=父页盖回,对称无穿帮。
                    enterTransition = {
                        val from = tabIdx(initialState.destination.route)
                        val to = tabIdx(targetState.destination.route)
                        if (from >= 0 && to >= 0) {
                            fadeIn(tween(210, delayMillis = 90, easing = EmphasizedDecel)) +
                                scaleIn(tween(210, delayMillis = 90, easing = EmphasizedDecel), initialScale = 0.92f)
                        } else {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Start,
                                tween(350, easing = EmphasizedDecel),
                            )
                        }
                    },
                    exitTransition = {
                        val from = tabIdx(initialState.destination.route)
                        val to = tabIdx(targetState.destination.route)
                        if (from >= 0 && to >= 0) {
                            fadeOut(tween(90, easing = EmphasizedAccel))
                        } else {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Start,
                                tween(350, easing = EmphasizedDecel),
                            ) { it / 4 }
                        }
                    },
                    // pop 也判断 tab：switchTo 用 popUpTo(主页),tab 切换时旧 tab 走 popExit,
                    // 必须与 enter/exit 对称否则「反方向弹一下」。
                    popEnterTransition = {
                        val from = tabIdx(initialState.destination.route)
                        val to = tabIdx(targetState.destination.route)
                        if (from >= 0 && to >= 0) {
                            fadeIn(tween(210, delayMillis = 90, easing = EmphasizedDecel)) +
                                scaleIn(tween(210, delayMillis = 90, easing = EmphasizedDecel), initialScale = 0.92f)
                        } else {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.End,
                                tween(350, easing = EmphasizedDecel),
                            ) { it / 4 }
                        }
                    },
                    popExitTransition = {
                        val from = tabIdx(initialState.destination.route)
                        val to = tabIdx(targetState.destination.route)
                        if (from >= 0 && to >= 0) {
                            fadeOut(tween(90, easing = EmphasizedAccel))
                        } else {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.End,
                                tween(350, easing = EmphasizedDecel),
                            )
                        }
                    },
                ) {
                    composable(NavTab.DASHBOARD.route) { DashboardScreen(ui, viewModel, padding) }
                    composable(NavTab.RULES.route) {
                        RulesScreen(ui, viewModel, onManage = { navController.navigate("ruleset_manager") }, contentPadding = padding)
                    }
                    composable(NavTab.MONITOR.route) {
                        MonitorScreen(
                            ui,
                            onOpenHistory = { navController.navigate("history_detail") },
                            onOpenLogs = { navController.navigate("logs_detail") },
                            contentPadding = padding,
                        )
                    }
                    composable("history_detail") {
                        HistoryDetailScreen(ui, viewModel, onBack = { navController.popBackStack() })
                    }
                    composable("logs_detail") {
                        LogsDetailScreen(onBack = { navController.popBackStack() })
                    }
                    composable(NavTab.SETTINGS.route) {
                        SettingsScreen(
                            ui, viewModel,
                            onOpenHelp = { navController.navigate("help") },
                            onReplayOnboarding = viewModel::replayOnboarding,
                            contentPadding = padding,
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
    popUpTo(NavTab.DASHBOARD.route) { saveState = true }
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
            )
        }
    }
}

@Composable
private fun SideNavRail(navController: NavController, destinations: List<NavTab>) {
    NavigationRail {
        val current = navController.currentRoute()
        // 均匀分散：两端 + 项间都用弹性空隙（近似 SpaceEvenly），横屏/展开态不再挤成一坨。
        Spacer(Modifier.weight(1f))
        destinations.forEachIndexed { i, dest ->
            if (i > 0) Spacer(Modifier.weight(0.6f))
            NavigationRailItem(
                modifier = Modifier.testTag("nav_${dest.route}"),
                selected = current == dest.route,
                onClick = { navController.switchTo(dest) },
                icon = { Icon(painterResource(dest.icon), contentDescription = stringResource(dest.label)) },
                label = { Text(stringResource(dest.label)) },
            )
        }
        Spacer(Modifier.weight(1f))
    }
}
