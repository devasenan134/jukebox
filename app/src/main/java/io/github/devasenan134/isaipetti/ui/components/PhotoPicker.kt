package io.github.devasenan134.isaipetti.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.devasenan134.isaipetti.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * "Take a photo" or "Choose from your photos" (and optionally "Remove"), for profile pictures, group
 * photos and playlist covers. Then the photo opens in [PhotoCropper] to frame it, and [onPicked] gets
 * the square as a small JPEG. Show it with [PhotoPicker.open].
 */
class PhotoPicker internal constructor(private val show: () -> Unit) {
    fun open() = show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberPhotoPicker(
    title: String,
    /** Pictures shown round (people, groups) get a round guide when cropping. */
    round: Boolean = false,
    /** Shown as "Remove …" when set (e.g. there's a picture now). */
    onRemove: (() -> Unit)? = null,
    onPicked: suspend (ByteArray) -> Unit,
): PhotoPicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sheetOpen by remember { mutableStateOf(false) }
    // The photo being framed, once it's loaded.
    var cropping by remember { mutableStateOf<Bitmap?>(null) }

    fun use(uri: Uri?) {
        if (uri == null) return
        scope.launch {
            val photo = withContext(Dispatchers.Default) { runCatching { loadUpright(context, uri) }.getOrNull() }
            if (photo == null) Toast.makeText(context, "Couldn't read that picture", Toast.LENGTH_SHORT).show()
            else cropping = photo
        }
    }

    cropping?.let { photo ->
        PhotoCropper(photo, round, onCancel = { cropping = null }) { square ->
            cropping = null
            scope.launch {
                val jpeg = withContext(Dispatchers.Default) {
                    ByteArrayOutputStream().use { out -> square.compress(Bitmap.CompressFormat.JPEG, 85, out); out.toByteArray() }
                }
                onPicked(jpeg)
            }
        }
    }

    // Android's photo picker: no permission needed, and only the chosen photo is shared with the app.
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { use(it) }
    // The camera app saves the photo into a file of ours (shared through the app's FileProvider).
    val photoFile = remember { File(File(context.cacheDir, "photos").apply { mkdirs() }, "camera.jpg") }
    val photoUri = remember { FileProvider.getUriForFile(context, "${context.packageName}.files", photoFile) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken -> if (taken) use(photoUri) }

    if (sheetOpen) {
        ModalBottomSheet(onDismissRequest = { sheetOpen = false }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                SheetOption(painterResource(R.drawable.ic_camera), "Take a photo") {
                    sheetOpen = false
                    try {
                        camera.launch(photoUri)
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(context, "No camera app found", Toast.LENGTH_SHORT).show()
                    }
                }
                SheetOption(painterResource(R.drawable.ic_photo), "Choose from your photos") {
                    sheetOpen = false
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                onRemove?.let { remove ->
                    SheetOption(rememberVectorPainter(Icons.Filled.Delete), "Remove picture") {
                        sheetOpen = false
                        remove()
                    }
                }
            }
        }
    }
    return remember { PhotoPicker { sheetOpen = true } }
}

@Composable
private fun SheetOption(icon: Painter, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 20.dp))
    }
}

/** The photo, upright whatever the camera's rotation, and no bigger than [MAX_LOAD] pixels on its long side. */
private fun loadUpright(context: Context, uri: Uri): Bitmap {
    if (Build.VERSION.SDK_INT >= 28) {
        // ImageDecoder turns the photo upright and can shrink it while reading, to save memory.
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val longSide = maxOf(info.size.width, info.size.height)
            if (longSide > MAX_LOAD) decoder.setTargetSampleSize(longSide / MAX_LOAD)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    @Suppress("DEPRECATION")
    val full = MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
    val longSide = maxOf(full.width, full.height)
    val small = if (longSide > MAX_LOAD) Bitmap.createScaledBitmap(full, full.width * MAX_LOAD / longSide, full.height * MAX_LOAD / longSide, true) else full
    return uprightLegacy(context, uri, small)
}

/** Android 8 doesn't turn photos upright on its own; the photo's EXIF data says how. */
private fun uprightLegacy(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
    val degrees = context.contentResolver.openInputStream(uri)?.use {
        when (android.media.ExifInterface(it).getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL,
        )) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } ?: 0f
    if (degrees == 0f) return bitmap
    val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/** Enough detail to zoom in a little when cropping, without using too much memory. */
private const val MAX_LOAD = 2048
