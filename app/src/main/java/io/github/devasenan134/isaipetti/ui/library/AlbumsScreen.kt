package io.github.devasenan134.isaipetti.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.devasenan134.isaipetti.data.Album
import io.github.devasenan134.isaipetti.data.SubsonicApi
import io.github.devasenan134.isaipetti.ui.Nav
import io.github.devasenan134.isaipetti.ui.components.AlbumCard
import io.github.devasenan134.isaipetti.ui.components.ErrorMessage
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.ScreenHeader
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class AlbumSort(val label: String, val type: String, val extra: Map<String, Any> = emptyMap()) {
    Name("A–Z", "alphabeticalByName"),
    Newest("Newest movies", "byYear", mapOf("fromYear" to 2100, "toYear" to 1900)),
    Oldest("Oldest movies", "byYear", mapOf("fromYear" to 1900, "toYear" to 2100)),
    Added("Recently added", "newest"),
}

/** Loads the album grid one page at a time as you scroll, so big libraries stay fast. */
class AlbumsViewModel(private val api: SubsonicApi) : ViewModel() {
    var sort by mutableStateOf(AlbumSort.Name)
        private set
    val albums = mutableStateListOf<Album>()
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var endReached = false
    private var job: Job? = null

    init {
        loadMore()
    }

    fun changeSort(newSort: AlbumSort) {
        if (newSort == sort) return
        sort = newSort
        job?.cancel()
        albums.clear()
        endReached = false
        loading = false
        loadMore()
    }

    fun loadMore() {
        if (loading || endReached) return
        loading = true
        error = null
        job = viewModelScope.launch {
            try {
                val page = api.albumList(sort.type, PAGE_SIZE, albums.size, sort.extra)
                albums += page
                endReached = page.size < PAGE_SIZE
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: "Couldn't load movies"
            } finally {
                loading = false
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 60
    }
}

@Composable
fun AlbumsScreen(nav: Nav) {
    val app = LocalApp.current
    val vm = viewModel { AlbumsViewModel(app.api) }
    val gridState = rememberLazyGridState()

    // When the last few cards come into view, fetch the next page.
    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= gridState.layoutInfo.totalItemsCount - 12
        }
    }
    LaunchedEffect(nearEnd, vm.albums.size) { if (nearEnd) vm.loadMore() }
    LaunchedEffect(vm.sort) { gridState.scrollToItem(0) }

    Column {
        ScreenHeader("Movies")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(AlbumSort.entries) { sort ->
                FilterChip(selected = vm.sort == sort, onClick = { vm.changeSort(sort) }, label = { Text(sort.label) })
            }
        }
        if (vm.albums.isEmpty() && vm.error != null) {
            ErrorMessage(vm.error!!, onRetry = vm::loadMore)
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            state = gridState,
            contentPadding = PaddingValues(10.dp),
        ) {
            items(vm.albums, key = { it.id }) { album ->
                AlbumCard(album, onClick = { nav.openAlbum(album.id) })
            }
            if (vm.loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}
