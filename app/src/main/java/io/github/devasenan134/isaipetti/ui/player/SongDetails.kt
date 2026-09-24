package io.github.devasenan134.isaipetti.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.devasenan134.isaipetti.data.SongDetails
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.formatDuration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** "About this song", below the player: who made it, where it's from, and the file itself. */
@Composable
fun SongDetailsSection(songId: String?, onOpenAlbum: (String) -> Unit, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val details by produceState<SongDetails?>(null, songId) {
        value = null
        value = songId?.let { runCatching { app.api.songDetails(it) }.getOrNull() }
    }
    Column(modifier) {
        Text("About this song", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
        val song = details
        if (song == null) {
            Text(
                if (songId == null) "Nothing is playing." else "Loading…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                val singers = song.artists.map { it.name }.ifEmpty { listOfNotNull(song.artist) }
                // In film music the album artist (music director) is the composer; songs without a composer tag use it.
                val composers = song.credited("composer").ifEmpty { listOfNotNull(song.displayComposer) }
                    .ifEmpty { song.albumArtists.map { it.name } }.ifEmpty { listOfNotNull(song.displayAlbumArtist) }
                Detail("Artists", singers.joinToString())
                Detail("Composer", composers.joinToString())
                Detail("Lyricist", song.credited("lyricist").joinToString())
                Detail("Movie", song.album.orEmpty(), onClick = song.albumId?.let { id -> { onOpenAlbum(id) } })
                Detail("Year", song.year?.toString().orEmpty())
                Detail("Genre", song.genres.map { it.name }.ifEmpty { listOfNotNull(song.genre) }.joinToString())
            }
        }
        Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Column(Modifier.padding(16.dp)) {
                Detail(
                    "Track",
                    listOfNotNull(song.discNumber?.takeIf { it > 1 }?.let { "Disc $it" }, song.track?.let { "Track $it" }).joinToString(", "),
                )
                Detail("Length", formatDuration(song.duration))
                Detail("Quality", quality(song))
                Detail("File size", song.size?.let(::fileSize).orEmpty())
                Detail("BPM", song.bpm?.takeIf { it > 0 }?.toString().orEmpty())
                Detail(
                    "Plays",
                    song.playCount?.let { count ->
                        (if (count == 1L) "Once" else "%,d times".format(count)) + (song.played?.let { ", last on ${date(it)}" } ?: "")
                    } ?: "Not played yet",
                )
                Detail("Added", song.created?.let(::date).orEmpty())
                Detail("Comment", song.comment.orEmpty())
            }
        }
    }
}

/** One labelled line. Empty values are skipped, so songs with fewer tags just show less. */
@Composable
private fun Detail(label: String, value: String, onClick: (() -> Unit)? = null) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (onClick != null) FontWeight.SemiBold else null,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).let { if (onClick != null) it.clickable(onClick = onClick) else it },
        )
    }
}

/** "FLAC · 1,411 kbps · 44.1 kHz · 16-bit · Stereo" */
private fun quality(song: SongDetails): String = listOfNotNull(
    song.suffix?.uppercase(),
    song.bitRate?.takeIf { it > 0 }?.let { "%,d kbps".format(it) },
    song.samplingRate?.takeIf { it > 0 }?.let { "%.1f kHz".format(it / 1000.0).replace(".0 ", " ") },
    song.bitDepth?.takeIf { it > 0 }?.let { "$it-bit" },
    when (song.channelCount) {
        1 -> "Mono"
        2 -> "Stereo"
        null, 0 -> null
        else -> "${song.channelCount} channels"
    },
).joinToString(" · ")

private fun fileSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
    else -> "%d KB".format(bytes / 1000)
}

/** "2026-09-12T18:04:00Z" -> "12 Sep 2026" */
private fun date(iso: String): String = runCatching {
    OffsetDateTime.parse(iso).format(DateTimeFormatter.ofPattern("d MMM yyyy"))
}.getOrDefault(iso.take(10))
