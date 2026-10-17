package com.example.mediaorganizer.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.mediaorganizer.settings.ThemeMode

// 主色和图标一致：靛蓝 → 紫
private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E0FF),
    onPrimaryContainer = Color(0xFF14105E),
    secondary = Color(0xFF5C5D72),
    secondaryContainer = Color(0xFFE1E0F9),
    onSecondaryContainer = Color(0xFF191A2C),
    tertiary = Color(0xFF7C3AED),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBDC2FF),
    onPrimary = Color(0xFF1F1A8A),
    primaryContainer = Color(0xFF3730A3),
    onPrimaryContainer = Color(0xFFE0E0FF),
    secondary = Color(0xFFC5C4DD),
    secondaryContainer = Color(0xFF444559),
    onSecondaryContainer = Color(0xFFE1E0F9),
    tertiary = Color(0xFFD2BBFF),
    background = Color(0xFF121318),
    surface = Color(0xFF121318),
    surfaceContainerLowest = Color(0xFF0D0E13),
    surfaceContainerLow = Color(0xFF1A1B21),
    surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF292A2F),
    surfaceContainerHighest = Color(0xFF34343A),
)

/** 状态色（成功/警告/失败等）：深色模式下换成更亮的色调，保证对比度 */
@Immutable
data class StatusColors(
    val ok: Color,
    val warn: Color,
    val bad: Color,
    val muted: Color,
    val accent: Color,
    val recommend: Color,
)

private val LightStatus = StatusColors(
    ok = Color(0xFF2E7D32), warn = Color(0xFFEF6C00), bad = Color(0xFFC62828),
    muted = Color(0xFF757575), accent = Color(0xFFFF7A00), recommend = Color(0xFF1FA463),
)

private val DarkStatus = StatusColors(
    ok = Color(0xFF81C784), warn = Color(0xFFFFB74D), bad = Color(0xFFEF9A9A),
    muted = Color(0xFF9E9E9E), accent = Color(0xFFFF9E40), recommend = Color(0xFF5CD08E),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

@Composable
fun AppTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as Activity).window
        // Android 15+ 强制沉浸，栏颜色无效，只需切换图标深浅；旧版本顺便把栏涂成背景色
        @Suppress("DEPRECATION")
        window.statusBarColor = colors.surface.toArgb()
        @Suppress("DEPRECATION")
        window.navigationBarColor = colors.surface.toArgb()
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
