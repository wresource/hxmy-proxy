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
