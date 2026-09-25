package io.github.devasenan134.isaipetti.ui.search

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.devasenan134.isaipetti.data.CatalogHit
import io.github.devasenan134.isaipetti.data.CatalogItem
import io.github.devasenan134.isaipetti.data.MusicRequest
import io.github.devasenan134.isaipetti.ui.Nav
import io.github.devasenan134.isaipetti.ui.components.ErrorMessage
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.ScreenHeader
import io.github.devasenan134.isaipetti.ui.components.SectionTitle
import io.github.devasenan134.isaipetti.ui.components.UiSize
import kotlinx.coroutines.launch

/**
 * Search results from the music catalog that aren't in the library, each with a Request button.
 * [onRequest] asks for one; [onCancel] takes your request back.
 */
fun LazyListScope.catalogResults(movies: List<CatalogHit>, songs: List<CatalogHit>, onRequest: (CatalogHit) -> Unit, onCancel: (CatalogHit) -> Unit) {
    if (movies.isEmpty() && songs.isEmpty()) return
    item(key = "catalog-title") {
        Column {
            SectionTitle("Not in the library")
            Text(
                "Request one and it gets added, usually within a day. You'll get a notification.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
    if (movies.isNotEmpty()) {
        item(key = "catalog-movies") {
            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                items(movies, key = { "catalog-${it.item.id}" }) { hit ->
                    Column(Modifier.width(UiSize.Tile).padding(6.dp)) {
                        Artwork(hit.item, Modifier.fillMaxWidth().aspectRatio(1f))
                        Spacer(Modifier.height(6.dp))
                        Text(hit.item.movie, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(hit.item.year?.toString(), hit.item.artist).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        RequestButton(hit.request, onRequest = { onRequest(hit) }, onCancel = { onCancel(hit) }, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
    items(songs, key = { "catalog-${it.item.id}" }) { hit ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(hit.item, Modifier.size(UiSize.SongThumb))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(hit.item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(hit.item.movie, hit.item.year?.toString(), hit.item.artist).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                hit.request?.takeIf { it.isOpen && !it.mine && it.askedBy.isNotEmpty() }?.let {
                    Text("Asked for by ${it.askedBy.joinToString()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                }
            }
            RequestButton(hit.request, onRequest = { onRequest(hit) }, onCancel = { onCancel(hit) })
        }
    }
}

/** "Request", or "Requested" once you asked (tap it to take the request back). */
@Composable
private fun RequestButton(request: MusicRequest?, onRequest: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val padding = PaddingValues(horizontal = 12.dp)
    if (request?.isOpen == true && request.mine) {
        OutlinedButton(onClick = onCancel, contentPadding = padding, modifier = modifier) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Text("Requested", modifier = Modifier.padding(start = 4.dp), maxLines = 1)
        }
    } else {
        FilledTonalButton(onClick = onRequest, contentPadding = padding, modifier = modifier) { Text("Request", maxLines = 1) }
    }
}

/** A catalog cover (from the internet, not Navidrome). */
@Composable
private fun Artwork(item: CatalogItem, modifier: Modifier = Modifier, corner: Dp = 8.dp) {
    AsyncImage(
        model = item.artworkUrl,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/**
 * Asking for a catalog song or movie from search, and taking it back. Returns the two actions for
 * [catalogResults]; [onChanged] gets the item's new request (null when taken back).
 */
@Composable
fun rememberRequestActions(onChanged: (itemId: String, MusicRequest?) -> Unit): Pair<(CatalogHit) -> Unit, (CatalogHit) -> Unit> {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cancelling by remember { mutableStateOf<CatalogHit?>(null) }
    cancelling?.let { hit ->
        AlertDialog(
            onDismissRequest = { cancelling = null },
            title = { Text("Take back your request?") },
            text = { Text(hit.item.label) },
            confirmButton = {
                TextButton(onClick = {
                    cancelling = null
                    val request = hit.request ?: return@TextButton
                    scope.launch {
                        runCatching { app.social.api.cancelMusicRequest(request.id) }
                            .onSuccess {
                                // Others who asked keep it open.
                                val others = request.askedBy - (app.social.me?.displayName ?: "")
                                onChanged(hit.item.id, if (others.isNotEmpty()) request.copy(mine = false, askedBy = others) else null)
                            }
                            .onFailure { Toast.makeText(context, it.message ?: "Couldn't take it back", Toast.LENGTH_SHORT).show() }
                    }
                }) { Text("Take back") }
            },
            dismissButton = { TextButton(onClick = { cancelling = null }) { Text("Keep it") } },
        )
    }
    val request: (CatalogHit) -> Unit = { hit ->
        scope.launch {
            runCatching { app.social.api.requestMusic(hit.item.id) }
                .onSuccess {
                    onChanged(hit.item.id, it)
                    Toast.makeText(context, "Requested. You'll get a notification when it's in the library.", Toast.LENGTH_LONG).show()
                }
                .onFailure { Toast.makeText(context, it.message ?: "Couldn't request it", Toast.LENGTH_SHORT).show() }
        }
    }
    return request to { hit: CatalogHit -> cancelling = hit }
}

/**
 * Your requests for music, and for admins everyone's, to answer: "Added" once the music is in the
 * library (everyone who asked gets a notification), or "Can't find" with an optional reason.
 */
@Composable
fun RequestsScreen(nav: Nav) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isAdmin by produceState(false) { value = runCatching { app.social.api.adminAccess().isAdmin }.getOrDefault(false) }
    var everyone by rememberSaveable { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<List<MusicRequest>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var declining by remember { mutableStateOf<MusicRequest?>(null) }
    var busy by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(everyone, reload) {
        runCatching { if (everyone) app.social.api.allMusicRequests() else app.social.api.musicRequests() }
            .onSuccess { list = it; error = null }
            .onFailure { error = it.message ?: "Couldn't load requests" }
    }
    fun replace(updated: MusicRequest) {
        list = list?.map { if (it.id == updated.id) updated else it }
    }
    fun act(request: MusicRequest, action: suspend () -> MusicRequest?) {
        busy = request.id
        scope.launch {
            runCatching { action() }
                .onSuccess { updated -> if (updated != null) replace(updated) else list = list?.filter { it.id != request.id } }
                .onFailure { Toast.makeText(context, it.message ?: "That didn't work", Toast.LENGTH_LONG).show() }
            busy = null
        }
    }

    declining?.let { request ->
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { declining = null },
            title = { Text("Can't find it?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${request.askedBy.joinToString()} will be told that ${request.item.label} couldn't be found.")
                    OutlinedTextField(note, { note = it.take(200) }, label = { Text("Why (optional)") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    declining = null
                    act(request) { app.social.api.declineMusicRequest(request.id, note.ifBlank { null }) }
                }) { Text("Tell them") }
            },
            dismissButton = { TextButton(onClick = { declining = null }) { Text("Cancel") } },
        )
    }

    Column {
        ScreenHeader("Requests", onBack = nav.back) {
            IconButton(onClick = { reload++ }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
        }
        if (isAdmin) {
            val tabs = listOf(false to "Mine", true to "Everyone's")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                tabs.forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = everyone == value,
                        onClick = { if (everyone != value) { everyone = value; list = null } },
                        shape = SegmentedButtonDefaults.itemShape(i, tabs.size),
                    ) { Text(label) }
                }
            }
        }
        val shown = list
        when {
            shown == null && error != null -> ErrorMessage(error!!, onRetry = { reload++ })
            shown == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            shown.isEmpty() -> Text(
                if (everyone) "No requests right now." else "Nothing requested yet. Search for a song or movie; ones that aren't in the library have a Request button.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(shown, key = { it.id }) { request ->
                    RequestRow(
                        request,
                        showAskers = everyone,
                        busy = busy == request.id,
                        onOpen = { request.albumId?.let(nav.openAlbum) },
                        onCancel = if (!everyone && request.isOpen) ({ act(request) { app.social.api.cancelMusicRequest(request.id); null } }) else null,
                        onDone = if (everyone && request.isOpen) ({ act(request) { app.social.api.completeMusicRequest(request.id) } }) else null,
                        onDecline = if (everyone && request.isOpen) ({ declining = request }) else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun RequestRow(
    request: MusicRequest,
    showAskers: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onCancel: (() -> Unit)?,
    onDone: (() -> Unit)?,
    onDecline: (() -> Unit)?,
) {
    val item = request.item
    Column(
        Modifier.fillMaxWidth()
            .clickable(enabled = request.status == "done" && request.albumId != null, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(item, Modifier.size(UiSize.SongThumb))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(if (item.isMovie) item.movie else item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (item.isMovie) listOfNotNull("Whole movie", item.year?.toString(), item.trackCount.takeIf { it > 0 }?.let { "$it songs" })
                    else listOfNotNull(item.movie, item.year?.toString(), item.artist)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val (status, color) = when (request.status) {
                    "done" -> "In the library · tap to open" to MaterialTheme.colorScheme.primary
                    "declined" -> ("Couldn't get it" + (request.note?.let { ": $it" } ?: "")) to MaterialTheme.colorScheme.error
                    else -> "Waiting · asked ${ago(request.requestedAt)}" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (showAskers && request.askedBy.isNotEmpty()) {
                    Text("Asked by ${request.askedBy.joinToString()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            if (onCancel != null) {
                IconButton(onClick = onCancel, enabled = !busy) { Icon(Icons.Filled.Close, contentDescription = "Take back the request") }
            }
        }
        if (onDone != null || onDecline != null) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                onDecline?.let { TextButton(onClick = it, enabled = !busy) { Text("Can't find") } }
                onDone?.let { Button(onClick = it, enabled = !busy) { Text("Added") } }
            }
        }
    }
}

private fun ago(millis: Long): String {
    val minutes = (System.currentTimeMillis() - millis) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        minutes < 2 * 24 * 60 -> "yesterday"
        else -> "${minutes / (24 * 60)} days ago"
    }
}
