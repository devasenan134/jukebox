package io.github.devasenan134.isaipetti.ui.mixes

import io.github.devasenan134.isaipetti.ui.components.UiSize
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import io.github.devasenan134.isaipetti.data.MIX_AUTHOR
import io.github.devasenan134.isaipetti.data.Playlist
import io.github.devasenan134.isaipetti.data.Song
import io.github.devasenan134.isaipetti.ui.components.Cover
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.SectionTitle
import kotlinx.coroutines.launch

/** Under a playlist you made: songs that would fit it, each with a button to add it (like Spotify's "Recommended"). */
fun LazyListScope.recommendedSongs(playlist: Playlist, onAdded: () -> Unit) {
    item(key = "recommended") { RecommendedSongs(playlist, onAdded) }
}

@Composable
private fun RecommendedSongs(playlist: Playlist, onAdded: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ids = remember(playlist) { playlist.entry.map { it.id } }
    var page by remember(playlist.id) { mutableIntStateOf(0) }
    var added by remember(playlist.id) { mutableStateOf(emptySet<String>()) }
    // Null while loading, or when the friends server has no mixes (then nothing shows).
    val songs by produceState<List<Song>?>(null, ids, page) {
        value = runCatching { app.social.api.recommend(ids, count = 10, page = page).map { it.toSong() } }.getOrNull()
    }
    val list = songs ?: return
    Column(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Recommended songs", Modifier.weight(1f))
            if (ids.isNotEmpty()) TextButton(onClick = { page = (page + 1) % 10 }, modifier = Modifier.padding(end = 8.dp, top = 12.dp)) { Text("Refresh") }
        }
        Text(
            if (ids.isEmpty()) "Add a few songs, and $MIX_AUTHOR will suggest more like them."
            else "By $MIX_AUTHOR, based on the songs in this playlist",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        list.filter { it.id !in added }.forEach { song ->
            Row(
                Modifier.fillMaxWidth().clickable { app.player.play(listOf(song)) }.padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(song.coverArt, Modifier.size(UiSize.SongThumb), size = 150, corner = 4.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(song.artist, song.album).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = {
                    scope.launch {
                        runCatching { app.api.updatePlaylist(playlist.id, addSongIds = listOf(song.id)) }
                            .onSuccess {
                                added = added + song.id
                                onAdded()
                                Toast.makeText(context, "Added to ${playlist.name}", Toast.LENGTH_SHORT).show()
                            }
                            .onFailure { Toast.makeText(context, it.message ?: "Couldn't add it", Toast.LENGTH_SHORT).show() }
                    }
                }) { Icon(Icons.Filled.Add, contentDescription = "Add to ${playlist.name}") }
            }
        }
    }
}
