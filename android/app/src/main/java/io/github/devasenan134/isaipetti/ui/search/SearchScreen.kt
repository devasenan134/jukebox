package io.github.devasenan134.isaipetti.ui.search

import io.github.devasenan134.isaipetti.ui.components.UiSize
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
import io.github.devasenan134.isaipetti.ui.components.AlbumCard
import io.github.devasenan134.isaipetti.ui.components.Cover
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.PlaylistCard
import io.github.devasenan134.isaipetti.ui.components.SectionTitle
import io.github.devasenan134.isaipetti.ui.components.SongRow
import kotlinx.coroutines.delay
import androidx.compose.foundation.lazy.LazyListScope
import io.github.devasenan134.isaipetti.data.Album
import io.github.devasenan134.isaipetti.data.CatalogResults
import io.github.devasenan134.isaipetti.data.LibrarySearchResults
import io.github.devasenan134.isaipetti.data.Song
import io.github.devasenan134.isaipetti.ui.Nav
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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
    // From the friends server when it has search: forgives spelling, and finds lyricists and actors.
    var smart by remember { mutableStateOf<LibrarySearchResults?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Songs and movies that aren't in the library, to request.
    var catalog by remember { mutableStateOf<CatalogResults?>(null) }
    val (requestMusic, cancelRequest) = rememberRequestActions { itemId, request -> catalog = catalog?.with(itemId, request) }
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
            smart = null
            return@LaunchedEffect
        }
        delay(200)
        coroutineScope {
            val better = async {
                try { app.social.api.search(query.trim()) } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    null // no friends server, or an older one: Navidrome's search is enough
                }
            }
            try {
                result = app.api.search(query.trim())
                error = null
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message
            }
            smart = better.await()
            if (smart != null) error = null
        }
    }
    // The catalog is slower and allows only so many searches a minute, so it waits for a longer pause.
    LaunchedEffect(query) {
        catalog = null
        val q = query.trim()
        if (q.length < 3) return@LaunchedEffect
        delay(700)
        catalog = try { app.social.api.catalog(q) } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null // no friends server, or one without requests
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
            placeholder = { Text("Songs, movies, artists, actors, lyricists") },
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
                    val names = (smart?.let { f -> f.people.map { it.name } + f.movies.map { it.name } + f.songs.map { it.song.title } }
                        ?: (result.artist.map { it.name } + result.album.map { it.name } + result.song.map { it.title }))
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
                    val found = smart
                    if (found != null) {
                        smartResults(found, nowPlaying.songId, nav, onPicked = saveSearch)
                    } else {
                        basicResults(result, nowPlaying.songId, nav, onPicked = saveSearch)
                    }
                    catalog?.let { catalogResults(it.movies, it.songs, requestMusic, cancelRequest) }
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
                    item {
                        Row(
                            Modifier.fillMaxWidth().clickable(onClick = nav.openRequests).padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(painterResource(R.drawable.ic_playlist_add), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.padding(start = 16.dp)) {
                                Text("Your requests", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "Songs and movies you asked to be added",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    AlbumCard(album, onClick = { nav.openAlbum(album.id) }, modifier = Modifier.width(UiSize.Tile))
                                }
                            }
                        }
                    }
                    if (searchedArtists.isNotEmpty()) {
                        item { SectionTitle("Your recent artists") }
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

/** Results from Navidrome's own search (exact spelling only). */
private fun LazyListScope.basicResults(result: SearchResult, playing: String?, nav: Nav, onPicked: () -> Unit) {
    val composers = result.artist.filter { it.isComposer }
    val singers = result.artist.filter { !it.isComposer }
    if (composers.isNotEmpty()) {
        item { SectionTitle("Composers") }
        items(composers, key = { "artist-${it.id}" }) { artist ->
            val app = LocalApp.current
            ArtistResult(artist) { onPicked(); app.searches.picked(artist); nav.openArtist(artist.id) }
        }
    }
    if (singers.isNotEmpty()) {
        item { SectionTitle("Artists") }
        items(singers, key = { "singer-${it.id}" }) { artist ->
            val app = LocalApp.current
            ArtistResult(artist) { onPicked(); app.searches.picked(artist); nav.openSinger(artist) }
        }
    }
    movieResults(result.album, nav, onPicked)
    songResults(result.song, emptyMap(), playing, nav, onPicked)
}

/** Results from the friends server's search: people (with what they do), movies (with why), songs. */
private fun LazyListScope.smartResults(found: LibrarySearchResults, playing: String?, nav: Nav, onPicked: () -> Unit) {
    if (found.people.isNotEmpty()) {
        item { SectionTitle("People") }
        items(found.people, key = { "person-${it.id}" }) { person ->
            val app = LocalApp.current
            val artist = person.toArtist()
            ArtistResult(artist, person.description) {
                onPicked()
                app.searches.picked(artist)
                // Composers open their movies; everyone else their songs (lyricists and actors: their own page).
                if ("composer" in person.roles) nav.openArtist(person.id) else nav.openSinger(artist)
            }
        }
    }
    movieResults(found.movies.map { it.toAlbum() }, nav, onPicked)
    songResults(
        found.songs.map { it.song.toSong() },
        found.songs.mapNotNull { hit -> hit.reason?.let { hit.song.id to listOfNotNull(it, hit.song.album).joinToString(" · ") } }.toMap(),
        playing, nav, onPicked,
    )
}

private fun LazyListScope.movieResults(movies: List<Album>, nav: Nav, onPicked: () -> Unit) {
    if (movies.isEmpty()) return
    item { SectionTitle("Movies") }
    item {
        val app = LocalApp.current
        LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
            items(movies, key = { it.id }) { album ->
                AlbumCard(album, onClick = { onPicked(); app.searches.picked(album); nav.openAlbum(album.id) }, modifier = Modifier.width(UiSize.Tile))
            }
        }
    }
}

/** [notes]: song id to a line shown instead of artist and movie (why it was found). */
private fun LazyListScope.songResults(songs: List<Song>, notes: Map<String, String>, playing: String?, nav: Nav, onPicked: () -> Unit) {
    if (songs.isEmpty()) return
    item { SectionTitle("Songs") }
    itemsIndexed(songs, key = { _, song -> "song-${song.id}" }) { index, song ->
        val app = LocalApp.current
        SongRow(
            song = song,
            onClick = { onPicked(); app.searches.picked(song); app.activity.song(song); app.player.play(songs, index) },
            isCurrent = song.id == playing,
            showCover = true,
            onOpenAlbum = nav.openAlbum,
            note = notes[song.id],
        )
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
private fun ArtistResult(artist: Artist, description: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(artist.coverArt, Modifier.size(UiSize.SongThumb).clip(CircleShape), size = 150, corner = 25.dp)
        Column(Modifier.padding(start = 14.dp)) {
            Text(artist.name, style = MaterialTheme.typography.bodyLarge)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
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
