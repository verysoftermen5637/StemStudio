package com.stemstudio.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val StudioBg = Color(0xFF0A0B0F)
val StudioSurface = Color(0xFF141722)
val StudioSurfaceElevated = Color(0xFF1C2030)
val TerminalBg = Color(0xFF050608)

val NeonCyan = Color(0xFF00F0FF)
val NeonMagenta = Color(0xFFFF2E93)
val NeonLime = Color(0xFF39FF14)
val NeonAmber = Color(0xFFFFB800)
val NeonViolet = Color(0xFFA855F7)
val NeonCoral = Color(0xFFFF6B4A)

private val StudioColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = Color(0xFF001418),
    secondary = NeonMagenta,
    onSecondary = Color.White,
    tertiary = NeonViolet,
    background = StudioBg,
    onBackground = Color(0xFFE6EAF2),
    surface = StudioSurface,
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = StudioSurfaceElevated,
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF2B3147)
)

@Composable
fun StemStudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StudioColorScheme,
        content = content
    )
}
