package com.mzstd.hxmyproxy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { !contentReady }
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
            ProvideAppLocale(ui.settings.language) {
                HxmyProxyTheme {
                    when (showOnboarding) {
                        true -> OnboardingScreen(onFinish = viewModel::completeOnboarding)
                        false -> AppRoot(viewModel)
                        null -> {} // 首启标志加载中（极短），先不画避免闪烁
                    }
                }
            }
        }
    }
}
