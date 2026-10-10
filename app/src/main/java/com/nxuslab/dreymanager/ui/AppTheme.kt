package com.nxuslab.dreymanager.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NxusColors = darkColorScheme(
    primary = Color(0xFF6E7BFF),
    onPrimary = Color(0xFF080B1C),
    primaryContainer = Color(0xFF202A68),
    onPrimaryContainer = Color(0xFFE8EAFF),
    secondary = Color(0xFF63E6BE),
    onSecondary = Color(0xFF06231D),
    secondaryContainer = Color(0xFF123D34),
    tertiary = Color(0xFFFFB86B),
    tertiaryContainer = Color(0xFF4B2E0C),
    background = Color(0xFF070A16),
    onBackground = Color(0xFFF5F7FF),
    surface = Color(0xFF10162A),
    surfaceVariant = Color(0xFF1B2540),
    onSurface = Color(0xFFF5F7FF),
    onSurfaceVariant = Color(0xFFC2C8D8),
    outline = Color(0xFF404A62),
    error = Color(0xFFFFB4AB),
)

@Composable
fun DreyManagerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NxusColors, content = content)
}
