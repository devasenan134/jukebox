package io.github.devasenan134.isaipetti.ui.search

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.DisposableEffect
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

/** A catalog cover (from the internet, not the server). */
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
    var rejecting by remember { mutableStateOf<MusicRequest?>(null) }
    var show by rememberSaveable { mutableStateOf("all") }
    /** Picking an album: to close (or relink) a request with, or (forDownload) as a download's destination. */
    var matching by remember { mutableStateOf<Pair<MusicRequest, Boolean>?>(null) }
    var busy by remember { mutableStateOf<Long?>(null) }
    val reviewPlayer = rememberReviewPlayer()

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
                .onSuccess { updated ->
                    if (updated != null) replace(updated) else list = list?.filter { it.id != request.id }
                    // Approving or rejecting an album decides the songs that come with it too.
                    if (updated?.stage != null) reload++
                }
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

    rejecting?.let { request ->
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { rejecting = null },
            title = { Text("Reject this download?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("The download is deleted and the request waits again, so it can be matched again. Nobody is told.")
                    OutlinedTextField(note, { note = it.take(200) }, label = { Text("What was wrong (optional)") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    rejecting = null
                    reviewPlayer.stop()
                    act(request) { app.social.api.rejectMusicRequest(request.id, note.ifBlank { null }) }
                }) { Text("Reject") }
            },
            dismissButton = { TextButton(onClick = { rejecting = null }) { Text("Cancel") } },
        )
    }

    matching?.let { (request, forDownload) ->
        AlbumPicker(
            request, forDownload,
            onClose = { matching = null },
            onPick = { album ->
                matching = null
                act(request) {
                    if (forDownload) app.social.api.setReviewDestination(request.id, album?.id)
                    else app.social.api.completeMusicRequest(request.id, album!!.id)
                }
            },
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
                item(key = "shows") {
                    androidx.compose.foundation.lazy.LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(SHOWS) { (key, name) ->
                            androidx.compose.material3.FilterChip(
                                selected = show == key,
                                onClick = { show = key },
                                label = { Text("$name ${shown.count { shows(key, it) }}") },
                            )
                        }
                    }
                }
                val toCheck = if (everyone && (show == "all" || show == "open")) shown.filter { it.stage == "review" && it.review != null && it.review.coveredBy == null } else emptyList()
                if (toCheck.isNotEmpty()) {
                    item(key = "check-title") {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("Ready to check", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Downloaded and tagged. Listen, then approve to move it into the library, or reject to delete it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(toCheck, key = { "check-${it.id}" }) { request ->
                        ReviewCard(
                            request,
                            comesWith = shown.filter { it.review?.coveredBy == request.id },
                            player = reviewPlayer,
                            busy = busy == request.id,
                            onApprove = { reviewPlayer.stop(); act(request) { app.social.api.approveMusicRequest(request.id) } },
                            onReject = { rejecting = request },
                            onChangeDestination = { matching = request to true },
                        )
                    }
                    item(key = "all-title") {
                        Text("${SHOWS.first { it.first == show }.second} requests", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
                val rows = shown.filter { shows(show, it) && !(everyone && it.stage == "review" && it.review?.coveredBy == null) }
                if (rows.isEmpty()) item(key = "none") {
                    Text("Nothing here.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                }
                items(rows, key = { it.id }) { request ->
                    RequestRow(
                        request,
                        showAskers = everyone,
                        busy = busy == request.id,
                        onOpen = { request.albumId?.let(nav.openAlbum) },
                        onCancel = if (!everyone && request.isOpen && request.stage == null) ({ act(request) { app.social.api.cancelMusicRequest(request.id); null } }) else null,
                        onDone = if (everyone && request.isOpen && request.stage == null) ({ act(request) { app.social.api.completeMusicRequest(request.id) } }) else null,
                        onDecline = if (everyone && request.isOpen && request.stage != "review") ({ declining = request }) else null,
                        onMatch = if (everyone && (request.isOpen || request.status == "done") && request.stage != "review") ({ matching = request to false }) else null,
                        onAgain = if (request.status == "declined") ({ act(request) { app.social.api.requestMusic(request.item.id) } }) else null,
                        onReopen = if (everyone && request.status == "done") ({ act(request) { app.social.api.reopenMusicRequest(request.id) } }) else null,
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
    onMatch: (() -> Unit)? = null,
    onAgain: (() -> Unit)? = null,
    onReopen: (() -> Unit)? = null,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (item.isMovie) item.movie else item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    KindTag(item.isMovie, Modifier.padding(start = 8.dp))
                }
                Text(
                    (if (item.isMovie) listOfNotNull(item.year?.toString(), item.trackCount.takeIf { it > 0 }?.let { "$it songs" })
                    else listOfNotNull(item.movie, item.year?.toString(), item.artist)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val colors = MaterialTheme.colorScheme
                val (status, color) = when {
                    request.status == "done" -> "In the library · tap to open" to colors.primary
                    request.status == "declined" -> ("Couldn't get it" + (request.note?.let { ": $it" } ?: "")) to colors.error
                    request.stage == "approved" -> (if (showAskers) "Approved · moving into the library" else "Found · being added") to colors.primary
                    request.stage == "review" -> (request.review?.coveredBy?.let { "Comes with request $it" } ?: "Found · being added") to colors.primary
                    request.stage == "rejected" -> "Rejected · removing the download" to colors.onSurfaceVariant
                    else -> ("Waiting · asked ${ago(request.requestedAt)}" +
                        (request.reviewNote?.takeIf { showAskers }?.let { " · last download rejected: $it" } ?: "")) to colors.onSurfaceVariant
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
        if (onDone != null || onDecline != null || onMatch != null || onAgain != null || onReopen != null) {
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                onAgain?.let { FilledTonalButton(onClick = it, enabled = !busy) { Text("Request again") } }
                onReopen?.let { TextButton(onClick = it, enabled = !busy) { Text("Reopen") } }
                onDecline?.let { TextButton(onClick = it, enabled = !busy) { Text("Can't find") } }
                onMatch?.let {
                    if (request.status == "done") TextButton(onClick = it, enabled = !busy) { Text("Change album") }
                    else FilledTonalButton(onClick = it, enabled = !busy) { Text("Match album") }
                }
                onDone?.let { Button(onClick = it, enabled = !busy) { Text("Added") } }
            }
        }
    }
}

/**
 * Plays downloads waiting for a check, one at a time, straight from the server (signed in with a header).
 * Your music pauses while one plays and carries on after.
 */
class ReviewPlayer(private val context: android.content.Context) {
    private val app = context.applicationContext as io.github.devasenan134.isaipetti.IsaipettiApp
    private var player: android.media.MediaPlayer? = null
    private var resumeMusic = false

    /** (request id, track number) playing or loading. */
    var current by mutableStateOf<Pair<Long, Int>?>(null)
        private set
    var loading by mutableStateOf(false)
        private set

    fun toggle(requestId: Long, n: Int) {
        if (current == requestId to n) { stop(); return }
        stop()
        val (url, headers) = app.social.api.reviewStream(requestId, n) ?: return
        current = requestId to n
        loading = true
        val mp = android.media.MediaPlayer()
        player = mp
        runCatching {
            mp.setDataSource(context, android.net.Uri.parse(url), headers)
            mp.setOnPreparedListener {
                if (player !== mp) return@setOnPreparedListener
                loading = false
                pauseMusic()
                it.start()
            }
            mp.setOnCompletionListener { if (player === mp) stop() }
            mp.setOnErrorListener { _, _, _ ->
                if (player === mp) { stop(); Toast.makeText(context, "Couldn't play it", Toast.LENGTH_SHORT).show() }
                true
            }
            mp.prepareAsync()
        }.onFailure { stop(); Toast.makeText(context, it.message ?: "Couldn't play it", Toast.LENGTH_SHORT).show() }
    }

    fun stop() {
        player?.release()
        player = null
        current = null
        loading = false
        if (resumeMusic && !app.player.nowPlaying.value.isPlaying) app.player.togglePlay()
        resumeMusic = false
    }

    private fun pauseMusic() {
        val inJam = app.social.listen.joined.value != null
        if (!inJam && app.player.nowPlaying.value.isPlaying) {
            app.player.togglePlay()
            resumeMusic = true
        }
    }
}

@Composable
private fun rememberReviewPlayer(): ReviewPlayer {
    val context = LocalContext.current
    val player = remember { ReviewPlayer(context) }
    DisposableEffect(player) { onDispose { player.stop() } }
    return player
}

/** A fulfilled request's download: what it is, where it goes, each track to listen to, then approve or reject. */
@Composable
private fun ReviewCard(
    request: MusicRequest,
    comesWith: List<MusicRequest>,
    player: ReviewPlayer,
    busy: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onChangeDestination: () -> Unit,
) {
    val review = request.review ?: return
    val colors = MaterialTheme.colorScheme
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceContainer)
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Row {
            AsyncImage(
                model = review.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(6.dp)).background(colors.surfaceVariant),
            )
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        review.album + (review.year?.let { " ($it)" } ?: ""),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    KindTag(request.item.isMovie, Modifier.padding(start = 8.dp))
                }
                val credits = listOfNotNull(review.composer?.let { "Music $it" }, review.lyricists?.let { "Lyrics $it" }).joinToString(" · ")
                if (credits.isNotEmpty()) Text(credits, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "Asked for ${request.item.label} by ${request.askedBy.joinToString()}",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (review.destination == "existing") "Joins ${review.destinationFolder ?: "its album"} in the library"
                        else "New album: ${review.destinationFolder ?: review.album}",
                        style = MaterialTheme.typography.bodySmall, color = colors.primary, modifier = Modifier.weight(1f, fill = false),
                    )
                    TextButton(onClick = onChangeDestination, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("Change", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (review.tracks.any { it.score } && review.scoreFolder != null) {
                    Text("Background score → ${review.scoreFolder}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                if (review.skipped.isNotEmpty()) {
                    Text("Already in the library, not downloaded: ${review.skipped.joinToString()}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
        review.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = colors.primary, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(6.dp))
        review.tracks.forEach { t ->
            val playing = player.current == request.id to t.n
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { player.toggle(request.id, t.n) }) {
                    if (playing && player.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(
                        androidx.compose.ui.res.painterResource(if (playing) io.github.devasenan134.isaipetti.R.drawable.ic_pause else io.github.devasenan134.isaipetti.R.drawable.ic_play),
                        contentDescription = if (playing) "Stop" else "Play ${t.title}",
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("${t.n}. ${t.title}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(t.singers, t.durationMs?.let(::clock), t.bitrate?.let { "$it kbps" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (t.score) LabelTag("Score", highlighted = false, modifier = Modifier.padding(start = 8.dp))
                LabelTag(
                    when (t.lyrics) { "synced" -> "Synced lyrics"; "plain" -> "Lyrics"; else -> "No lyrics" },
                    highlighted = t.lyrics != "none",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (comesWith.isNotEmpty()) {
            Text(
                "Also closes " + comesWith.joinToString("; ") { "“${it.item.title}” for ${it.askedBy.joinToString()}" },
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            review.source?.let { source ->
                TextButton(onClick = { runCatching { uri.openUri(source) } }) {
                    Text("Matched page" + (review.score?.let { " · score ${it.toInt()}" } ?: ""), style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onReject, enabled = !busy) { Text("Reject") }
            Button(onClick = onApprove, enabled = !busy) { Text("Approve") }
        }
    }
}

private val SHOWS = listOf("all" to "All", "open" to "Open", "closed" to "Closed", "notfound" to "Not found")

private fun shows(show: String, r: MusicRequest) = when (show) {
    "open" -> r.status == "open"
    "closed" -> r.status == "done"
    "notfound" -> r.status == "declined"
    else -> true
}

/**
 * Picks an album from the library: to close a request with (or point a closed one at the right album), or as where
 * a download goes ([forDownload]: "New album instead" is offered too).
 */
@Composable
private fun AlbumPicker(request: MusicRequest, forDownload: Boolean, onPick: (io.github.devasenan134.isaipetti.data.MovieHit?) -> Unit, onClose: () -> Unit) {
    val app = LocalApp.current
    var query by remember { mutableStateOf(request.review?.album ?: request.item.movie) }
    var found by remember { mutableStateOf<List<io.github.devasenan134.isaipetti.data.MovieHit>?>(null) }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { found = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(250)
        found = runCatching { app.social.api.search(q).movies }.getOrDefault(emptyList())
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (forDownload) "Where does it go?" else if (request.status == "done") "Point it at another album" else "Which album is it?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (forDownload) "The download joins the album you pick, with its tags, when you approve it."
                    else "Closes ${request.item.label} with the album you pick" + (if (request.status == "done") "." else "; the people who asked are told."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(query, { query = it }, label = { Text("Search the library") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val albums = found
                when {
                    albums == null -> CircularProgressIndicator(Modifier.size(24.dp))
                    albums.isEmpty() -> Text("No albums found.", style = MaterialTheme.typography.bodySmall)
                    else -> LazyColumn(Modifier.height(300.dp)) {
                        items(albums, key = { it.id }) { a ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onPick(a) }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                io.github.devasenan134.isaipetti.ui.components.Cover(a.coverArt, Modifier.size(44.dp), size = 150, corner = 4.dp)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(a.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOfNotNull(a.year?.toString(), a.composer, "${a.songCount} songs").joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        dismissButton = if (forDownload) ({ TextButton(onClick = { onPick(null) }) { Text("New album instead") } }) else null,
    )
}

private fun clock(ms: Long): String {
    val s = (ms + 500) / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** "Album" or "Song", so a request's kind shows at a glance. */
@Composable
private fun KindTag(album: Boolean, modifier: Modifier = Modifier) = LabelTag(if (album) "Album" else "Song", album, modifier)

/** A small tag: filled in the accent colour when [highlighted], outlined otherwise. */
@Composable
private fun LabelTag(text: String, highlighted: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(4.dp)
    val colors = MaterialTheme.colorScheme
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (highlighted) colors.primary else colors.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier
            .clip(shape)
            .then(if (highlighted) Modifier.background(colors.primary.copy(alpha = 0.18f)) else Modifier.border(1.dp, colors.outline, shape))
            .padding(horizontal = 7.dp, vertical = 1.dp),
    )
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
