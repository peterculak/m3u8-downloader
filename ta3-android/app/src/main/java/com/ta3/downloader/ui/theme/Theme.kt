package com.ta3.downloader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF3B82F6),          // Blue
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1D4ED8),
    secondary = Color(0xFF10B981),        // Green for downloaded badge
    onSecondary = Color.White,
    background = Color(0xFF0F172A),       // Slate 900
    surface = Color(0xFF1E293B),          // Slate 800
    surfaceVariant = Color(0xFF334155),   // Slate 700
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFF8FAFC),
    onSurfaceVariant = Color(0xFF94A3B8),
    error = Color(0xFFEF4444),
    outline = Color(0xFF334155)
)

@Composable
fun TA3Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
