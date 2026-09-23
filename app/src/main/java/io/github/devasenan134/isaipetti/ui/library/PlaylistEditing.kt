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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.devasenan134.isaipetti.data.Playlist
import io.github.devasenan134.isaipetti.data.Song
import io.github.devasenan134.isaipetti.ui.components.Cover
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.SectionTitle
import io.github.devasenan134.isaipetti.ui.components.songCount
import kotlinx.coroutines.launch

/** Pick one of your playlists (or make a new one) to add [song] to. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(song: Song, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val username = app.session.credentials.value?.username
    // Only playlists you made can be changed.
    val playlists by produceState<List<Playlist>?>(null) {
        value = runCatching { app.api.playlists().filter { it.owner == username }.sortedBy { it.name.lowercase() } }.getOrDefault(emptyList())
    }
    var creating by remember { mutableStateOf(false) }

    fun added(name: String) {
        Toast.makeText(context, "Added to $name", Toast.LENGTH_SHORT).show()
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        SectionTitle("Add \"${song.title}\" to")
        LazyColumn(Modifier.padding(bottom = 16.dp)) {
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
            val list = playlists
            if (list == null) {
                item { Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
            }
            items(list.orEmpty(), key = { it.id }) { playlist ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        scope.launch {
                            runCatching { app.api.updatePlaylist(playlist.id, addSongIds = listOf(song.id)) }
                                .onSuccess { added(playlist.name) }
                                .onFailure { Toast.makeText(context, it.message ?: "Couldn't add it", Toast.LENGTH_SHORT).show() }
                        }
                    }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(playlist.coverArt, Modifier.size(48.dp), size = 150, corner = 6.dp)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(playlist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(songCount(playlist.songCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (creating) {
        NameDialog(title = "New playlist", confirm = "Create", onDismiss = { creating = false }) { name ->
            scope.launch {
                runCatching { app.api.createPlaylist(name, songId = song.id) }
                    .onSuccess { added(it.name) }
                    .onFailure { Toast.makeText(context, it.message ?: "Couldn't create it", Toast.LENGTH_SHORT).show() }
                creating = false
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, confirm: String, initial: String = "", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
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
                .onSuccess { done?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }; onChanged() }
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
