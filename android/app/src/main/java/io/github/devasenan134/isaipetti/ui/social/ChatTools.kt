package io.github.devasenan134.isaipetti.ui.social

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.devasenan134.isaipetti.data.ChatMessage
import io.github.devasenan134.isaipetti.data.Conversation
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import kotlinx.coroutines.delay

/** Pick the chats to forward a message to (up to 10). */
@Composable
fun ForwardDialog(onForward: (List<Long>) -> Unit, onDismiss: () -> Unit) {
    val social = LocalApp.current.social
    val me = social.me?.id
    val chats by social.conversations.collectAsStateWithLifecycle()
    val choices = chats.filter { it.canMessage }
    var picked by remember { mutableStateOf(emptySet<Long>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Forward to…") },
        text = {
            if (choices.isEmpty()) Text("You have no chats to forward to.")
            else LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(choices, key = { it.id }) { chat ->
                    val checked = chat.id in picked
                    fun toggle() { picked = if (checked) picked - chat.id else if (picked.size < 10) picked + chat.id else picked }
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = ::toggle).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChatAvatar(chat, me)
                        Text(chat.title(me), Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Checkbox(checked = checked, onCheckedChange = { toggle() })
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onForward(picked.toList()) }, enabled = picked.isNotEmpty()) {
                Text(if (picked.size > 1) "Send to ${picked.size}" else "Send")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChatAvatar(chat: Conversation, me: Long?) {
    if (chat.isGroup) GroupAvatar("c${chat.id}", size = 36.dp, conversation = chat)
    else chat.members.firstOrNull { it.id != me }?.let { Avatar(it.displayName, it.username, size = 36.dp, user = it) }
}

/**
 * Search inside a chat: a box at the top and the matching messages under it, newest first, with
 * what matched in bold. Tapping one goes to it in the chat.
 */
@Composable
fun ChatSearch(conversationId: Long, me: Long?, onPick: (Long) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val api = LocalApp.current.social.api
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ChatMessage>?>(null) }
    var failed by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // Ask the server a moment after typing stops.
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) { results = null; return@LaunchedEffect }
        delay(300)
        val found = runCatching { api.search(conversationId, q) }
        failed = found.isFailure
        results = found.getOrNull()
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(100) },
                placeholder = { Text("Search this chat") },
                singleLine = true,
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close search") }
        }
        val list = results
        when {
            failed -> Hint("Couldn't search right now")
            list == null -> Hint("Search messages and shared songs")
            list.isEmpty() -> Hint("Nothing found for \"${query.trim()}\"")
            else -> LazyColumn(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                items(list, key = { it.id }) { m ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(m.id) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row {
                            Text(
                                if (m.sender.id == me) "You" else m.sender.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(chatTime(m.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(highlight(m.summary(), query.trim()), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
}

/** [text] with every place [query] appears (ignoring case) in bold. */
private fun highlight(text: String, query: String): AnnotatedString = buildAnnotatedString {
    if (query.isEmpty()) { append(text); return@buildAnnotatedString }
    var at = 0
    while (true) {
        val found = text.indexOf(query, at, ignoreCase = true)
        if (found < 0) break
        append(text.substring(at, found))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(found, found + query.length)) }
        at = found + query.length
    }
    append(text.substring(at))
}
