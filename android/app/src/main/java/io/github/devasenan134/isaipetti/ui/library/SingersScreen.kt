package io.github.devasenan134.isaipetti.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import io.github.devasenan134.isaipetti.ui.Nav
import io.github.devasenan134.isaipetti.ui.components.LoadableContent
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.ScreenHeader
import io.github.devasenan134.isaipetti.ui.components.rememberLoader

/** Artists: everyone credited as an artist on songs, the ones on the most songs first, with their photos. */
@Composable
fun SingersScreen(nav: Nav) {
    val app = LocalApp.current
    val loader = rememberLoader("artists-v2") {
        app.social.api.peopleV2(role = "artist", limit = 10_000).people.map { it.toArtist() }.sortedByDescending { it.songCount }
    }
    Column {
        ScreenHeader("Artists", onBack = nav.back)
        LoadableContent(loader) { singers ->
            LazyColumn {
                items(singers, key = { it.id }) { singer ->
                    PersonListRow(singer, if (singer.songCount == 1) "1 song" else "%,d songs".format(singer.songCount)) { nav.openSinger(singer) }
                }
            }
        }
    }
}

/** A singer: every song they're credited on (the person page, which loads them all from the server). */
@Composable
fun SingerScreen(id: String, name: String, coverArt: String?, nav: Nav) = PersonScreen(id, name, coverArt, nav)
