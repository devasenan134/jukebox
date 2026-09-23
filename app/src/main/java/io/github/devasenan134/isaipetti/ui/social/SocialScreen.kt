package io.github.devasenan134.isaipetti.ui.social

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.devasenan134.isaipetti.R
import io.github.devasenan134.isaipetti.data.Conversation
import io.github.devasenan134.isaipetti.data.Friend
import io.github.devasenan134.isaipetti.data.Invite
import io.github.devasenan134.isaipetti.data.SocialUser
import io.github.devasenan134.isaipetti.social.Social
import io.github.devasenan134.isaipetti.ui.Nav
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.ScreenHeader
import io.github.devasenan134.isaipetti.ui.components.SectionTitle
import kotlinx.coroutines.launch

@Composable
fun SocialScreen(nav: Nav) {
    val app = LocalApp.current
    val social = app.social
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by social.status.collectAsStateWithLifecycle()
    val friends by social.friends.collectAsStateWithLifecycle()
    val requests by social.requests.collectAsStateWithLifecycle()
    val conversations by social.conversations.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) } // "invite", "add", "group"

    LaunchedEffect(Unit) { social.refresh() }

    fun openDm(user: SocialUser) = scope.launch {
        try {
            nav.openChat(social.api.openDm(user.id).id)
        } catch (e: Exception) {
            Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
        }
    }

    Column {
        ScreenHeader("Friends") {
            IconButton(onClick = { dialog = "add" }) { Icon(painterResource(R.drawable.ic_person_add), contentDescription = "Add friend") }
            IconButton(onClick = { dialog = "invite" }) { Icon(Icons.Filled.Share, contentDescription = "Invite") }
        }
        if (status == Social.Status.Unavailable) {
            Text(
                "Can't reach the friends server right now. Music still works; retrying…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        val unread = conversations.sumOf { it.unread }
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { TabLabel("Chats", unread) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { TabLabel("Friends", requests.incoming.size) })
        }
        when (tab) {
            0 -> ChatList(conversations, social.me?.id, friends, onOpen = { nav.openChat(it.id) }, onNewGroup = { dialog = "group" })
            else -> FriendList(
                friends = friends,
                incoming = requests.incoming,
                outgoing = requests.outgoing,
                onOpen = ::openDm,
                onAccept = { scope.launch { runCatching { social.api.acceptFriend(it.id) }; social.refresh() } },
                onDecline = { scope.launch { runCatching { social.api.declineFriend(it.id) }; social.refresh() } },
                onRemove = { scope.launch { runCatching { social.api.removeFriend(it.id) }; social.refresh() } },
                onInvite = { dialog = "invite" },
            )
        }
    }

    when (dialog) {
        "invite" -> InviteDialog(onDismiss = { dialog = null })
        "add" -> AddFriendDialog(onDismiss = { dialog = null })
        "group" -> NewGroupDialog(friends, onDismiss = { dialog = null }, onCreated = {
            dialog = null
            nav.openChat(it.id)
        })
    }
}

@Composable
private fun TabLabel(text: String, count: Int) {
    if (count > 0) BadgedBox(badge = { Badge { Text("$count") } }) { Text(text, Modifier.padding(end = 6.dp)) } else Text(text)
}

@Composable
private fun ChatList(
    conversations: List<Conversation>,
    me: Long?,
    friends: List<Friend>,
    onOpen: (Conversation) -> Unit,
    onNewGroup: () -> Unit,
) {
    val onlineIds = friends.filter { it.online }.map { it.user.id }.toSet()
    // Chats you can't message in anymore (they left, or aren't your friend) can be deleted with a long press.
    var deleting by remember { mutableStateOf<Conversation?>(null) }
    val sessions by LocalApp.current.social.listen.sessions.collectAsStateWithLifecycle()
    deleting?.let { c -> DeleteChatDialog(c.title(me), c.id, onDone = { deleting = null }) }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            OutlinedButton(onClick = onNewGroup, modifier = Modifier.padding(16.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("New group chat", Modifier.padding(start = 8.dp))
            }
        }
        if (conversations.isEmpty()) {
            item { EmptyHint("No chats yet. Open the Friends tab and tap a friend to start one.") }
        }
        items(conversations, key = { it.id }) { c ->
            val other = c.members.firstOrNull { it.id != me }
            val title = c.title(me)
            Row(
                Modifier.fillMaxWidth()
                    .combinedClickable(onLongClick = if (c.canMessage) null else ({ deleting = c }), onClick = { onOpen(c) })
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(title, if (c.isGroup) "c${c.id}" else other?.username ?: "", online = !c.isGroup && other?.id in onlineIds)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (c.unread > 0) FontWeight.Bold else null,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (sessions[c.id].orEmpty().isNotEmpty()) Text(
                        "🎧 Listening together",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    ) else Text(
                        c.lastMessage?.let { m ->
                            val who = if (m.sender.id == me) "You: " else if (c.isGroup) "${m.sender.displayName}: " else ""
                            if (m.system) m.systemText(me) else who + (m.song?.let { "♪ ${it.title}${it.clipLabel}" + if (m.body.isNotBlank()) " – ${m.body}" else "" } ?: m.body)
                        } ?: if (c.isGroup) c.members.joinToString { it.displayName } else "Say hi 👋",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    c.lastMessage?.let { Text(chatTime(it.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (c.unread > 0) Badge(Modifier.padding(top = 4.dp)) { Text("${c.unread}") }
                }
            }
        }
    }
}

@Composable
private fun FriendList(
    friends: List<Friend>,
    incoming: List<SocialUser>,
    outgoing: List<SocialUser>,
    onOpen: (SocialUser) -> Unit,
    onAccept: (SocialUser) -> Unit,
    onDecline: (SocialUser) -> Unit,
    onRemove: (SocialUser) -> Unit,
    onInvite: () -> Unit,
) {
    val player = LocalApp.current.player
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        if (incoming.isNotEmpty()) {
            item { SectionTitle("Friend requests") }
            items(incoming, key = { "in${it.id}" }) { user ->
                PersonRow(user, subtitle = "@${user.username} wants to be friends") {
                    TextButton(onClick = { onDecline(user) }) { Text("Ignore") }
                    Button(onClick = { onAccept(user) }) { Text("Accept") }
                }
            }
        }
        item { SectionTitle("Friends · ${friends.count { it.online }} online") }
        if (friends.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyHint("Invite friends with a code, or add people who already use Isaipetti by their username.")
                    Button(onClick = onInvite, modifier = Modifier.padding(top = 12.dp)) { Text("Invite a friend") }
                }
            }
        }
        items(friends, key = { "f${it.user.id}" }) { friend ->
            var menu by remember { mutableStateOf(false) }
            val song = friend.nowPlaying
            PersonRow(
                user = friend.user,
                online = friend.online,
                subtitle = when {
                    song != null -> "♪ ${song.title}" + (song.artist?.let { " · $it" } ?: "")
                    friend.online -> "Online"
                    else -> "@${friend.user.username}"
                },
                highlight = song != null,
                onClick = { onOpen(friend.user) },
            ) {
                if (song != null) {
                    IconButton(onClick = { player.play(listOf(song.toSong())) }) {
                        Icon(painterResource(R.drawable.ic_play), contentDescription = "Play what they're playing")
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Message") }, onClick = { menu = false; onOpen(friend.user) })
                        DropdownMenuItem(text = { Text("Remove friend") }, onClick = { menu = false; onRemove(friend.user) })
                    }
                }
            }
        }
        if (outgoing.isNotEmpty()) {
            item { SectionTitle("Sent requests") }
            items(outgoing, key = { "out${it.id}" }) { user ->
                PersonRow(user, subtitle = "Waiting for @${user.username}") {
                    TextButton(onClick = { onDecline(user) }) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun PersonRow(
    user: SocialUser,
    subtitle: String,
    online: Boolean = false,
    highlight: Boolean = false,
    onClick: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(user.displayName, user.username, online = online)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(user.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { actions() }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
    )
}

/** Creates a one-time code and offers to share it (WhatsApp, SMS, …). */
@Composable
private fun InviteDialog(onDismiss: () -> Unit) {
    val app = LocalApp.current
    val social = app.social
    val context = LocalContext.current
    var invite by remember { mutableStateOf<Invite?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            invite = social.api.createInvite()
        } catch (e: Exception) {
            error = e.message
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite a friend") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    invite == null -> CircularProgressIndicator()
                    else -> {
                        Text(invite!!.code, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "Works once, for 7 days. They'll become your friend automatically when they sign up.",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            val code = invite?.code
            Button(enabled = code != null, onClick = {
                // The app has no servers built in, so the invite says what to type.
                val creds = app.session.credentials.value
                val text = "Join me on Isaipetti! Install the app, tap \"Got an invite code? Sign up\" and enter:\n" +
                    "Music server: ${creds?.server.orEmpty().removePrefix("https://")}\n" +
                    "Friends server: ${creds?.socialServer.orEmpty().removePrefix("https://")}\n" +
                    "Invite code: $code"
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share invite"))
            }) { Text("Share") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun AddFriendDialog(onDismiss: () -> Unit) {
    val social = LocalApp.current.social
    val scope = rememberCoroutineScope()
    var username by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a friend") },
        text = {
            Column {
                OutlinedTextField(value = username, onValueChange = { username = it.trim() }, label = { Text("Their username") }, singleLine = true)
                result?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            Button(enabled = username.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    result = try {
                        val status = social.api.addFriend(username).status
                        social.refresh()
                        if (status == "friends") "You're now friends with $username!" else "Request sent. They'll see it in their Friends tab."
                    } catch (e: Exception) {
                        e.message
                    }
                    busy = false
                }
            }) { Text("Send request") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun NewGroupDialog(friends: List<Friend>, onDismiss: () -> Unit, onCreated: (Conversation) -> Unit) {
    val social = LocalApp.current.social
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    val chosen = remember { mutableStateListOf<Long>() }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New group chat") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Group name") }, singleLine = true)
                if (friends.isEmpty()) EmptyHint("Add some friends first.")
                LazyColumn(Modifier.heightIn(max = 320.dp).padding(top = 8.dp)) {
                    items(friends, key = { it.user.id }) { f ->
                        val id = f.user.id
                        Row(
                            Modifier.fillMaxWidth().clickable { if (id in chosen) chosen.remove(id) else chosen.add(id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = id in chosen, onCheckedChange = { if (it) chosen.add(id) else chosen.remove(id) })
                            Text(f.user.displayName)
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank() && chosen.isNotEmpty(), onClick = {
                scope.launch {
                    try {
                        val group = social.api.createGroup(name.trim(), chosen.toList())
                        social.refreshConversationsSoon()
                        onCreated(group)
                    } catch (e: Exception) {
                        error = e.message
                    }
                }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Confirms deleting a chat you can't message in anymore, then deletes it. */
@Composable
fun DeleteChatDialog(title: String, conversationId: Long, onDone: () -> Unit, onDeleted: () -> Unit = {}) {
    val social = LocalApp.current.social
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Delete the chat \"$title\"?") },
        text = { Text("The chat and its messages are removed from your phone. This can't be undone.") },
        confirmButton = {
            Button(onClick = {
                scope.launch {
                    try {
                        social.deleteConversation(conversationId)
                        onDeleted()
                    } catch (e: Exception) {
                        Toast.makeText(context, e.message ?: "Couldn't delete", Toast.LENGTH_SHORT).show()
                    }
                    onDone()
                }
            }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
