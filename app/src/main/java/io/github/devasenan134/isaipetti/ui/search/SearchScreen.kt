package io.github.devasenan134.isaipetti.ui.search

import androidx.compose.foundation.clickable
import io.github.devasenan134.isaipetti.data.Album
import io.github.devasenan134.isaipetti.R
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.runtime.produceState
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.devasenan134.isaipetti.data.SearchResult
import io.github.devasenan134.isaipetti.ui.Nav
import io.github.devasenan134.isaipetti.ui.components.AlbumCard
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.SectionTitle
import io.github.devasenan134.isaipetti.ui.components.SongRow
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(nav: Nav) {
    val app = LocalApp.current
    val nowPlaying by app.player.nowPlaying.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var result by androidx.compose.runtime.remember { mutableStateOf(SearchResult()) }
    var error by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    val history by app.searches.queries.collectAsStateWithLifecycle()
    val recentSongs by app.recent.songs.collectAsStateWithLifecycle()
    // Recently played movies come from Navidrome, so they include what you played on other devices.
    val recentAlbums by produceState(emptyList<Album>()) {
        value = runCatching { app.api.albumList("recent", 15) }.getOrDefault(emptyList())
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

    Column {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Songs, movies, composers, singers") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) ({
                IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
            }) else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { saveSearch() }),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            if (query.isBlank()) {
                if (recentSongs.isNotEmpty()) {
                    item { SectionTitle("Recently played") }
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
                if (history.isNotEmpty()) {
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
                if (recentSongs.isEmpty() && recentAlbums.isEmpty() && history.isEmpty()) {
                    item {
                        Text(
                            "Search your music. What you play and search for shows up here.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
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
    }
}
