package io.github.devasenan134.isaipetti.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.devasenan134.isaipetti.ui.Nav
import io.github.devasenan134.isaipetti.ui.components.Cover
import io.github.devasenan134.isaipetti.ui.components.LoadableContent
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.ScreenHeader
import io.github.devasenan134.isaipetti.ui.components.rememberLoader

/** Music directors (album artists), biggest catalogue first. */
@Composable
fun ArtistsScreen(nav: Nav) {
    val app = LocalApp.current
    val loader = rememberLoader("artists") { app.api.artists().sortedByDescending { it.albumCount } }
    Column {
        ScreenHeader("Composers")
        LoadableContent(loader) { artists ->
            LazyColumn {
                items(artists, key = { it.id }) { artist ->
                    Row(
                        Modifier.fillMaxWidth().clickable { nav.openArtist(artist.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(artist.coverArt, Modifier.size(52.dp).clip(CircleShape), size = 150, corner = 26.dp)
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(artist.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${artist.albumCount} movies",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
