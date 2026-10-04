package io.github.devasenan134.isaipetti.ui.social

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.devasenan134.isaipetti.IsaipettiApp
import io.github.devasenan134.isaipetti.R
import io.github.devasenan134.isaipetti.data.Invite
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Most unused invites anyone can have at once (the friends server checks this too). */
private const val MAX_INVITES = 5

/**
 * Your invite codes: copy or share an unused one again, delete it, or make a new one.
 * Used and expired codes from the last 30 days are listed below, with who joined.
 */
@Composable
fun InvitesDialog(onDismiss: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var invites by remember { mutableStateOf<List<Invite>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // The code just made, shown first and highlighted.
    var fresh by remember { mutableStateOf<String?>(null) }
    // Asks once more before deleting.
    var deleting by remember { mutableStateOf<Invite?>(null) }

    suspend fun load() {
        runCatching { app.social.api.invites() }
            .onSuccess { invites = it; error = null }
            .onFailure { error = it.message ?: "Couldn't load your invites" }
    }
    LaunchedEffect(Unit) { load() }

    val now = System.currentTimeMillis()
    val active = invites.orEmpty().filter { it.usedBy == null && it.expiresAt > now }
    val past = invites.orEmpty().filter { it !in active }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your invites") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Each code works once, for 7 days. Whoever signs up with it becomes your friend. " +
                        "You can have $MAX_INVITES unused codes at a time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
                    invites == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else -> {
                        Text(
                            "${MAX_INVITES - active.size} of $MAX_INVITES left",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                        if (active.isEmpty()) {
                            Text("No unused codes. Tap New invite to make one.", style = MaterialTheme.typography.bodyMedium)
                        }
                        active.sortedByDescending { it.code == fresh }.forEach { invite ->
                            ActiveInvite(
                                invite,
                                highlighted = invite.code == fresh,
                                now = now,
                                onCopy = { copyCode(context, invite.code) },
                                onShare = { shareInvite(app, context, invite.code) },
                                onDelete = { deleting = invite },
                            )
                        }
                        if (past.isNotEmpty()) {
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            past.forEach { PastInvite(it) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = invites != null && active.size < MAX_INVITES && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching { app.social.api.createInvite() }
                            .onSuccess { fresh = it.code; load() }
                            .onFailure { Toast.makeText(context, it.message ?: "Couldn't make an invite", Toast.LENGTH_SHORT).show() }
                        busy = false
                    }
                },
            ) { Text("New invite") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )

    deleting?.let { invite ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${invite.code}?") },
            text = { Text("The code stops working. If you've sent it to someone, they won't be able to sign up with it.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        runCatching { app.social.api.deleteInvite(invite.code) }
                            .onSuccess { load(); Toast.makeText(context, "Invite deleted", Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(context, it.message ?: "Couldn't delete it", Toast.LENGTH_SHORT).show(); load() }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun ActiveInvite(invite: Invite, highlighted: Boolean, now: Long, onCopy: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                invite.code,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = if (highlighted) 22.sp else 18.sp,
                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                (if (highlighted) "New · " else "") + expiresIn(invite.expiresAt - now),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            IconButton(onClick = onCopy) { Icon(painterResource(R.drawable.ic_copy), contentDescription = "Copy code", Modifier.size(20.dp)) }
            IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = "Share invite", Modifier.size(20.dp)) }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete invite", Modifier.size(20.dp)) }
        }
    }
}

@Composable
private fun PastInvite(invite: Invite) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            invite.code,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            invite.usedBy?.let { "Joined: ${it.displayName}" } ?: "Expired",
            style = MaterialTheme.typography.bodySmall,
            color = if (invite.usedBy != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Expires in 6 days", "Expires in 5 hours", "Expires in a few minutes". */
private fun expiresIn(millis: Long): String {
    val days = TimeUnit.MILLISECONDS.toDays(millis)
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    return "Expires in " + when {
        days >= 1 -> if (days == 1L) "1 day" else "$days days"
        hours >= 1 -> if (hours == 1L) "1 hour" else "$hours hours"
        else -> "a few minutes"
    }
}

private fun copyCode(context: Context, code: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Isaipetti invite code", code))
    Toast.makeText(context, "Copied $code", Toast.LENGTH_SHORT).show()
}

/** Shares the code with what to type, since the app has no servers built in. */
private fun shareInvite(app: IsaipettiApp, context: Context, code: String) {
    val creds = app.session.credentials.value
    val text = "Join me on Isaipetti! Install the app, tap \"Got an invite code? Sign up\" and enter:\n" +
        "Music server: ${creds?.server.orEmpty().removePrefix("https://")}\n" +
        "Friends server: ${creds?.socialServer.orEmpty().removePrefix("https://")}\n" +
        "Invite code: $code"
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share invite"))
}
