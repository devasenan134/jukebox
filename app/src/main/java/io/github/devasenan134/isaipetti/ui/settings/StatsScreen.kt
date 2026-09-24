package io.github.devasenan134.isaipetti.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.devasenan134.isaipetti.data.ListeningStats
import io.github.devasenan134.isaipetti.data.RangeStats
import io.github.devasenan134.isaipetti.data.TopItem
import io.github.devasenan134.isaipetti.data.UserStats
import io.github.devasenan134.isaipetti.ui.Nav
import io.github.devasenan134.isaipetti.ui.components.Cover
import io.github.devasenan134.isaipetti.ui.components.LoadableContent
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.ScreenHeader
import io.github.devasenan134.isaipetti.ui.components.SectionTitle
import io.github.devasenan134.isaipetti.ui.components.rememberLoader
import io.github.devasenan134.isaipetti.ui.social.Avatar
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val RANGES = listOf("today" to "Today", "7d" to "7 days", "30d" to "30 days", "all" to "All time")

/** One colour for every chart here, checked for contrast against each theme's background. */
@Composable
private fun chartColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFCC8214) else Color(0xFF9A5C00)

/**
 * Everyone's listening, for admins only (the server checks too).
 * Numbers come from Navidrome's play history: a song counts once half of it (or 4 minutes) is heard.
 */
@Composable
fun StatsScreen(nav: Nav) {
    val app = LocalApp.current
    val loader = rememberLoader("listening-stats") { app.social.api.listeningStats(ZoneId.systemDefault().id) }
    Column {
        ScreenHeader("Listening stats", onBack = nav.back) {
            IconButton(onClick = loader::reload) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
        }
        LoadableContent(loader) { stats -> StatsContent(stats) }
    }
}

@Composable
private fun StatsContent(stats: ListeningStats) {
    var range by rememberSaveable { mutableStateOf("7d") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val people = stats.users
        .map { it to (it.ranges[range] ?: RangeStats()) }
        .sortedWith(compareByDescending<Pair<UserStats, RangeStats>> { it.second.hours }.thenByDescending { it.first.lastPlayedAt ?: 0 })
    val maxHours = people.maxOfOrNull { it.second.hours }?.takeIf { it > 0 } ?: 1.0
    val totalHours = people.sumOf { it.second.hours }
    val listeners = people.count { it.second.plays > 0 }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                RANGES.forEachIndexed { i, (key, label) ->
                    SegmentedButton(
                        selected = range == key,
                        onClick = { range = key },
                        shape = SegmentedButtonDefaults.itemShape(i, RANGES.size),
                    ) { Text(label, maxLines = 1) }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(formatHours(totalHours), style = MaterialTheme.typography.displaySmall)
                Text(
                    "listened by $listeners of ${people.size} people · ${people.sumOf { it.second.plays }} plays",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(people, key = { it.first.username }) { (user, stats) ->
            PersonRow(
                user = user,
                stats = stats,
                fraction = (stats.hours / maxHours).toFloat(),
                expanded = expanded == user.username,
                onClick = { expanded = if (expanded == user.username) null else user.username },
            )
        }
        item {
            SectionTitle("Last 30 days, everyone")
            val dates = stats.daily.map { LocalDate.parse(it.date) }
            BarChart(
                values = stats.daily.map { it.hours },
                describe = { i -> "${dates[i].format(DAY)}: ${formatHours(stats.daily[i].hours)}" },
                axisLabels = listOf(0 to dates.firstOrNull()?.format(SHORT_DAY).orEmpty(), dates.lastIndex to "Today"),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        item {
            SectionTitle("Time of day, last 30 days")
            BarChart(
                values = stats.hourOfDay,
                describe = { h -> "${hourLabel(h)}–${hourLabel((h + 1) % 24)}: ${formatHours(stats.hourOfDay[h])}" },
                axisLabels = listOf(0 to "12am", 6 to "6am", 12 to "12pm", 18 to "6pm", 23 to "11pm"),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        item {
            Text(
                "A song counts as played once half of it (or 4 minutes) is heard, and adds its full length. " +
                    "Updated ${Instant.ofEpochMilli(stats.generatedAt).atZone(ZoneId.systemDefault()).format(TIME)}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun PersonRow(user: UserStats, stats: RangeStats, fraction: Float, expanded: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).animateContentSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(user.displayName, user.username, size = 40.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(user.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${stats.plays} plays · " + (user.lastPlayedAt?.let { "last played ${ago(it)}" } ?: "no plays yet"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(formatHours(stats.hours), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        // Compared with whoever listened most in this range.
        Box(
            Modifier.padding(start = 52.dp, top = 6.dp).fillMaxWidth().height(6.dp)
                .clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(chartColor()))
        }
        if (expanded) {
            if (stats.plays == 0) {
                Text(
                    "Nothing played in this range.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 52.dp, top = 12.dp),
                )
            } else {
                Column(Modifier.padding(start = 52.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TopList("Top songs", stats.topSongs, showCovers = true)
                    TopList("Top movies", stats.topMovies, showCovers = true)
                    TopList("Top composers", stats.topComposers, showCovers = false)
                }
            }
        }
    }
}

@Composable
private fun TopList(title: String, items: List<TopItem>, showCovers: Boolean) {
    if (items.isEmpty()) return
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.forEach { item ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showCovers) Cover(item.coverArt, Modifier.size(36.dp), size = 100, corner = 4.dp)
                Column(Modifier.weight(1f).padding(horizontal = if (showCovers) 10.dp else 0.dp)) {
                    Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    item.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text("${item.plays}×", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * A single-series bar chart: thin bars rounded at the top, a quiet baseline, a few axis labels,
 * and tap-a-bar to read its exact value above the chart.
 */
@Composable
private fun BarChart(
    values: List<Double>,
    describe: (Int) -> String,
    axisLabels: List<Pair<Int, String>>,
    modifier: Modifier = Modifier,
) {
    if (values.isEmpty()) return
    var selected by remember(values) { mutableStateOf<Int?>(null) }
    val color = chartColor()
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val max = values.max().takeIf { it > 0 } ?: 1.0
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier.fillMaxWidth()) {
        // The value you tapped (or the busiest bar, as a hint that bars can be tapped).
        val shown = selected ?: values.indices.maxBy { values[it] }
        Text(
            if (selected == null) "Most: ${describe(shown)}" else describe(shown),
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected == null) muted else MaterialTheme.colorScheme.onSurface,
        )
        Canvas(
            Modifier.fillMaxWidth().height(120.dp).padding(top = 8.dp)
                .semantics { contentDescription = values.indices.joinToString { describe(it) } }
                .pointerInput(values) {
                    detectTapGestures { offset ->
                        val i = (offset.x / (size.width / values.size)).toInt().coerceIn(values.indices)
                        selected = if (selected == i) null else i
                    }
                },
        ) {
            val slot = size.width / values.size
            val gap = 2.dp.toPx()
            val radius = 4.dp.toPx()
            values.forEachIndexed { i, v ->
                if (v <= 0) return@forEachIndexed
                val h = (v / max * size.height).toFloat().coerceAtLeast(2.dp.toPx())
                val left = i * slot + gap / 2
                val barWidth = slot - gap
                // Round only the top: the bar grows from the baseline.
                val path = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = left, top = size.height - h, right = left + barWidth, bottom = size.height,
                            topLeftCornerRadius = CornerRadius(minOf(radius, barWidth / 2)),
                            topRightCornerRadius = CornerRadius(minOf(radius, barWidth / 2)),
                        ),
                    )
                }
                drawPath(path, color = if (selected == null || selected == i) color else color.copy(alpha = 0.4f))
            }
            drawRect(baseline, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
        }
        Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            axisLabels.forEach { (index, label) ->
                val bias = if (values.size <= 1) 0f else index.toFloat() / (values.size - 1) * 2 - 1
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                    modifier = Modifier.align(androidx.compose.ui.BiasAlignment(bias, 0f)),
                )
            }
        }
    }
}

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")
private val SHORT_DAY = DateTimeFormatter.ofPattern("d MMM")
private val TIME = DateTimeFormatter.ofPattern("d MMM, HH:mm")

/** "35 min" below an hour, "4.3 h" above. */
private fun formatHours(hours: Double): String = when {
    hours <= 0 -> "0 min"
    hours < 1 -> "${(hours * 60).roundToInt().coerceAtLeast(1)} min"
    else -> "%.1f h".format(hours)
}

private fun hourLabel(hour: Int): String = when {
    hour == 0 -> "12am"
    hour < 12 -> "${hour}am"
    hour == 12 -> "12pm"
    else -> "${hour - 12}pm"
}

private fun ago(millis: Long): String {
    val minutes = (System.currentTimeMillis() - millis) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        minutes < 2 * 24 * 60 -> "yesterday"
        minutes < 7 * 24 * 60 -> "${minutes / (24 * 60)} days ago"
        else -> Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(SHORT_DAY)
    }
}
