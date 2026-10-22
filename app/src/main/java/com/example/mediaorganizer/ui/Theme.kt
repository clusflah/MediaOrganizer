package com.example.mediaorganizer.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

// 柔和的紫色：卡片接近纯白、页面是很浅的紫，靠底色和留白分层，边框用浅灰紫
private val LightColors = lightColorScheme(
    primary = Color(0xFF7562C9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAE4FB),
    onPrimaryContainer = Color(0xFF2A1D66),
    secondary = Color(0xFF5F5A72),
    secondaryContainer = Color(0xFFE8E4F3),
    onSecondaryContainer = Color(0xFF1D1A2C),
    tertiary = Color(0xFF8E4EC6),
    background = Color(0xFFF6F3FC),
    surface = Color(0xFFF6F3FC),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFAFE),
    surfaceContainer = Color(0xFFF1EDF9),
    surfaceContainerHigh = Color(0xFFEBE6F5),
    surfaceContainerHighest = Color(0xFFE5E0F0),
    outlineVariant = Color(0xFFE4E0ED),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFCBBEFF),
    onPrimary = Color(0xFF3A2A86),
    primaryContainer = Color(0xFF52429E),
    onPrimaryContainer = Color(0xFFEAE4FB),
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
        MaterialTheme(colorScheme = colors, shapes = AppShapes, content = content)
    }
}

/** 圆角：输入框、按钮 12dp，卡片 20dp，比默认的更圆润 */
private val AppShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

/** 页面左右边距 */
val PageGutter = 20.dp

/** 输入框：细而浅的边框，长路径、服务器地址仍然看得清边界 */
@Composable
fun appFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
)

/** 白色内容卡片：不用阴影，靠和背景的色差分层 */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.material3.Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = PageGutter - 4.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.large,
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) { Column(Modifier.padding(18.dp), content = content) }
}

/** 区域小标题：「媒体目录」「设置」 */
@Composable
fun SectionTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(bottom = 10.dp)) {
        androidx.compose.material3.Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        if (subtitle != null) androidx.compose.material3.Text(
            subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
