package io.github.devasenan134.isaipetti.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.devasenan134.isaipetti.data.Artist
import io.github.devasenan134.isaipetti.data.SubsonicApi
import io.github.devasenan134.isaipetti.ui.Nav
import io.github.devasenan134.isaipetti.ui.components.Cover
import io.github.devasenan134.isaipetti.ui.components.ErrorMessage
import io.github.devasenan134.isaipetti.ui.components.LoadableContent
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.ScreenHeader
import io.github.devasenan134.isaipetti.ui.components.rememberLoader
import kotlinx.coroutines.launch

/** Loads singers a page at a time as you scroll (a big library has thousands). */
class SingersViewModel(private val api: SubsonicApi) : ViewModel() {
    val singers = mutableStateListOf<Artist>()
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var offset = 0
    private var endReached = false

    init {
        loadMore()
    }

    fun loadMore() {
        if (loading || endReached) return
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val page = api.singers(offset, PAGE)
                offset += page.size
                endReached = page.size < PAGE
                // Search returns every kind of artist; keep the ones credited on songs.
                singers += page.filter { "artist" in it.roles && singers.none { s -> s.id == it.id } }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: "Couldn't load artists"
            } finally {
                loading = false
            }
        }
    }

    private companion object {
        const val PAGE = 200
    }
}

/** Singers: everyone credited as an artist on songs, A to Z. */
@Composable
fun SingersScreen(nav: Nav) {
    val app = LocalApp.current
    val vm = viewModel { SingersViewModel(app.api) }
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 20
        }
    }
    LaunchedEffect(nearEnd, vm.singers.size) { if (nearEnd) vm.loadMore() }

    Column {
        ScreenHeader("Artists", onBack = nav.back)
        if (vm.singers.isEmpty() && vm.error != null) {
            ErrorMessage(vm.error!!, onRetry = vm::loadMore)
            return@Column
        }
        LazyColumn(state = listState) {
            items(vm.singers, key = { it.id }) { singer ->
                Row(
                    Modifier.fillMaxWidth().clickable { nav.openSinger(singer) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(singer.coverArt, Modifier.size(52.dp).clip(CircleShape), size = 150, corner = 26.dp)
                    Text(singer.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                }
            }
            if (vm.loading) {
                item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            }
        }
    }
}

/** A singer: the songs they're credited on, with Play and Shuffle. */
@Composable
fun SingerScreen(id: String, name: String, coverArt: String?, nav: Nav) {
    val app = LocalApp.current
    val loader = rememberLoader("singer-$id") { app.api.songsBy(id, name).sortedBy { it.title.lowercase() } }
    Column {
        ScreenHeader("", onBack = nav.back)
        LoadableContent(loader) { songs ->
            SongList(
                onPlay = {
                    app.searches.picked(Artist(id, name, coverArt = coverArt, roles = listOf("artist")))
                    app.activity.artist(id, name, coverArt)
                },
                coverArt = coverArt,
                title = name,
                subtitle = "Artist",
                songs = songs,
                onSubtitleClick = null,
                showCovers = true,
                nav = nav,
            )
        }
    }
}
