package com.mzstd.hxmyproxy.ui.locale

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.mzstd.hxmyproxy.core.model.AppLanguage
import java.util.Locale

fun AppLanguage.toLocale(): Locale? = when (this) {
    AppLanguage.SYSTEM -> null
    AppLanguage.ENGLISH -> Locale.ENGLISH
    AppLanguage.CHINESE -> Locale.SIMPLIFIED_CHINESE
}

/** 返回应用了所选语言的 Context（SYSTEM 时原样返回）。供 Service/通知取本地化字符串。 */
fun Context.localizedFor(language: AppLanguage): Context {
    val locale = language.toLocale() ?: return this
    val config = Configuration(resources.configuration).apply { setLocale(locale) }
    return createConfigurationContext(config)
}

/** 在 Compose 树中覆盖 Locale，使 stringResource 即时随所选语言切换（无需重启 Activity）。 */
@Composable
fun ProvideAppLocale(language: AppLanguage, content: @Composable () -> Unit) {
    val locale = language.toLocale()
    if (locale == null) {
        content()
        return
    }
    val baseConfig = LocalConfiguration.current
    val baseContext = LocalContext.current
    val localizedConfig = remember(locale, baseConfig) {
        Configuration(baseConfig).apply { setLocale(locale) }
    }
    // 关键：保持 baseContext（Activity）作为 ContextWrapper 的 base，仅覆盖 resources，
    // 这样 findActivity()/ActivityResultRegistryOwner 等 Activity 作用域查找仍可用。
    val localizedContext = remember(locale, baseContext, localizedConfig) {
        object : ContextWrapper(baseContext) {
            private val localizedResources: Resources =
                baseContext.createConfigurationContext(localizedConfig).resources

            override fun getResources(): Resources = localizedResources
        }
    }
    CompositionLocalProvider(
        LocalConfiguration provides localizedConfig,
        LocalContext provides localizedContext,
    ) {
        content()
    }
}
