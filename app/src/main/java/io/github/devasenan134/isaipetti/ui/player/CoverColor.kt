package io.github.devasenan134.isaipetti.ui.player

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A calm colour from the cover art, for the player's background: a muted shade that suits a dark
 * or light screen, rather than the loudest colour on the cover. Null until it's worked out.
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
                val swatch = if (darkTheme) {
                    palette.darkMutedSwatch ?: palette.mutedSwatch ?: palette.darkVibrantSwatch ?: palette.dominantSwatch
                } else {
                    palette.lightMutedSwatch ?: palette.mutedSwatch ?: palette.lightVibrantSwatch ?: palette.dominantSwatch
                }
                swatch?.rgb?.let { Color(it) }
            }.getOrNull()
        }
    }
    return color
}
