package io.github.devasenan134.isaipetti.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.devasenan134.isaipetti.playback.QueueEntry
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.SectionTitle
import coil3.compose.AsyncImage

/** A queue entry with a key that survives other songs moving or leaving (the player's index doesn't). */
private data class Keyed(val key: String, val entry: QueueEntry)

/** "songId#n" for the n-th time a song is in the queue, so a song queued twice still gets two keys. */
private fun List<QueueEntry>.keyed(): List<Keyed> {
    val seen = mutableMapOf<String, Int>()
    return map { entry ->
        val n = seen.merge(entry.item.mediaId, 1, Int::plus)!!
        Keyed("${entry.item.mediaId}#$n", entry)
    }
}

/**
 * The play queue in the order it will play. Tap a song to jump to it, drag the handle to move it,
 * or swipe it left to take it out. In someone else's jam the queue is theirs, so it's view-only.
 * Moving songs needs shuffle off (in shuffle, the play order isn't the queue's order).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(onDismiss: () -> Unit) {
    val app = LocalApp.current
    val player = app.player
    val queue by player.queue.collectAsStateWithLifecycle()
    val now by player.nowPlaying.collectAsStateWithLifecycle()
    val joined by app.social.listen.joined.collectAsStateWithLifecycle()
    val owners by app.social.listen.owners.collectAsStateWithLifecycle()
    val readOnly = joined?.let { owners[it] }?.let { it != app.social.me?.id } ?: false
    val canMove = !readOnly && !now.shuffle

    val (entries, currentIndex) = queue
    val startAt = entries.indexOfFirst { it.index == currentIndex }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startAt)

    // While a song is being dragged (and until the player reports the move), show our own order.
    var local by remember { mutableStateOf<List<Keyed>?>(null) }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(entries) { if (dragKey == null) local = null }
    val shown = local ?: entries.keyed()
    // The drag handler outlives compositions, so it reads the latest list through this.
    val latestShown by rememberUpdatedState(shown)

    /** Swaps the dragged song with whichever row its middle is now over. */
    fun followDrag() {
        val order = local ?: return
        val info = listState.layoutInfo.visibleItemsInfo
        val dragged = info.firstOrNull { it.key == dragKey } ?: return
        val middle = dragged.offset + dragOffset + dragged.size / 2
        val target = info.firstOrNull { it.key != dragKey && middle >= it.offset && middle < it.offset + it.size } ?: return
        if (target.index !in order.indices || dragged.index !in order.indices) return
        local = order.toMutableList().apply { add(target.index, removeAt(dragged.index)) }
        dragOffset -= target.offset - dragged.offset
    }

    fun endDrag() {
        val order = local
        val key = dragKey
        dragKey = null
        dragOffset = 0f
        val moved = order?.indexOfFirst { it.key == key } ?: -1
        val from = order?.getOrNull(moved)?.entry?.index
        // Without shuffle, a row's place in the list is its index in the player.
        if (from != null && from != moved) player.moveInQueue(from, moved) else local = null
    }

    // Near the top or bottom edge, keep scrolling so a song can be dragged anywhere in a long queue.
    LaunchedEffect(dragKey) {
        if (dragKey == null) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val step = listState.edgeScrollStep(dragKey, dragOffset)
            if (step != 0f) {
                val scrolled = listState.scrollBy(step)
                dragOffset += scrolled
                followDrag()
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        SectionTitle("Queue · ${entries.size} songs")
        if (!readOnly && now.shuffle) {
            Text(
                "Turn off shuffle to move songs",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
            )
        }
        LazyColumn(state = listState) {
            items(shown, key = { it.key }) { row ->
                val entry = row.entry
                val isCurrent = entry.index == currentIndex
                val dragging = row.key == dragKey
                val rowModifier = if (dragging) {
                    Modifier.zIndex(1f).graphicsLayer { translationY = dragOffset; shadowElevation = 8f }
                } else {
                    Modifier.animateItem()
                }
                // The playing song stays put: swiping it away would cut the music off.
                val removable = !readOnly && !isCurrent && dragKey == null
                // The swipe state outlives this row's index changing, and can report one swipe twice.
                val latestEntry by rememberUpdatedState(entry)
                val removed = remember { booleanArrayOf(false) }
                val swipe = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value == SwipeToDismissBoxValue.EndToStart && !removed[0]) {
                            removed[0] = true
                            player.removeFromQueue(latestEntry.index, latestEntry.item.mediaId)
                        }
                        value == SwipeToDismissBoxValue.EndToStart
                    },
                )
                SwipeToDismissBox(
                    state = swipe,
                    modifier = rowModifier,
                    enableDismissFromStartToEnd = false,
                    enableDismissFromEndToStart = removable,
                    backgroundContent = { RemoveHint() },
                ) {
                    QueueRow(
                        entry = entry,
                        isCurrent = isCurrent,
                        onClick = { player.jumpTo(entry.index) },
                        handle = if (!canMove) null else Modifier.pointerInput(row.key) {
                            detectDragGestures(
                                onDragStart = {
                                    local = latestShown
                                    dragKey = row.key
                                    dragOffset = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    followDrag()
                                },
                                onDragEnd = { endDrag() },
                                onDragCancel = { endDrag() },
                            )
                        },
                    )
                }
            }
        }
    }
}

/** How far to scroll this frame while dragging: more the closer the song is to the list's edge. */
private fun LazyListState.edgeScrollStep(dragKey: String?, dragOffset: Float): Float {
    val info = layoutInfo
    val dragged = info.visibleItemsInfo.firstOrNull { it.key == dragKey } ?: return 0f
    val top = dragged.offset + dragOffset
    val bottom = top + dragged.size
    val edge = dragged.size.toFloat()
    return when {
        top < info.viewportStartOffset + edge / 2 -> -(edge / 6)
        bottom > info.viewportEndOffset - edge / 2 -> edge / 6
        else -> 0f
    }
}

@Composable
private fun QueueRow(entry: QueueEntry, isCurrent: Boolean, onClick: () -> Unit, handle: Modifier?) {
    val meta = entry.item.mediaMetadata
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).clickable(onClick = onClick)
            .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = if (handle == null) 16.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = meta.artworkUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                meta.title?.toString().orEmpty(),
                fontWeight = if (isCurrent) FontWeight.Bold else null,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                meta.artist?.toString().orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (handle != null) {
            Icon(
                Icons.Filled.Menu,
                contentDescription = "Drag to move",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = handle.padding(horizontal = 16.dp, vertical = 10.dp).size(24.dp),
            )
        }
    }
}

@Composable
private fun RemoveHint() {
    Row(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
        Text(
            "Remove",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
