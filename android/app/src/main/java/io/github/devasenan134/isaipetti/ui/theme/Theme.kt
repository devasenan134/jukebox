package io.github.devasenan134.isaipetti.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.devasenan134.isaipetti.data.ThemeMode

// Jukebox's "Saffron" palettes, from the logo (gold through saffron to deep orange); the dark one
// matches web/src/styles.css. Greys lean slightly warm, like the logo's dark version.
private val Saffron = Color(0xFFFFA12B)
private val White = Color(0xFFFFFFFF)

private val JukeboxDarkColors = darkColorScheme(
    primary = Saffron,
    onPrimary = Color(0xFF2B1300),
    primaryContainer = Color(0xFF5C3000),
    onPrimaryContainer = Color(0xFFFFDCB8),
    inversePrimary = Color(0xFF9A4A00),
    secondary = Color(0xFFADA59D),
    onSecondary = Color(0xFF141210),
    secondaryContainer = Color(0xFF2C2723),
    onSecondaryContainer = White,
    tertiary = Color(0xFFFFE36E),
    onTertiary = Color(0xFF3A2F00),
    tertiaryContainer = Color(0xFF544500),
    onTertiaryContainer = Color(0xFFFFF0B3),
    background = Color(0xFF141210),
    onBackground = White,
    surface = Color(0xFF141210),
    onSurface = White,
    surfaceVariant = Color(0xFF2C2723),
    onSurfaceVariant = Color(0xFFADA59D),
    surfaceTint = Saffron,
    surfaceDim = Color(0xFF1A1714),
    surfaceBright = Color(0xFF35302B),
    surfaceContainerLowest = Color(0xFF0C0A09),
    surfaceContainerLow = Color(0xFF1A1714),
    surfaceContainer = Color(0xFF211D1A),
    surfaceContainerHigh = Color(0xFF2A2521),
    surfaceContainerHighest = Color(0xFF35302B),
    inverseSurface = White,
    inverseOnSurface = Color(0xFF141210),
    outline = Color(0xFF78716A),
    outlineVariant = Color(0xFF302B27),
    error = Color(0xFFF3727F),
)

// On light screens saffron itself is too pale for text, so the accent is a deeper burnt saffron.
private val JukeboxLightColors = lightColorScheme(
    primary = Color(0xFFA84B00),
    onPrimary = White,
    primaryContainer = Color(0xFFFFDCBE),
    onPrimaryContainer = Color(0xFF351000),
    inversePrimary = Saffron,
    secondary = Color(0xFF6E5F55),
    onSecondary = White,
    secondaryContainer = Color(0xFFF3E3D7),
    onSecondaryContainer = Color(0xFF261A12),
    tertiary = Color(0xFF7A5900),
    onTertiary = White,
    tertiaryContainer = Color(0xFFFFDF94),
    onTertiaryContainer = Color(0xFF261A00),
    background = Color(0xFFFFF8F4),
    onBackground = Color(0xFF221A15),
    surface = Color(0xFFFFF8F4),
    onSurface = Color(0xFF221A15),
    surfaceVariant = Color(0xFFF0E0D3),
    onSurfaceVariant = Color(0xFF53443A),
    surfaceTint = Color(0xFFA84B00),
    surfaceDim = Color(0xFFE8D7CC),
    surfaceBright = Color(0xFFFFF8F4),
    surfaceContainerLowest = White,
    surfaceContainerLow = Color(0xFFFCF2EB),
    surfaceContainer = Color(0xFFF7ECE4),
    surfaceContainerHigh = Color(0xFFF1E6DE),
    surfaceContainerHighest = Color(0xFFEBE0D8),
    inverseSurface = Color(0xFF382F29),
    inverseOnSurface = Color(0xFFFFEDE4),
    outline = Color(0xFF85746A),
    outlineVariant = Color(0xFFD8C3B6),
    error = Color(0xFFBA1A1A),
)

/** Whether the app is showing dark colours: the choice in Settings, or the phone's for Auto. */
@Composable
fun isAppInDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

/**
 * Jukebox's Saffron theme, light or dark as chosen in Settings (Auto follows the phone).
 * With [wallpaper] on Android 12+, the colours come from the wallpaper instead.
 */
@Composable
fun IsaipettiTheme(
    mode: ThemeMode = ThemeMode.System,
    wallpaper: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark = isAppInDarkTheme(mode)
    val colors = when {
        wallpaper && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> JukeboxDarkColors
        else -> JukeboxLightColors
    }
    MaterialTheme(colorScheme = colors, typography = IsaipettiTypography, content = content)
}

/** Official JukeboxTheme alias */
@Composable
fun JukeboxTheme(
    mode: ThemeMode = ThemeMode.System,
    wallpaper: Boolean = false,
    content: @Composable () -> Unit,
) = IsaipettiTheme(mode, wallpaper, content)
