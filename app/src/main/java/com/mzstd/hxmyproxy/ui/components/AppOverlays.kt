package com.mzstd.hxmyproxy.ui.components

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** Android popup/dialog windows install their own platform locals. Preserve app overrides
 * without changing the established, navigation-preserving ProvideAppLocale implementation. */
internal class AppWindowEnvironment(val context: Context, val configuration: Configuration, val density: Density) {
    @Composable fun Provide(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration, LocalDensity provides density, content = content)
    }
}

@Composable
internal fun rememberAppWindowEnvironment(): AppWindowEnvironment {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    return remember(context, configuration, density) { AppWindowEnvironment(context, configuration, density) }
}

@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val environment = rememberAppWindowEnvironment()
    AlertDialog(onDismissRequest = onDismissRequest, modifier = modifier,
        confirmButton = { environment.Provide(confirmButton) },
        dismissButton = dismissButton?.let { slot -> @Composable { environment.Provide(slot) } },
        title = title?.let { slot -> @Composable { environment.Provide(slot) } },
        text = text?.let { slot -> @Composable { environment.Provide(slot) } })
}

@Composable
fun AppDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    val environment = rememberAppWindowEnvironment()
    DropdownMenu(expanded, onDismissRequest, modifier) {
        val scope = this
        environment.Provide { content(scope) }
    }
}
