package io.github.devasenan134.isaipetti.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand palette: "brass & night".
private val Night = Color(0xFF16131F)
private val Plum = Color(0xFF1F1A2E)
private val Dusk = Color(0xFF2A2340)
private val Brass = Color(0xFFF5B942)
private val DeepBrass = Color(0xFF7A5200)
private val Kumkum = Color(0xFFE8643C)
private val Jasmine = Color(0xFFF6F0E4)
private val Haze = Color(0xFFA99FBF)
private val Cream = Color(0xFFFBF6EC)
private val Ink = Color(0xFF1B1726)

private val DarkColors = darkColorScheme(
    primary = Brass,
    onPrimary = Color(0xFF3F2B00),
    primaryContainer = Color(0xFF5C3F00),
    onPrimaryContainer = Color(0xFFFFDEA6),
    inversePrimary = DeepBrass,
    secondary = Haze,
    onSecondary = Night,
    secondaryContainer = Color(0xFF3B3050),
    onSecondaryContainer = Jasmine,
    tertiary = Kumkum,
    onTertiary = Color(0xFF3A0B00),
    tertiaryContainer = Color(0xFF7A2A12),
    onTertiaryContainer = Color(0xFFFFDBD0),
    background = Night,
    onBackground = Jasmine,
    surface = Night,
    onSurface = Jasmine,
    surfaceVariant = Dusk,
    onSurfaceVariant = Haze,
    surfaceTint = Brass,
    surfaceDim = Night,
    surfaceBright = Color(0xFF3A3152),
    surfaceContainerLowest = Color(0xFF110F18),
    surfaceContainerLow = Color(0xFF1B1727),
    surfaceContainer = Plum,
    surfaceContainerHigh = Dusk,
    surfaceContainerHighest = Color(0xFF332B4A),
    inverseSurface = Jasmine,
    inverseOnSurface = Ink,
    outline = Color(0xFF4A4063),
    outlineVariant = Color(0xFF3A3152),
)

private val LightColors = lightColorScheme(
    primary = DeepBrass,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDEA6),
    onPrimaryContainer = Color(0xFF271900),
    inversePrimary = Brass,
    secondary = Color(0xFF5D5470),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6DDF3),
    onSecondaryContainer = Ink,
    tertiary = Color(0xFFB23A1C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDBD0),
    onTertiaryContainer = Color(0xFF3A0B00),
    background = Cream,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEDE3D2),
    onSurfaceVariant = Color(0xFF5D5470),
    surfaceTint = DeepBrass,
    surfaceDim = Color(0xFFE2D8C6),
    surfaceBright = Cream,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F0E4),
    surfaceContainer = Color(0xFFF3EBDD),
    surfaceContainerHigh = Color(0xFFEDE3D2),
    surfaceContainerHighest = Color(0xFFE7DCC9),
    inverseSurface = Plum,
    inverseOnSurface = Jasmine,
    outline = Color(0xFF8A8199),
    outlineVariant = Color(0xFFD6CCBB),
)

/** Always the Isaipetti brass palette; follows the system light/dark setting. */
@Composable
fun IsaipettiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = IsaipettiTypography,
        content = content,
    )
}
