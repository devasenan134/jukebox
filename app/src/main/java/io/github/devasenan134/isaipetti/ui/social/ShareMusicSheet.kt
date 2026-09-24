package io.github.devasenan134.isaipetti.ui.social

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.devasenan134.isaipetti.data.ChatMessage
import io.github.devasenan134.isaipetti.data.SongRef
import io.github.devasenan134.isaipetti.ui.components.Cover
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.SectionTitle
import kotlinx.coroutines.launch

/**
 * The music button in a chat: pick what's playing or a recently played song, optionally just a
 * part of it, and send it to this chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareMusicSheet(conversationId: Long, onSent: (ChatMessage) -> Unit, onDismiss: () -> Unit, replyTo: Long? = null) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val now by app.player.nowPlaying.collectAsStateWithLifecycle()
    val recent by app.recent.songs.collectAsStateWithLifecycle()
    val playing = now.song
    val choices = listOfNotNull(playing) + recent.filter { it.id != playing?.id }.take(RECENT_SHOWN)
    var selected by remember { mutableStateOf<SongRef?>(null) }
    var sending by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val song = selected
        if (song == null) {
            SectionTitle("Share music")
            if (choices.isEmpty()) {
                Text(
                    "Play something first, and it shows up here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(Modifier.padding(bottom = 16.dp)) {
                itemsIndexed(choices, key = { _, it -> it.id }) { i, choice ->
                    SongChoice(choice, label = if (i == 0 && playing != null) "Playing now" else null) { selected = choice }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
                IconButton(onClick = { selected = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Text("Share this song", style = MaterialTheme.typography.titleMedium)
            }
            SongChoice(song, label = null, onClick = null)
            Column(Modifier.padding(bottom = 16.dp)) {
                val shared = clipOptions(song)
                Button(
                    enabled = !sending,
                    onClick = {
                        sending = true
                        scope.launch {
                            try {
                                onSent(app.social.api.sendMessage(conversationId, "", shared, replyTo))
                                onDismiss()
                            } catch (e: Exception) {
                                Toast.makeText(context, e.message ?: "Couldn't send", Toast.LENGTH_SHORT).show()
                                sending = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text(if (shared.isClip) "Send this part" else "Send") }
            }
        }
    }
}

@Composable
private fun SongChoice(song: SongRef, label: String?, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(song.coverArt, Modifier.size(48.dp), size = 150, corner = 6.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(song.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(label, song.artist).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (label != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val RECENT_SHOWN = 10
