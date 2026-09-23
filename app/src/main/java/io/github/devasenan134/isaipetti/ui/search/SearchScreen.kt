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
import androidx.compose.runtime.produceState
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
import io.github.devasenan134.isaipetti.data.Album
import io.github.devasenan134.isaipetti.data.Artist
import io.github.devasenan134.isaipetti.data.SearchResult
import io.github.devasenan134.isaipetti.ui.Nav
import io.github.devasenan134.isaipetti.ui.components.AlbumCard
import io.github.devasenan134.isaipetti.ui.components.Cover
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.SectionTitle
import io.github.devasenan134.isaipetti.ui.components.SongRow
import kotlinx.coroutines.delay

/**
 * Search, and browsing:
 *  - before you tap the search bar: Movies and Composers boxes, then what you played recently;
 *  - with the search bar tapped but empty: your recent searches;
 *  - once you type: results.
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
    val recentSongs by app.recent.songs.collectAsStateWithLifecycle()
    // Recently played movies come from Navidrome, so they include what you played on other devices.
    val recentAlbums by produceState(emptyList<Album>()) {
        value = runCatching { app.api.albumList("recent", 15) }.getOrDefault(emptyList())
    }
    // Composers of those movies, in the same order, with their pictures.
    val recentComposers by produceState(emptyList<Artist>(), recentAlbums) {
        val ids = recentAlbums.mapNotNull { it.artistId }.distinct()
        if (ids.isEmpty()) return@produceState
        val byId = runCatching { app.api.artists() }.getOrDefault(emptyList()).associateBy { it.id }
        value = ids.mapNotNull { byId[it] }.take(10)
    }
    // A search counts for the history once you use it: press search, or open a result.
    val saveSearch = { app.searches.add(query) }

    // Wait until typing pauses for 300 ms before searching. Typing again cancels the pending search.
    LaunchedEffect(query) {
        if (query.isBlank()) {
            result = SearchResult()
            return@LaunchedEffect
        }
        delay(300)
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
                    if (result.artist.isNotEmpty()) {
                        item { SectionTitle("Composers") }
                        items(result.artist, key = { "artist-${it.id}" }) { artist ->
                            Text(
                                artist.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth().clickable { saveSearch(); nav.openArtist(artist.id) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                    if (result.album.isNotEmpty()) {
                        item { SectionTitle("Movies") }
                        item {
                            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                                items(result.album, key = { it.id }) { album ->
                                    AlbumCard(album, onClick = { saveSearch(); nav.openAlbum(album.id) }, modifier = Modifier.width(140.dp))
                                }
                            }
                        }
                    }
                    if (result.song.isNotEmpty()) {
                        item { SectionTitle("Songs") }
                        itemsIndexed(result.song, key = { _, song -> "song-${song.id}" }) { index, song ->
                            SongRow(
                                song = song,
                                onClick = { saveSearch(); app.player.play(result.song, index) },
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
                        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            BrowseBox("Movies", painterResource(R.drawable.ic_album), MaterialTheme.colorScheme.primaryContainer, nav.openAlbums, Modifier.weight(1f))
                            BrowseBox(
                                "Composers",
                                rememberVectorPainter(Icons.Filled.Person),
                                MaterialTheme.colorScheme.tertiaryContainer,
                                nav.openArtists,
                                Modifier.weight(1f),
                            )
                        }
                    }
                    if (recentSongs.isNotEmpty()) {
                        item { SectionTitle("Recently played songs") }
                        val songs = recentSongs.take(5).map { it.toSong() }
                        itemsIndexed(songs, key = { _, song -> "recent-${song.id}" }) { index, song ->
                            SongRow(
                                song = song,
                                onClick = { app.player.play(songs, index) },
                                isCurrent = song.id == nowPlaying.songId,
                                showCover = true,
                                onOpenAlbum = nav.openAlbum,
                            )
                        }
                    }
                    if (recentAlbums.isNotEmpty()) {
                        item { SectionTitle("Recently played movies") }
                        item {
                            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                                items(recentAlbums, key = { "recent-album-${it.id}" }) { album ->
                                    AlbumCard(album, onClick = { nav.openAlbum(album.id) }, modifier = Modifier.width(140.dp))
                                }
                            }
                        }
                    }
                    if (recentComposers.isNotEmpty()) {
                        item { SectionTitle("Recently played composers") }
                        item {
                            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                                items(recentComposers, key = { "recent-artist-${it.id}" }) { artist ->
                                    ComposerCard(artist) { nav.openArtist(artist.id) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A big coloured box that opens a whole section (Movies, Composers). */
@Composable
private fun BrowseBox(label: String, icon: Painter, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.height(96.dp).clip(RoundedCornerShape(12.dp)).background(color).clickable(onClick = onClick).padding(14.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.TopStart))
        Icon(icon, contentDescription = null, modifier = Modifier.align(Alignment.BottomEnd).size(36.dp))
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
