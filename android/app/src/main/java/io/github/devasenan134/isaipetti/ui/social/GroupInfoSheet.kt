package io.github.devasenan134.isaipetti.ui.social

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.devasenan134.isaipetti.R
import io.github.devasenan134.isaipetti.data.Conversation
import io.github.devasenan134.isaipetti.data.SocialUser
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A group's members, sorted into who's in the jam, who's online and who's offline. The group's
 * owner can also rename the group, add friends and remove people here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoSheet(conversation: Conversation, onDismiss: () -> Unit) {
    val social = LocalApp.current.social
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = social.me?.id
    val isOwner = conversation.createdBy == me
    val friends by social.friends.collectAsStateWithLifecycle()
    val sessions by social.listen.sessions.collectAsStateWithLifecycle()
    val owners by social.listen.owners.collectAsStateWithLifecycle()
    val jam = sessions[conversation.id].orEmpty().toSet()
    val jamHost = owners[conversation.id]

    // Who's online: asked of the server now and every 15 seconds while this is open (it knows about
    // members who aren't your friends too), plus friends' live presence in between.
    var onlineIds by remember { mutableStateOf(emptySet<Long>()) }
    LaunchedEffect(conversation.id) {
        while (true) {
            runCatching { social.api.onlineMembers(conversation.id) }.onSuccess { onlineIds = it.toSet() }
            delay(15_000)
        }
    }
    val friendsOnline = friends.filter { it.online }.map { it.user.id }.toSet()
    fun online(id: Long) = id == me || id in onlineIds || id in friendsOnline

    var renaming by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<SocialUser?>(null) }

    fun change(action: suspend () -> Unit, done: String) {
        scope.launch {
            runCatching { action() }
                .onSuccess { social.refreshConversationsSoon(); Toast.makeText(context, done, Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, it.message ?: "Couldn't change the group", Toast.LENGTH_SHORT).show() }
        }
    }

    // Everyone once: the jam first, then online, then offline; you first in your section, then by name.
    val members = conversation.members.sortedWith(compareBy<SocialUser> { it.id != me }.thenBy { it.displayName.lowercase() })
    val inJam = members.filter { it.id in jam }.sortedBy { it.id != jamHost }
    val onlineNow = members.filter { it.id !in jam && online(it.id) }
    val offline = members.filter { it.id !in jam && !online(it.id) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn {
            item {
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    GroupAvatar("c${conversation.id}", size = 96.dp, conversation = conversation)
                    Row(Modifier.padding(top = 12.dp, start = 16.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            conversation.name.orEmpty(),
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isOwner) {
                            IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, contentDescription = "Rename the group") }
                        }
                    }
                    Text(
                        "Group · ${conversation.members.size} members",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (isOwner) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { adding = true }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        Text("Add people", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
            listOf("In the jam" to inJam, "Online" to onlineNow, "Offline" to offline).forEach { (title, people) ->
                if (people.isEmpty()) return@forEach
                item(key = title) {
                    Text(
                        "$title · ${people.size}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(people, key = { "$title-${it.id}" }) { user ->
                    MemberRow(
                        user = user,
                        isMe = user.id == me,
                        online = online(user.id),
                        note = listOfNotNull(
                            if (user.id == jamHost) "Hosting the jam" else null,
                            if (user.id == conversation.createdBy) "Group owner" else null,
                        ).joinToString(" · "),
                        hostingJam = user.id == jamHost,
                        onRemove = if (isOwner && user.id != me) ({ removing = user }) else null,
                    )
                }
            }
        }
    }

    if (renaming) {
        RenameGroupDialog(conversation.name.orEmpty(), onDismiss = { renaming = false }) { name ->
            renaming = false
            change({ social.api.renameGroup(conversation.id, name) }, "Group renamed")
        }
    }
    if (adding) {
        val candidates = friends.map { it.user }.filter { f -> conversation.members.none { it.id == f.id } }
        AddPeopleDialog(candidates, onDismiss = { adding = false }) { picked ->
            adding = false
            change({ social.api.addMembers(conversation.id, picked) }, if (picked.size == 1) "Added to the group" else "Added ${picked.size} people")
        }
    }
    removing?.let { user ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${user.displayName}?") },
            text = { Text("They'll leave ${conversation.name.orEmpty()} and the chat will disappear for them.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    change({ social.api.removeMember(conversation.id, user.id) }, "${user.displayName} was removed")
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MemberRow(user: SocialUser, isMe: Boolean, online: Boolean, note: String, hostingJam: Boolean, onRemove: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(user.displayName, user.username, online = online, user = user)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(if (isMe) "You" else user.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note.isNotEmpty()) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        if (hostingJam) {
            Icon(
                painterResource(R.drawable.ic_jam_dj),
                contentDescription = "Hosting the jam",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp).size(20.dp),
            )
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove ${user.displayName}") }
        }
    }
}

/** A new name for the group (up to 50 characters, like when it was made). */
@Composable
private fun RenameGroupDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename group") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(50) },
                label = { Text("Group name") },
                supportingText = { Text("${name.length}/50") },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(onClick = { onRename(trimmed) }, enabled = trimmed.isNotEmpty() && trimmed != current) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pick friends to add to the group. */
@Composable
private fun AddPeopleDialog(candidates: List<SocialUser>, onDismiss: () -> Unit, onAdd: (List<Long>) -> Unit) {
    var picked by remember { mutableStateOf(emptySet<Long>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add people") },
        text = {
            if (candidates.isEmpty()) {
                Text("All your friends are already in this group.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(candidates, key = { it.id }) { friend ->
                        val checked = friend.id in picked
                        Row(
                            Modifier.fillMaxWidth().clickable { picked = if (checked) picked - friend.id else picked + friend.id }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(friend.displayName, friend.username, size = 36.dp, user = friend)
                            Text(friend.displayName, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Checkbox(checked = checked, onCheckedChange = { picked = if (it) picked + friend.id else picked - friend.id })
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(picked.toList()) }, enabled = picked.isNotEmpty()) {
                Text(if (picked.size > 1) "Add ${picked.size}" else "Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
