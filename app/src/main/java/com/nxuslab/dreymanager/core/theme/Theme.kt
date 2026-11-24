package com.nxuslab.dreymanager.core.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Paleta "Pastel calmo" (NxusLife 3.0). Fundo névoa, cartões brancos e acentos pastel; textos
 * usam a versão escura de cada tom. Contraste medido (WCAG), sobre branco:
 * - [Text] ~14:1, [TextMuted] ~6,3:1, [AccentLight] ~8:1
 * - branco sobre [Accent] (botões): ~5,6:1
 * - [Alert] ~6,6:1, [Danger] ~5,5:1, [Positive] ~5,3:1
 */
object NxusColors {
    val Background = Color(0xFFF4F2F8)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceHigh = Color(0xFFEFEBF7)
    val Outline = Color(0xFFDCD7E6)
    val Accent = Color(0xFF6B5BB8)
    val AccentLight = Color(0xFF54458F)
    val Text = Color(0xFF2D2A38)
    val TextMuted = Color(0xFF615C70)
    val Alert = Color(0xFF8E4A2C)
    val Danger = Color(0xFFB0413E)
    val Positive = Color(0xFF2F7A55)

    // Tons pastel para cartões e destaques (texto por cima: a versão escura correspondente).
    val Lavender = Color(0xFFDED6F5)
    val Sage = Color(0xFFCFE5D6)
    val SageDark = Color(0xFF2F6B4C)
    val Peach = Color(0xFFFADBCB)
    val Sky = Color(0xFFD4E6F5)
    val SkyDark = Color(0xFF2C5A80)
    val Ring = Color(0xFF9FCFB2)
}

private val scheme = lightColorScheme(
    primary = NxusColors.Accent,
    onPrimary = Color.White,
    primaryContainer = NxusColors.Lavender,
    onPrimaryContainer = NxusColors.AccentLight,
    secondary = NxusColors.AccentLight,
    onSecondary = Color.White,
    // Botões segmentados e chips selecionados: lavanda clara.
    secondaryContainer = NxusColors.Lavender,
    onSecondaryContainer = NxusColors.AccentLight,
    background = NxusColors.Background,
    onBackground = NxusColors.Text,
    surface = NxusColors.Surface,
    onSurface = NxusColors.Text,
    surfaceVariant = NxusColors.SurfaceHigh,
    onSurfaceVariant = NxusColors.TextMuted,
    surfaceContainer = NxusColors.Surface,
    surfaceContainerHigh = NxusColors.Surface,
    surfaceContainerHighest = NxusColors.SurfaceHigh,
    surfaceContainerLow = NxusColors.Background,
    outline = NxusColors.Outline,
    outlineVariant = NxusColors.Outline,
    error = NxusColors.Danger,
    onError = Color.White,
    tertiary = NxusColors.Alert,
    onTertiary = Color.White,
)

private val typography = Typography(
    displayLarge = TextStyle(fontSize = 52.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
)

/** Tema claro pastel: o app inteiro usa esta paleta. */
@Composable
fun NxusTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
