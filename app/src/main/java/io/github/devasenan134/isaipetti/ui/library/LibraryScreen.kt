package io.github.devasenan134.isaipetti.ui.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.automirrored.filled.List
import io.github.devasenan134.isaipetti.ui.mixes.madeForName
import io.github.devasenan134.isaipetti.ui.components.UiSize
import androidx.compose.foundation.background
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Add
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.devasenan134.isaipetti.R
import io.github.devasenan134.isaipetti.data.Playlist
import io.github.devasenan134.isaipetti.ui.Nav
import io.github.devasenan134.isaipetti.ui.components.rememberPageTint
import io.github.devasenan134.isaipetti.ui.components.pageGradient
import io.github.devasenan134.isaipetti.ui.mixes.MixCover
import io.github.devasenan134.isaipetti.data.MIX_AUTHOR
import io.github.devasenan134.isaipetti.ui.components.Cover
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.ScreenHeader
import io.github.devasenan134.isaipetti.ui.components.SongRow
import io.github.devasenan134.isaipetti.ui.components.formatTotalDuration
import io.github.devasenan134.isaipetti.ui.components.songCount

/** The sections of Your Library, in the order of the chips (swipe between them). */
private enum class LibraryFilter(val label: String) { All("All"), Playlists("Playlists"), Movies("Movies"), Mine("My Playlists") }

/** Your Library: liked songs, saved mixes, liked movies, liked playlists and playlists you created. */
@Composable
fun LibraryScreen(nav: Nav) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val likedSongs by app.likes.songs.collectAsStateWithLifecycle()
    val likedAlbums by app.likes.albums.collectAsStateWithLifecycle()
    val likedPlaylists by app.likes.playlists.collectAsStateWithLifecycle()
    // Mixes by Isai Pettai you saved; they keep updating here.
    val savedMixes by app.mixes.followed.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { app.likes.refresh(); app.mixes.refresh() }
    val credentials by app.session.credentials.collectAsStateWithLifecycle()
    // Playlists you created in Navidrome, next to the ones you liked.
    var reloadOwn by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    val ownPlaylists by produceState(emptyList<Playlist>(), credentials?.username, reloadOwn) {
        value = runCatching { app.api.playlists().filter { it.owner == credentials?.username } }.getOrDefault(emptyList())
    }
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.All) }
    // Liked first (newest like first), then the rest of your own. Every playlist is under Search → Playlists.
    val playlists = likedPlaylists + ownPlaylists.filter { own -> likedPlaylists.none { it.id == own.id } }

    // List or grid, remembered on this phone.
    val prefs = remember { context.getSharedPreferences("library", android.content.Context.MODE_PRIVATE) }
    var grid by remember { mutableStateOf(prefs.getBoolean("grid", false)) }

    Column {
        ScreenHeader("Your Library") {
            IconButton(onClick = {
                grid = !grid
                prefs.edit().putBoolean("grid", grid).apply()
            }) {
                if (grid) Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Show as list")
                else Icon(painterResource(R.drawable.ic_grid), contentDescription = "Show as grid")
            }
            IconButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, contentDescription = "New playlist") }
        }
        if (creating) {
            NameDialog(title = "New playlist", confirm = "Create", onDismiss = { creating = false }) { name ->
                scope.launch {
                    runCatching { app.api.createPlaylist(name) }
                        .onSuccess { reloadOwn++; nav.openPlaylist(it.id) }
                        .onFailure { Toast.makeText(context, it.message ?: "Couldn't create it", Toast.LENGTH_SHORT).show() }
                    creating = false
                }
            }
        }
        // Swipe sideways between All, Movies and Playlists, or tap a chip.
        val pager = rememberPagerState(initialPage = filter.ordinal) { LibraryFilter.entries.size }
        LaunchedEffect(pager.currentPage) { filter = LibraryFilter.entries[pager.currentPage] }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(LibraryFilter.entries) { f ->
                FilterChip(
                    selected = pager.targetPage == f.ordinal,
                    onClick = { scope.launch { pager.animateScrollToPage(f.ordinal) } },
                    label = { Text(f.label) },
                )
            }
        }
        // Pages start at the top (a pager centres them by default).
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { it }, verticalAlignment = Alignment.Top) { page ->
            val filter = LibraryFilter.entries[page]
            val madeFor = madeForName()
            // "Playlists": mixes and playlists you saved from others. "My Playlists": Liked songs and the ones you made.
            val mine = { p: Playlist -> p.owner == credentials?.username }
            val showMine = filter == LibraryFilter.All || filter == LibraryFilter.Mine
            val showSaved = filter == LibraryFilter.All || filter == LibraryFilter.Playlists
            val entries = buildList {
                if (showMine) {
                    add(LibraryEntry("liked", "Liked songs", "Playlist · ${songCount(likedSongs.size)}", nav.openLikedSongs) { LikedTile(it) })
                }
                if (showSaved) {
                    savedMixes.forEach { mix ->
                        val subtitle = listOfNotNull(
                            if (mix.personal && madeFor != null) "Made for $madeFor" else if (mix.endless) "Station" else "Mix",
                            "by $MIX_AUTHOR",
                            if (mix.endless || mix.songCount == 0) null else songCount(mix.songCount),
                        ).joinToString(" · ")
                        add(LibraryEntry("mix-${mix.id}", mix.title, subtitle, { nav.openMix(mix.id) }, round = mix.round) { MixCover(mix, size = it) })
                    }
                }
                playlists.filter { if (mine(it)) showMine else showSaved }.forEach { playlist ->
                    val subtitle = listOfNotNull("Playlist", playlist.owner?.let { "by $it" }, songCount(playlist.songCount)).joinToString(" · ")
                    add(LibraryEntry("playlist-${playlist.id}", playlist.name, subtitle, { nav.openPlaylist(playlist.id) }) {
                        Cover(playlist.coverArt, Modifier.size(it), size = 300, corner = 6.dp)
                    })
                }
                if (filter == LibraryFilter.All || filter == LibraryFilter.Movies) {
                    likedAlbums.forEach { album ->
                        add(LibraryEntry("album-${album.id}", album.name, listOfNotNull("Movie", album.artist).joinToString(" · "), { nav.openAlbum(album.id) }) {
                            Cover(album.coverArt, Modifier.size(it), size = 300, corner = 6.dp)
                        })
                    }
                }
            }
            // Liked songs is always there in All and My Playlists, so "empty" means nothing else is.
            val empty = entries.none { it.key != "liked" }
            val emptyHint: @Composable () -> Unit = {
                Text(
                    when (filter) {
                        LibraryFilter.Movies -> "Tap ♡ on a movie to keep it here."
                        LibraryFilter.Playlists -> "Tap ♡ on playlists and mixes to keep them here."
                        LibraryFilter.Mine -> "Playlists you make show up here. Tap + to make one."
                        LibraryFilter.All -> "Tap ♡ on songs, movies, playlists and mixes to keep them here."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (grid) {
                LazyVerticalGrid(columns = GridCells.Adaptive(UiSize.LibraryGridCell), contentPadding = PaddingValues(8.dp)) {
                    items(entries, key = { it.key }) { entry -> LibraryTile(entry) }
                    if (empty) item(span = { GridItemSpan(maxLineSpan) }) { emptyHint() }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(entries, key = { it.key }) { entry ->
                        LibraryRow(entry.title, entry.subtitle, leading = { entry.art(UiSize.LibraryThumb) }, onClick = entry.onClick)
                    }
                    if (empty) item { emptyHint() }
                }
            }
        }
    }
}

/** One thing in Your Library, shown as a row or a tile. [art] draws its picture at a given size. */
private class LibraryEntry(
    val key: String,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit,
    val round: Boolean = false,
    val art: @Composable (Dp) -> Unit,
)

/** Grid view: a big picture with the name and what it is below. */
@Composable
private fun LibraryTile(entry: LibraryEntry) {
    Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = entry.onClick).padding(6.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) { entry.art(maxWidth) }
        Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(
            entry.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** All your liked songs, newest likes first, with Play and Shuffle. */
@Composable
fun LikedSongsScreen(nav: Nav) {
    val app = LocalApp.current
    val player = app.player
    val songs by app.likes.songs.collectAsStateWithLifecycle()
    val nowPlaying by player.nowPlaying.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { app.likes.refresh() }
    val tint = rememberPageTint(MaterialTheme.colorScheme.primary)
    Column {
        ScreenHeader("", onBack = nav.back, color = tint)
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Column(Modifier.fillMaxWidth().pageGradient(tint).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    LikedTile(UiSize.HeaderArt)
                    Text("Liked songs", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
                    Text(
                        "${songCount(songs.size)} · ${formatTotalDuration(songs.sumOf { it.duration })}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { app.activity.liked(); player.play(songs, source = LIKED) }, enabled = songs.isNotEmpty()) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Text("Play", Modifier.padding(start = 8.dp))
                        }
                        FilledTonalButton(onClick = { app.activity.liked(); player.play(songs, shuffle = true, source = LIKED) }, enabled = songs.isNotEmpty()) {
                            Icon(painterResource(R.drawable.ic_shuffle), contentDescription = null)
                            Text("Shuffle", Modifier.padding(start = 8.dp))
                        }
                    }
                    Box(Modifier.padding(top = 8.dp)) { ResumeButton(LIKED, songs) { app.activity.liked() } }
                }
            }
            if (songs.isEmpty()) {
                item {
                    Text(
                        "Songs you like show up here. Tap ♡ in the player, or Like in a song's ⋮ menu.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                SongRow(
                    song = song,
                    onClick = { app.activity.song(song); player.play(songs, index, source = LIKED) },
                    isCurrent = song.id == nowPlaying.songId,
                    showCover = true,
                    onOpenAlbum = nav.openAlbum,
                    inLikedSongs = true,
                )
            }
        }
    }
}

@Composable
private fun LibraryRow(title: String, subtitle: String, leading: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Queue source for Liked songs, so it can be resumed like a playlist. */
private const val LIKED = "liked"

/** The "Liked songs" artwork: a heart on the brand colour. */
@Composable
internal fun LikedTile(size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 10)).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(size * 0.45f))
    }
}
