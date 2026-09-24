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
import androidx.compose.foundation.clickable
import io.github.devasenan134.isaipetti.ui.player.QueueSheet
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import io.github.devasenan134.isaipetti.data.ReplyQuote
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.font.FontStyle
import kotlinx.coroutines.flow.drop
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.core.content.FileProvider
import java.io.File

private const val PAGE = 50

/** Someone counts as typing for this long after their last "typing" (the app sends one every 3 seconds). */
private const val TYPING_SHOWN_MS = 6_000L

/** The emoji offered when you long-press a message. */
private val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

// Receiving GIFs and stickers from the keyboard (contentReceiver) is still marked experimental.
@OptIn(ExperimentalFoundationApi::class)
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
    val draft = rememberTextFieldState()
    var sending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // The message being replied to (swipe a message right, or long-press it), shown above the text box.
    var replyingTo by remember { mutableStateOf<ChatMessage?>(null) }
    val input = remember { FocusRequester() }
    // A message the chat just jumped to (tapping a reply's quote), lit up for a moment.
    var highlighted by remember { mutableStateOf<Long?>(null) }
    // A photo or GIF picked from the gallery or camera, waiting for its caption; a picture on its way.
    var outgoing by remember { mutableStateOf<OutgoingImage?>(null) }
    var uploading by remember { mutableStateOf(false) }
    // The photo or GIF open full screen.
    var viewing by remember { mutableStateOf<ChatMessage?>(null) }
    // Your message whose text is being changed (the text box holds the new text), or being deleted.
    var editing by remember { mutableStateOf<ChatMessage?>(null) }
    var deleting by remember { mutableStateOf<ChatMessage?>(null) }

    fun add(message: ChatMessage) {
        // A message we already have comes back when it changes (an answered song request): replace it.
        val i = messages.indexOfFirst { it.id == message.id }
        if (i >= 0) messages[i] = message else messages.add(message)
    }

    /** The jam's owner answers a song request; an accepted song plays right away or joins the jam's queue. */
    fun answerRequest(message: ChatMessage, accept: Boolean) {
        scope.launch {
            try {
                add(social.api.answerRequest(conversationId, message.id, accept))
                if (accept) message.song?.let {
                    if (message.requestMode == "now") app.player.playRequestedNow(it.toSong()) else app.player.queueRequested(it.toSong())
                }
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

    fun reply(message: ChatMessage) {
        replyingTo = message
        runCatching { input.requestFocus() }
    }

    /** Scrolls to the message a reply quotes, if it's loaded, and lights it up. */
    fun jumpTo(id: Long) {
        val i = messages.indexOfFirst { it.id == id }
        if (i < 0) {
            Toast.makeText(context, "That message is further back. Load earlier messages to see it", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            listState.animateScrollToItem(messages.size - 1 - i)
            highlighted = id
            delay(1_500)
            if (highlighted == id) highlighted = null
        }
    }

    /** Sends a photo, GIF or sticker (as a reply, if one is being written). */
    fun sendPicture(image: OutgoingImage, caption: String) {
        uploading = true
        val replyTo = replyingTo?.id
        scope.launch {
            try {
                add(social.api.sendImage(conversationId, image.bytes, image.mime, image.kind, image.width, image.height, caption, replyTo))
                if (replyingTo?.id == replyTo) replyingTo = null
                social.refreshConversationsSoon()
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Couldn't send the picture", Toast.LENGTH_SHORT).show()
            }
            uploading = false
        }
    }

    fun picked(uri: android.net.Uri?) {
        if (uri == null) return
        scope.launch {
            val image = prepareChatPicture(context, uri)
            if (image == null) Toast.makeText(context, "Couldn't read that picture, or it's over 5 MB", Toast.LENGTH_SHORT).show()
            outgoing = image
        }
    }
    // Android's photo picker (no permission needed; GIFs included), or the camera app.
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked(it) }
    val photoFile = remember { File(File(context.cacheDir, "photos").apply { mkdirs() }, "chat-camera.jpg") }
    val photoUri = remember { FileProvider.getUriForFile(context, "${context.packageName}.files", photoFile) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken -> if (taken) picked(photoUri) }

    fun pin(message: Long, hours: Int) {
        scope.launch {
            runCatching { social.api.pin(conversationId, message, hours) }
                .onSuccess { social.refreshConversationsSoon() }
                .onFailure { Toast.makeText(context, it.message ?: "Couldn't pin it", Toast.LENGTH_SHORT).show() }
        }
    }

    fun unpin(message: Long) {
        scope.launch {
            runCatching { social.api.unpin(conversationId, message) }
                .onSuccess { social.refreshConversationsSoon(); Toast.makeText(context, "Unpinned", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, it.message ?: "Couldn't unpin it", Toast.LENGTH_SHORT).show() }
        }
    }

    /** Your reaction to a message (the same emoji again takes it away). */
    fun react(message: ChatMessage, emoji: String?) {
        scope.launch {
            runCatching { social.api.react(conversationId, message.id, emoji) }
                .onSuccess { add(it) }
                .onFailure { Toast.makeText(context, it.message ?: "Couldn't react", Toast.LENGTH_SHORT).show() }
        }
    }

    fun startEditing(message: ChatMessage) {
        editing = message
        replyingTo = null
        draft.setTextAndPlaceCursorAtEnd(message.body)
        runCatching { input.requestFocus() }
    }

    fun stopEditing() {
        editing = null
        draft.clearText()
    }

    fun saveEdit(message: ChatMessage, body: String) {
        sending = true
        scope.launch {
            try {
                add(social.api.editMessage(conversationId, message.id, body))
                stopEditing()
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Couldn't change it", Toast.LENGTH_SHORT).show()
            }
            sending = false
        }
    }

    // Tell the others you're typing (Social sends it at most every few seconds).
    LaunchedEffect(conversationId) {
        snapshotFlow { draft.text.toString() }.drop(1).collect { if (it.isNotBlank() && editing == null) social.sendTyping(conversationId) }
    }

    fun send(body: String, song: SongRef? = null) {
        sending = true
        scope.launch {
            try {
                add(social.api.sendMessage(conversationId, body, song, replyingTo?.id))
                replyingTo = null
                if (song == null) draft.clearText()
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
    // We started the jam here: its button ends it (for everyone).
    val myJam = inSession && jamOwner == me
    // Who else is in the jam, for the line next to the chat's name.
    val jammers = conversation?.members.orEmpty().filter { it.id in listeners && it.id != me }.map { it.displayName }
    val jammerNames = when (jammers.size) {
        0 -> ""
        1 -> jammers[0]
        else -> jammers.dropLast(1).joinToString() + " and " + jammers.last()
    }
    val jamNote = when {
        listeners.isEmpty() -> null
        inSession && jammers.isEmpty() -> "Your jam · waiting for others"
        inSession -> "Jamming with $jammerNames"
        else -> "$jammerNames ${if (jammers.size == 1) "is" else "are"} jamming"
    }
    var showQueue by remember { mutableStateOf(false) }
    if (showQueue) QueueSheet(onDismiss = { showQueue = false })
    // A group's members: who's in the jam, online, offline (and adding/removing for its owner).
    var showMembers by remember { mutableStateOf(false) }
    if (showMembers && conversation?.isGroup == true) GroupInfoSheet(conversation, onDismiss = { showMembers = false })
    Column(Modifier.imePadding()) {
        ScreenHeader(
            conversation?.title(me) ?: "Chat",
            onBack = nav.back,
            note = jamNote,
            onTitleClick = if (conversation?.isGroup == true) ({ showMembers = true }) else null,
        ) {
            // The jam's queue: everyone in it can look, the host can also move and remove songs.
            if (inSession) {
                IconButton(onClick = { showQueue = true }) {
                    Icon(painterResource(R.drawable.ic_queue), contentDescription = "Jam queue")
                }
            }
            if (conversation?.canMessage == true) {
                // One button, four looks: a record to start a jam, the DJ deck while it's yours (tap to
                // end it for everyone), headphones while you're in someone else's (tap to leave), and
                // headphones with a plus when one is on that you haven't joined (tap to join).
                val (icon, label) = when {
                    myJam -> R.drawable.ic_jam_dj to "End jam"
                    inSession -> R.drawable.ic_headphones to "Leave jam"
                    listeners.isNotEmpty() -> R.drawable.ic_jam_join to "Join jam"
                    else -> R.drawable.ic_jam to "Start a jam"
                }
                IconButton(onClick = {
                    when {
                        myJam -> {
                            listen.leave()
                            Toast.makeText(context, "Jam ended", Toast.LENGTH_SHORT).show()
                        }
                        inSession -> listen.leave()
                        listeners.isEmpty() -> listen.start(conversationId)
                        else -> listen.join(conversationId)
                    }
                }) {
                    Icon(
                        painterResource(icon),
                        contentDescription = label,
                        tint = if (listeners.isNotEmpty()) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                }
            }
            // The group's ⋮ menu is always the rightmost button.
            if (conversation?.isGroup == true) {
                GroupMenu(
                    conversation.title(me), conversation.createdBy == me, conversationId,
                    hasPicture = conversation.picture != null, onGone = nav.back, onShowMembers = { showMembers = true },
                )
            }
        }
        // Who's typing here: anyone heard from in the last few seconds (a clock ticks while someone is).
        val typingHere = social.typing.collectAsStateWithLifecycle().value[conversationId].orEmpty()
        var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(typingHere) {
            clock = System.currentTimeMillis()
            while (typingHere.values.any { System.currentTimeMillis() - it < TYPING_SHOWN_MS }) {
                delay(1_000)
                clock = System.currentTimeMillis()
            }
        }
        val typers = typingHere.filter { (id, at) -> id != me && clock - at < TYPING_SHOWN_MS }.keys
            .mapNotNull { id -> conversation?.members?.firstOrNull { it.id == id }?.displayName }
        val typingText = when {
            typers.isEmpty() -> null
            conversation?.isGroup != true -> "typing…"
            typers.size == 1 -> "${typers[0]} is typing…"
            else -> typers.dropLast(1).joinToString() + " and " + typers.last() + " are typing…"
        }
        val subtitle = when {
            conversation == null -> null
            typingText != null -> typingText
            conversation.isGroup -> conversation.members.joinToString { if (it.id == me) "You" else it.displayName }
            otherFriend?.nowPlaying != null -> "♪ Listening to ${otherFriend.nowPlaying.title}"
            otherFriend?.online == true -> "Online"
            else -> null
        }
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (typingText != null || otherFriend?.nowPlaying != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // In a group this line lists the members, so tapping it opens them too.
                modifier = Modifier.padding(start = 60.dp, end = 16.dp, bottom = 4.dp)
                    .then(if (conversation?.isGroup == true) Modifier.clickable { showMembers = true } else Modifier),
            )
        }
        // Pins that haven't run out (the server drops them too, but the chat may have been open a while).
        val pins = conversation?.pins.orEmpty().filter { it.expiresAt > System.currentTimeMillis() }
        val pinnedIds = pins.map { it.message.id }.toSet()
        PinnedBar(pins, me, onJump = ::jumpTo, onUnpin = ::unpin)
        viewing?.let { ImageViewer(it, if (it.sender.id == me) "You" else it.sender.displayName, onDismiss = { viewing = null }) }
        outgoing?.let { image ->
            SendPictureDialog(
                image,
                replyingTo = replyingTo?.let { if (it.sender.id == me) "yourself" else it.sender.displayName },
                onSend = { caption -> outgoing = null; sendPicture(image, caption) },
                onDismiss = { outgoing = null },
            )
        }


        // "Seen" under your newest message: who has read up to it.
        val lastMine = messages.lastOrNull { it.sender.id == me && !it.system && !it.deleted }
        val seenLabel = lastMine?.let { m ->
            val others = conversation?.members.orEmpty().filter { it.id != me }
            val readers = conversation?.readMarks.orEmpty().filter { it.lastReadId >= m.id }.mapNotNull { mark -> others.firstOrNull { it.id == mark.userId } }
            when {
                readers.isEmpty() -> null
                conversation?.isGroup != true -> "Seen"
                readers.size == others.size -> "Seen by everyone"
                else -> "Seen by " + readers.joinToString { it.displayName }
            }
        }
        deleting?.let { target ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text("Delete for everyone?") },
                text = { Text("It's replaced with \"This message was deleted\" for everyone in the chat.") },
                confirmButton = {
                    TextButton(onClick = {
                        deleting = null
                        scope.launch {
                            runCatching { social.api.deleteMessage(conversationId, target.id) }
                                .onSuccess { add(it); if (editing?.id == target.id) stopEditing() }
                                .onFailure { Toast.makeText(context, it.message ?: "Couldn't delete it", Toast.LENGTH_SHORT).show() }
                        }
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
            )
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
                if (message.system) SystemLine(
                    message.systemText(me),
                    // "… pinned a message" goes to that message.
                    onClick = message.replyTo?.takeIf { !it.hidden }?.let { quote -> { jumpTo(quote.id) } },
                )
                else Bubble(
                    message,
                    mine = message.sender.id == me,
                    showName = showName,
                    // Only the jam's owner answers requests, while the jam is on.
                    canAnswer = message.request == "pending" && message.sender.id != me && jamOwner == me && joinedJam == conversationId,
                    jamOwnerName = conversation?.members?.firstOrNull { it.id == jamOwner }?.displayName,
                    onAnswer = { accept -> answerRequest(message, accept) },
                    me = me,
                    highlighted = highlighted == message.id,
                    onReply = if (conversation?.canMessage == true && !message.deleted) ({ reply(message) }) else null,
                    onQuoteClick = ::jumpTo,
                    pinned = message.id in pinnedIds,
                    onPin = if (conversation?.canMessage == true && !message.deleted) ({ hours -> pin(message.id, hours) }) else null,
                    onReact = if (conversation?.canMessage == true && !message.deleted) ({ emoji -> react(message, emoji) }) else null,
                    onEdit = if (message.isOwnEditable(me) && message.song == null) ({ startEditing(message) }) else null,
                    onDelete = if (message.isOwnEditable(me)) ({ deleting = message }) else null,
                    seenLabel = if (message.id == lastMine?.id) seenLabel else null,
                    onUnpin = { unpin(message.id) },
                    onOpenPicture = { viewing = message },
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
        editing?.let { target ->
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Quote(target.quote(), me, Modifier.weight(1f), heading = "Editing message")
                IconButton(onClick = ::stopEditing) { Icon(Icons.Filled.Close, contentDescription = "Stop editing") }
            }
        }
        replyingTo?.let { target ->
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Quote(target.quote(), me, Modifier.weight(1f), heading = "Replying to ${if (target.sender.id == me) "yourself" else target.sender.displayName}")
                IconButton(onClick = { replyingTo = null }) { Icon(Icons.Filled.Close, contentDescription = "Cancel reply") }
            }
        }
        if (uploading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // A photo or GIF from the phone, or a new photo from the camera.
            var attaching by remember { mutableStateOf(false) }
            Box {
                IconButton(enabled = !uploading, onClick = { attaching = true }) {
                    Icon(painterResource(R.drawable.ic_photo), contentDescription = "Send a photo")
                }
                DropdownMenu(expanded = attaching, onDismissRequest = { attaching = false }) {
                    DropdownMenuItem(text = { Text("Photo or GIF from gallery") }, onClick = {
                        attaching = false
                        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                    DropdownMenuItem(text = { Text("Take a photo") }, onClick = {
                        attaching = false
                        runCatching { camera.launch(photoUri) }.onFailure { Toast.makeText(context, "No camera app found", Toast.LENGTH_SHORT).show() }
                    })
                }
            }
            // Share what's playing or a recently played song, whole or just a part.
            var sharingMusic by remember { mutableStateOf(false) }
            IconButton(enabled = !sending, onClick = { sharingMusic = true }) {
                Icon(painterResource(R.drawable.ic_music_note), contentDescription = "Share a song")
            }
            if (sharingMusic) {
                ShareMusicSheet(
                    conversationId,
                    onSent = { add(it); replyingTo = null; social.refreshConversationsSoon() },
                    onDismiss = { sharingMusic = false },
                    replyTo = replyingTo?.id,
                )
            }
            OutlinedTextField(
                state = draft,
                placeholder = { Text("Message") },
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f).focusRequester(input)
                    // GIFs and stickers from the keyboard (Gboard, Samsung's keyboard…) are sent right away.
                    .contentReceiver { content ->
                        if (!content.hasMediaType(MediaType.Image)) return@contentReceiver content
                        val description = content.clipMetadata.clipDescription
                        val mime = (0 until description.mimeTypeCount).map(description::getMimeType).firstOrNull { it.startsWith("image/") }
                        content.consume { item ->
                            val uri = item.uri ?: return@consume false
                            val image = readKeyboardImage(context, uri, mime)
                            if (image == null) Toast.makeText(context, "Couldn't send that, or it's over 5 MB", Toast.LENGTH_SHORT).show()
                            else sendPicture(image, "")
                            true
                        }
                    },
            )
            val editingNow = editing
            IconButton(
                // A picture's caption can be emptied; other messages need some text.
                enabled = !sending && (draft.text.isNotBlank() || editingNow?.image != null),
                onClick = { if (editingNow != null) saveEdit(editingNow, draft.text.toString().trim()) else send(draft.text.toString().trim()) },
            ) {
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
    me: Long? = null,
    highlighted: Boolean = false,
    onReply: (() -> Unit)? = null,
    onQuoteClick: (Long) -> Unit = {},
    pinned: Boolean = false,
    onPin: ((hours: Int) -> Unit)? = null,
    onUnpin: () -> Unit = {},
    onOpenPicture: () -> Unit = {},
    onReact: ((String?) -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    seenLabel: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    // Swiping a message to the right (past [replyAt]) replies to it, like in WhatsApp.
    val replyAt = with(LocalDensity.current) { 64.dp.toPx() }
    val swipe = remember { Animatable(0f) }
    var menu by remember { mutableStateOf(false) }
    var choosingPin by remember { mutableStateOf(false) }
    if (choosingPin && onPin != null) PinDialog(onPin = { choosingPin = false; onPin(it) }, onDismiss = { choosingPin = false })
    val sticker = message.image?.isSticker == true
    val myReaction = message.reactions.firstOrNull { me in it.userIds }?.emoji
    // Long-press opens the menu when there's something in it.
    val hasMenu = !message.deleted && (onReply != null || onReact != null || message.body.isNotBlank() || pinned || onDelete != null)
    val flash by animateColorAsState(
        if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
        label = "highlight",
    )
    val swipeToReply = if (onReply == null) Modifier else Modifier.pointerInput(message.id) {
        detectHorizontalDragGestures(
            onDragEnd = {
                if (swipe.value >= replyAt) onReply()
                scope.launch { swipe.animateTo(0f) }
            },
            onDragCancel = { scope.launch { swipe.animateTo(0f) } },
        ) { change, amount ->
            val before = swipe.value
            val next = (before + amount).coerceIn(0f, replyAt * 1.4f)
            if (before < replyAt && next >= replyAt) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            change.consume()
            scope.launch { swipe.snapTo(next) }
        }
    }
    Box(Modifier.fillMaxWidth().background(flash, RoundedCornerShape(12.dp)).then(swipeToReply)) {
        // The reply arrow that shows up behind a message as it's swiped.
        Icon(
            painterResource(R.drawable.ic_reply),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(22.dp)
                .alpha((swipe.value / replyAt).coerceIn(0f, 1f)),
        )
        Column(
            Modifier.fillMaxWidth().offset { IntOffset(swipe.value.roundToInt(), 0) },
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            if (showName) {
                Text(
                    message.sender.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp),
                )
            }
            Box {
                Surface(
                    // A sticker stands on its own, without a bubble.
                    color = when {
                        sticker -> Color.Transparent
                        mine -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    shape = RoundedCornerShape(
                        topStart = 18.dp, topEnd = 18.dp,
                        bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp,
                    ),
                    modifier = Modifier.widthIn(max = 300.dp).pointerInput(message.id, hasMenu) {
                        detectTapGestures(onLongPress = { if (hasMenu) menu = true })
                    },
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        message.replyTo?.let { quote ->
                            Quote(
                                quote, me,
                                Modifier.padding(bottom = 6.dp)
                                    .then(if (quote.hidden) Modifier else Modifier.clickable { onQuoteClick(quote.id) }),
                            )
                        }
                        message.image?.let {
                            ChatPicture(
                                message,
                                Modifier.chatPictureSize(message)
                                    .then(if (sticker) Modifier else Modifier.clip(RoundedCornerShape(12.dp)))
                                    .combinedClickable(
                                        onClick = { if (!sticker) onOpenPicture() },
                                        onLongClick = { if (hasMenu) menu = true },
                                    ),
                                contentScale = if (sticker) ContentScale.Fit else ContentScale.Crop,
                            )
                            if (message.body.isNotBlank()) androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                        }
                        if (message.request != null) {
                            Text(
                                (if (mine) "You asked to play" else "${message.sender.displayName} asked to play") +
                                    if (message.requestMode == "now") " now" else " next",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                        message.song?.let {
                            SongCard(
                                it,
                                Modifier.padding(bottom = if (message.body.isNotBlank() || message.request != null) 6.dp else 0.dp),
                                playable = message.request == null,
                            )
                        }
                        if (message.deleted) {
                            Text(
                                if (mine) "You deleted this message" else "This message was deleted",
                                style = MaterialTheme.typography.bodyLarge,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (message.body.isNotBlank()) Text(message.body, style = MaterialTheme.typography.bodyLarge)
                        message.request?.let { status -> SongRequestStatus(status, message.requestMode == "now", canAnswer, jamOwnerName, onAnswer) }
                        Text(
                            (if (message.editedAt != null && !message.deleted) "edited · " else "") + chatTime(message.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                        )
                    }
                }
                // Long-press a message: react, reply, pin, copy, or change or delete your own.
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (onReact != null) {
                        Row(Modifier.padding(horizontal = 8.dp)) {
                            QUICK_REACTIONS.forEach { emoji ->
                                Text(
                                    emoji,
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.clip(RoundedCornerShape(50))
                                        .background(if (emoji == myReaction) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent)
                                        .clickable { menu = false; onReact(if (emoji == myReaction) null else emoji) }
                                        .padding(6.dp),
                                )
                            }
                        }
                    }
                    if (onReply != null) DropdownMenuItem(text = { Text("Reply") }, onClick = { menu = false; onReply() })
                    if (pinned) DropdownMenuItem(text = { Text("Unpin") }, onClick = { menu = false; onUnpin() })
                    else if (onPin != null) DropdownMenuItem(text = { Text("Pin") }, onClick = { menu = false; choosingPin = true })
                    if (message.body.isNotBlank()) {
                        DropdownMenuItem(text = { Text("Copy text") }, onClick = {
                            menu = false
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Message", message.body))
                        })
                    }
                    if (onEdit != null) DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                    if (onDelete != null) {
                        DropdownMenuItem(text = { Text("Delete for everyone", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            // Reactions under the message: tap one to react the same way (or take yours back).
            if (message.reactions.isNotEmpty()) {
                Row(Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    message.reactions.forEach { reaction ->
                        val chosen = me in reaction.userIds
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            border = if (chosen) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp))
                                .then(if (onReact != null) Modifier.clickable { onReact(if (chosen) null else reaction.emoji) } else Modifier),
                        ) {
                            Text(
                                reaction.emoji + if (reaction.userIds.size > 1) " ${reaction.userIds.size}" else "",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
            seenLabel?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, end = 6.dp),
                )
            }
        }
    }
}

/** A quoted message: who wrote it and a line of what it said, with a bar down its side. */
@Composable
private fun Quote(quote: ReplyQuote, me: Long?, modifier: Modifier = Modifier, heading: String? = null) {
    Row(
        modifier
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
            .height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                heading ?: if (quote.sender.id == me) "You" else quote.sender.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                quote.preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A line about the chat itself, like "Alice left the group". */
@Composable
private fun SystemLine(text: String, onClick: (() -> Unit)? = null) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
    )
}

/** The ⋮ menu of a group chat: leave it, or (for its owner) delete it for everyone. */
@Composable
private fun GroupMenu(title: String, isOwner: Boolean, conversationId: Long, hasPicture: Boolean, onGone: () -> Unit, onShowMembers: () -> Unit) {
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
            DropdownMenuItem(text = { Text(if (isOwner) "Members · add or remove" else "Members") }, onClick = { open = false; onShowMembers() })
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
private fun SongRequestStatus(status: String, playNow: Boolean, canAnswer: Boolean, ownerName: String?, onAnswer: (Boolean) -> Unit) {
    if (canAnswer) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onAnswer(true) }) { Text(if (playNow) "Play now" else "Play next") }
            OutlinedButton(onClick = { onAnswer(false) }) { Text("Decline") }
        }
        return
    }
    Text(
        when (status) {
            "accepted" -> if (playNow) "✓ Played" else "✓ Added to the queue"
            "declined" -> "Declined"
            "expired" -> "The jam ended before this was answered"
            else -> "Waiting for ${ownerName ?: "the host"}"
        },
        style = MaterialTheme.typography.labelMedium,
        color = if (status == "accepted") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
