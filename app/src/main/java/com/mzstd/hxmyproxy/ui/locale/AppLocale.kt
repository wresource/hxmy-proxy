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
    val baseConfig = LocalConfiguration.current
    val baseContext = LocalContext.current
    // 关键：无论是否 SYSTEM，结构都恒定地走 CompositionLocalProvider 包裹 content()。
    // 若按 locale==null 提前 return（直接调 content()），SYSTEM↔具体语言切换会跨越
    // 调用点结构边界，导致 content() 子树（含 NavController/NavHost）整体重建并重置到起始页
    // ——表现为“在设置页改语言被弹回主页”。恒定结构可避免该重挂载。
    val localizedConfig = remember(locale, baseConfig) {
        if (locale == null) baseConfig else Configuration(baseConfig).apply { setLocale(locale) }
    }
    // 保持 baseContext（Activity）作为 ContextWrapper 的 base，仅覆盖 resources，
    // 这样 findActivity()/ActivityResultRegistryOwner 等 Activity 作用域查找仍可用。
    val localizedContext = remember(locale, baseContext, localizedConfig) {
        if (locale == null) {
            baseContext
        } else {
            object : ContextWrapper(baseContext) {
                private val localizedResources: Resources =
                    baseContext.createConfigurationContext(localizedConfig).resources

                override fun getResources(): Resources = localizedResources
            }
        }
    }
    CompositionLocalProvider(
        LocalConfiguration provides localizedConfig,
        LocalContext provides localizedContext,
    ) {
        content()
    }
}
