package com.nxuslab.dreymanager.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DreyColors = darkColorScheme(
    primary = Color(0xFF9A8CFF),
    onPrimary = Color(0xFF19122E),
    primaryContainer = Color(0xFF302657),
    onPrimaryContainer = Color(0xFFE7E0FF),
    secondary = Color(0xFF53D4B7),
    onSecondary = Color(0xFF06231D),
    secondaryContainer = Color(0xFF123D34),
    tertiary = Color(0xFFFFB86B),
    tertiaryContainer = Color(0xFF4B2E0C),
    background = Color(0xFF0B1020),
    onBackground = Color(0xFFF3F1FF),
    surface = Color(0xFF131A2C),
    surfaceVariant = Color(0xFF20293D),
    onSurface = Color(0xFFF3F1FF),
    onSurfaceVariant = Color(0xFFC2C8D8),
    outline = Color(0xFF404A62),
    error = Color(0xFFFFB4AB),
)

@Composable
fun DreyManagerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DreyColors, content = content)
}
