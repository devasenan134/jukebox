package io.github.devasenan134.isaipetti.ui.mixes

import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import io.github.devasenan134.isaipetti.ui.components.UiSize
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.devasenan134.isaipetti.IsaipettiApp
import io.github.devasenan134.isaipetti.R
import io.github.devasenan134.isaipetti.data.Mix
import io.github.devasenan134.isaipetti.data.MixSection
import io.github.devasenan134.isaipetti.ui.Nav
import io.github.devasenan134.isaipetti.ui.components.Cover
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.SectionTitle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The mix's colour as a Compose colour ("#3B6E8F"). */
fun Mix.tint(): Color = runCatching { Color(android.graphics.Color.parseColor(color)) }.getOrDefault(Color(0xFF7A3E9D))

/**
 * A mix's artwork, made on the phone. Three looks:
 * - made for you (Daily Mixes, Discover Weekly...): a colour gradient, like cover art;
 * - "This Is" and stations: the composer's, singer's or song's picture on a soft pastel;
 * - everything else (moods, decades, charts): the covers of its first movies.
 * All carry the "Isai Pettai" mark and the mix's name.
 */
@Composable
fun MixCover(mix: Mix, modifier: Modifier = Modifier, size: Dp = 150.dp) {
    val big = size > 120.dp
    val tiny = size < 80.dp // a thumbnail: just the artwork
    val tint = mix.tint()
    val pastel = mix.round || mix.endless
    val gradient = !pastel && mix.personal
    val textColor = if (pastel) Color(0xFF1F1B24) else Color.White
    val background = when {
        pastel -> Modifier.background(pastelOf(tint))
        gradient -> Modifier.background(Brush.linearGradient(listOf(glowOf(tint), tint, partnerOf(tint))))
        else -> Modifier.background(tint)
    }
    Box(modifier.size(size).clip(RoundedCornerShape(size / 18)).then(background)) {
        when {
            pastel -> Cover(
                mix.covers.firstOrNull(),
                Modifier.align(Alignment.Center).padding(bottom = if (tiny) 0.dp else size / 6)
                    .size(size * if (tiny) 0.72f else 0.56f).clip(if (mix.round) CircleShape else RoundedCornerShape(size / 16)),
                size = 300, corner = if (mix.round) size else size / 16,
            )
            gradient -> Unit
            mix.covers.size >= 4 -> Column(Modifier.fillMaxSize()) {
                mix.covers.take(4).chunked(2).forEach { row ->
                    Row(Modifier.weight(1f)) { row.forEach { Cover(it, Modifier.weight(1f).fillMaxSize(), size = 200, corner = 0.dp) } }
                }
            }
            mix.covers.isNotEmpty() -> Cover(mix.covers.first(), Modifier.fillMaxSize(), size = 400, corner = 0.dp)
        }
        // Covers get darker towards the bottom so the name stays readable on any picture.
        if (!pastel && !gradient) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to tint.copy(alpha = 0.95f))))
        }
        if (tiny) return@Box
        Row(Modifier.align(Alignment.TopStart).padding(size / 18), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(if (mix.endless) R.drawable.ic_radio else R.drawable.ic_music_note), contentDescription = null,
                tint = textColor, modifier = Modifier.size(if (big) 14.dp else 10.dp),
            )
            Text(
                "Isai Pettai", color = textColor, fontWeight = FontWeight.Bold,
                fontSize = if (big) 12.sp else 9.sp, modifier = Modifier.padding(start = 3.dp),
            )
        }
        Text(
            mix.title,
            color = textColor,
            fontWeight = FontWeight.ExtraBold,
            fontSize = if (big) (if (gradient) 26.sp else 20.sp) else 14.sp,
            lineHeight = if (big) (if (gradient) 28.sp else 22.sp) else 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = size / 14, vertical = size / 16),
        )
    }
}

/** A soft pastel of [color]: the same hue, pale and gentle. */
private fun pastelOf(color: Color): Color = withHsl(color) { it[1] = 0.55f; it[2] = 0.84f }

/** For gradients: a brighter, warmer neighbour of [color] and a deeper, cooler one. */
private fun glowOf(color: Color): Color = withHsl(color) { it[0] = (it[0] + 330f) % 360f; it[1] = maxOf(it[1], 0.6f); it[2] = 0.58f }
private fun partnerOf(color: Color): Color = withHsl(color) { it[0] = (it[0] + 40f) % 360f; it[1] = maxOf(it[1], 0.5f); it[2] = 0.28f }

private fun withHsl(color: Color, change: (FloatArray) -> Unit): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    change(hsl)
    return Color(ColorUtils.HSLToColor(hsl))
}

/** A tile on Home: the artwork, then the mix's one-line description. */
@Composable
fun MixCard(mix: Mix, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(UiSize.Tile).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(6.dp)) {
        MixCover(mix, size = UiSize.Tile - 12.dp)
        Text(
            mix.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Home's rows of mixes ("Made for you", "Moods and vibes", ...). */
fun LazyListScope.mixSections(sections: List<MixSection>, nav: Nav) {
    for (section in sections) {
        if (section.mixes.isEmpty()) continue
        item(key = "mixes-${section.id}") {
            Column {
                // "Made for Devs", like Spotify's "Made For <name>".
                val name = madeForName()
                SectionTitle(if (section.id == "made-for-you" && name != null) "Made for $name" else section.title)
                LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                    items(section.mixes, key = { it.id }) { mix -> MixCard(mix, onClick = { nav.openMix(mix.id) }) }
                }
            }
        }
    }
}

/** Your name for "Made for …": your name on the friends server, or else your username. */
@Composable
fun madeForName(): String? {
    val app = LocalApp.current
    return app.social.me?.displayName?.takeIf { it.isNotBlank() } ?: app.session.credentials.value?.username
}

/** "Updated today", "Updated yesterday", "Updated 12 Sep". */
fun updatedText(millis: Long): String {
    if (millis <= 0) return ""
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return "Updated " + when (day) {
        today -> "today"
        today.minusDays(1) -> "yesterday"
        else -> day.format(DateTimeFormatter.ofPattern("d MMM"))
    }
}

/**
 * Starts a station by Isai Pettai: [kind] is "song", "album", "composer" or "singer". It plays at once
 * and keeps going (the player asks for more songs as it goes).
 */
suspend fun startStation(app: IsaipettiApp, context: Context, kind: String, id: String) {
    runCatching { app.social.api.mix("radio-$kind-$id") }
        .onSuccess { station ->
            app.activity.mix(station)
            app.player.play(station.songs.map { it.toSong() }, source = station.source)
            Toast.makeText(context, "Playing ${station.title}", Toast.LENGTH_SHORT).show()
        }
        .onFailure { Toast.makeText(context, it.message ?: "Couldn't start the station", Toast.LENGTH_SHORT).show() }
}
