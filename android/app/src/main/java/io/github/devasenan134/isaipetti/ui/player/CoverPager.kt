package io.github.devasenan134.isaipetti.ui.player

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.absoluteValue
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import coil3.compose.AsyncImage

/**
 * Album art for every song in the queue, side by side. Swipe to change songs.
 * It works both ways: swiping plays that song, and when the song changes
 * (Next button, song ends) the pager slides to follow. The previous and next covers peek in at the sides,
 * smaller and dimmer.
 */
@Composable
fun CoverPager(modifier: Modifier = Modifier, swipeable: Boolean = true) {
    val player = LocalApp.current.player
    val queue by player.queue.collectAsStateWithLifecycle()
    val (entries, currentIndex) = queue
    if (entries.isEmpty()) return

    // Pages follow play order, so page N is the Nth song that will play (shuffle included).
    val currentPage = entries.indexOfFirst { it.index == currentIndex }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = currentPage) { entries.size }
    val latestEntries by rememberUpdatedState(entries)
    val latestCurrent by rememberUpdatedState(currentIndex)

    // The song changed somewhere else: slide to it.
    LaunchedEffect(currentPage) {
        if (pagerState.settledPage != currentPage) pagerState.animateScrollToPage(currentPage)
    }

    // The user swiped to a new page: play that song.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val entry = latestEntries.getOrNull(page) ?: return@collect
            if (entry.index != latestCurrent) player.skipTo(entry.index)
        }
    }

    HorizontalPager(
        userScrollEnabled = swipeable,
        state = pagerState,
        contentPadding = PaddingValues(horizontal = 36.dp),
        pageSpacing = 8.dp,
        key = { entries.getOrNull(it)?.index ?: it },
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) { page ->
        // How far this page is from the middle (0 = the current one), as it's swiped.
        val away = { (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction }
        AsyncImage(
            model = entries.getOrNull(page)?.item?.mediaMetadata?.artworkUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .graphicsLayer {
                    val d = away().coerceIn(-1f, 1f)
                    val scale = 1f - 0.18f * d.absoluteValue
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - 0.5f * d.absoluteValue
                    // Drawn a little closer, with a small gap (pages can't be stacked behind one another).
                    translationX = d * size.width * 0.08f
                }
                .fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)),
        )
    }
}
