package io.github.devasenan134.isaipetti.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.devasenan134.isaipetti.R
import io.github.devasenan134.isaipetti.data.Artist
import io.github.devasenan134.isaipetti.data.SearchResult
import io.github.devasenan134.isaipetti.ui.Nav
import io.github.devasenan134.isaipetti.ui.components.AlbumCard
import io.github.devasenan134.isaipetti.ui.components.Cover
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.PlaylistCard
import io.github.devasenan134.isaipetti.ui.components.SectionTitle
import io.github.devasenan134.isaipetti.ui.components.SongRow
import kotlinx.coroutines.delay

/**
 * Search, and browsing:
 *  - before you tap the search bar: Movies, Composers, Artists and Playlists boxes, then what you picked
 *    from searches before (songs, movies, composers, artists) and playlists you played;
 *  - with the search bar tapped but empty: your recent searches;
 *  - once you type: live suggestions, then results.
 */
@Composable
fun SearchScreen(nav: Nav) {
    val app = LocalApp.current
    val focusManager = LocalFocusManager.current
    val nowPlaying by app.player.nowPlaying.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf(SearchResult()) }
    var error by remember { mutableStateOf<String?>(null) }
    val history by app.searches.queries.collectAsStateWithLifecycle()
    // What you picked from search results before, newest first.
    val searchedSongs by app.searches.songs.collectAsStateWithLifecycle()
    val searchedAlbums by app.searches.albums.collectAsStateWithLifecycle()
    val searchedArtists by app.searches.artists.collectAsStateWithLifecycle()
    val recentPlaylists by app.recentPlaylists.playlists.collectAsStateWithLifecycle()
    // A search counts for the history once you use it: press search, or open a result.
    val saveSearch = { app.searches.add(query) }

    // Search live as you type, once typing pauses for a moment. Typing again cancels the pending search.
    LaunchedEffect(query) {
        if (query.isBlank()) {
            result = SearchResult()
            return@LaunchedEffect
        }
        delay(200)
        try {
            result = app.api.search(query.trim())
            error = null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message
        }
    }
    // Back while the search bar is active closes it, before leaving the page.
    BackHandler(enabled = focused || query.isNotEmpty()) {
        query = ""
        focusManager.clearFocus()
    }

    Column {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Songs, movies, composers, singers") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty() || focused) ({
                IconButton(onClick = { query = ""; focusManager.clearFocus() }) { Icon(Icons.Filled.Close, contentDescription = "Close search") }
            }) else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { saveSearch() }),
            modifier = Modifier.fillMaxWidth().padding(16.dp).onFocusChanged { focused = it.isFocused },
        )
        error?.takeIf { query.isNotBlank() }?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            when {
                query.isNotBlank() -> {
                    // Suggestions while typing: matching past searches, then names from the results.
                    val q = query.trim()
                    val past = history.filter { it.contains(q, ignoreCase = true) && !it.equals(q, ignoreCase = true) }.take(3)
                    val names = (result.artist.map { it.name } + result.album.map { it.name } + result.song.map { it.title })
                        .filter { it.contains(q, ignoreCase = true) && !it.equals(q, ignoreCase = true) }
                        .distinctBy { it.lowercase() }
                        .filterNot { name -> past.any { it.equals(name, ignoreCase = true) } }
                        .take(5)
                    items(past, key = { "suggest-past-$it" }) { text ->
                        Suggestion(text, painterResource(R.drawable.ic_history)) { query = text; app.searches.add(text) }
                    }
                    items(names, key = { "suggest-$it" }) { text ->
                        Suggestion(text, rememberVectorPainter(Icons.Filled.Search)) { query = text; app.searches.add(text) }
                    }
                    val composers = result.artist.filter { it.isComposer }
                    val singers = result.artist.filter { !it.isComposer }
                    if (composers.isNotEmpty()) {
                        item { SectionTitle("Composers") }
                        items(composers, key = { "artist-${it.id}" }) { artist ->
                            ArtistResult(artist) { saveSearch(); app.searches.picked(artist); nav.openArtist(artist.id) }
                        }
                    }
                    if (singers.isNotEmpty()) {
                        item { SectionTitle("Artists") }
                        items(singers, key = { "singer-${it.id}" }) { artist ->
                            ArtistResult(artist) { saveSearch(); app.searches.picked(artist); nav.openSinger(artist) }
                        }
                    }
                    if (result.album.isNotEmpty()) {
                        item { SectionTitle("Movies") }
                        item {
                            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                                items(result.album, key = { it.id }) { album ->
                                    AlbumCard(album, onClick = { saveSearch(); app.searches.picked(album); nav.openAlbum(album.id) }, modifier = Modifier.width(140.dp))
                                }
                            }
                        }
                    }
                    if (result.song.isNotEmpty()) {
                        item { SectionTitle("Songs") }
                        itemsIndexed(result.song, key = { _, song -> "song-${song.id}" }) { index, song ->
                            SongRow(
                                song = song,
                                onClick = { saveSearch(); app.searches.picked(song); app.player.play(result.song, index) },
                                isCurrent = song.id == nowPlaying.songId,
                                showCover = true,
                                onOpenAlbum = nav.openAlbum,
                            )
                        }
                    }
                }

                focused -> {
                    if (history.isEmpty()) {
                        item { Hint("Your recent searches show up here.") }
                    } else {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SectionTitle("Recent searches", Modifier.weight(1f))
                                TextButton(onClick = app.searches::clear, modifier = Modifier.padding(top = 12.dp, end = 8.dp)) { Text("Clear") }
                            }
                        }
                        items(history, key = { "q-$it" }) { past ->
                            Row(
                                Modifier.fillMaxWidth().clickable { query = past }.padding(start = 16.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(painterResource(R.drawable.ic_history), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(past, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(horizontal = 16.dp))
                                IconButton(onClick = { app.searches.remove(past) }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove from history", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                else -> {
                    item {
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                BrowseBox("Movies", painterResource(R.drawable.ic_album), MaterialTheme.colorScheme.primaryContainer, nav.openAlbums, Modifier.weight(1f))
                                BrowseBox(
                                    "Composers",
                                    rememberVectorPainter(Icons.Filled.Person),
                                    MaterialTheme.colorScheme.tertiaryContainer,
                                    nav.openArtists,
                                    Modifier.weight(1f),
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                BrowseBox("Artists", painterResource(R.drawable.ic_music_note), MaterialTheme.colorScheme.secondaryContainer, nav.openSingers, Modifier.weight(1f))
                                BrowseBox(
                                    "Playlists",
                                    painterResource(R.drawable.ic_queue),
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    nav.openPlaylists,
                                    Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    if (searchedSongs.isNotEmpty()) {
                        item { SectionTitle("Your recent songs") }
                        val songs = searchedSongs.take(5).map { it.toSong() }
                        itemsIndexed(songs, key = { _, song -> "searched-${song.id}" }) { index, song ->
                            SongRow(
                                song = song,
                                onClick = { app.player.play(songs, index) },
                                isCurrent = song.id == nowPlaying.songId,
                                showCover = true,
                                onOpenAlbum = nav.openAlbum,
                            )
                        }
                    }
                    if (searchedAlbums.isNotEmpty()) {
                        item { SectionTitle("Your recent movies") }
                        item {
                            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                                items(searchedAlbums, key = { "searched-album-${it.id}" }) { album ->
                                    AlbumCard(album, onClick = { nav.openAlbum(album.id) }, modifier = Modifier.width(140.dp))
                                }
                            }
                        }
                    }
                    if (searchedArtists.isNotEmpty()) {
                        item { SectionTitle("Your recent composers and artists") }
                        item {
                            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                                items(searchedArtists, key = { "searched-artist-${it.id}" }) { artist ->
                                    ComposerCard(artist) { if (artist.isComposer) nav.openArtist(artist.id) else nav.openSinger(artist) }
                                }
                            }
                        }
                    }
                    if (recentPlaylists.isNotEmpty()) {
                        item { SectionTitle("Recently played playlists") }
                        item {
                            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                                items(recentPlaylists, key = { "recent-playlist-${it.id}" }) { playlist ->
                                    PlaylistCard(playlist, onClick = { nav.openPlaylist(playlist.id) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A suggestion while typing: tap to search for it. */
@Composable
private fun Suggestion(text: String, icon: Painter, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.dp))
    }
}

/** A composer or singer in the results, with their picture. */
@Composable
private fun ArtistResult(artist: Artist, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(artist.coverArt, Modifier.size(44.dp).clip(CircleShape), size = 150, corner = 22.dp)
        Text(artist.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 14.dp))
    }
}

/** A big coloured box that opens a whole section (Movies, Composers, Artists, Playlists). */
@Composable
private fun BrowseBox(label: String, icon: Painter, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.height(80.dp).clip(RoundedCornerShape(12.dp)).background(color).clickable(onClick = onClick).padding(14.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge, maxLines = 1, modifier = Modifier.align(Alignment.TopStart))
        Icon(icon, contentDescription = null, modifier = Modifier.align(Alignment.BottomEnd).size(32.dp))
    }
}

@Composable
private fun ComposerCard(artist: Artist, onClick: () -> Unit) {
    Column(
        Modifier.width(110.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Cover(artist.coverArt, Modifier.size(96.dp).clip(CircleShape), size = 200, corner = 48.dp)
        Text(
            artist.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
}
