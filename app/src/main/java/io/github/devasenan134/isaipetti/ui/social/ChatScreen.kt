package io.github.devasenan134.isaipetti.ui.social

import io.github.devasenan134.isaipetti.ui.components.rememberPhotoPicker
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.layout.Box
import io.github.devasenan134.isaipetti.data.SocialUser
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.devasenan134.isaipetti.R
import io.github.devasenan134.isaipetti.data.ChatMessage
import io.github.devasenan134.isaipetti.data.SongRef
import io.github.devasenan134.isaipetti.push.Notifications
import io.github.devasenan134.isaipetti.ui.Nav
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.ScreenHeader
import kotlinx.coroutines.launch

private const val PAGE = 50

@Composable
fun ChatScreen(conversationId: Long, nav: Nav) {
    val app = LocalApp.current
    val social = app.social
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = social.me?.id
    val conversations by social.conversations.collectAsStateWithLifecycle()
    val friends by social.friends.collectAsStateWithLifecycle()
    val conversation = conversations.firstOrNull { it.id == conversationId }
    val joinedJam by social.listen.joined.collectAsStateWithLifecycle()
    val jamOwners by social.listen.owners.collectAsStateWithLifecycle()
    val jamOwner = jamOwners[conversationId]

    val messages = remember { mutableStateListOf<ChatMessage>() }
    var hasOlder by remember { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun add(message: ChatMessage) {
        // A message we already have comes back when it changes (an answered song request): replace it.
        val i = messages.indexOfFirst { it.id == message.id }
        if (i >= 0) messages[i] = message else messages.add(message)
    }

    /** The jam's owner answers a song request; an accepted song joins the jam's queue. */
    fun answerRequest(message: ChatMessage, accept: Boolean) {
        scope.launch {
            try {
                add(social.api.answerRequest(conversationId, message.id, accept))
                if (accept) message.song?.let { app.player.queueRequested(it.toSong()) }
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Couldn't answer the request", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Load the latest page, then follow new messages live while this screen is open.
    LaunchedEffect(conversationId) {
        runCatching { social.api.messages(conversationId) }.onSuccess { page ->
            messages.clear()
            messages.addAll(page)
            hasOlder = page.size == PAGE
            page.lastOrNull()?.let { social.markRead(conversationId, it.id) }
        }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
        social.messages.collect { if (it.conversationId == conversationId) add(it) }
    }
    // The owner deleted this group for everyone.
    LaunchedEffect(conversationId) {
        social.removed.collect {
            if (it == conversationId) {
                Toast.makeText(context, "This group was deleted", Toast.LENGTH_SHORT).show()
                nav.back()
            }
        }
    }
    DisposableEffect(conversationId) {
        social.openConversationId = conversationId
        Notifications.clearChat(context, conversationId)
        onDispose { social.openConversationId = null }
    }
    // Jump to the newest message when one arrives (the list is drawn bottom-up).
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(0) }

    fun send(body: String, song: SongRef? = null) {
        sending = true
        scope.launch {
            try {
                add(social.api.sendMessage(conversationId, body, song))
                if (song == null) draft = ""
                social.refreshConversationsSoon()
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Couldn't send", Toast.LENGTH_SHORT).show()
            }
            sending = false
        }
    }

    val other = conversation?.members?.firstOrNull { it.id != me }
    val otherFriend = friends.firstOrNull { it.user.id == other?.id }
    val listen = social.listen
    val sessions by listen.sessions.collectAsStateWithLifecycle()
    val joined by listen.joined.collectAsStateWithLifecycle()
    val listeners = sessions[conversationId].orEmpty()
    val inSession = joined == conversationId
    Column(Modifier.imePadding()) {
        ScreenHeader(conversation?.title(me) ?: "Chat", onBack = nav.back) {
            if (conversation?.isGroup == true) GroupMenu(conversation.title(me), conversation.createdBy == me, conversationId, hasPicture = conversation.picture != null, onGone = nav.back)
            if (conversation?.canMessage == true) {
                IconButton(onClick = {
                    when {
                        inSession -> listen.leave()
                        listeners.isEmpty() -> listen.start(conversationId)
                        else -> listen.join(conversationId)
                    }
                }) {
                    Icon(
                        painterResource(R.drawable.ic_headphones),
                        contentDescription = if (inSession) "Stop listening together" else "Listen together",
                        tint = if (inSession) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                }
            }
        }
        val subtitle = when {
            conversation == null -> null
            conversation.isGroup -> conversation.members.joinToString { if (it.id == me) "You" else it.displayName }
            otherFriend?.nowPlaying != null -> "♪ Listening to ${otherFriend.nowPlaying.title}"
            otherFriend?.online == true -> "Online"
            else -> null
        }
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (otherFriend?.nowPlaying != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 60.dp, end = 16.dp, bottom = 4.dp),
            )
        }

        if (listeners.isNotEmpty() && conversation != null) {
            ListenBar(conversation.members, listeners, me, inSession, onJoin = { listen.join(conversationId) }, onLeave = listen::leave)
        }

        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            val newestFirst = messages.asReversed()
            itemsIndexed(newestFirst, key = { _, m -> m.id }) { i, message ->
                // Show the sender's name in groups when a new person starts talking.
                val olderNeighbour = newestFirst.getOrNull(i + 1)
                val showName = conversation?.isGroup == true && message.sender.id != me && olderNeighbour?.sender?.id != message.sender.id
                if (message.system) SystemLine(message.systemText(me))
                else Bubble(
                    message,
                    mine = message.sender.id == me,
                    showName = showName,
                    // Only the jam's owner answers requests, while the jam is on.
                    canAnswer = message.request == "pending" && message.sender.id != me && jamOwner == me && joinedJam == conversationId,
                    jamOwnerName = conversation?.members?.firstOrNull { it.id == jamOwner }?.displayName,
                    onAnswer = { accept -> answerRequest(message, accept) },
                )
            }
            if (hasOlder) {
                item {
                    TextButton(onClick = {
                        scope.launch {
                            val older = runCatching { social.api.messages(conversationId, before = messages.firstOrNull()?.id) }.getOrDefault(emptyList())
                            messages.addAll(0, older.filter { o -> messages.none { it.id == o.id } })
                            hasOlder = older.size == PAGE
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Load earlier messages") }
                }
            }
        }

        if (conversation?.canMessage == false) {
            // They left or aren't your friend anymore: no replying, but the chat can be deleted.
            var confirm by remember { mutableStateOf(false) }
            if (confirm) DeleteChatDialog(conversation.title(me), conversationId, onDone = { confirm = false }, onDeleted = nav.back)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (conversation.isGroup) "Everyone else has left this group." else "You can't message ${conversation.title(me)} anymore.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { confirm = true }) { Text("Delete chat") }
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Share what's playing or a recently played song, whole or just a part.
            var sharingMusic by remember { mutableStateOf(false) }
            IconButton(enabled = !sending, onClick = { sharingMusic = true }) {
                Icon(painterResource(R.drawable.ic_music_note), contentDescription = "Share a song")
            }
            if (sharingMusic) {
                ShareMusicSheet(conversationId, onSent = { add(it); social.refreshConversationsSoon() }, onDismiss = { sharingMusic = false })
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Message") },
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f),
            )
            IconButton(enabled = draft.isNotBlank() && !sending, onClick = { send(draft.trim()) }) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun Bubble(
    message: ChatMessage,
    mine: Boolean,
    showName: Boolean,
    canAnswer: Boolean = false,
    jamOwnerName: String? = null,
    onAnswer: (Boolean) -> Unit = {},
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (showName) {
            Text(
                message.sender.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, top = 6.dp),
            )
        }
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp,
            ),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (message.request != null) {
                    Text(
                        if (mine) "You asked to play" else "${message.sender.displayName} asked to play",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                message.song?.let { SongCard(it, Modifier.padding(bottom = if (message.body.isNotBlank() || message.request != null) 6.dp else 0.dp)) }
                if (message.body.isNotBlank()) Text(message.body, style = MaterialTheme.typography.bodyLarge)
                message.request?.let { status -> SongRequestStatus(status, canAnswer, jamOwnerName, onAnswer) }
                Text(
                    chatTime(message.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                )
            }
        }
    }
}

/** "Alice and Bob are listening together · Join", or "Listening together with Alice · Leave" once you're in. */
@Composable
private fun ListenBar(members: List<SocialUser>, listeners: List<Long>, me: Long?, inSession: Boolean, onJoin: () -> Unit, onLeave: () -> Unit) {
    val others = members.filter { it.id in listeners && it.id != me }.map { it.displayName }
    val names = when (others.size) {
        0 -> ""
        1 -> others[0]
        else -> others.dropLast(1).joinToString() + " and " + others.last()
    }
    val text = when {
        inSession && others.isEmpty() -> "Listening together. Waiting for others to join"
        inSession -> "Listening together with $names"
        else -> "$names ${if (others.size == 1) "is" else "are"} listening together"
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_headphones), contentDescription = null, Modifier.size(18.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
            if (inSession) TextButton(onClick = onLeave) { Text("Leave") } else Button(onClick = onJoin) { Text("Join") }
        }
    }
}

/** A line about the chat itself, like "Alice left the group". */
@Composable
private fun SystemLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}

/** The ⋮ menu of a group chat: leave it, or (for its owner) delete it for everyone. */
@Composable
private fun GroupMenu(title: String, isOwner: Boolean, conversationId: Long, hasPicture: Boolean, onGone: () -> Unit) {
    val social = LocalApp.current.social
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) } // "leave" or "delete"
    // Anyone in the group can change its photo; the chat shows who did.
    suspend fun changePicture(change: suspend () -> Unit, done: String) {
        runCatching { change() }
            .onSuccess { social.refreshConversationsSoon(); Toast.makeText(context, done, Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(context, it.message ?: "Couldn't change the photo", Toast.LENGTH_SHORT).show() }
    }
    val picker = rememberPhotoPicker(
        title = "Group photo",
        round = true,
        onRemove = if (hasPicture) ({ scope.launch { changePicture({ social.api.removeGroupPicture(conversationId) }, "Group photo removed") } }) else null,
    ) { jpeg -> changePicture({ social.api.setGroupPicture(conversationId, jpeg) }, "Group photo updated") }

    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Change group photo") }, onClick = { open = false; picker.open() })
            DropdownMenuItem(text = { Text("Leave group") }, onClick = { open = false; confirm = "leave" })
            if (isOwner) {
                DropdownMenuItem(
                    text = { Text("Delete for everyone", color = MaterialTheme.colorScheme.error) },
                    onClick = { open = false; confirm = "delete" },
                )
            }
        }
    }

    confirm?.let { action ->
        val leaving = action == "leave"
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (leaving) "Leave \"$title\"?" else "Delete \"$title\" for everyone?") },
            text = {
                Text(
                    if (leaving) "You won't get its messages anymore, and the others will see that you left." +
                        (if (isOwner) " Someone else in the group becomes its owner." else "")
                    else "The group and all its messages are deleted for every member. This can't be undone.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirm = null
                        scope.launch {
                            try {
                                if (leaving) social.leaveGroup(conversationId) else social.deleteForEveryone(conversationId)
                                onGone()
                            } catch (e: Exception) {
                                Toast.makeText(context, e.message ?: "Something went wrong", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = if (leaving) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(if (leaving) "Leave" else "Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

/** Under a song request: Accept/Decline for the jam's owner, or where the request stands for everyone else. */
@Composable
private fun SongRequestStatus(status: String, canAnswer: Boolean, ownerName: String?, onAnswer: (Boolean) -> Unit) {
    if (canAnswer) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onAnswer(true) }) { Text("Accept") }
            OutlinedButton(onClick = { onAnswer(false) }) { Text("Decline") }
        }
        return
    }
    Text(
        when (status) {
            "accepted" -> "✓ Added to the queue"
            "declined" -> "Declined"
            "expired" -> "The jam ended before this was answered"
            else -> "Waiting for ${ownerName ?: "the host"}"
        },
        style = MaterialTheme.typography.labelMedium,
        color = if (status == "accepted") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
