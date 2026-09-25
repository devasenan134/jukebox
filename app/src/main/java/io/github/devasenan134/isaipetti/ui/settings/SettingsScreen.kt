package io.github.devasenan134.isaipetti.ui.settings

import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import io.github.devasenan134.isaipetti.data.Appearance
import io.github.devasenan134.isaipetti.data.ThemeMode
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import io.github.devasenan134.isaipetti.R
import io.github.devasenan134.isaipetti.ui.components.rememberPhotoPicker
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.devasenan134.isaipetti.BuildConfig
import io.github.devasenan134.isaipetti.lockscreen.LockScreenLyrics
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.devasenan134.isaipetti.data.PasswordRules
import io.github.devasenan134.isaipetti.data.SocialSession
import io.github.devasenan134.isaipetti.data.SubsonicApi
import io.github.devasenan134.isaipetti.ui.Nav
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.PasswordStrength
import io.github.devasenan134.isaipetti.ui.components.ScreenHeader
import io.github.devasenan134.isaipetti.ui.social.Avatar
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(nav: Nav) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val credentials by app.session.credentials.collectAsStateWithLifecycle()
    val social by app.session.social.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Profile picture: kept on the friends server, so friends see it too.
    suspend fun savePicture(change: suspend () -> io.github.devasenan134.isaipetti.data.SocialUser, done: String) {
        runCatching { change() }
            .onSuccess { user ->
                app.session.social.value?.let { app.session.saveSocial(io.github.devasenan134.isaipetti.data.SocialSession(it.token, user)) }
                Toast.makeText(context, done, Toast.LENGTH_SHORT).show()
            }
            .onFailure { Toast.makeText(context, it.message ?: "Couldn't change your picture", Toast.LENGTH_SHORT).show() }
    }
    val picker = rememberPhotoPicker(
        title = "Profile picture",
        round = true,
        onRemove = if (social?.user?.avatar != null) ({ scope.launch { savePicture({ app.social.api.removeAvatar() }, "Picture removed") } }) else null,
    ) { jpeg -> savePicture({ app.social.api.setAvatar(jpeg) }, "Profile picture updated") }
    val username = credentials?.username.orEmpty()
    val displayName = social?.user?.displayName ?: username

    Column(Modifier.imePadding()) {
        ScreenHeader("Settings", onBack = nav.back)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Profile
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Tap the picture to change it (needs the friends server, where it's kept).
                    Box(Modifier.clip(CircleShape).clickable(enabled = social != null) { picker.open() }) {
                        Avatar(displayName, username, size = 72.dp, user = social?.user)
                        if (social != null) {
                            Icon(
                                painterResource(R.drawable.ic_camera), contentDescription = "Change profile picture",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.align(Alignment.BottomEnd).size(24.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape).padding(4.dp),
                            )
                        }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text(displayName, style = MaterialTheme.typography.titleLarge)
                        Text("@$username", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "Music: " + credentials?.server?.removePrefix("https://").orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Friends: " + (credentials?.socialServer?.removePrefix("https://") ?: "not set"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { renaming = true }, enabled = social != null) { Text("Edit") }
                }
            }

            if (social != null) ListeningStatsCard(onOpen = nav.openStats)

            AppearanceCard()

            LockScreenCard()

            ChangePasswordCard()

            UpdatesCard()

            FeedbackCard(enabled = social != null)

            OutlinedButton(
                onClick = { confirmLogout = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Log out") }

            Text(
                "Isaipetti ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 24.dp),
            )
        }
    }

    if (renaming) {
        RenameDialog(current = displayName, onDismiss = { renaming = false }, onSaved = { renaming = false })
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out?") },
            text = { Text("Music stops and friends will see you offline.") },
            confirmButton = {
                Button(onClick = {
                    confirmLogout = false
                    scope.launch {
                        app.player.stop()
                        app.recent.clear()
                        app.recentPlaylists.clear()
                        app.queueMemory.clear()
                        app.myPlaylists.clear()
                        app.activity.clear()
                        app.searches.clearAll()
                        app.likes.clear()
                        app.mixes.clear()
                        app.social.logout()
                        app.session.clear()
                    }
                }) { Text("Log out") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}

/**
 * The new password goes straight from the phone to Navidrome over HTTPS. The friends server
 * never sees it, and nothing stores it: the app keeps only the salted token, as at login.
 */
@Composable
private fun ChangePasswordCard() {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val username = app.session.credentials.collectAsStateWithLifecycle().value?.username.orEmpty()
    val check = if (new.isNotEmpty()) PasswordRules.check(new, username) else null
    val problem = when {
        new.isNotEmpty() && new == current -> "The new password is the same as the current one"
        confirm.isNotEmpty() && confirm != new -> "The new passwords don't match"
        else -> null
    }
    val canSave = !busy && current.isNotEmpty() && check?.problem == null && new.isNotEmpty() && confirm == new && problem == null

    fun save() {
        busy = true
        error = null
        scope.launch {
            try {
                val old = app.session.credentials.value ?: throw IllegalStateException("Not logged in")
                app.api.changePassword(current, new)
                // Switch this phone to the new password right away (as a fresh salted token).
                val updated = SubsonicApi.credentialsFor(old.server, old.username, new)
                app.session.save(updated)
                // Sign out other phones from the friends server; Navidrome already rejects their old login.
                runCatching { app.social.api.logoutOthers() }
                current = ""
                new = ""
                confirm = ""
                Toast.makeText(context, "Password changed. Other devices will need to log in again.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                error = e.message ?: "Couldn't change the password"
            }
            busy = false
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Change password", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            PasswordField("Current password", current) { current = it }
            PasswordField("New password", new) { new = it }
            check?.let { PasswordStrength(it) }
            PasswordField("Repeat new password", confirm) { confirm = it }
            (error ?: problem)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = ::save, enabled = canSave, modifier = Modifier.fillMaxWidth()) {
                if (busy) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp) else Text("Update password")
            }
        }
    }
}

/** Light, dark or automatic, and optionally the wallpaper's colours (Android 12+). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceCard() {
    val appearance = LocalApp.current.appearance
    val mode by appearance.mode.collectAsStateWithLifecycle()
    val wallpaper by appearance.wallpaper.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { i, option ->
                    SegmentedButton(
                        selected = mode == option,
                        onClick = { appearance.setMode(option) },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                    ) { Text(option.label) }
                }
            }
            Text(
                "Auto follows your phone's dark mode setting.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use wallpaper colours", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (Appearance.wallpaperSupported) "Colours from your wallpaper instead of Graphite & mango"
                        else "Needs Android 12 or later",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = wallpaper && Appearance.wallpaperSupported,
                    onCheckedChange = appearance::setWallpaper,
                    enabled = Appearance.wallpaperSupported,
                )
            }
        }
    }
}

/**
 * Lyrics over the lock screen, and keeping the screen on while they show. Android has to allow the
 * app's "full-screen" notifications for it (Android 14 lets people turn that off); if it doesn't,
 * this says so and opens the right settings page.
 */
@Composable
private fun LockScreenCard() {
    val settings = LocalApp.current.lockScreen
    val context = LocalContext.current
    val lyrics by settings.lyrics.collectAsStateWithLifecycle()
    val keepOn by settings.keepScreenOn.collectAsStateWithLifecycle()
    // Checked again whenever you come back to Settings (e.g. from Android's settings).
    var allowed by remember { mutableStateOf(LockScreenLyrics.canShow(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = LockScreenLyrics.canShow(context) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Lock screen", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            SettingSwitch(
                "Lyrics on the lock screen",
                "Lock your phone while music plays, and the lyrics show when you turn the screen on, " +
                    "on the song's colours. Swipe up to get to the normal lock screen.",
                checked = lyrics,
                onChange = settings::setLyrics,
            )
            SettingSwitch(
                "Keep the screen on",
                "While the lyrics show and the music plays.",
                checked = keepOn,
                onChange = settings::setKeepScreenOn,
                enabled = lyrics,
            )
            if (lyrics && !allowed) {
                Text(
                    "Android needs to allow Isaipetti's notifications, including full-screen ones, to show the lyrics over the lock screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = {
                    val notificationsOn = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
                    val intent = if (!notificationsOn || Build.VERSION.SDK_INT < 34) {
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    } else {
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:${context.packageName}".toUri())
                    }
                    runCatching { context.startActivity(intent) }
                }) { Text("Allow in Android settings") }
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(current) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your name") },
        text = {
            Column {
                Text("This is what friends see in chats and their friends list.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, modifier = Modifier.padding(top = 8.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank(), onClick = {
                scope.launch {
                    try {
                        val user = app.social.api.rename(name.trim())
                        app.session.social.value?.let { app.session.saveSocial(SocialSession(it.token, user)) }
                        onSaved()
                    } catch (e: Exception) {
                        error = e.message
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Only shown to Navidrome admins: the server says whether you are one. */
@Composable
private fun ListeningStatsCard(onOpen: () -> Unit) {
    val app = LocalApp.current
    val isAdmin by produceState(false) { value = runCatching { app.social.api.adminAccess().isAdmin }.getOrDefault(false) }
    if (!isAdmin) return
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp)) {
            Text("Listening stats", style = MaterialTheme.typography.titleMedium)
            Text(
                "How much everyone listens, and what. Only admins see this.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
