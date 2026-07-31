package com.mzstd.hxmyproxy

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzstd.hxmyproxy.core.model.ThemeMode
import com.mzstd.hxmyproxy.ui.AppRoot
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.locale.ProvideAppLocale
import com.mzstd.hxmyproxy.ui.onboarding.OnboardingScreen
import com.mzstd.hxmyproxy.ui.theme.HxmyProxyTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var contentReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // SplashScreen API：保持启动屏直到首屏关键数据（onboarding 标志）就绪，
        // 消除「图标 → 空白 → 主界面」的跳变。必须在 super.onCreate 之前安装。
        //
        // **必须带上限**：contentReady 要等 DataStore 首读经 Flow 回到 composition，正常几十毫秒，
        // 但这形成了一个环——启动屏用 pre-draw 拦住绘制，而放行条件恰恰要等「绘制驱动的 composition」
        // 读到值。正常情况下 composition 仍会推进所以环能自解；一旦有东西拖住帧（磁盘慢、存储满，
        // 或 Compose 仪器测试接管帧时钟），这个环就闭合成死锁，用户看到的是**永不消失的启动屏**。
        // 实测：MainUiTest 因此整整挂死 7 分钟（截图只有一片 windowSplashScreenBackground 薄荷绿）。
        // 到点就放行：宁可先画一帧空内容（下一帧数据就到），也不无限挂着。
        val splash = installSplashScreen()
        val splashDeadline = android.os.SystemClock.uptimeMillis() + SPLASH_MAX_MS
        splash.setKeepOnScreenCondition {
            !contentReady && android.os.SystemClock.uptimeMillis() < splashDeadline
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 有底部 NavigationBar 时关闭系统对比层，避免三键导航下的半透明遮罩（edge-to-edge skill 要求，SDK 29+）。
        window.isNavigationBarContrastEnforced = false
        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            val ui by viewModel.uiState.collectAsStateWithLifecycle()
            val showOnboarding by viewModel.showOnboarding.collectAsStateWithLifecycle()
            // 首屏关键数据就绪（onboarding 标志由 null 变为 true/false）→ 放行启动屏。
            if (showOnboarding != null) contentReady = true
            // 外观三选项：跟随系统/浅色/深色。app 内切换只触发 recompose（不重建 Activity）。
            val darkTheme = when (ui.settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // 系统栏图标深浅跟随 app 内主题（enableEdgeToEdge 默认只跟系统 uiMode）：
            // 手动选深色而系统在浅色时，若不重设，状态栏图标会保持深色压在深背景上看不见。
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                )
                window.isNavigationBarContrastEnforced = false
                onDispose {}
            }
            ProvideAppLocale(ui.settings.language) {
                HxmyProxyTheme(darkTheme = darkTheme) {
                    when (showOnboarding) {
                        true -> OnboardingScreen(onFinish = viewModel::completeOnboarding)
                        false -> AppRoot(viewModel)
                        null -> {} // 首启标志加载中（极短），先不画避免闪烁
                    }
                }
            }
        }
    }

    private companion object {
        /** 启动屏最长驻留（见 onCreate 里的死锁说明）。DataStore 首读正常几十毫秒，2s 已是数十倍冗余。 */
        const val SPLASH_MAX_MS = 2000L
    }
}
