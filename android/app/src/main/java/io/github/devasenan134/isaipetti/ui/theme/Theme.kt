package io.github.devasenan134.isaipetti.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.LocalContext
import android.os.Build
import io.github.devasenan134.isaipetti.data.ThemeMode
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand palette: "Graphite & mango" — a soft graphite grey with a ripe mango accent. The grey is
// neutral on purpose, so album covers bring the colour.
private val Graphite = Color(0xFF202026)
private val Slate = Color(0xFF2A2A32)
private val Stone = Color(0xFF35353F)
private val Mango = Color(0xFFFFB547)
private val DeepMango = Color(0xFF9A5C00)
private val Snow = Color(0xFFFFFFFF)
private val Silver = Color(0xFFDCDCE4)
private val Ink = Color(0xFF141418)

private val DarkColors = darkColorScheme(
    primary = Mango,
    onPrimary = Color(0xFF3D2600),
    primaryContainer = Color(0xFF6B4B12),
    onPrimaryContainer = Color(0xFFFFE0B0),
    inversePrimary = DeepMango,
    secondary = Silver,
    onSecondary = Graphite,
    secondaryContainer = Color(0xFF3E3E49),
    onSecondaryContainer = Snow,
    tertiary = Color(0xFF8FD3C1),
    onTertiary = Color(0xFF00382D),
    tertiaryContainer = Color(0xFF1F5145),
    onTertiaryContainer = Color(0xFFABF0DC),
    background = Graphite,
    onBackground = Snow,
    surface = Graphite,
    onSurface = Snow,
    surfaceVariant = Stone,
    onSurfaceVariant = Silver,
    surfaceTint = Mango,
    surfaceDim = Color(0xFF1A1A1F),
    surfaceBright = Color(0xFF3E3E49),
    surfaceContainerLowest = Color(0xFF18181C),
    surfaceContainerLow = Color(0xFF25252B),
    surfaceContainer = Slate,
    surfaceContainerHigh = Stone,
    surfaceContainerHighest = Color(0xFF3E3E49),
    inverseSurface = Snow,
    inverseOnSurface = Ink,
    outline = Color(0xFF5C5C69),
    outlineVariant = Color(0xFF4A4A56),
)

private val LightColors = lightColorScheme(
    primary = DeepMango,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE0B0),
    onPrimaryContainer = Color(0xFF3D2600),
    inversePrimary = Mango,
    secondary = Color(0xFF4C4C57),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAEAEE),
    onSecondaryContainer = Ink,
    tertiary = Color(0xFF006B58),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFABF0DC),
    onTertiaryContainer = Color(0xFF002019),
    background = Color.White,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEAEAEE),
    onSurfaceVariant = Color(0xFF4C4C57),
    surfaceTint = DeepMango,
    surfaceDim = Color(0xFFDDDDE3),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F8FA),
    surfaceContainer = Color(0xFFF5F5F7),
    surfaceContainerHigh = Color(0xFFEFEFF2),
    surfaceContainerHighest = Color(0xFFEAEAEE),
    inverseSurface = Slate,
    inverseOnSurface = Snow,
    outline = Color(0xFF7C7C88),
    outlineVariant = Color(0xFFD6D6DE),
)

/** Whether the app is showing dark colours right now (the Settings choice, or the phone's). */
@Composable
fun isAppInDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

/**
 * Graphite & mango, light or dark as chosen in Settings (or as the phone is set). With [wallpaper] on
 * Android 12+, Android's colours from the wallpaper ("Material You") instead.
 */
@Composable
fun IsaipettiTheme(mode: ThemeMode = ThemeMode.System, wallpaper: Boolean = false, content: @Composable () -> Unit) {
    val dark = isAppInDarkTheme(mode)
    val context = LocalContext.current
    val colors = when {
        wallpaper && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = IsaipettiTypography, content = content)
}
