package io.github.devasenan134.isaipetti.ui.mixes

import io.github.devasenan134.isaipetti.ui.components.UiSize
import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.devasenan134.isaipetti.R
import io.github.devasenan134.isaipetti.data.MIX_AUTHOR
import io.github.devasenan134.isaipetti.data.Mix
import io.github.devasenan134.isaipetti.ui.Nav
import io.github.devasenan134.isaipetti.ui.components.LikeButton
import io.github.devasenan134.isaipetti.ui.components.LoadableContent
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.ScreenHeader
import io.github.devasenan134.isaipetti.ui.components.SongRow
import io.github.devasenan134.isaipetti.ui.components.formatTotalDuration
import io.github.devasenan134.isaipetti.ui.components.rememberLoader
import io.github.devasenan134.isaipetti.ui.components.rememberPageTint
import io.github.devasenan134.isaipetti.ui.components.pageGradient
import io.github.devasenan134.isaipetti.ui.components.songCount
import kotlinx.coroutines.launch

/** A mix or station by Isai Pettai: its artwork, what it is, and its songs. */
@Composable
fun MixScreen(id: String, nav: Nav) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loader = rememberLoader("mix-$id") { app.social.api.mix(id) }
    val followed by app.mixes.followed.collectAsStateWithLifecycle()
    val nowPlaying by app.player.nowPlaying.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    val tint = rememberPageTint((loader.state as? io.github.devasenan134.isaipetti.ui.components.Loadable.Ready)?.value?.tint())

    Column {
        ScreenHeader("", onBack = nav.back, color = tint) {
            val mix = (loader.state as? io.github.devasenan134.isaipetti.ui.components.Loadable.Ready)?.value
            if (mix != null && !mix.endless && mix.songs.isNotEmpty()) {
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Save a copy as a playlist") },
                            onClick = {
                                menuOpen = false
                                scope.launch { saveAsPlaylist(mix, app, context, nav) }
                            },
                        )
                    }
                }
            }
        }
        LoadableContent(loader) { mix ->
            val songs = remember(mix) { mix.songs.map { it.toSong() } }
            val play = { index: Int, shuffle: Boolean ->
                app.activity.mix(mix)
                app.player.play(songs, index, shuffle = shuffle, source = mix.source)
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                item {
                    Column(Modifier.fillMaxWidth().pageGradient(tint).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        MixCover(mix, size = UiSize.HeaderArt)
                        Text(mix.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
                        // The author, like "Made for you by Spotify".
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_music_note), contentDescription = null, tint = mix.tint(), modifier = Modifier.size(16.dp))
                            val madeFor = madeForName()
                            Text(
                                (if (mix.personal && madeFor != null) "Made for $madeFor · " else "") + "By $MIX_AUTHOR",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                        Text(
                            mix.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Text(
                            if (mix.endless) "Station · plays endlessly"
                            else listOf(songCount(songs.size), formatTotalDuration(songs.sumOf { it.duration }), updatedText(mix.updatedAt))
                                .filter { it.isNotEmpty() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { play(0, false) }, enabled = songs.isNotEmpty()) {
                                Icon(if (mix.endless) painterResource(R.drawable.ic_radio) else painterResource(R.drawable.ic_play), contentDescription = null, Modifier.size(20.dp))
                                Text(if (mix.endless) "Play station" else "Play", Modifier.padding(start = 8.dp))
                            }
                            if (!mix.endless) {
                                FilledTonalButton(onClick = { play(0, true) }, enabled = songs.isNotEmpty()) {
                                    Icon(painterResource(R.drawable.ic_shuffle), contentDescription = null)
                                    Text("Shuffle", Modifier.padding(start = 8.dp))
                                }
                            }
                            // Saving keeps it in Your Library, where it goes on updating.
                            LikeButton(followed.any { it.id == mix.id }, onToggle = {
                                scope.launch {
                                    val saving = !app.mixes.isFollowed(mix.id)
                                    runCatching { app.mixes.toggleFollow(mix) }
                                        .onSuccess { Toast.makeText(context, if (saving) "Saved to Your Library" else "Removed from Your Library", Toast.LENGTH_SHORT).show() }
                                        .onFailure { Toast.makeText(context, it.message ?: "Couldn't change it", Toast.LENGTH_SHORT).show() }
                                }
                            })
                        }
                        if (mix.endless) {
                            Text(
                                "Up first",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            )
                        }
                    }
                }
                if (songs.isEmpty()) {
                    item {
                        Text(
                            "Nothing here right now. Mixes fill up as you listen.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                itemsIndexed(songs, key = { index, song -> "$index-${song.id}" }) { index, song ->
                    SongRow(
                        song = song,
                        onClick = { play(index, false) },
                        isCurrent = song.id == nowPlaying.songId,
                        showCover = true,
                        onOpenAlbum = nav.openAlbum,
                    )
                }
            }
        }
    }
}

/** Copies the mix's songs as they are today into a new Navidrome playlist of your own (it won't change after that). */
private suspend fun saveAsPlaylist(mix: Mix, app: io.github.devasenan134.isaipetti.IsaipettiApp, context: android.content.Context, nav: Nav) {
    runCatching {
        val playlist = app.api.createPlaylist(mix.title)
        val today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))
        app.api.updatePlaylist(
            playlist.id,
            addSongIds = mix.songs.map { it.id },
            comment = "By $MIX_AUTHOR. ${mix.title} as it was on $today.",
        )
        playlist
    }.onSuccess {
        app.myPlaylists.refresh()
        Toast.makeText(context, "Saved as a playlist", Toast.LENGTH_SHORT).show()
        nav.openPlaylist(it.id)
    }.onFailure { Toast.makeText(context, it.message ?: "Couldn't save it", Toast.LENGTH_SHORT).show() }
}
