package io.github.devasenan134.isaipetti.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand palette: "Indigo night" — deep indigo with a periwinkle accent, bright cool text.
private val Night = Color(0xFF0E1024)
private val Deep = Color(0xFF171A36)
private val Dusk = Color(0xFF22264A)
private val Periwinkle = Color(0xFF8FA0FF)
private val Indigo = Color(0xFF3949AB)
private val Moon = Color(0xFFF7F7FF)
private val Mist = Color(0xFFC3C6E6)
private val Paper = Color(0xFFFAFAFF)
private val Ink = Color(0xFF121433)

private val DarkColors = darkColorScheme(
    primary = Periwinkle,
    onPrimary = Color(0xFF0B1260),
    primaryContainer = Color(0xFF303A8C),
    onPrimaryContainer = Color(0xFFDCE1FF),
    inversePrimary = Indigo,
    secondary = Mist,
    onSecondary = Night,
    secondaryContainer = Color(0xFF2E3360),
    onSecondaryContainer = Moon,
    tertiary = Color(0xFFFFB4D0),
    onTertiary = Color(0xFF4A0A2C),
    tertiaryContainer = Color(0xFF6B2748),
    onTertiaryContainer = Color(0xFFFFD9E5),
    background = Night,
    onBackground = Moon,
    surface = Night,
    onSurface = Moon,
    surfaceVariant = Dusk,
    onSurfaceVariant = Mist,
    surfaceTint = Periwinkle,
    surfaceDim = Night,
    surfaceBright = Color(0xFF30355E),
    surfaceContainerLowest = Color(0xFF0A0B1C),
    surfaceContainerLow = Color(0xFF13152D),
    surfaceContainer = Deep,
    surfaceContainerHigh = Dusk,
    surfaceContainerHighest = Color(0xFF2B3057),
    inverseSurface = Moon,
    inverseOnSurface = Ink,
    outline = Color(0xFF4A4F82),
    outlineVariant = Color(0xFF2F3460),
)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE1FF),
    onPrimaryContainer = Color(0xFF0B1260),
    inversePrimary = Periwinkle,
    secondary = Color(0xFF4B4F72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1E3F5),
    onSecondaryContainer = Ink,
    tertiary = Color(0xFF8E3A5E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD9E5),
    onTertiaryContainer = Color(0xFF3A0620),
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE1E3F5),
    onSurfaceVariant = Color(0xFF4B4F72),
    surfaceTint = Indigo,
    surfaceDim = Color(0xFFD9DBEE),
    surfaceBright = Paper,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F4FD),
    surfaceContainer = Color(0xFFEEEFFB),
    surfaceContainerHigh = Color(0xFFE7E8F7),
    surfaceContainerHighest = Color(0xFFE1E3F5),
    inverseSurface = Deep,
    inverseOnSurface = Moon,
    outline = Color(0xFF7A7EA3),
    outlineVariant = Color(0xFFCDD0EA),
)

/** Always the Isaipetti "Indigo night" palette; follows the system light/dark setting. */
@Composable
fun IsaipettiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = IsaipettiTypography,
        content = content,
    )
}
