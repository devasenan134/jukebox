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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.devasenan134.isaipetti.data.Album
import io.github.devasenan134.isaipetti.data.Artist
import io.github.devasenan134.isaipetti.data.RecentActivity
import io.github.devasenan134.isaipetti.data.Song
import io.github.devasenan134.isaipetti.ui.Nav
import io.github.devasenan134.isaipetti.ui.components.AlbumCard
import io.github.devasenan134.isaipetti.ui.components.Cover
import io.github.devasenan134.isaipetti.ui.components.LoadableContent
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.ScreenHeader
import io.github.devasenan134.isaipetti.ui.components.SectionTitle
import io.github.devasenan134.isaipetti.ui.components.rememberLoader
import io.github.devasenan134.isaipetti.ui.library.LikedTile
import io.github.devasenan134.isaipetti.ui.mixes.mixSections
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private data class HomeData(
    val recent: List<Album>,
    val frequent: List<Album>,
    val newest: List<Album>,
    val random: List<Album>,
)

@Composable
fun HomeScreen(nav: Nav) {
    val app = LocalApp.current
    val recentlyPlayed by app.activity.items.collectAsStateWithLifecycle()
    // Mixes by Isai Pettai; worked out again on the server whenever the library or your listening changed.
    val mixes by app.mixes.home.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { app.mixes.refresh() }
    val loader = rememberLoader("home") {
        // Fetch all rows at the same time instead of one after another.
        coroutineScope {
            val recent = async { app.api.albumList("recent", 20) }
            val frequent = async { app.api.albumList("frequent", 20) }
            val newest = async { app.api.albumList("newest", 20) }
            val random = async { app.api.albumList("random", 20) }
            HomeData(recent.await(), frequent.await(), newest.await(), random.await())
        }
    }

    Column {
        ScreenHeader("இசைப்பெட்டி") {
            IconButton(onClick = { loader.reload(); app.mixes.refresh() }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
            IconButton(onClick = nav.openSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
        }
        LoadableContent(loader) { data ->
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                // Playlists live under Search → Playlists and in Your Library.
                // Everything you played lately; until there's some, the movies Navidrome says you played.
                if (recentlyPlayed.isNotEmpty()) recentRow(recentlyPlayed, nav) { app.player.play(listOf(it)) }
                else albumRow("Recently played", data.recent, nav)
                mixes?.let { mixSections(it.sections, nav) }
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

/** "Recently played": songs, movies, playlists and Liked songs as squares; composers and artists as circles. */
private fun androidx.compose.foundation.lazy.LazyListScope.recentRow(items: List<RecentActivity.Item>, nav: Nav, playSong: (Song) -> Unit) {
    item(key = "recently-played") {
        Column {
            SectionTitle("Recently played")
            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                items(items, key = { "${it.kind}-${it.id}" }) { item ->
                    RecentTile(item) {
                        when (item.kind) {
                            RecentActivity.Kind.Song -> item.song?.let { playSong(it.toSong()) }
                            RecentActivity.Kind.Movie -> nav.openAlbum(item.id)
                            RecentActivity.Kind.Playlist -> nav.openPlaylist(item.id)
                            RecentActivity.Kind.Composer -> nav.openArtist(item.id)
                            RecentActivity.Kind.Artist -> nav.openSinger(Artist(item.id, item.title, coverArt = item.coverArt, roles = listOf("artist")))
                            RecentActivity.Kind.Liked -> nav.openLikedSongs()
                            RecentActivity.Kind.Mix -> nav.openMix(item.id)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentTile(item: RecentActivity.Item, onClick: () -> Unit) {
    val round = item.kind == RecentActivity.Kind.Composer || item.kind == RecentActivity.Kind.Artist
    Column(
        Modifier.width(140.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(6.dp),
        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        when {
            item.kind == RecentActivity.Kind.Liked -> LikedTile(128.dp)
            round -> Cover(item.coverArt, Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape), size = 300, corner = 64.dp)
            else -> Cover(item.coverArt, Modifier.fillMaxWidth().aspectRatio(1f))
        }
        Text(
            item.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (round) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.padding(top = 6.dp),
        )
        val kind = when (item.kind) {
            RecentActivity.Kind.Song -> "Song"
            RecentActivity.Kind.Movie -> "Movie"
            else -> null
        }
        Text(
            listOfNotNull(kind, item.subtitle).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (round) TextAlign.Center else TextAlign.Start,
        )
    }
}
