package io.github.devasenan134.isaipetti.ui.search

import androidx.compose.foundation.clickable
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
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            if (result.artist.isNotEmpty()) {
                item { SectionTitle("Composers") }
                items(result.artist, key = { "artist-${it.id}" }) { artist ->
                    Text(
                        artist.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { nav.openArtist(artist.id) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
            if (result.album.isNotEmpty()) {
                item { SectionTitle("Movies") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                        items(result.album, key = { it.id }) { album ->
                            AlbumCard(album, onClick = { nav.openAlbum(album.id) }, modifier = Modifier.width(140.dp))
                        }
                    }
                }
            }
            if (result.song.isNotEmpty()) {
                item { SectionTitle("Songs") }
                itemsIndexed(result.song, key = { _, song -> "song-${song.id}" }) { index, song ->
                    SongRow(
                        song = song,
                        onClick = { app.player.play(result.song, index) },
                        isCurrent = song.id == nowPlaying.songId,
                        showCover = true,
                        onOpenAlbum = nav.openAlbum,
                    )
                }
            }
        }
    }
}
