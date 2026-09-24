package io.github.devasenan134.isaipetti.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.core.net.toUri
import io.github.devasenan134.isaipetti.ui.player.rememberCoverColor

// The colour at the top of movie, playlist, mix, singer and composer pages, like the player's:
// taken from the cover and faded into the background below the header.

/** The page's colour from its cover art; the plain background until it's worked out (then it fades in). */
@Composable
fun rememberPageTint(coverArt: String?): Color {
    val app = LocalApp.current
    val base = MaterialTheme.colorScheme.background
    val uri = coverArt?.let { app.api.coverUrl(it, 600)?.toUri() }
    val color = rememberCoverColor(uri, darkTheme = base.luminance() < 0.5f)
    val tint by animateColorAsState(color ?: base, tween(500), label = "page tint")
    return tint
}

/** A page's colour from a given colour (a mix's, or the brand colour for Liked songs), toned like cover colours. */
@Composable
fun rememberPageTint(color: Color?): Color {
    val base = MaterialTheme.colorScheme.background
    val target = color?.let { toned(it, dark = base.luminance() < 0.5f) } ?: base
    val tint by animateColorAsState(target, tween(500), label = "page tint")
    return tint
}

/** The same brightness and saturation limits as the player's cover colour, so pages match it. */
private fun toned(color: Color, dark: Boolean): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    hsl[1] = hsl[1].coerceAtMost(if (dark) 0.75f else 0.6f)
    hsl[2] = if (dark) 0.3f else 0.82f
    return Color(ColorUtils.HSLToColor(hsl))
}

/** [tint] at the top, fading into the page's background at the bottom. */
@Composable
fun Modifier.pageGradient(tint: Color): Modifier =
    background(Brush.verticalGradient(listOf(tint, MaterialTheme.colorScheme.background)))
