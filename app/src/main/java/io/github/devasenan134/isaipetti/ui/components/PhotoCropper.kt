package io.github.devasenan134.isaipetti.ui.components

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.devasenan134.isaipetti.R
import kotlin.math.roundToInt

/** The biggest picture sent: plenty for a profile picture or a cover, and about 100 KB as a JPEG. */
const val MAX_PICTURE_SIDE = 640

/**
 * Crop a photo to a square before it's used: pinch to zoom, drag to move it under the frame, and
 * rotate in quarter turns. [round] shows a circle guide (profile and group pictures are shown round).
 * [onDone] gets the framed part, [MAX_PICTURE_SIDE] pixels at most.
 */
@Composable
fun PhotoCropper(photo: Bitmap, round: Boolean, onCancel: () -> Unit, onDone: (Bitmap) -> Unit) {
    var bitmap by remember(photo) { mutableStateOf(photo) }
    // The area the photo is shown in, the zoom, and how far the photo is moved from the frame's middle.
    var area by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember(bitmap) { mutableFloatStateOf(0f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    val margin = with(LocalDensity.current) { 24.dp.toPx() }

    val side = (minOf(area.width, area.height) - 2 * margin).coerceAtLeast(1f)
    val frame = Rect(Offset((area.width - side) / 2, (area.height - side) / 2), Size(side, side))
    // Smallest zoom that still fills the frame, so there's never an empty edge.
    val minScale = side / minOf(bitmap.width, bitmap.height)
    val zoom = if (scale < minScale) minScale else scale

    fun keepCovered(o: Offset, s: Float): Offset {
        val maxX = (bitmap.width * s - side) / 2
        val maxY = (bitmap.height * s - side) / 2
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    /** The part of the photo inside the frame, in the photo's own pixels. */
    fun cropped(): Bitmap {
        val left = (bitmap.width * zoom - side) / 2 - offset.x
        val top = (bitmap.height * zoom - side) / 2 - offset.y
        val x = (left / zoom).roundToInt().coerceIn(0, bitmap.width - 1)
        val y = (top / zoom).roundToInt().coerceIn(0, bitmap.height - 1)
        val size = (side / zoom).roundToInt().coerceIn(1, minOf(bitmap.width - x, bitmap.height - y))
        val square = Bitmap.createBitmap(bitmap, x, y, size, size)
        return if (size > MAX_PICTURE_SIDE) Bitmap.createScaledBitmap(square, MAX_PICTURE_SIDE, MAX_PICTURE_SIDE, true) else square
    }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            Text(
                "Pinch to zoom, drag to move",
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp),
            )
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            Canvas(
                Modifier.weight(1f).fillMaxWidth()
                    .onSizeChanged { area = it }
                    .pointerInput(bitmap, side) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            // Read the zoom now (not when the gesture began), so pinches add up.
                            val current = if (scale < minScale) minScale else scale
                            val s = (current * gestureZoom).coerceIn(minScale, minScale * 6)
                            scale = s
                            offset = keepCovered(offset + pan, s)
                        }
                    },
            ) {
                if (area == IntSize.Zero) return@Canvas
                val w = bitmap.width * zoom
                val h = bitmap.height * zoom
                val topLeft = frame.center + offset - Offset(w / 2, h / 2)
                drawImage(image, dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()), dstSize = IntSize(w.roundToInt(), h.roundToInt()))
                // Dim everything outside the frame, then outline it.
                val hole = Path().apply { if (round) addOval(frame) else addRoundRect(RoundRect(frame, CornerRadius(12.dp.toPx()))) }
                clipPath(hole, clipOp = ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.6f)) }
                drawPath(hole, Color.White, style = Stroke(2.dp.toPx()))
            }
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onCancel) { Text("Cancel", color = Color.White) }
                Box(Modifier.weight(1f))
                OutlinedButton(onClick = { bitmap = rotated(bitmap) }) {
                    Icon(painterResource(R.drawable.ic_rotate), contentDescription = null, tint = Color.White)
                    Text("Rotate", color = Color.White, modifier = Modifier.padding(start = 6.dp))
                }
                Button(onClick = { onDone(cropped()) }, enabled = area != IntSize.Zero) { Text("Done") }
            }
        }
    }
}

/** A quarter turn clockwise. */
private fun rotated(bitmap: Bitmap): Bitmap =
    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(90f) }, true)
