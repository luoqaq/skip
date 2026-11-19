package com.luo.skip.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 严格对齐 PRD 2.1 节 Design Tokens。
 * 浅色：多层柔光阴影立层级；深色：去阴影，用表面色差立层级。
 */
@Immutable
data class SkipColors(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val accent: Color,
    val accentInk: Color,
    val accentSoft: Color,
    val warn: Color,
    val warnSoft: Color,
    val line: Color,
)

private val LightColors = SkipColors(
    isDark = false,
    bg = Color(0xFFF4F5F3),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFECEEEB),
    ink = Color(0xFF1B1B19),
    ink2 = Color(0xFF6B6A64),
    ink3 = Color(0xFFA29F97),
    accent = Color(0xFF12A06B),
    accentInk = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFE1F4EC),
    warn = Color(0xFFC2410C),
    warnSoft = Color(0xFFFBE9DD),
    line = Color(0x121B1B19), // rgba(27,27,25,.07)
)

private val DarkColors = SkipColors(
    isDark = true,
    bg = Color(0xFF121311),
    surface = Color(0xFF1C1D1B),
    surface2 = Color(0xFF262724),
    ink = Color(0xFFEDEBE6),
    ink2 = Color(0xFFA3A19B),
    ink3 = Color(0xFF6E6C66),
    accent = Color(0xFF3DCB92),
    accentInk = Color(0xFF0E1A15),
    accentSoft = Color(0x243DCB92), // rgba(61,203,146,.14)
    warn = Color(0xFFF08A5D),
    warnSoft = Color(0x24F08A5D), // rgba(240,138,93,.14)
    line = Color(0x0FFFFFFF), // rgba(255,255,255,.06)
)

val LocalSkipColors = staticCompositionLocalOf { LightColors }

private val LightScheme: ColorScheme = lightColorScheme(
    primary = LightColors.accent, onPrimary = LightColors.accentInk,
    background = LightColors.bg, onBackground = LightColors.ink,
    surface = LightColors.surface, onSurface = LightColors.ink,
    surfaceVariant = LightColors.surface2, onSurfaceVariant = LightColors.ink2,
    error = LightColors.warn, errorContainer = LightColors.warnSoft,
    outline = LightColors.ink3,
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = DarkColors.accent, onPrimary = DarkColors.accentInk,
    background = DarkColors.bg, onBackground = DarkColors.ink,
    surface = DarkColors.surface, onSurface = DarkColors.ink,
    surfaceVariant = DarkColors.surface2, onSurfaceVariant = DarkColors.ink2,
    error = DarkColors.warn, errorContainer = DarkColors.warnSoft,
    outline = DarkColors.ink3,
)

@Composable
fun SkipTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (dark) DarkColors else LightColors
    val scheme = if (dark) DarkScheme else LightScheme
    CompositionLocalProvider(LocalSkipColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            content = content,
        )
    }
}
