package io.github.devasenan134.isaipetti.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.devasenan134.isaipetti.data.PasswordRules
import io.github.devasenan134.isaipetti.data.SocialSession
import io.github.devasenan134.isaipetti.data.SubsonicApi
import io.github.devasenan134.isaipetti.ui.components.LocalApp
import io.github.devasenan134.isaipetti.ui.components.PasswordStrength
import kotlinx.coroutines.launch

/** The server the app uses unless you change it under "Additional settings" (if you run your own). */
const val DEFAULT_SERVER = "jukebox.craftingtable.cc"

/**
 * Log in, or sign up with an invite code from a friend. The app connects to [DEFAULT_SERVER]; someone running
 * their own server types its address under "Additional settings". Jukebox serves music and friends at one
 * address; a separate friends server can still be given for the older two-server setup.
 */
@Composable
fun LoginScreen() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var signingUp by rememberSaveable { mutableStateOf(false) }
    var server by rememberSaveable { mutableStateOf(DEFAULT_SERVER) }
    var friendsServer by rememberSaveable { mutableStateOf("") }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var inviteCode by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    fun submit() {
        busy = true
        error = null
        scope.launch {
            try {
                // One server does both (Jukebox), unless a separate friends server was given.
                val social = SubsonicApi.normalizeServer(friendsServer.ifBlank { server })
                val credentials = SubsonicApi.credentialsFor(server, username, password).copy(socialServer = social)
                if (signingUp) {
                    // The server makes the account, then we log in.
                    val response = app.social.apiFor(social).signup(inviteCode, username.trim(), password, displayName.trim())
                    app.session.saveSocial(SocialSession(response.sessionToken, response.user))
                }
                app.api.ping(credentials) // checks the server address and password
                app.session.save(credentials) // AppRoot then switches to the main screen
                app.likes.refresh()
                app.myPlaylists.refresh()
            } catch (e: Exception) {
                error = e.message ?: "Couldn't connect"
                busy = false
            }
        }
    }

    // New accounts must pass the password rules; logging in accepts whatever password you already have.
    val passwordCheck = if (signingUp && password.isNotEmpty()) PasswordRules.check(password, username) else null
    val canSubmit = !busy && server.isNotBlank() && username.isNotBlank() && password.isNotEmpty() &&
        (!signingUp || (inviteCode.isNotBlank() && passwordCheck?.problem == null))

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("இசைப்பெட்டி", fontSize = 34.sp, color = MaterialTheme.colorScheme.primary)
            Text(if (signingUp) "Join your friends on Isaipetti" else "Isaipetti", style = MaterialTheme.typography.titleMedium)
            val reason by app.session.logoutReason.collectAsStateWithLifecycle()
            reason?.let { Text(it, color = MaterialTheme.colorScheme.tertiary, textAlign = TextAlign.Center) }

            if (signingUp) {
                OutlinedTextField(
                    value = inviteCode, onValueChange = { inviteCode = it.uppercase() }, label = { Text("Invite code") },
                    placeholder = { Text("ABCD-EFGH") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
            }
            OutlinedTextField(
                value = username, onValueChange = { username = it.trim() }, label = { Text("Username") },
                supportingText = if (signingUp) ({ Text("Letters, numbers, . _ -  (3–24)") }) else null,
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            if (signingUp) {
                OutlinedTextField(
                    value = displayName, onValueChange = { displayName = it }, label = { Text("Your name (shown to friends)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
            }
            OutlinedTextField(
                value = password, onValueChange = { password = it }, label = { Text("Password") },
                supportingText = if (signingUp && password.isEmpty()) ({ Text("At least 10 characters. A few words work well") }) else null,
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            passwordCheck?.let { PasswordStrength(it) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = ::submit, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
                if (busy) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
                else Text(if (signingUp) "Create account" else "Log in")
            }
            TextButton(onClick = {
                signingUp = !signingUp
                error = null
            }) {
                Text(if (signingUp) "Already have an account? Log in" else "Got an invite code? Sign up")
            }

            // Where the app connects. Only someone running their own server needs to change it.
            TextButton(onClick = { showSettings = !showSettings }) {
                Text("Additional settings")
                Icon(
                    if (showSettings) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (showSettings) "Hide" else "Show",
                )
            }
            if (!showSettings) {
                Text(
                    "Server: ${server.ifBlank { DEFAULT_SERVER }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedTextField(
                    value = server, onValueChange = { server = it.trim() }, label = { Text("Server") },
                    placeholder = { Text(DEFAULT_SERVER) },
                    supportingText = { Text("Running your own Jukebox? Type its address") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = friendsServer, onValueChange = { friendsServer = it.trim() },
                    label = { Text("Friends server (optional)") },
                    placeholder = { Text("Same as the server") },
                    supportingText = { Text("Only if friends and chat run at a different address") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                if (server != DEFAULT_SERVER || friendsServer.isNotEmpty()) {
                    TextButton(onClick = { server = DEFAULT_SERVER; friendsServer = "" }) { Text("Use $DEFAULT_SERVER") }
                }
            }
        }
    }
}
