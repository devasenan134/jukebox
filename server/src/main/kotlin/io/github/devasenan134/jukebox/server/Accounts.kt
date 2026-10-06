package io.github.devasenan134.jukebox.server

import io.ktor.http.HttpStatusCode
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Logins, sign-ups and invite codes.
 *
 * Passwords are checked by [SignIn]; the app then gets a session token for this server. Accounts are made
 * with an invite code, or by whoever runs the server (`jukebox user add`, see [UserCommand]).
 */
class Accounts(
    private val db: Db,
    private val signIn: SignIn,
    private val onFriendsAdded: suspend (Long, Long) -> Unit,
) {
    private val random = SecureRandom()

    suspend fun login(request: LoginRequest): SessionResponse {
        val username = request.username.trim()
        if (!signIn.checkToken(username, request.salt, request.token)) {
            throw ApiError(HttpStatusCode.Unauthorized, "Wrong username or password")
        }
        return db.tx {
            val user = queryOne("SELECT * FROM users WHERE username = ? AND deleted_at IS NULL", username) { it.toUser() }
                ?: throw ApiError(HttpStatusCode.Unauthorized, "Wrong username or password")
            SessionResponse(newSession(user.id), user)
        }
    }

    suspend fun signup(request: SignupRequest): SessionResponse {
        val username = request.username.trim()
        val displayName = request.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: username
        if (!USERNAME.matches(username)) {
            throw ApiError(HttpStatusCode.BadRequest, "Usernames are 3–24 letters, numbers, dots, dashes or underscores")
        }
        PasswordRules.check(request.password, username).problem?.let { throw ApiError(HttpStatusCode.BadRequest, it) }
        if (displayName.length > 40) throw ApiError(HttpStatusCode.BadRequest, "Display name is too long")

        val code = normalizeCode(request.inviteCode)
        val inviterId = db.tx { validInviteCreator(code) }
            ?: throw ApiError(HttpStatusCode.BadRequest, "That invite code isn't valid or has expired")

        val session = db.tx {
            if (queryOne("SELECT 1 FROM users WHERE username = ?", username) { 1 } != null) {
                throw ApiError(HttpStatusCode.Conflict, "That username is taken")
            }
            // Someone may have used the same code in the meantime; the code is still consumed only once.
            val user = createUser(username, displayName)
            val claimed = update("UPDATE invites SET used_by = ?, used_at = ? WHERE code = ? AND used_by IS NULL", user.id, now(), code)
            if (claimed == 0) throw ApiError(HttpStatusCode.BadRequest, "That invite code was just used by someone else")
            // You become friends with whoever invited you.
            val t = now()
            update("INSERT OR IGNORE INTO friendships VALUES (?, ?, ?)", user.id, inviterId, t)
            update("INSERT OR IGNORE INTO friendships VALUES (?, ?, ?)", inviterId, user.id, t)
            SessionResponse(newSession(user.id), user)
        }
        signIn.setPassword(session.user.id, request.password)
        onFriendsAdded(session.user.id, inviterId)
        return session
    }

    /** Changes your password after checking the current one; your other devices are signed out. */
    suspend fun changePassword(user: UserDto, current: String, new: String, currentToken: String) {
        if (!signIn.checkPassword(user.username, current)) throw ApiError(HttpStatusCode.Forbidden, "Your current password is wrong")
        PasswordRules.check(new, user.username).problem?.let { throw ApiError(HttpStatusCode.BadRequest, it) }
        signIn.setPassword(user.id, new)
        logoutOthers(user.id, currentToken)
    }

    // ---------- Navidrome's own sign-in and account calls ----------
    // App versions up to 0.12.1 change a password the way Navidrome wanted: sign in with the password
    // (/auth/login), read the account (/api/user/{id}), save it with the new one. Jukebox answers the same calls
    // so those apps keep working; newer ones use POST /me/password. Remove these once every phone has 0.12.2 or later.
    // The token is only good for these calls, for ten minutes.

    private val navidromeTokens = ConcurrentHashMap<String, Pair<Long, Long>>()

    suspend fun navidromeLogin(username: String, password: String): JsonObject {
        val name = username.trim()
        if (!signIn.checkPassword(name, password)) throw ApiError(HttpStatusCode.Unauthorized, "Wrong username or password")
        val userId = signIn.userId(name)
        val user = db.tx { queryOne("SELECT * FROM users WHERE id = ?", userId) { it.toUser() } }!!
        val token = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        navidromeTokens.entries.removeIf { it.value.second < now() }
        navidromeTokens[token] = user.id to now() + TimeUnit.MINUTES.toMillis(10)
        return buildJsonObject {
            put("id", user.id.toString()); put("token", token); put("name", user.displayName); put("username", user.username); put("isAdmin", isAdmin(user.id))
        }
    }

    /** The account a Navidrome-style token stands for, if it's [id]'s and still good. */
    private suspend fun navidromeUser(authorization: String?, id: String): UserDto {
        val token = authorization?.removePrefix("Bearer ")?.trim().orEmpty()
        val (userId, expires) = navidromeTokens[token] ?: throw ApiError(HttpStatusCode.Unauthorized, "Not logged in")
        if (expires < now()) throw ApiError(HttpStatusCode.Unauthorized, "Not logged in")
        if (id != userId.toString()) throw ApiError(HttpStatusCode.Forbidden, "That's someone else's account")
        return db.tx { queryOne("SELECT * FROM users WHERE id = ?", userId) { it.toUser() } } ?: throw ApiError(HttpStatusCode.NotFound, "No such account")
    }

    suspend fun navidromeAccount(authorization: String?, id: String): JsonObject {
        val user = navidromeUser(authorization, id)
        val email = db.tx { queryOne("SELECT email FROM users WHERE id = ?", user.id) { it.getString(1) } }
        return buildJsonObject {
            put("id", user.id.toString()); put("userName", user.username); put("name", user.displayName); put("email", email.orEmpty()); put("isAdmin", isAdmin(user.id))
        }
    }

    /** Saves a new password ([body] has currentPassword and password); like Navidrome, other devices stay signed in. */
    suspend fun navidromeChangePassword(authorization: String?, id: String, body: JsonObject) {
        val user = navidromeUser(authorization, id)
        val current = body["currentPassword"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val new = body["password"]?.jsonPrimitive?.contentOrNull ?: return
        if (!signIn.checkPassword(user.username, current)) throw ApiError(HttpStatusCode.BadRequest, "Your current password is wrong")
        PasswordRules.check(new, user.username).problem?.let { throw ApiError(HttpStatusCode.BadRequest, it) }
        signIn.setPassword(user.id, new)
    }

    /** Admins are marked on the account (`jukebox user admin`). */
    suspend fun isAdmin(userId: Long): Boolean = db.tx { queryOne("SELECT is_admin FROM users WHERE id = ?", userId) { it.getInt(1) == 1 } } == true

    suspend fun rename(userId: Long, displayName: String): UserDto {
        val name = displayName.trim()
        if (name.isEmpty() || name.length > 40) throw ApiError(HttpStatusCode.BadRequest, "Names are 1–40 characters")
        return db.tx {
            update("UPDATE users SET display_name = ? WHERE id = ?", name, userId)
            queryOne("SELECT * FROM users WHERE id = ?", userId) { it.toUser() }!!
        }
    }

    /** Ends every session of this user except [currentToken] (used after a password change). */
    suspend fun logoutOthers(userId: Long, currentToken: String): Int = db.tx {
        update("DELETE FROM sessions WHERE user_id = ? AND token_hash != ?", userId, hash(currentToken))
    }

    suspend fun logout(token: String) = db.tx { update("DELETE FROM sessions WHERE token_hash = ?", hash(token)) }

    /** The user a session token belongs to, or null if it's unknown. */
    suspend fun userForToken(token: String): UserDto? = db.tx {
        queryOne(
            "SELECT u.* FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ?", hash(token),
        ) { it.toUser() }
    }

    suspend fun createInvite(userId: Long): InviteDto = db.tx {
        val active = queryOne(
            "SELECT count(*) FROM invites WHERE created_by = ? AND used_by IS NULL AND expires_at > ?", userId, now(),
        ) { it.getInt(1) } ?: 0
        if (active >= MAX_ACTIVE_INVITES) {
            throw ApiError(HttpStatusCode.BadRequest, "You already have $MAX_ACTIVE_INVITES unused invites")
        }
        val code = (1..8).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")
        val expiresAt = now() + TimeUnit.DAYS.toMillis(INVITE_DAYS)
        update("INSERT INTO invites (code, created_by, created_at, expires_at) VALUES (?, ?, ?, ?)", code, userId, now(), expiresAt)
        InviteDto(formatCode(code), expiresAt)
    }

    /** Deletes one of your unused invites, so the code stops working and frees a place for a new one. */
    suspend fun deleteInvite(userId: Long, code: String) = db.tx {
        val deleted = update("DELETE FROM invites WHERE code = ? AND created_by = ? AND used_by IS NULL", normalizeCode(code), userId)
        if (deleted == 0) throw ApiError(HttpStatusCode.NotFound, "That invite is already used or isn't yours")
    }

    /** Your invites from the last 30 days, newest first, including who used them. */
    suspend fun invites(userId: Long): List<InviteDto> = db.tx {
        query(
            """SELECT i.code, i.expires_at, u.id, u.username, u.display_name
               FROM invites i LEFT JOIN users u ON u.id = i.used_by
               WHERE i.created_by = ? AND i.created_at > ? ORDER BY i.created_at DESC""",
            userId, now() - TimeUnit.DAYS.toMillis(30),
        ) { rs ->
            val usedBy = if (rs.getObject("id") != null) rs.toUser() else null
            InviteDto(formatCode(rs.getString("code")), rs.getLong("expires_at"), usedBy)
        }
    }

    private fun java.sql.Connection.createUser(username: String, displayName: String): UserDto {
        update("INSERT INTO users (username, display_name, created_at) VALUES (?, ?, ?)", username, displayName, now())
        return queryOne("SELECT * FROM users WHERE username = ?", username) { it.toUser() }!!
    }

    private fun java.sql.Connection.newSession(userId: Long): String {
        val token = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        update("INSERT INTO sessions VALUES (?, ?, ?)", hash(token), userId, now())
        return token
    }

    private fun java.sql.Connection.validInviteCreator(code: String): Long? =
        queryOne("SELECT created_by FROM invites WHERE code = ? AND used_by IS NULL AND expires_at > ?", code, now()) { it.getLong(1) }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        val USERNAME = Regex("^[A-Za-z0-9._-]{3,24}$")
        // No 0/O, 1/I/L: codes are read aloud and typed on phones.
        private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        private const val MAX_ACTIVE_INVITES = 5
        private const val INVITE_DAYS = 7L

        fun normalizeCode(code: String) = code.uppercase().filter { it.isLetterOrDigit() }
        fun formatCode(code: String) = code.chunked(4).joinToString("-")
    }
}

/** Removes a user from everything social but keeps their row for chat history. Returns their former friends. */
fun java.sql.Connection.retireUser(userId: Long): List<Long> {
    val friends = query("SELECT friend_id FROM friendships WHERE user_id = ?", userId) { it.getLong(1) }
    update("DELETE FROM sessions WHERE user_id = ?", userId)
    update("DELETE FROM devices WHERE user_id = ?", userId)
    update("DELETE FROM liked_playlists WHERE user_id = ?", userId)
    update("DELETE FROM friendships WHERE user_id = ? OR friend_id = ?", userId, userId)
    update("DELETE FROM friend_requests WHERE from_id = ? OR to_id = ?", userId, userId)
    update("DELETE FROM invites WHERE created_by = ? AND used_by IS NULL", userId)
    // Leave group chats; DMs keep them as a member so the chat still has a name.
    update(
        "DELETE FROM conversation_members WHERE user_id = ? AND conversation_id IN (SELECT id FROM conversations WHERE kind = 'group')",
        userId,
    )
    update(
        """UPDATE users SET username = 'deleted:' || id, display_name = display_name || ' (left)',
           navidrome_id = NULL, deleted_at = ? WHERE id = ?""",
        now(), userId,
    )
    return friends
}
