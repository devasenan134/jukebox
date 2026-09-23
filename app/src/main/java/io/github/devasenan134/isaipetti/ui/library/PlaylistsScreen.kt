package io.github.devasenan134.isaipetti.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.devasenan134.isaipetti.ui.Nav
import io.github.devasenan134.isaipetti.ui.components.LoadableContent
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.PlaylistCard
import io.github.devasenan134.isaipetti.ui.components.ScreenHeader
import io.github.devasenan134.isaipetti.ui.components.rememberLoader

/** Every playlist in Navidrome that you can see, A to Z. */
@Composable
fun PlaylistsScreen(nav: Nav) {
    val app = LocalApp.current
    val loader = rememberLoader("playlists") { app.api.playlists().sortedBy { it.name.lowercase() } }
    Column {
        ScreenHeader("Playlists", onBack = nav.back)
        LoadableContent(loader) { playlists ->
            LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), contentPadding = PaddingValues(10.dp)) {
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistCard(playlist, onClick = { nav.openPlaylist(playlist.id) }, modifier = Modifier)
                }
            }
        }
    }
}
