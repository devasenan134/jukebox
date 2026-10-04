package io.github.devasenan134.isaipetti.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

private val Context.sessionDataStore by preferencesDataStore(name = "session")

/**
 * Login details for the Navidrome server.
 *
 * The password itself is never stored. Subsonic accepts `token = md5(password + salt)`,
 * so we keep only the salt and token.
 */
data class Credentials(
    val server: String,
    val username: String,
    val salt: String,
    val token: String,
    /** The companion server for friends, chat and listen together; null if you didn't enter one. */
    val socialServer: String? = null,
)

/** Logged in to the friends server as [user]. */
data class SocialSession(val token: String, val user: SocialUser)

class SessionStore(private val context: Context) {
    private val server = stringPreferencesKey("server")
    private val username = stringPreferencesKey("username")
    private val salt = stringPreferencesKey("salt")
    private val token = stringPreferencesKey("token")
    private val socialServer = stringPreferencesKey("social_server")
    private val socialToken = stringPreferencesKey("social_token")
    private val socialUser = stringPreferencesKey("social_user")

    private val _credentials = MutableStateFlow<Credentials?>(null)

    /** The logged-in account, or null when logged out. The UI watches this to pick login vs. main screen. */
    val credentials: StateFlow<Credentials?> = _credentials

    private val _social = MutableStateFlow<SocialSession?>(null)

    /** The login for the friends server, or null if not connected (yet). Separate from Navidrome on purpose:
     *  music keeps working even when the friends server is down. */
    val social: StateFlow<SocialSession?> = _social

    /** Reads the saved login once at app start. It's a tiny file, so blocking briefly is fine. */
    fun load() {
        val prefs = runBlocking { context.sessionDataStore.data.first() }
        _social.value = prefs[socialToken]?.let { t ->
            prefs[socialUser]?.let { runCatching { Json.decodeFromString<SocialUser>(it) }.getOrNull() }?.let { SocialSession(t, it) }
        }
        _credentials.value = Credentials(
            server = prefs[server] ?: return,
            username = prefs[username] ?: return,
            salt = prefs[salt] ?: return,
            token = prefs[token] ?: return,
            socialServer = prefs[socialServer],
        )
    }

    suspend fun save(credentials: Credentials) {
        _logoutReason.value = null
        context.sessionDataStore.edit {
            it[server] = credentials.server
            it[username] = credentials.username
            it[salt] = credentials.salt
            it[token] = credentials.token
            credentials.socialServer?.let { url -> it[socialServer] = url } ?: it.remove(socialServer)
        }
        _credentials.value = credentials
    }

    suspend fun saveSocial(session: SocialSession?) {
        context.sessionDataStore.edit {
            if (session == null) {
                it.remove(socialToken)
                it.remove(socialUser)
            } else {
                it[socialToken] = session.token
                it[socialUser] = Json.encodeToString(SocialUser.serializer(), session.user)
            }
        }
        _social.value = session
    }

    private val _logoutReason = MutableStateFlow<String?>(null)

    /** Why the app logged out by itself, shown on the login screen. */
    val logoutReason: StateFlow<String?> = _logoutReason

    suspend fun clear(reason: String? = null) {
        context.sessionDataStore.edit { it.clear() }
        _logoutReason.value = reason
        _social.value = null
        _credentials.value = null
    }
}
