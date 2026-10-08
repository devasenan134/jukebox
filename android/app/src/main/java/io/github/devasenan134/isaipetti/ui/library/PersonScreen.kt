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
 * A lyricist or actor, found in search (the friends API knows them; the Subsonic API doesn't):
 * the movies they acted in or wrote for, then all their songs with Play and Shuffle.
 */
@Composable
fun PersonScreen(id: String, name: String, coverArt: String?, nav: Nav) {
    val app = LocalApp.current
    val loader = rememberLoader("person-$id") {
        try {
            val p2 = app.social.api.personV2(id)
            io.github.devasenan134.isaipetti.data.PersonPage(
                person = io.github.devasenan134.isaipetti.data.PersonHit(
                    id = p2.id,
                    name = p2.name,
                    roles = p2.roles,
                    coverArt = p2.coverArt,
                    songCount = p2.songCount,
                    movieCount = p2.movieCount,
                ),
                movies = p2.movies.map {
                    io.github.devasenan134.isaipetti.data.MovieHit(
                        id = it.id,
                        name = it.title,
                        year = it.year,
                        composer = it.composers.firstOrNull()?.name,
                        coverArt = it.coverArt,
                        songCount = it.songCount,
                    )
                },
                songs = p2.songs.map { r ->
                    io.github.devasenan134.isaipetti.data.MixSong(
                        id = r.id,
                        title = r.title,
                        artist = r.singers.joinToString(", ") { it.name },
                        duration = maxOf(1, (r.durationMs / 1000).toInt()),
                        coverArt = r.coverArt,
                        artists = r.singers.map { io.github.devasenan134.isaipetti.data.ArtistRef(id = it.id, name = it.name) },
                    )
                },
            )
        } catch (_: Exception) {
            app.social.api.person(id)
        }
    }
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
                    item { SectionTitle("Albums") }
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
