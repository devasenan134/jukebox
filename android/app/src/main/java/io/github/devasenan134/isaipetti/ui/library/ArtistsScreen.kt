package io.github.devasenan134.isaipetti.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.devasenan134.isaipetti.data.Artist
import io.github.devasenan134.isaipetti.ui.Nav
import io.github.devasenan134.isaipetti.ui.components.Cover
import io.github.devasenan134.isaipetti.ui.components.LoadableContent
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.ScreenHeader
import io.github.devasenan134.isaipetti.ui.components.rememberLoader

/** Composers, biggest catalogue first, with their photos. */
@Composable
fun ArtistsScreen(nav: Nav) {
    val app = LocalApp.current
    val loader = rememberLoader("composers-v2") {
        app.social.api.peopleV2(role = "composer", limit = 10_000).people.map { it.toArtist() }.sortedByDescending { it.albumCount }
    }
    Column {
        ScreenHeader("Composers", onBack = nav.back)
        LoadableContent(loader) { composers ->
            LazyColumn {
                items(composers, key = { it.id }) { composer ->
                    PersonListRow(composer, "${composer.albumCount} ${if (composer.albumCount == 1) "album" else "albums"}") {
                        nav.openArtist(composer.id)
                    }
                }
            }
        }
    }
}

/** A person in a list: their photo (or newest album cover) in a circle, their name, and a count. */
@Composable
fun PersonListRow(person: Artist, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(person.coverArt, Modifier.size(52.dp).clip(CircleShape), size = 150, corner = 26.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(person.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
