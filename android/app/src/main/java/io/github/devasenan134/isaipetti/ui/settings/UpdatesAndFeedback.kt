package io.github.devasenan134.isaipetti.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.devasenan134.isaipetti.BuildConfig
import io.github.devasenan134.isaipetti.data.AppUpdate
import io.github.devasenan134.isaipetti.data.BugReport
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.releaseNotesText
import io.github.devasenan134.isaipetti.ui.components.ExpandableText
import androidx.compose.runtime.produceState
import kotlinx.coroutines.launch

/** Settings card: this version, and checking for / installing a newer one. */
@Composable
fun UpdatesCard() {
    val updates = LocalApp.current.updates
    val scope = rememberCoroutineScope()
    val available by updates.available.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var showing by remember { mutableStateOf<AppUpdate?>(null) }
    // Patch notes: the new version's when there is one, otherwise the version you're running.
    val notesVersion = available?.version ?: updates.currentVersion
    val notes by produceState<String?>(null, notesVersion, available) {
        value = available?.notes?.takeIf { it.isNotBlank() } ?: updates.notesFor(notesVersion)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("App version", style = MaterialTheme.typography.titleMedium)
            Text(
                available?.let { "You have ${updates.currentVersion}. Version ${it.version} is available." }
                    ?: status ?: "You have ${updates.currentVersion}.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            notes?.let {
                Text(
                    "What's new in $notesVersion",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                ExpandableText(releaseNotesText(it))
            }
            if (available != null) {
                Button(onClick = { showing = available }, modifier = Modifier.fillMaxWidth()) { Text("Update to ${available!!.version}") }
            } else {
                OutlinedButton(
                    enabled = !checking,
                    onClick = {
                        checking = true
                        scope.launch {
                            status = runCatching { updates.check() }.fold(
                                onSuccess = { if (it == null) "You have the latest version (${updates.currentVersion})." else null },
                                onFailure = { it.message ?: "Couldn't check for updates" },
                            )
                            checking = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (checking) "Checking…" else "Check for updates") }
            }
        }
    }
    showing?.let { UpdateDialog(it, onDismiss = { showing = null }) }
}

/**
 * "Version x is available": shows the patch notes, then downloads the new version and opens
 * Android's installer. The first time, Android asks to allow Isaipetti to install apps.
 */
@Composable
fun UpdateDialog(update: AppUpdate, onDismiss: () -> Unit) {
    val updates = LocalApp.current.updates
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var needsPermission by remember { mutableStateOf(false) }

    fun start() {
        if (!updates.canInstall()) {
            needsPermission = true
            return
        }
        needsPermission = false
        error = null
        progress = 0f
        scope.launch {
            try {
                val apk = updates.download(update) { progress = it }
                updates.install(apk)
                onDismiss()
            } catch (e: Exception) {
                error = e.message ?: "Download failed"
                progress = null
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (progress == null) onDismiss() },
        title = { Text("Isaipetti ${update.version} is available") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    needsPermission -> Text(
                        "To install updates, allow Isaipetti to install apps. Turn on \"Allow from this source\", " +
                            "come back, and tap Update again.",
                    )
                    progress != null -> {
                        Text("Downloading…")
                        LinearProgressIndicator(progress = { progress!! }, modifier = Modifier.fillMaxWidth())
                    }
                    else -> Text(
                        releaseNotesText(update.notes.ifBlank { "A new version is ready." }),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            when {
                needsPermission -> Button(onClick = { updates.openInstallPermission(); needsPermission = false }) { Text("Allow") }
                progress == null -> Button(onClick = ::start) { Text("Update") }
            }
        },
        dismissButton = {
            if (progress == null) {
                TextButton(onClick = {
                    updates.dismiss(update)
                    onDismiss()
                }) { Text("Later") }
            }
        },
    )
}

/** The two kinds of feedback, each with its own wording. */
private enum class Feedback(
    val kind: String,
    val button: String,
    val titleLabel: String,
    val descriptionLabel: String,
    val includeDeviceByDefault: Boolean,
) {
    Bug("bug", "Report a bug", "What's wrong, in a few words", "What happened, and what did you expect?", true),
    Feature("feature", "Suggest a feature", "Your idea, in a few words", "What would you like the app to do, and why?", false),
}

/** Settings card for bug reports and feature requests. */
@Composable
fun FeedbackCard(enabled: Boolean) {
    var open by rememberSaveable { mutableStateOf<Feedback?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Feedback", style = MaterialTheme.typography.titleMedium)
            Text(
                if (enabled) "Found a bug or have an idea? It's posted as an issue on the app's GitHub page."
                else "Feedback needs the friends server, which isn't connected right now.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Feedback.entries.forEach { type ->
                    OutlinedButton(onClick = { open = type }, enabled = enabled, modifier = Modifier.weight(1f)) { Text(type.button) }
                }
            }
        }
    }
    open?.let { FeedbackDialog(it, onDismiss = { open = null }) }
}

@Composable
private fun FeedbackDialog(type: Feedback, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var includeDevice by rememberSaveable { mutableStateOf(type.includeDeviceByDefault) }
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf<BugReport?>(null) }
    val deviceInfo = "Isaipetti ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
        "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

    sent?.let { issue ->
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Thanks!") },
            text = { Text("It's issue #${issue.number} on GitHub.") },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(issue.url)))
                    onDismiss()
                }) { Text("Open it") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        )
        return
    }

    val canSend = title.trim().length >= 3 && description.trim().length >= 10 && !sending
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(type.button) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(120) },
                    label = { Text(type.titleLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(5000) },
                    label = { Text(type.descriptionLabel) },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth().clickable { includeDevice = !includeDevice }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeDevice, onCheckedChange = { includeDevice = it })
                    Column {
                        Text("Include app and phone details")
                        Text(deviceInfo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    "This is posted publicly on GitHub. Your name isn't shown, but don't include passwords or anything private.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(enabled = canSend, onClick = {
                sending = true
                scope.launch {
                    try {
                        sent = app.social.api.sendFeedback(type.kind, title.trim(), description.trim(), deviceInfo.takeIf { includeDevice })
                    } catch (e: Exception) {
                        Toast.makeText(context, e.message ?: "Couldn't send it", Toast.LENGTH_LONG).show()
                    }
                    sending = false
                }
            }) { Text(if (sending) "Sending…" else "Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !sending) { Text("Cancel") } },
    )
}
