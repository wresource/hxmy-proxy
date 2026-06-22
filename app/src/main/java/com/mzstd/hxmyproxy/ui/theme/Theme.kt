package com.mzstd.hxmyproxy.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// hxmy proxy 品牌配色：teal 主色 + sky 副色 + coral 强调（取自启动图标）。
private val LightColorScheme = lightColorScheme(
    primary = BrandTeal,
    onPrimary = Color.White,
    primaryContainer = BrandTealContainer,
    onPrimaryContainer = Color(0xFF00201C),
    secondary = BrandSky,
    onSecondary = Color.White,
    tertiary = Coral,
    onTertiary = Color.White,
    tertiaryContainer = CoralContainer,
    onTertiaryContainer = Color(0xFF3A0B00),
    background = MintSurfaceLight,
    onBackground = InkLight,
    surface = MintSurfaceLight,
    onSurface = InkLight,
    surfaceVariant = MintSurfaceVariantLight,
    onSurfaceVariant = InkVariantLight,
    outline = OutlineLight,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

private val DarkColorScheme = darkColorScheme(
    primary = BrandTealLight,
    onPrimary = Color(0xFF00382F),
    primaryContainer = BrandTealDark,
    onPrimaryContainer = BrandTealContainer,
    secondary = BrandSkyLight,
    onSecondary = Color(0xFF003547),
    tertiary = CoralLight,
    onTertiary = Color(0xFF5A1900),
    tertiaryContainer = CoralDark,
    onTertiaryContainer = CoralContainer,
    background = MintSurfaceDark,
    onBackground = InkDark,
    surface = MintSurfaceDark,
    onSurface = InkDark,
    surfaceVariant = MintSurfaceVariantDark,
    onSurfaceVariant = InkVariantDark,
    outline = OutlineDark,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun HxmyProxyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 默认关掉动态取色：优先展示品牌配色（壁纸取色会盖掉品牌）。需要时可显式开启。
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
