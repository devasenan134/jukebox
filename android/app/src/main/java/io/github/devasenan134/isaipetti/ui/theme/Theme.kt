package io.github.devasenan134.isaipetti.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.devasenan134.isaipetti.data.ThemeMode

// Jukebox dark palette matching web/src/styles.css
private val AppBackground = Color(0xFF121212)
private val Mango = Color(0xFFFFB547)
private val OnMango = Color(0xFF1D1300)
private val MangoContainer = Color(0xFF4A3412)
private val OnMangoContainer = Color(0xFFFFE0B0)
private val TertiaryMint = Color(0xFF8FD3C1)
private val OnTertiary = Color(0xFF00382D)
private val White = Color(0xFFFFFFFF)
private val MutedText = Color(0xFFA7A7A7)
private val DarkSurfaceLowest = Color(0xFF0A0A0A)
private val DarkSurfaceLow = Color(0xFF181818)
private val DarkSurface = Color(0xFF1F1F1F)
private val DarkSurfaceHigh = Color(0xFF282828)
private val DarkSurfaceHighest = Color(0xFF333333)
private val DarkSurfaceVariant = Color(0xFF2A2A2A)
private val OutlineGrey = Color(0xFF727272)
private val OutlineVariantGrey = Color(0xFF2E2E2E)
private val ErrorRed = Color(0xFFF3727F)

private val JukeboxDarkColors = darkColorScheme(
    primary = Mango,
    onPrimary = OnMango,
    primaryContainer = MangoContainer,
    onPrimaryContainer = OnMangoContainer,
    inversePrimary = Color(0xFF9A5C00),
    secondary = MutedText,
    onSecondary = AppBackground,
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = White,
    tertiary = TertiaryMint,
    onTertiary = OnTertiary,
    tertiaryContainer = Color(0xFF1F5145),
    onTertiaryContainer = Color(0xFFABF0DC),
    background = AppBackground,
    onBackground = White,
    surface = AppBackground,
    onSurface = White,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = MutedText,
    surfaceTint = Mango,
    surfaceDim = DarkSurfaceLow,
    surfaceBright = DarkSurfaceHighest,
    surfaceContainerLowest = DarkSurfaceLowest,
    surfaceContainerLow = DarkSurfaceLow,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurfaceHigh,
    surfaceContainerHighest = DarkSurfaceHighest,
    inverseSurface = White,
    inverseOnSurface = AppBackground,
    outline = OutlineGrey,
    outlineVariant = OutlineVariantGrey,
    error = ErrorRed,
)

/** Whether the app is showing dark colours (Jukebox is designed as a dark music app). */
@Composable
fun isAppInDarkTheme(mode: ThemeMode): Boolean = true

/**
 * Jukebox dark theme (matching the web app styling).
 * With [wallpaper] on Android 12+, dynamic Material You dark colors can be used if enabled.
 */
@Composable
fun IsaipettiTheme(
    mode: ThemeMode = ThemeMode.Dark,
    wallpaper: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        wallpaper && Build.VERSION.SDK_INT >= 31 -> dynamicDarkColorScheme(context)
        else -> JukeboxDarkColors
    }
    MaterialTheme(colorScheme = colors, typography = IsaipettiTypography, content = content)
}

/** Official JukeboxTheme alias */
@Composable
fun JukeboxTheme(
    mode: ThemeMode = ThemeMode.Dark,
    wallpaper: Boolean = false,
    content: @Composable () -> Unit,
) = IsaipettiTheme(mode, wallpaper, content)
