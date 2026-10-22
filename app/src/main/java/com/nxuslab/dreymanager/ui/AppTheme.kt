package com.nxuslab.dreymanager.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val NxusColors = darkColorScheme(
    primary = Color(0xFF8994FF),
    onPrimary = Color(0xFF080B1C),
    primaryContainer = Color(0xFF293577),
    onPrimaryContainer = Color(0xFFE8EAFF),
    secondary = Color(0xFF72E8C4),
    onSecondary = Color(0xFF06231D),
    secondaryContainer = Color(0xFF123D34),
    tertiary = Color(0xFFFFB86B),
    tertiaryContainer = Color(0xFF4B2E0C),
    background = Color(0xFF080B17),
    onBackground = Color(0xFFF5F7FF),
    surface = Color(0xFF12182C),
    surfaceVariant = Color(0xFF1C2643),
    onSurface = Color(0xFFF5F7FF),
    onSurfaceVariant = Color(0xFFC2C8D8),
    outline = Color(0xFF404A62),
    error = Color(0xFFFFB4AB),
)

private val NxusShapes = Shapes(
    extraSmall = RoundedCornerShape(10),
    small = RoundedCornerShape(14),
    medium = RoundedCornerShape(18),
    large = RoundedCornerShape(24),
    extraLarge = RoundedCornerShape(30),
)

private val NxusTypography = Typography().copy(
    headlineMedium = Typography().headlineMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleLarge = Typography().titleLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold),
    titleMedium = Typography().titleMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold),
    bodyLarge = Typography().bodyLarge.copy(fontFamily = FontFamily.SansSerif, lineHeight = 23.sp),
    labelLarge = Typography().labelLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold),
)

@Composable
fun DreyManagerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NxusColors, shapes = NxusShapes, typography = NxusTypography, content = content)
}
