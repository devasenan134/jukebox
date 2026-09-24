package io.github.devasenan134.isaipetti.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.devasenan134.isaipetti.ui.Nav
import io.github.devasenan134.isaipetti.ui.components.AlbumCard
import io.github.devasenan134.isaipetti.ui.components.LoadableContent
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.ScreenHeader
import io.github.devasenan134.isaipetti.ui.components.SectionTitle
import io.github.devasenan134.isaipetti.ui.components.rememberLoader
import io.github.devasenan134.isaipetti.ui.components.rememberPageTint
import io.github.devasenan134.isaipetti.ui.components.UiSize

/**
 * A lyricist or actor, found in search (the friends server knows them; Navidrome's pages don't):
 * the movies they acted in or wrote for, then all their songs with Play and Shuffle.
 */
@Composable
fun PersonScreen(id: String, name: String, coverArt: String?, nav: Nav) {
    val app = LocalApp.current
    val loader = rememberLoader("person-$id") { app.social.api.person(id) }
    val tint = rememberPageTint(coverArt)
    Column {
        ScreenHeader("", onBack = nav.back, color = tint)
        LoadableContent(loader) { page ->
            SongList(
                tint = tint,
                onPlay = { app.searches.picked(page.person.toArtist()) },
                coverArt = coverArt ?: page.person.coverArt,
                title = page.person.name.ifBlank { name },
                subtitle = page.person.description,
                source = "person:$id",
                songs = page.songs.map { it.toSong() },
                onSubtitleClick = null,
                showCovers = true,
                nav = nav,
                aboveSongs = if (page.movies.isEmpty()) null else ({
                    item { SectionTitle("Movies") }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                            items(page.movies, key = { it.id }) { movie ->
                                AlbumCard(movie.toAlbum(), onClick = { nav.openAlbum(movie.id) }, modifier = Modifier.width(UiSize.Tile))
                            }
                        }
                    }
                    item { SectionTitle("Songs") }
                }),
            )
        }
    }
}
