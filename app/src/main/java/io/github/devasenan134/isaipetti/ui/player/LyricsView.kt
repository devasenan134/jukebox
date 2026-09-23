package io.github.devasenan134.isaipetti.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.devasenan134.isaipetti.data.StructuredLyrics
import io.github.devasenan134.isaipetti.ui.components.Loadable
import io.github.devasenan134.isaipetti.ui.components.LocalApp

/**
 * Lyrics for the current song. Synced lyrics highlight the line being sung and scroll
 * to keep it in view; tap a line to jump there. Unsynced lyrics are plain scrolling text.
 */
@Composable
fun LyricsView(songId: String?, positionMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val lyrics by produceState<Loadable<StructuredLyrics?>>(Loadable.Loading, songId) {
        value = Loadable.Loading
        value = try {
            // Prefer synced lyrics when the server has several versions.
            val all = songId?.let { app.api.lyrics(it) }.orEmpty().filter { it.line.isNotEmpty() }
            Loadable.Ready(all.firstOrNull { it.synced } ?: all.firstOrNull())
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Loadable.Failed(e.message ?: "Couldn't load lyrics")
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        when (val state = lyrics) {
            Loadable.Loading -> CircularProgressIndicator()
            is Loadable.Failed -> Message(state.message)
            is Loadable.Ready -> when {
                state.value == null -> Message("No lyrics for this song yet")
                state.value.synced -> SyncedLyrics(state.value, positionMs, onSeek)
                else -> PlainLyrics(state.value)
            }
        }
    }
}

@Composable
private fun SyncedLyrics(lyrics: StructuredLyrics, positionMs: Long, onSeek: (Long) -> Unit) {
    val lines = lyrics.line
    // The current line is the last one whose start time has passed.
    val current = lines.indexOfLast { (it.start ?: 0) + lyrics.offset <= positionMs }
    val listState = rememberLazyListState()

    // Keep the current line about a third of the way down, unless the user is scrolling.
    LaunchedEffect(current) {
        if (current < 0 || listState.isScrollInProgress) return@LaunchedEffect
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == current }) listState.scrollToItem(current)
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == current } ?: return@LaunchedEffect
        val visualTop = item.offset - info.viewportStartOffset
        listState.animateScrollBy((visualTop - info.viewportSize.height / 3).toFloat())
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(vertical = 160.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(lines) { index, line ->
            val color by animateColorAsState(
                if (index == current) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                label = "lyricColor",
            )
            Text(
                line.value.ifBlank { "♪" },
                color = color,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = if (index == current) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { line.start?.let { onSeek(it + lyrics.offset) } }
                    .padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun PlainLyrics(lyrics: StructuredLyrics) {
    Text(
        lyrics.line.joinToString("\n") { it.value },
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
    )
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge,
    )
}
