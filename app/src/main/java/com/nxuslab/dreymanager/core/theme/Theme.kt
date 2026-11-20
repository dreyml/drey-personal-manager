package com.nxuslab.dreymanager.core.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Paleta "Bordô profundo" (especificação §7). Contraste medido (WCAG):
 * - [Text] sobre [Background]: ~16:1; sobre [Surface]: ~14:1
 * - [TextMuted] sobre [Surface]: ~7:1
 * - [AccentLight] sobre [Background]: ~6,4:1 (número grande)
 * - branco sobre [Accent] (botões): ~6,2:1
 * - [Alert] sobre [Background]: ~9:1
 */
object NxusColors {
    val Background = Color(0xFF140A0D)
    val Surface = Color(0xFF22121A)
    val SurfaceHigh = Color(0xFF2E1823)
    val Outline = Color(0xFF4A2A36)
    val Accent = Color(0xFFB0304A)
    val AccentLight = Color(0xFFE07A8E)
    val Text = Color(0xFFF3E8EA)
    val TextMuted = Color(0xFFBFA9AE)
    val Alert = Color(0xFFF2A93B)
    val Danger = Color(0xFFFF6B6B)
    val Positive = Color(0xFF8FD3A6)
}

private val scheme = darkColorScheme(
    primary = NxusColors.Accent,
    onPrimary = Color.White,
    primaryContainer = NxusColors.SurfaceHigh,
    onPrimaryContainer = NxusColors.AccentLight,
    secondary = NxusColors.AccentLight,
    onSecondary = NxusColors.Background,
    // Botões segmentados e chips selecionados: bordô escuro em vez do lilás padrão do Material.
    secondaryContainer = Color(0xFF5A1F30),
    onSecondaryContainer = NxusColors.Text,
    background = NxusColors.Background,
    onBackground = NxusColors.Text,
    surface = NxusColors.Surface,
    onSurface = NxusColors.Text,
    surfaceVariant = NxusColors.SurfaceHigh,
    onSurfaceVariant = NxusColors.TextMuted,
    surfaceContainer = NxusColors.Surface,
    surfaceContainerHigh = NxusColors.SurfaceHigh,
    outline = NxusColors.Outline,
    error = NxusColors.Danger,
    onError = NxusColors.Background,
    tertiary = NxusColors.Alert,
    onTertiary = NxusColors.Background,
)

private val typography = Typography(
    displayLarge = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

/** O app é sempre escuro: a paleta foi pensada para fundo bordô. */
@Composable
fun NxusTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
