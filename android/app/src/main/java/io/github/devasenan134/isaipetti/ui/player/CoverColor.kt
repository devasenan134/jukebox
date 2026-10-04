package io.github.devasenan134.isaipetti.ui.player

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The cover's colour, for the player's background, like Spotify: the colour that covers most of the
 * cover (a red cover gives red), with its brightness set to suit a dark or light screen and its
 * saturation capped so it never looks neon. Null until it's worked out.
 */
@Composable
fun rememberCoverColor(artworkUri: Uri?, darkTheme: Boolean): Color? {
    val context = LocalContext.current
    val color by produceState<Color?>(null, artworkUri, darkTheme) {
        if (artworkUri == null) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            runCatching {
                // A small copy is plenty to find the colours, and it's likely cached already.
                val request = ImageRequest.Builder(context).data(artworkUri).size(128).allowHardware(false).build()
                val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
                    ?: return@runCatching null
                val palette = Palette.from(bitmap).generate()
                // The biggest colour on the cover, unless it's nearly grey; then the most vivid one.
                val candidates = listOfNotNull(
                    palette.dominantSwatch,
                    palette.vibrantSwatch,
                    palette.darkVibrantSwatch,
                    palette.mutedSwatch,
                )
                val swatch = candidates.firstOrNull { it.hsl[1] >= 0.2f } ?: candidates.firstOrNull() ?: return@runCatching null
                val hsl = swatch.hsl.copyOf()
                hsl[1] = hsl[1].coerceAtMost(if (darkTheme) 0.75f else 0.6f)
                hsl[2] = if (darkTheme) 0.3f else 0.82f
                Color(ColorUtils.HSLToColor(hsl))
            }.getOrNull()
        }
    }
    return color
}
