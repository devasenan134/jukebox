package io.github.devasenan134.isaipetti.ui.home

import io.github.devasenan134.isaipetti.data.SongRef
import io.github.devasenan134.isaipetti.ui.components.SongRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.remember
import io.github.devasenan134.isaipetti.ui.components.UiSize
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
import io.github.devasenan134.isaipetti.data.Mix
import io.github.devasenan134.isaipetti.playback.NowPlaying
import io.github.devasenan134.isaipetti.ui.mixes.MixCover
import io.github.devasenan134.isaipetti.ui.components.SpinningDisc
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
    val playedSongs by app.recent.songs.collectAsStateWithLifecycle()
    val recents = remember(playedSongs, recentlyPlayed) { splitRecents(playedSongs, recentlyPlayed) }
    val nowPlaying by app.player.nowPlaying.collectAsStateWithLifecycle()
    // Mixes by Isai Pettai; worked out again on the server whenever the library or your listening changed.
    val mixes by app.mixes.home.collectAsStateWithLifecycle()
    // Every mix we know, so "Jump back in" can draw a mix with its own art (not just one song's cover).
    val followedMixes by app.mixes.followed.collectAsStateWithLifecycle()
    val knownMixes = remember(mixes, followedMixes) { (mixes?.sections.orEmpty().flatMap { it.mixes } + followedMixes).associateBy { it.id } }
    // What the music is playing from (a playlist, movie, mix…), for the "Now playing" tile.
    val playingFrom = remember(nowPlaying.songId, nowPlaying.source, recentlyPlayed) { playingFrom(nowPlaying, recentlyPlayed) }
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
        ScreenHeader("இசைப்பெட்டி", titleColor = MaterialTheme.colorScheme.primary) {
            IconButton(onClick = { loader.reload(); app.mixes.refresh() }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
            IconButton(onClick = nav.openSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
        }
        LoadableContent(loader) { data ->
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                // 1. Made for you, as tiles.
                val sections = mixes?.sections.orEmpty()
                mixSections(sections.filter { it.id == "made-for-you" }, nav)
                // 2. Every song you played lately, as a list.
                if (recents.songs.isNotEmpty()) {
                    item(key = "recent-songs-title") { SectionTitle("Recently Played") }
                    itemsIndexed(recents.songs, key = { _, song -> "recent-song-${song.id}" }) { index, song ->
                        SongRow(
                            song = song,
                            onClick = { app.activity.song(song); app.player.play(listOf(song)) },
                            isCurrent = song.id == nowPlaying.songId,
                            showCover = true,
                            onOpenAlbum = nav.openAlbum,
                        )
                    }
                }
                // 3. "Jump back in": movies, playlists, artists and mixes you played as a whole, as tiles.
                // Until there's any listening, the movies Navidrome says you played.
                if (recents.collections.isNotEmpty() || playingFrom != null) {
                    recentRow(recents.collections, playingFrom, nowPlaying.isPlaying, knownMixes, nav) { app.player.play(listOf(it)) }
                }
                else if (recents.songs.isEmpty()) albumRow("Jump back in", data.recent, nav)
                mixSections(sections.filter { it.id != "made-for-you" }, nav)
                albumRow("Most played", data.frequent, nav)
                albumRow("Recently added", data.newest, nav)
                albumRow("Random picks", data.random, nav)
            }
        }
    }
}

/**
 * Home's recents. "Recently Played" lists every song that played, however it started (tapped, or next
 * in a movie, playlist or mix); "Jump back in" has the movies, playlists, Liked songs, mixes and artists
 * you started as a whole (Play, Shuffle, Resume).
 */
private data class Recents(val songs: List<Song>, val collections: List<RecentActivity.Item>)

private const val RECENT_SONGS = 6

private fun splitRecents(played: List<SongRef>, items: List<RecentActivity.Item>): Recents = Recents(
    songs = played.take(RECENT_SONGS).map { it.toSong() },
    collections = items.filter { it.kind != RecentActivity.Kind.Song },
)

private fun androidx.compose.foundation.lazy.LazyListScope.albumRow(title: String, albums: List<Album>, nav: Nav) {
    if (albums.isEmpty()) return
    item(key = title) {
        Column {
            SectionTitle(title)
            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                items(albums, key = { it.id }) { album ->
                    AlbumCard(album, onClick = { nav.openAlbum(album.id) }, modifier = Modifier.width(UiSize.Tile))
                }
            }
        }
    }
}

/**
 * Where the music is playing from, as a "Jump back in" item: the playlist, movie, mix, composer,
 * artist or Liked songs it was started from, or else the playing song's movie.
 */
private fun playingFrom(now: NowPlaying, items: List<RecentActivity.Item>): RecentActivity.Item? {
    if (now.songId == null) return null
    val source = now.source.orEmpty()
    val kinds = mapOf(
        "playlist:" to RecentActivity.Kind.Playlist,
        "album:" to RecentActivity.Kind.Movie,
        Mix.SOURCE_PREFIX to RecentActivity.Kind.Mix,
        "composer:" to RecentActivity.Kind.Composer,
        "singer:" to RecentActivity.Kind.Artist,
    )
    val from = if (source == "liked") RecentActivity.Kind.Liked to "liked"
    else kinds.entries.firstOrNull { source.startsWith(it.key) }?.let { it.value to source.removePrefix(it.key) }
    from?.let { (kind, id) -> items.firstOrNull { it.kind == kind && it.id == id } }?.let { return it }
    val albumId = now.albumId ?: return null
    return RecentActivity.Item(RecentActivity.Kind.Movie, albumId, now.album.ifBlank { now.title }, now.artist, now.song?.coverArt)
}

/**
 * "Jump back in": what's playing now first (with a turning record), then movies, playlists, mixes and
 * Liked songs as squares, and composers and artists as circles.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.recentRow(
    items: List<RecentActivity.Item>,
    playing: RecentActivity.Item?,
    isPlaying: Boolean,
    mixes: Map<String, Mix>,
    nav: Nav,
    playSong: (Song) -> Unit,
) {
    fun open(item: RecentActivity.Item) = when (item.kind) {
        RecentActivity.Kind.Song -> item.song?.let { playSong(it.toSong()) } ?: Unit
        RecentActivity.Kind.Movie -> nav.openAlbum(item.id)
        RecentActivity.Kind.Playlist -> nav.openPlaylist(item.id)
        RecentActivity.Kind.Composer -> nav.openArtist(item.id)
        RecentActivity.Kind.Artist -> nav.openSinger(Artist(item.id, item.title, coverArt = item.coverArt, roles = listOf("artist")))
        RecentActivity.Kind.Liked -> nav.openLikedSongs()
        RecentActivity.Kind.Mix -> nav.openMix(item.id)
    }
    // What's playing is already the first tile.
    val rest = items.filterNot { playing != null && it.kind == playing.kind && it.id == playing.id }
    item(key = "recently-played") {
        Column {
            SectionTitle("Jump back in")
            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                if (playing != null) {
                    item(key = "now-playing") { RecentTile(playing, mixes[playing.id], nowPlaying = isPlaying) { open(playing) } }
                }
                items(rest, key = { "${it.kind}-${it.id}" }) { item ->
                    RecentTile(item, mixes[item.id].takeIf { item.kind == RecentActivity.Kind.Mix }) { open(item) }
                }
            }
        }
    }
}

@Composable
private fun RecentTile(
    item: RecentActivity.Item,
    /** For a mix: the mix itself, to draw its own art. */
    mix: Mix?,
    /** Set on the "Now playing" tile: whether the music is playing (the record turns) or paused. */
    nowPlaying: Boolean? = null,
    onClick: () -> Unit,
) {
    val round = item.kind == RecentActivity.Kind.Composer || item.kind == RecentActivity.Kind.Artist
    Column(
        Modifier.width(UiSize.Tile).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(6.dp),
        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Box {
            when {
                item.kind == RecentActivity.Kind.Liked -> LikedTile(UiSize.Tile - 12.dp)
                item.kind == RecentActivity.Kind.Mix && mix != null -> MixCover(mix, size = UiSize.Tile - 12.dp)
                round -> Cover(item.coverArt, Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape), size = 300, corner = 64.dp)
                else -> Cover(item.coverArt, Modifier.fillMaxWidth().aspectRatio(1f))
            }
            if (nowPlaying != null) {
                // Top right: mix art has its name at the bottom and "Isai Pettai" at the top left.
                SpinningDisc(nowPlaying, Modifier.align(Alignment.TopEnd).padding(8.dp).size(56.dp))
            }
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
            if (nowPlaying != null) (if (nowPlaying) "Now playing" else "Paused") else listOfNotNull(kind, item.subtitle).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = if (nowPlaying != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (round) TextAlign.Center else TextAlign.Start,
        )
    }
}
