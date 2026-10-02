package io.github.devasenan134.jukebox.server

import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * Keeps our users in step with Navidrome. Runs every few minutes.
 *
 * People are matched by Navidrome's permanent user id, so:
 *  - a renamed account keeps its friends and chats (we just update the username), and
 *  - a deleted account is removed: it disappears from friend lists, requests and group chats,
 *    and can't log in. Its row stays (renamed, marked "(left)") so old messages keep a sender,
 *    and the username becomes free again.
 */
class Cleanup(private val db: Db, private val navidrome: Navidrome, private val hub: Hub) {
    private val log = LoggerFactory.getLogger("cleanup")

    data class Result(val removed: List<String>, val renamed: List<Pair<String, String>>)

    suspend fun run(): Result {
        val existing = navidrome.users()
        if (existing.isNullOrEmpty()) {
            log.warn("Skipping cleanup: couldn't get the user list from Navidrome")
            return Result(emptyList(), emptyList())
        }
        val byId = existing.associateBy { it.id }
        val byName = existing.associateBy { it.userName.lowercase() }

        data class Ours(val id: Long, val username: String, val navidromeId: String?)
        val ours = db.tx {
            query("SELECT id, username, navidrome_id FROM users WHERE deleted_at IS NULL") { Ours(it.getLong(1), it.getString(2), it.getString(3)) }
        }

        // Users from before ids were stored get their id from their current username, once.
        val resolved = ours.map { u -> u to (u.navidromeId ?: byName[u.username.lowercase()]?.id) }
        val backfill = resolved.filter { (u, id) -> u.navidromeId == null && id != null }
        if (backfill.isNotEmpty()) db.tx {
            backfill.forEach { (u, id) -> update("UPDATE users SET navidrome_id = ? WHERE id = ? AND navidrome_id IS NULL", id, u.id) }
        }

        val gone = resolved.filter { (_, id) -> id == null || id !in byId }.map { it.first }
        // Safety net: removing more than half at once is more likely a glitch than real deletions.
        if (gone.size > 1 && gone.size * 2 > ours.size) {
            log.warn("Skipping cleanup: it would remove ${gone.size} of ${ours.size} users, which looks wrong")
            return Result(emptyList(), emptyList())
        }

        val renamed = resolved.mapNotNull { (u, id) ->
            val current = id?.let { byId[it] }?.userName ?: return@mapNotNull null
            if (current != u.username) Triple(u.id, u.username, current) else null
        }
        renamed.forEach { (userId, old, new) ->
            runCatching { db.tx { update("UPDATE users SET username = ? WHERE id = ?", new, userId) } }
                .onSuccess { log.info("$old was renamed to $new in Navidrome") }
                .onFailure { log.warn("Couldn't follow rename $old -> $new: ${it.message}") }
        }

        gone.forEach { remove(it.id, it.username) }
        return Result(gone.map { it.username }, renamed.map { it.second to it.third })
    }

    private suspend fun remove(userId: Long, username: String) {
        val formerFriends = db.tx { retireUser(userId) }
        hub.kick(userId)
        formerFriends.forEach { hub.send(listOf(it), FriendRemovedEvent(userId)) }
        log.info("Removed $username (deleted from Navidrome)")
    }
}

/** Removes a user from everything social but keeps their row for chat history. Returns their former friends. */
fun Connection.retireUser(userId: Long): List<Long> {
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
