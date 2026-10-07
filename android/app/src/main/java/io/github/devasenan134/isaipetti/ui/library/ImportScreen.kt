package io.github.devasenan134.isaipetti.ui.library

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.devasenan134.isaipetti.data.ImportPreview
import io.github.devasenan134.isaipetti.data.ImportRow
import io.github.devasenan134.isaipetti.data.MixSong
import io.github.devasenan134.isaipetti.ui.Nav
import io.github.devasenan134.isaipetti.ui.components.Cover
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.ScreenHeader
import io.github.devasenan134.isaipetti.ui.components.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SOURCES = mapOf("spotify" to "Spotify", "apple" to "Apple Music", "youtube" to "YouTube", "file" to "your file")

/** Files bigger than this aren't playlists (the server takes up to 12 MB). */
private const val MAX_FILE_BYTES = 12L * 1024 * 1024

/**
 * Importing a playlist (screens/ImportScreen.tsx on the website): a link to a Spotify, Apple Music or YouTube
 * playlist, an exported file (CSV, M3U, Apple Music XML) or a pasted list. The server reads the songs and finds
 * them in the library; here you check the matches, pick where it wasn't sure, request what's missing, and save
 * it as a playlist of yours. [sharedLink] is a link shared to the app from another app (Spotify's Share).
 */
@Composable
fun ImportScreen(nav: Nav, sharedLink: String? = null) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by rememberSaveable { mutableStateOf(sharedLink.orEmpty()) }
    var text by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }

    fun read(url: String? = null, body: String? = null, name: String? = null) {
        busy = true
        scope.launch {
            runCatching { app.social.api.importPreview(url, body, name) }
                .onSuccess { preview = it }
                .onFailure { Toast.makeText(context, it.message ?: "Couldn't read that playlist", Toast.LENGTH_LONG).show() }
            busy = false
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val file = runCatching { withContext(Dispatchers.IO) { readText(context, uri) } }.getOrNull()
            when {
                file == null -> Toast.makeText(context, "Couldn't read that file", Toast.LENGTH_SHORT).show()
                file.second.length > MAX_FILE_BYTES -> Toast.makeText(context, "That file is too big (12 MB at most)", Toast.LENGTH_SHORT).show()
                else -> read(body = file.second, name = file.first)
            }
        }
    }

    // Opened with a shared link: look it up straight away.
    androidx.compose.runtime.LaunchedEffect(sharedLink) { if (!sharedLink.isNullOrBlank()) read(url = sharedLink) }

    preview?.let { p ->
        ImportReview(p, onBack = { preview = null }, onSaved = { id ->
            app.myPlaylists.refresh()
            nav.back()
            nav.openPlaylist(id)
        })
        return
    }

    Column {
        ScreenHeader("Import a playlist", onBack = nav.back)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("From a link", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "A public playlist on Spotify, Apple Music, YouTube or YouTube Music. In that app: Share › Copy link, or share it straight to Isaipetti.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        link, { link = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://open.spotify.com/playlist/…") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { read(url = link.trim()) }, enabled = link.isNotBlank() && !busy) { Text("Find songs") }
                        TextButton(onClick = {
                            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                            clip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()?.let { link = it }
                        }) { Text("Paste") }
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("From a file", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "A CSV from Exportify (all of a Spotify playlist), TuneMyMusic or Soundiiz, an M3U playlist, or an Apple Music export.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(onClick = { pickFile.launch(arrayOf("text/*", "application/xml", "audio/x-mpegurl", "application/vnd.apple.mpegurl", "*/*")) }, enabled = !busy) {
                        Text("Choose a file")
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Or paste a list", style = MaterialTheme.typography.titleMedium)
                    Text("One song a line, like \"Kanave Kanave - Anirudh\".", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(text, { text = it }, minLines = 4, modifier = Modifier.fillMaxWidth())
                    FilledTonalButton(onClick = { read(body = text, name = "Imported playlist") }, enabled = text.isNotBlank() && !busy) { Text("Find songs") }
                }
            }
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Reading the playlist and finding its songs…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** A chosen file's name (without its extension) and text. */
private fun readText(context: Context, uri: Uri): Pair<String, String> {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }?.substringBeforeLast('.') ?: "Imported playlist"
    val text = context.contentResolver.openInputStream(uri)?.use { input ->
        val bytes = input.readNBytes((MAX_FILE_BYTES + 1).toInt())
        String(bytes, Charsets.UTF_8)
    } ?: error("unreadable")
    return name to text
}

/** Checking the matches before saving. */
@Composable
private fun ImportReview(preview: ImportPreview, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(preview.name) }
    // Per row: the song chosen for it (the match, a choice, or none), and whether it goes in.
    val chosen = remember { mutableStateListOf<MixSong?>().apply { addAll(preview.rows.map { it.match }) } }
    val included = remember { mutableStateListOf<Boolean>().apply { addAll(preview.rows.map { it.match != null }) } }
    val requested = remember { mutableStateMapOf<Int, Boolean>() } // false: asking, true: done
    var saving by remember { mutableStateOf(false) }
    val found = preview.rows.count { it.match != null }
    val songs = chosen.indices.mapNotNull { i -> chosen[i]?.takeIf { included[i] } }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ScreenHeader("Import a playlist", onBack = onBack)
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(100) }, label = { Text("Playlist name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(
                    "$found of ${preview.rows.size} songs from ${SOURCES[preview.source] ?: preview.source} are in the library." +
                        if (found < preview.rows.size) " The others can be requested." else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                preview.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Button(
                    onClick = {
                        saving = true
                        scope.launch {
                            runCatching { app.social.api.importCreate(name.trim().ifEmpty { preview.name }, songs.map { it.id }) }
                                .onSuccess {
                                    Toast.makeText(context, "Saved with ${it.songCount} songs", Toast.LENGTH_SHORT).show()
                                    onSaved(it.playlistId)
                                }
                                .onFailure {
                                    Toast.makeText(context, it.message ?: "Couldn't save it", Toast.LENGTH_LONG).show()
                                    saving = false
                                }
                        }
                    },
                    enabled = songs.isNotEmpty() && !saving,
                ) { Text(if (saving) "Saving…" else "Save playlist (${songs.size} songs)") }
            }
        }
        itemsIndexed(preview.rows) { i, row ->
            ImportRowView(
                row, chosen[i], included[i], requested[i],
                onInclude = { included[i] = it },
                onChoose = { s -> chosen[i] = s; included[i] = s != null },
                onRequest = {
                    requested[i] = false
                    scope.launch {
                        runCatching { app.social.api.importRequest(row.track) }
                            .onSuccess { requested[i] = true }
                            .onFailure {
                                requested.remove(i)
                                Toast.makeText(context, it.message ?: "Couldn't request it", Toast.LENGTH_LONG).show()
                            }
                    }
                },
            )
        }
    }
}

@Composable
private fun ImportRowView(
    row: ImportRow,
    chosen: MixSong?,
    included: Boolean,
    request: Boolean?,
    onInclude: (Boolean) -> Unit,
    onChoose: (MixSong?) -> Unit,
    onRequest: () -> Unit,
) {
    val t = row.track
    var menu by remember { mutableStateOf(false) }
    val unsure = chosen != null && !row.sure && chosen.id == row.match?.id
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        // The song as the other service has it…
        Text(t.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(t.artists.joinToString().ifEmpty { null }, t.durationMs?.let { formatDuration((it / 1000).toInt()) }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // …and what it is here.
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (chosen != null) {
                Checkbox(checked = included, onCheckedChange = onInclude)
                Cover(chosen.coverArt, Modifier.size(40.dp), size = 150, corner = 4.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(chosen.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(chosen.album, chosen.artist).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (unsure) Text("Is this the one?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            } else {
                Text(
                    if (row.choices.isNotEmpty()) "Not sure which song this is" else "Not in the library",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
            }
            if (row.choices.size > (if (chosen != null) 1 else 0)) {
                Box {
                    TextButton(onClick = { menu = true }) { Text(if (chosen != null) "Change" else "Pick") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        row.choices.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(listOfNotNull(c.title, c.album, c.artist).joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                onClick = { menu = false; onChoose(c) },
                            )
                        }
                        DropdownMenuItem(text = { Text("None of these") }, onClick = { menu = false; onChoose(null) })
                    }
                }
            }
            if (chosen == null) {
                when (request) {
                    true -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Text("Requested", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                    else -> FilledTonalButton(onClick = onRequest, enabled = request == null, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Request") }
                }
            }
        }
    }
}

/** A Spotify, Apple Music or YouTube link in shared text ("Listen to X on Spotify: https://…"). */
fun playlistLinkIn(text: String?): String? =
    text?.let { Regex("""https?://(?:open\.spotify\.com|spotify\.link|music\.apple\.com|(?:www\.|music\.|m\.)?youtube\.com|youtu\.be)/\S+""").find(it)?.value }
