package io.github.devasenan134.jukebox.server

import io.ktor.http.HttpStatusCode

/**
 * Friends work like most apps: send a request by username, the other person accepts.
 * If both people request each other, they become friends straight away.
 */
class Friends(private val db: Db) {
    lateinit var hub: Hub
    var push: Push? = null

    suspend fun friendIds(userId: Long): List<Long> = db.tx {
        query("SELECT friend_id FROM friendships WHERE user_id = ?", userId) { it.getLong(1) }
    }

    suspend fun areFriends(a: Long, b: Long): Boolean = db.tx {
        queryOne("SELECT 1 FROM friendships WHERE user_id = ? AND friend_id = ?", a, b) { true } ?: false
    }

    /** Friends list, online people first, then alphabetical. */
    suspend fun list(userId: Long): List<FriendDto> {
        val friends = db.tx {
            query(
                "SELECT u.* FROM friendships f JOIN users u ON u.id = f.friend_id WHERE f.user_id = ?", userId,
            ) { it.toUser() }
        }
        return friends
            .map { friendDto(it) }
            .sortedWith(compareByDescending<FriendDto> { it.online }.thenBy { it.user.displayName.lowercase() })
    }

    fun friendDto(user: UserDto) = FriendDto(user, hub.isOnline(user.id), hub.nowPlaying(user.id))

    suspend fun requests(userId: Long): FriendRequestsDto = db.tx {
        FriendRequestsDto(
            incoming = query(
                "SELECT u.* FROM friend_requests r JOIN users u ON u.id = r.from_id WHERE r.to_id = ? ORDER BY r.created_at DESC",
                userId,
            ) { it.toUser() },
            outgoing = query(
                "SELECT u.* FROM friend_requests r JOIN users u ON u.id = r.to_id WHERE r.from_id = ? ORDER BY r.created_at DESC",
                userId,
            ) { it.toUser() },
        )
    }

    suspend fun request(me: UserDto, username: String): AddFriendResponse {
        val target = db.tx { queryOne("SELECT * FROM users WHERE username = ?", username.trim()) { it.toUser() } }
            ?: throw ApiError(HttpStatusCode.NotFound, "No one called \"${username.trim()}\" has joined Jukebox yet")
        if (target.id == me.id) throw ApiError(HttpStatusCode.BadRequest, "That's you!")
        if (areFriends(me.id, target.id)) return AddFriendResponse("friends")

        val theyAskedFirst = db.tx {
            queryOne("SELECT 1 FROM friend_requests WHERE from_id = ? AND to_id = ?", target.id, me.id) { true } ?: false
        }
        if (theyAskedFirst) {
            accept(me, target.id)
            return AddFriendResponse("friends")
        }
        val isNew = db.tx { update("INSERT OR IGNORE INTO friend_requests VALUES (?, ?, ?)", me.id, target.id, now()) } > 0
        if (isNew) {
            hub.send(listOf(target.id), FriendRequestEvent(me))
            if (!hub.isVisible(target.id)) push?.friendRequest(me, target.id)
        }
        return AddFriendResponse("requested")
    }

    suspend fun accept(me: UserDto, fromId: Long) {
        db.tx {
            val removed = update("DELETE FROM friend_requests WHERE from_id = ? AND to_id = ?", fromId, me.id)
            if (removed == 0) throw ApiError(HttpStatusCode.NotFound, "That friend request doesn't exist anymore")
            update("DELETE FROM friend_requests WHERE from_id = ? AND to_id = ?", me.id, fromId)
            val t = now()
            update("INSERT OR IGNORE INTO friendships VALUES (?, ?, ?)", me.id, fromId, t)
            update("INSERT OR IGNORE INTO friendships VALUES (?, ?, ?)", fromId, me.id, t)
        }
        announceFriendship(me.id, fromId)
        if (!hub.isVisible(fromId)) push?.friendAccepted(me, fromId)
    }

    suspend fun decline(me: UserDto, fromId: Long) {
        db.tx { update("DELETE FROM friend_requests WHERE (from_id = ? AND to_id = ?) OR (from_id = ? AND to_id = ?)", fromId, me.id, me.id, fromId) }
    }

    suspend fun remove(me: UserDto, friendId: Long) {
        db.tx {
            update("DELETE FROM friendships WHERE (user_id = ? AND friend_id = ?) OR (user_id = ? AND friend_id = ?)", me.id, friendId, friendId, me.id)
        }
        hub.send(listOf(friendId), FriendRemovedEvent(me.id))
        hub.send(listOf(me.id), FriendRemovedEvent(friendId))
    }

    /** Tells both people about their new friend, including whether the other is online. */
    suspend fun announceFriendship(a: Long, b: Long) {
        val (userA, userB) = db.tx {
            val load = { id: Long -> queryOne("SELECT * FROM users WHERE id = ?", id) { it.toUser() }!! }
            load(a) to load(b)
        }
        hub.send(listOf(a), FriendAddedEvent(friendDto(userB)))
        hub.send(listOf(b), FriendAddedEvent(friendDto(userA)))
    }
}
