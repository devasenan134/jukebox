package io.github.devasenan134.isaipetti.ui.library

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.devasenan134.isaipetti.data.Playlist
import io.github.devasenan134.isaipetti.data.RecentActivity
import io.github.devasenan134.isaipetti.data.Song
import io.github.devasenan134.isaipetti.ui.components.Cover
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.songCount
import kotlinx.coroutines.launch

/**
 * "Save to", like Spotify: Liked songs and each of your playlists, ticked where the song already is.
 * Tick or untick, then Done adds or removes it everywhere at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(song: Song, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val username = app.session.credentials.value?.username
    // Your playlists with their songs, to see where this song already is (only yours can be changed).
    var playlists by remember { mutableStateOf<List<Playlist>?>(null) }
    val checked = remember { mutableStateMapOf<String, Boolean>() }
    var liked by remember { mutableStateOf(app.likes.isLiked(song)) }
    var creating by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val mine = runCatching { app.api.playlists().filter { it.owner == username }.sortedBy { it.name.lowercase() } }.getOrDefault(emptyList())
        val full = coroutineScope { mine.map { p -> async { runCatching { app.api.playlist(p.id) }.getOrDefault(p) } }.awaitAll() }
        full.forEach { p -> checked[p.id] = p.entry.any { it.id == song.id } }
        playlists = full
    }

    fun done() {
        val list = playlists.orEmpty() // still loading: only Liked songs can have changed
        saving = true
        scope.launch {
            var changed = 0
            if (liked != app.likes.isLiked(song)) {
                app.likes.toggle(song)
                changed++
            }
            val failed = mutableListOf<String>()
            for (p in list) {
                val had = p.entry.any { it.id == song.id }
                val want = checked[p.id] == true
                if (had == want) continue
                runCatching {
                    if (want) app.api.updatePlaylist(p.id, addSongIds = listOf(song.id))
                    else app.api.updatePlaylist(p.id, removeIndexes = p.entry.indices.filter { p.entry[it].id == song.id })
                }.onSuccess { changed++ }.onFailure { failed += p.name }
            }
            val message = when {
                failed.isNotEmpty() -> "Couldn't change ${failed.joinToString()}"
                changed > 0 -> "Saved"
                else -> null
            }
            message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
            if (changed > 0) app.myPlaylists.refresh()
            onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Save to", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(onClick = ::done, enabled = !saving) { Text(if (saving) "Saving…" else "Done") }
        }
        Text(
            song.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyColumn(Modifier.padding(top = 8.dp, bottom = 16.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().clickable { creating = true }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(48.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, contentDescription = null) }
                    }
                    Text("New playlist", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 14.dp))
                }
            }
            item {
                SaveRow(
                    title = "Liked songs",
                    subtitle = null,
                    leading = {
                        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary) }
                        }
                    },
                    checked = liked,
                    onChange = { liked = it },
                )
            }
            val list = playlists
            if (list == null) {
                item { Text("Loading your playlists…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
            }
            items(list.orEmpty(), key = { it.id }) { playlist ->
                SaveRow(
                    title = playlist.name,
                    // Navidrome won't change the songs of smart playlists or ones synced from a file.
                    subtitle = if (playlist.readonly) "Can't be changed here (smart or file playlist)" else songCount(playlist.songCount),
                    leading = { Cover(playlist.coverArt, Modifier.size(48.dp), size = 150, corner = 6.dp) },
                    checked = checked[playlist.id] == true,
                    onChange = { checked[playlist.id] = it },
                    enabled = !playlist.readonly,
                )
            }
        }
    }

    if (creating) {
        NameDialog(title = "New playlist", confirm = "Create", onDismiss = { creating = false }) { name ->
            scope.launch {
                runCatching { app.api.createPlaylist(name, songId = song.id) }
                    .onSuccess { created ->
                        // It's made with the song already in it: show it ticked.
                        val withSong = created.copy(entry = listOf(song), songCount = 1)
                        playlists = listOf(withSong) + playlists.orEmpty()
                        checked[created.id] = true
                        Toast.makeText(context, "Created \"${created.name}\"", Toast.LENGTH_SHORT).show()
                    }
                    .onFailure { Toast.makeText(context, it.message ?: "Couldn't create it", Toast.LENGTH_SHORT).show() }
                creating = false
            }
        }
    }
}

@Composable
private fun SaveRow(
    title: String,
    subtitle: String?,
    leading: @Composable () -> Unit,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }
            .alpha(if (enabled) 1f else 0.5f)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Asks for a playlist name. */
@Composable
internal fun NameDialog(title: String, confirm: String, initial: String = "", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it.take(100) }, label = { Text("Name") }, singleLine = true)
        },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The ⋮ menu on a playlist you made: rename it, make it public or private, or delete it.
 * Deleting asks twice, because it can't be undone.
 */
@Composable
fun PlaylistOwnerMenu(playlist: Playlist, onChanged: () -> Unit, onDeleted: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleteStep by remember { mutableStateOf(0) } // 0: not deleting, 1: first question, 2: second

    fun change(what: suspend () -> Unit, done: String? = null) {
        scope.launch {
            runCatching { what() }
                .onSuccess {
                    done?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                    app.myPlaylists.refresh()
                    onChanged()
                }
                .onFailure { Toast.makeText(context, it.message ?: "Couldn't change the playlist", Toast.LENGTH_SHORT).show() }
        }
    }

    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Playlist options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Rename") }, onClick = { open = false; renaming = true })
            DropdownMenuItem(
                text = { Text(if (playlist.public) "Make private" else "Make public") },
                onClick = {
                    open = false
                    change(
                        { app.api.updatePlaylist(playlist.id, public = !playlist.public) },
                        if (playlist.public) "Only you can see it now" else "Everyone on the server can see it now",
                    )
                },
            )
            DropdownMenuItem(
                text = { Text("Delete playlist", color = MaterialTheme.colorScheme.error) },
                onClick = { open = false; deleteStep = 1 },
            )
        }
    }

    if (renaming) {
        NameDialog(title = "Rename playlist", confirm = "Save", initial = playlist.name, onDismiss = { renaming = false }) { name ->
            renaming = false
            change({ app.api.updatePlaylist(playlist.id, name = name) })
        }
    }
    when (deleteStep) {
        1 -> AlertDialog(
            onDismissRequest = { deleteStep = 0 },
            title = { Text("Delete \"${playlist.name}\"?") },
            text = { Text("The playlist is removed from the server for everyone. The songs stay in your library.") },
            confirmButton = { Button(onClick = { deleteStep = 2 }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { deleteStep = 0 }) { Text("Cancel") } },
        )
        2 -> AlertDialog(
            onDismissRequest = { deleteStep = 0 },
            title = { Text("Are you sure?") },
            text = { Text("\"${playlist.name}\" and its ${songCount(playlist.songCount)} will be deleted. This can't be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        deleteStep = 0
                        scope.launch {
                            runCatching { app.api.deletePlaylist(playlist.id) }
                                .onSuccess {
                                    if (app.likes.isLiked(playlist)) app.likes.toggle(playlist)
                                    app.recentPlaylists.forget(playlist.id)
                                    app.activity.forget(RecentActivity.Kind.Playlist, playlist.id)
                                    app.myPlaylists.refresh()
                                    Toast.makeText(context, "Playlist deleted", Toast.LENGTH_SHORT).show()
                                    onDeleted()
                                }
                                .onFailure { Toast.makeText(context, it.message ?: "Couldn't delete it", Toast.LENGTH_SHORT).show() }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete for good") }
            },
            dismissButton = { TextButton(onClick = { deleteStep = 0 }) { Text("Keep it") } },
        )
    }
}
