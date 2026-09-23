package io.github.devasenan134.isaipetti.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.devasenan134.isaipetti.data.Album
import io.github.devasenan134.isaipetti.data.Playlist
import io.github.devasenan134.isaipetti.ui.Nav
import io.github.devasenan134.isaipetti.ui.components.AlbumCard
import io.github.devasenan134.isaipetti.ui.components.Cover
import io.github.devasenan134.isaipetti.ui.components.LoadableContent
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.ScreenHeader
import io.github.devasenan134.isaipetti.ui.components.SectionTitle
import io.github.devasenan134.isaipetti.ui.components.rememberLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private data class HomeData(
    val playlists: List<Playlist>,
    val recent: List<Album>,
    val frequent: List<Album>,
    val newest: List<Album>,
    val random: List<Album>,
)

@Composable
fun HomeScreen(nav: Nav) {
    val app = LocalApp.current
    val loader = rememberLoader("home") {
        // Fetch all rows at the same time instead of one after another.
        coroutineScope {
            val playlists = async { app.api.playlists() }
            val recent = async { app.api.albumList("recent", 20) }
            val frequent = async { app.api.albumList("frequent", 20) }
            val newest = async { app.api.albumList("newest", 20) }
            val random = async { app.api.albumList("random", 20) }
            HomeData(playlists.await(), recent.await(), frequent.await(), newest.await(), random.await())
        }
    }

    Column {
        ScreenHeader("இசைப்பெட்டி") {
            IconButton(onClick = loader::reload) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
            IconButton(onClick = nav.openSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
        }
        LoadableContent(loader) { data ->
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                if (data.playlists.isNotEmpty()) {
                    item { SectionTitle("Playlists") }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                            items(data.playlists, key = { it.id }) { PlaylistCard(it) { nav.openPlaylist(it.id) } }
                        }
                    }
                }
                albumRow("Recently played", data.recent, nav)
                albumRow("Most played", data.frequent, nav)
                albumRow("Recently added", data.newest, nav)
                albumRow("Random picks", data.random, nav)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.albumRow(title: String, albums: List<Album>, nav: Nav) {
    if (albums.isEmpty()) return
    item(key = title) {
        Column {
            SectionTitle(title)
            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                items(albums, key = { it.id }) { album ->
                    AlbumCard(album, onClick = { nav.openAlbum(album.id) }, modifier = Modifier.width(140.dp))
                }
            }
        }
    }
}

@Composable
private fun PlaylistCard(playlist: Playlist, onClick: () -> Unit) {
    Column(Modifier.width(140.dp).clickable(onClick = onClick).padding(6.dp)) {
        Cover(playlist.coverArt, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(
            playlist.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            "${playlist.songCount} songs",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
