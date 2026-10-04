package io.github.devasenan134.isaipetti.ui.social

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.devasenan134.isaipetti.R
import io.github.devasenan134.isaipetti.data.ChatMessage
import io.github.devasenan134.isaipetti.data.Pin
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.loadUpright
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** A picture ready to send: its file ([mime]), what it is ("photo", "gif" or "sticker") and its size. */
class OutgoingImage(val bytes: ByteArray, val mime: String, val kind: String, val width: Int, val height: Int, val preview: Bitmap?)

/** The server takes pictures up to 5 MB (GIFs and stickers are sent as they are). */
private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024

/** Photos are shrunk to this many pixels on their long side: sharp on a phone, a few hundred KB. */
private const val PHOTO_SIDE = 1600

/**
 * A picture from the gallery or camera, ready to send. A GIF stays as it is (so it still moves);
 * anything else is turned upright, shrunk and saved as a JPEG. Null if it can't be read or is too big.
 */
suspend fun prepareChatPicture(context: Context, uri: Uri): OutgoingImage? = withContext(Dispatchers.Default) {
    runCatching {
        if (context.contentResolver.getType(uri) == "image/gif") return@runCatching readAsIs(context, uri, "image/gif", "gif")
        val loaded = loadUpright(context, uri, PHOTO_SIDE)
        val long = maxOf(loaded.width, loaded.height)
        val photo = if (long > PHOTO_SIDE) {
            Bitmap.createScaledBitmap(loaded, loaded.width * PHOTO_SIDE / long, loaded.height * PHOTO_SIDE / long, true)
        } else loaded
        val jpeg = ByteArrayOutputStream().use { out -> photo.compress(Bitmap.CompressFormat.JPEG, 82, out); out.toByteArray() }
        OutgoingImage(jpeg, "image/jpeg", "photo", photo.width, photo.height, photo)
    }.getOrNull()
}

/**
 * A GIF or sticker the keyboard (Gboard, Samsung's keyboard…) handed to the text box. It's read
 * right away, while the keyboard still lets the app see it. GIFs are "gif"; everything else
 * (PNG and WebP stickers, animated or not) is a "sticker".
 */
fun readKeyboardImage(context: Context, uri: Uri, mime: String?): OutgoingImage? = runCatching {
    val type = mime ?: context.contentResolver.getType(uri) ?: "image/png"
    readAsIs(context, uri, type, if (type == "image/gif") "gif" else "sticker")
}.getOrNull()

private fun readAsIs(context: Context, uri: Uri, mime: String, kind: String): OutgoingImage? {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    if (bytes.size > MAX_IMAGE_BYTES) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    return OutgoingImage(bytes, mime, kind, bounds.outWidth, bounds.outHeight, preview = null)
}

/** A message's picture, loaded from the friends server (GIFs and animated stickers move). */
@Composable
fun ChatPicture(message: ChatMessage, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val url = LocalApp.current.social.api.imageUrl(message) ?: return
    FriendsServerPicture(url, modifier, contentScale, description = message.image?.let { if (it.isSticker) "Sticker" else "Picture" })
}

/** How wide a photo or GIF is drawn in a bubble, and its shape (very tall or wide ones are cropped a bit). */
fun Modifier.chatPictureSize(message: ChatMessage): Modifier {
    val image = message.image ?: return this
    val ratio = (image.width.toFloat() / image.height).coerceIn(0.6f, 1.8f)
    return if (image.isSticker) width(140.dp).aspectRatio(ratio) else width(240.dp).aspectRatio(ratio)
}

/**
 * A photo or GIF over everything else, on black: pinch to zoom, drag to look around, double-tap to
 * zoom in or out. It can be saved to the phone's Pictures.
 */
@Composable
fun ImageViewer(message: ChatMessage, title: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val api = LocalApp.current.social.api
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            ChatPicture(
                message,
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                        })
                    }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                contentScale = ContentScale.Fit,
            )
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().background(Color.Black.copy(alpha = 0.5f)).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(chatTime(message.createdAt), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = {
                    scope.launch {
                        val saved = runCatching { saveToPhone(context, api.downloadImage(message), "isaipetti-${message.id}") }
                        Toast.makeText(
                            context,
                            saved.fold({ "Saved to Pictures" }, { it.message ?: "Couldn't save it" }),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }) { Text("Save", color = Color.White) }
            }
            if (message.body.isNotBlank()) {
                Text(
                    message.body,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f)).navigationBarsPadding().padding(16.dp),
                )
            }
        }
    }
}

/** Puts a picture in the phone's Pictures/Isaipetti folder (Android 10 and newer need no permission for that). */
private suspend fun saveToPhone(context: Context, file: Pair<ByteArray, String>, name: String) = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT < 29) throw IllegalStateException("Saving pictures needs Android 10 or newer")
    val (bytes, mime) = file
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name + "." + mime.substringAfter('/').substringBefore(';'))
        put(MediaStore.Images.Media.MIME_TYPE, mime)
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Isaipetti")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("Couldn't save it")
    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("Couldn't save it")
}

/** Shows the picture you picked before it's sent, with room for a caption. */
@Composable
fun SendPictureDialog(image: OutgoingImage, replyingTo: String?, onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var caption by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (replyingTo != null) "Reply to $replyingTo" else if (image.kind == "gif") "Send GIF" else "Send photo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                image.preview?.let {
                    Image(
                        it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(12.dp)),
                    )
                } ?: Text("A GIF, ${image.width} × ${image.height}")
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it.take(1000) },
                    placeholder = { Text("Add a caption") },
                    maxLines = 3,
                )
            }
        },
        confirmButton = { Button(onClick = { onSend(caption.trim()) }) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** How long a pin lasts. */
private val PIN_CHOICES = listOf(24 to "24 hours", 24 * 7 to "7 days", 24 * 30 to "30 days")

/** Pick how long a message stays pinned (7 days unless you choose otherwise). */
@Composable
fun PinDialog(onPin: (hours: Int) -> Unit, onDismiss: () -> Unit) {
    var hours by remember { mutableIntStateOf(24 * 7) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pin for how long?") },
        text = {
            Column {
                Text(
                    "Everyone in the chat sees it at the top. You can unpin it any time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                PIN_CHOICES.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = hours == value, onClick = { hours = value }).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = hours == value, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onPin(hours) }) { Text("Pin") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The pinned messages, one at a time, under the chat's name. Tapping it goes to that message and
 * moves on to the next pin (like WhatsApp); a long press offers to unpin it.
 */
@Composable
fun PinnedBar(pins: List<Pin>, me: Long?, onJump: (Long) -> Unit, onUnpin: (Long) -> Unit) {
    if (pins.isEmpty()) return
    var index by remember { mutableIntStateOf(0) }
    val pin = pins[index % pins.size]
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .pointerInput(pins) {
                    detectTapGestures(
                        onTap = {
                            onJump(pins[index % pins.size].message.id)
                            index = (index + 1) % pins.size
                        },
                        onLongPress = { menu = true },
                    )
                }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_pin), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    (if (pins.size > 1) "Pinned message ${index % pins.size + 1} of ${pins.size}" else "Pinned message") +
                        " · " + if (pin.message.sender.id == me) "You" else pin.message.sender.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(pin.message.preview, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Go to message") }, onClick = { menu = false; onJump(pin.message.id) })
            DropdownMenuItem(text = { Text("Unpin") }, onClick = { menu = false; onUnpin(pin.message.id) })
        }
    }
}
