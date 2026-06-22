package com.mzstd.hxmyproxy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzstd.hxmyproxy.ui.AppRoot
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.locale.ProvideAppLocale
import com.mzstd.hxmyproxy.ui.theme.HxmyProxyTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 有底部 NavigationBar 时关闭系统对比层，避免三键导航下的半透明遮罩（edge-to-edge skill 要求，SDK 29+）。
        window.isNavigationBarContrastEnforced = false
        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            val ui by viewModel.uiState.collectAsStateWithLifecycle()
            ProvideAppLocale(ui.settings.language) {
                HxmyProxyTheme {
                    AppRoot(viewModel)
                }
            }
        }
    }
}
