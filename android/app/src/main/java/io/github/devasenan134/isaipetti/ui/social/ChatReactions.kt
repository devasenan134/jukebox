package io.github.devasenan134.isaipetti.ui.social

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import io.github.devasenan134.isaipetti.data.ChatMessage
import io.github.devasenan134.isaipetti.data.SocialUser

/** The emoji offered first when you long-press a message, chosen by you and kept on this phone. */
class QuickReactions(context: Context) {
    private val prefs = context.getSharedPreferences("chat", Context.MODE_PRIVATE)

    var emoji by mutableStateOf(prefs.getString(KEY, null)?.split(' ')?.filter { it.isNotBlank() }?.takeIf { it.size == COUNT } ?: DEFAULT)
        private set

    fun set(list: List<String>) {
        emoji = list
        prefs.edit { putString(KEY, list.joinToString(" ")) }
    }

    companion object {
        const val COUNT = 6
        val DEFAULT = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
        private const val KEY = "quickReactions"
    }
}

/** Emoji to pick from (any other can be typed with the keyboard). */
private val EMOJI = listOf(
    "👍", "❤️", "😂", "😮", "😢", "🙏", "🔥", "🥰", "😍", "🤩", "😘", "😊", "😁", "🤣", "😅", "😆",
    "😉", "😎", "🤗", "🤔", "🙄", "😏", "😬", "😴", "🥺", "😭", "😱", "😡", "🤯", "🥳", "😇", "🤭",
    "🫡", "🫶", "👏", "🙌", "💪", "👌", "✌️", "🤞", "🤘", "👀", "👎", "💯", "✨", "🎉", "🎶", "🎵",
    "🎧", "🎤", "🎸", "🥁", "💃", "🕺", "💔", "💙", "💚", "💛", "🧡", "💜", "🖤", "🤍", "☕", "🍿",
    "🌙", "☀️", "🌹", "💐", "🙈", "💀", "🤡", "👻", "🤖", "😤", "😳", "🥲",
)

/** Something that looks like an emoji (no letters or digits), for the "type any emoji" box. */
private fun looksLikeEmoji(text: String) = text.isNotBlank() && text.length <= 16 && text.none { it.isLetterOrDigit() || it.isWhitespace() }

/** Pick any emoji to react with: from the grid, or typed with the keyboard's emoji. */
@Composable
fun EmojiPickerDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("React with…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EmojiGrid(onPick)
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.trim().take(16) },
                    placeholder = { Text("Or type any emoji") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onPick(typed) }, enabled = looksLikeEmoji(typed)) { Text("React") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmojiGrid(onPick: (String) -> Unit) {
    LazyVerticalGrid(GridCells.Adaptive(44.dp), Modifier.heightIn(max = 260.dp)) {
        items(EMOJI) { emoji ->
            Text(
                emoji,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onPick(emoji) }.padding(6.dp),
            )
        }
    }
}

/**
 * Change the emoji in the long-press row: tap a place in the row, then the emoji to put there
 * (from the grid, or typed).
 */
@Composable
fun EditQuickReactionsDialog(current: List<String>, onSave: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var row by remember { mutableStateOf(current) }
    var slot by remember { mutableIntStateOf(0) }
    var typed by remember { mutableStateOf("") }
    fun put(emoji: String) {
        // An emoji already in the row swaps places instead of appearing twice.
        val was = row.indexOf(emoji)
        row = row.toMutableList().also { if (was >= 0) it[was] = row[slot]; it[slot] = emoji }
        slot = (slot + 1) % row.size
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your quick reactions") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Tap a place, then the emoji to put there.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEachIndexed { i, emoji ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (i == slot) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent,
                            border = BorderStroke(if (i == slot) 2.dp else 1.dp, if (i == slot) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { slot = i },
                        ) {
                            Text(emoji, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(6.dp))
                        }
                    }
                }
                EmojiGrid(::put)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it.trim().take(16) },
                        placeholder = { Text("Or type any emoji") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { put(typed); typed = "" }, enabled = looksLikeEmoji(typed)) { Text("Use") }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(row) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = { row = QuickReactions.DEFAULT; slot = 0 }) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * Who reacted to a message: "All" and a tab per emoji, each listing people. Your own reaction
 * says "Tap to remove".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactionsSheet(message: ChatMessage, people: List<SocialUser>, me: Long?, onRemoveMine: (() -> Unit)?, onDismiss: () -> Unit) {
    val reactions = message.reactions
    val all = reactions.flatMap { r -> r.userIds.map { it to r.emoji } }
    var tab by remember { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 16.dp) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("All ${all.size}") })
            reactions.forEachIndexed { i, r ->
                Tab(selected = tab == i + 1, onClick = { tab = i + 1 }, text = { Text("${r.emoji} ${r.userIds.size}") })
            }
        }
        val shown = if (tab == 0) all else reactions.getOrNull(tab - 1)?.let { r -> r.userIds.map { it to r.emoji } }.orEmpty()
        // You first, then the others by name.
        val sorted = shown.sortedWith(compareBy<Pair<Long, String>> { it.first != me }.thenBy { (id, _) -> people.firstOrNull { it.id == id }?.displayName?.lowercase() })
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            items(sorted, key = { "${it.first}-${it.second}" }) { (id, emoji) ->
                val person = people.firstOrNull { it.id == id }
                val mine = id == me
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (mine && onRemoveMine != null) Modifier.clickable { onRemoveMine(); onDismiss() } else Modifier)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (person != null) Avatar(person.displayName, person.username, size = 40.dp, user = person)
                    else Surface(Modifier.size(40.dp), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHighest) {}
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(if (mine) "You" else person?.displayName ?: "Someone who left", style = MaterialTheme.typography.bodyLarge)
                        if (mine && onRemoveMine != null) {
                            Text("Tap to remove", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(emoji, style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
    }
}
