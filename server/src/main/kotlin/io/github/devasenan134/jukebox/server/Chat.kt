package io.github.devasenan134.jukebox.server

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet

/**
 * Conversations are either a DM between two friends or a named group of friends.
 * A message is text, a shared song, or both. Messages are saved, so history is there
 * when you open the app; people who are online also get them instantly through the [Hub].
 */
class Chat(
    private val db: Db,
    private val friends: Friends,
    private val hub: Hub,
    private val groupPictures: PictureFolder,
    private val dbPath: String,
) {
    /** Who is listening together in a chat (set once listen-together is running). */
    var listenersOf: (Long) -> List<Long> = { emptyList() }
    var listenOwnerOf: (Long) -> Long? = { null }

    /** Called when someone leaves a group, and when a group is deleted (to end listen-together there). */
    var onLeft: suspend (userId: Long, conversationId: Long) -> Unit = { _, _ -> }
    var onRemoved: suspend (conversationId: Long, members: List<Long>) -> Unit = { _, _ -> }

    /** Called with the message and the members who don't have the app on screen (for push notifications). */
    var onUnseen: suspend (MessageDto, ConversationDto, List<Long>) -> Unit = { _, _, _ -> }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun conversations(userId: Long): List<ConversationDto> = db.tx {
        val ids = query("SELECT conversation_id FROM conversation_members WHERE user_id = ? AND hidden = 0", userId) { it.getLong(1) }
        ids.map { conversation(it, userId) }
            .sortedByDescending { it.lastMessage?.createdAt ?: 0 }
    }

    /** Opens the DM with a friend, creating it the first time. */
    suspend fun openDm(me: UserDto, otherId: Long): ConversationDto {
        if (!friends.areFriends(me.id, otherId)) throw ApiError(HttpStatusCode.Forbidden, "You can only message friends")
        return db.tx {
            val key = listOf(me.id, otherId).sorted().joinToString(":")
            val existing = queryOne("SELECT id FROM conversations WHERE dm_key = ?", key) { it.getLong(1) }
            // Opening a DM you deleted earlier brings it back (its old history stays cleared).
            existing?.let { update("UPDATE conversation_members SET hidden = 0 WHERE conversation_id = ? AND user_id = ?", it, me.id) }
            val id = existing ?: insert(
                "INSERT INTO conversations (kind, dm_key, created_by, created_at) VALUES ('dm', ?, ?, ?)", key, me.id, now(),
            ).also { id ->
                update("INSERT INTO conversation_members (conversation_id, user_id) VALUES (?, ?), (?, ?)", id, me.id, id, otherId)
            }
            conversation(id, me.id)
        }
    }

    suspend fun createGroup(me: UserDto, request: NewGroupRequest): ConversationDto {
        val name = groupName(request.name)
        val memberIds = (request.memberIds.toSet() - me.id)
        if (memberIds.isEmpty()) throw ApiError(HttpStatusCode.BadRequest, "Add at least one friend")
        val myFriends = friends.friendIds(me.id).toSet()
        if (!myFriends.containsAll(memberIds)) throw ApiError(HttpStatusCode.Forbidden, "You can only add friends to a group")
        return db.tx {
            val id = insert("INSERT INTO conversations (kind, name, created_by, created_at) VALUES ('group', ?, ?, ?)", name, me.id, now())
            (memberIds + me.id).forEach { update("INSERT INTO conversation_members (conversation_id, user_id) VALUES (?, ?)", id, it) }
            conversation(id, me.id)
        }
    }

    /** Up to [limit] messages older than [before] (or the newest ones), oldest first. */
    suspend fun messages(userId: Long, conversationId: Long, before: Long?, limit: Int): List<MessageDto> = db.tx {
        requireMember(conversationId, userId)
        val cleared = clearedId(conversationId, userId)
        query(
            "$MESSAGE_SELECT WHERE m.conversation_id = ? AND m.id < ? AND m.id > ? ORDER BY m.id DESC LIMIT ?",
            conversationId, before ?: Long.MAX_VALUE, cleared, limit.coerceIn(1, 100),
        ) { it.toMessage().seenAfter(cleared) }.reversed().let { withReactions(it) }
    }

    /** A listener asks the session's owner for a song. It shows in the chat with Accept/Decline for the owner. */
    suspend fun requestSong(me: UserDto, conversationId: Long, song: SongRef, mode: String): MessageDto {
        if (mode != "next" && mode != "now") throw ApiError(HttpStatusCode.BadRequest, "Ask to play it next or now")
        val waiting = db.tx {
            queryOne(
                "SELECT COUNT(*) FROM messages WHERE conversation_id = ? AND sender_id = ? AND request = 'pending'",
                conversationId, me.id,
            ) { it.getInt(1) } ?: 0
        }
        if (waiting >= MAX_PENDING_REQUESTS) {
            throw ApiError(HttpStatusCode.TooManyRequests, "You have $waiting requests waiting. Wait for an answer first")
        }
        return send(me, conversationId, SendMessageRequest(song = song), songRequest = mode)
    }

    /** The owner accepts or declines a pending request; everyone in the chat sees the answer. */
    suspend fun answerRequest(conversationId: Long, messageId: Long, accept: Boolean): MessageDto {
        val (message, members) = db.tx {
            val changed = update(
                "UPDATE messages SET request = ? WHERE id = ? AND conversation_id = ? AND request = 'pending'",
                if (accept) "accepted" else "declined", messageId, conversationId,
            )
            if (changed == 0) throw ApiError(HttpStatusCode.Conflict, "That request was already answered")
            loadMessage(messageId)!! to memberIds(conversationId)
        }
        hub.send(members, MessageUpdatedEvent(message))
        return message
    }

    /** A listening session ended: its unanswered requests can't be played anymore. */
    suspend fun expireRequests(conversationId: Long) {
        val (expired, members) = db.tx {
            val ids = query("SELECT id FROM messages WHERE conversation_id = ? AND request = 'pending'", conversationId) { it.getLong(1) }
            if (ids.isEmpty()) return@tx emptyList<MessageDto>() to emptyList()
            update("UPDATE messages SET request = 'expired' WHERE conversation_id = ? AND request = 'pending'", conversationId)
            ids.mapNotNull { loadMessage(it) } to memberIds(conversationId)
        }
        expired.forEach { hub.send(members, MessageUpdatedEvent(it)) }
    }

    suspend fun send(
        me: UserDto,
        conversationId: Long,
        request: SendMessageRequest,
        songRequest: String? = null,
        image: ChatImage? = null,
        voice: VoiceNote? = null,
        forwarded: Boolean = false,
    ): MessageDto {
        val body = request.body.trim()
        if (body.isEmpty() && request.song == null && image == null && voice == null) throw ApiError(HttpStatusCode.BadRequest, "Message is empty")
        if (listOfNotNull(image, request.song, voice).size > 1) throw ApiError(HttpStatusCode.BadRequest, "Send the picture, song or recording separately")
        if (body.length > 4000) throw ApiError(HttpStatusCode.BadRequest, "Message is too long")
        request.song?.let(::checkClip)
        val (message, members, conversation) = db.tx {
            requireMember(conversationId, me.id)
            // You can reply to any message of the chat you can see, except lines like "… left the group".
            request.replyTo?.let { replyTo ->
                val ok = queryOne(
                    "SELECT 1 FROM messages WHERE id = ? AND conversation_id = ? AND system = 0 AND id > ?",
                    replyTo, conversationId, clearedId(conversationId, me.id),
                ) { true }
                if (ok == null) throw ApiError(HttpStatusCode.BadRequest, "That message can't be replied to")
            }
            // A DM only works while you're still friends (and they still have an account).
            val dmPartner = queryOne(
                """SELECT cm.user_id FROM conversations c JOIN conversation_members cm ON cm.conversation_id = c.id
                   WHERE c.id = ? AND c.kind = 'dm' AND cm.user_id != ?""",
                conversationId, me.id,
            ) { it.getLong(1) }
            if (dmPartner != null && queryOne("SELECT 1 FROM friendships WHERE user_id = ? AND friend_id = ?", me.id, dmPartner) { true } == null) {
                throw ApiError(HttpStatusCode.Forbidden, "You can't message this person anymore")
            }
            val songJson = request.song?.let { json.encodeToString(SongRef.serializer(), it) }
            val id = insert(
                """INSERT INTO messages (conversation_id, sender_id, body, song_json, created_at, request, request_mode, reply_to,
                   image_kind, image_width, image_height, voice_ms, forwarded, mentions) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                conversationId, me.id, body, songJson, now(), songRequest?.let { "pending" }, songRequest, request.replyTo,
                image?.kind, image?.width, image?.height, voice?.durationMs, if (forwarded) 1 else 0,
                mentionsColumn(conversationId, me.id, request.mentions),
            )
            // If saving the file fails, the message isn't sent either.
            image?.let { imagesOf(conversationId).save(id, it.bytes, it.extension) }
            voice?.let { voicesOf(conversationId).save(id, it.bytes, "m4a") }
            // A new message brings the chat back for anyone who had deleted it.
            update("UPDATE conversation_members SET hidden = 0 WHERE conversation_id = ?", conversationId)
            // Your own message counts as read.
            update("UPDATE conversation_members SET last_read_id = ? WHERE conversation_id = ? AND user_id = ?", id, conversationId, me.id)
            val message = queryOne("$MESSAGE_SELECT WHERE m.id = ?", id) { it.toMessage() }!!
            val cleared = clearedIds(conversationId)
            Triple(message, cleared, conversation(conversationId, me.id))
        }
        // Everyone gets it, but a reply to a message from before someone joined doesn't show them what it said.
        members.entries.groupBy({ message.seenAfter(it.value) }, { it.key }).forEach { (copy, ids) -> hub.send(ids, MessageEvent(copy)) }
        onUnseen(message, conversation, members.keys.filter { it != me.id && !hub.isVisible(it) })
        return message
    }

    suspend fun markRead(userId: Long, conversationId: Long, messageId: Long) {
        val (members, lastRead) = db.tx {
            requireMember(conversationId, userId)
            update(
                "UPDATE conversation_members SET last_read_id = max(last_read_id, ?) WHERE conversation_id = ? AND user_id = ?",
                messageId, conversationId, userId,
            )
            memberIds(conversationId) to queryOne(
                "SELECT last_read_id FROM conversation_members WHERE conversation_id = ? AND user_id = ?", conversationId, userId,
            ) { it.getLong(1) }!!
        }
        // The others' apps can say "Seen".
        hub.send(members.filter { it != userId }, ReadEvent(conversationId, userId, lastRead))
    }

    /** Tells the others in a chat that [userId] is typing (only those online get it; nothing is saved). */
    suspend fun typing(userId: Long, conversationId: Long) {
        val members = db.tx {
            requireMember(conversationId, userId)
            memberIds(conversationId)
        }
        hub.send(members.filter { it != userId && hub.isOnline(it) }, TypingEvent(conversationId, userId))
    }

    /** Changes the text (or caption) of your own message. Not song requests or lines like "… left the group". */
    suspend fun edit(me: UserDto, conversationId: Long, messageId: Long, newBody: String, mentions: List<Long>? = null): MessageDto {
        val body = newBody.trim()
        if (body.length > 4000) throw ApiError(HttpStatusCode.BadRequest, "Message is too long")
        return changed(me, conversationId, messageId) {
            val hasMore = ownMessage(me, conversationId, messageId, "edit")
            if (body.isEmpty() && !hasMore) throw ApiError(HttpStatusCode.BadRequest, "Message is empty")
            update("UPDATE messages SET body = ?, edited_at = ? WHERE id = ?", body, now(), messageId)
            if (mentions != null) update("UPDATE messages SET mentions = ? WHERE id = ?", mentionsColumn(conversationId, me.id, mentions), messageId)
        }
    }

    /** Who a message may @mention: other members of the chat (anyone else is dropped), at most 20; null for none. */
    private fun Connection.mentionsColumn(conversationId: Long, senderId: Long, mentions: List<Long>): String? {
        if (mentions.isEmpty()) return null
        val members = memberIds(conversationId).toSet()
        return mentions.distinct().filter { it in members && it != senderId }.take(20).joinToString(",").ifEmpty { null }
    }

    /**
     * Deletes your own message for everyone. What it said (text, song, picture) is gone; it stays as
     * "This message was deleted", so replies to it still make sense. Its pins and reactions go too.
     */
    suspend fun deleteMessage(me: UserDto, conversationId: Long, messageId: Long): MessageDto {
        val message = changed(me, conversationId, messageId) {
            ownMessage(me, conversationId, messageId, "delete")
            update(
                """UPDATE messages SET body = '', song_json = NULL, image_kind = NULL, image_width = NULL, image_height = NULL,
                   voice_ms = NULL, request = NULL, request_mode = NULL, reply_to = NULL, deleted_at = ? WHERE id = ?""",
                now(), messageId,
            )
            update("DELETE FROM pins WHERE message_id = ?", messageId)
            update("DELETE FROM reactions WHERE message_id = ?", messageId)
            imagesOf(conversationId).remove(messageId)
            voicesOf(conversationId).remove(messageId)
        }
        // Pins and chat lists changed as well.
        hub.send(db.tx { memberIds(conversationId) }, ConversationUpdatedEvent(conversationId))
        return message
    }

    /** Reacts to a message with [emoji], replacing your earlier reaction; null takes yours away. */
    suspend fun react(me: UserDto, conversationId: Long, messageId: Long, emoji: String?): MessageDto {
        val clean = emoji?.trim()
        if (clean != null && (clean.isEmpty() || clean.length > 16 || clean.any { it.isLetterOrDigit() || it.isWhitespace() })) {
            throw ApiError(HttpStatusCode.BadRequest, "React with an emoji")
        }
        return changed(me, conversationId, messageId) {
            requireMember(conversationId, me.id)
            val ok = queryOne(
                "SELECT 1 FROM messages WHERE id = ? AND conversation_id = ? AND system = 0 AND deleted_at IS NULL AND id > ?",
                messageId, conversationId, clearedId(conversationId, me.id),
            ) { true }
            if (ok == null) throw ApiError(HttpStatusCode.BadRequest, "You can't react to that message")
            if (clean == null) update("DELETE FROM reactions WHERE message_id = ? AND user_id = ?", messageId, me.id)
            else update(
                "INSERT OR REPLACE INTO reactions (message_id, user_id, emoji, reacted_at) VALUES (?, ?, ?, ?)",
                messageId, me.id, clean, now(),
            )
        }
    }

    /**
     * Runs [change] on a message of the chat, then sends everyone the message as it is now (each as
     * they're allowed to see it).
     */
    private suspend fun changed(me: UserDto, conversationId: Long, messageId: Long, change: Connection.() -> Unit): MessageDto {
        val (message, cleared) = db.tx {
            change()
            loadMessage(messageId)!! to clearedIds(conversationId)
        }
        cleared.entries.groupBy({ message.seenAfter(it.value) }, { it.key }).forEach { (copy, ids) -> hub.send(ids, MessageUpdatedEvent(copy)) }
        return message.seenAfter(cleared[me.id] ?: 0)
    }

    /** Your own, ordinary message: returns whether it has a song or picture besides its text. */
    private fun Connection.ownMessage(me: UserDto, conversationId: Long, messageId: Long, verb: String): Boolean {
        requireMember(conversationId, me.id)
        val row = queryOne(
            """SELECT sender_id, system, request, deleted_at, song_json IS NOT NULL OR image_kind IS NOT NULL OR voice_ms IS NOT NULL
               FROM messages WHERE id = ? AND conversation_id = ?""",
            messageId, conversationId,
        ) { Triple(it.getLong(1), it.getInt(2) == 1 || it.getString(3) != null || it.getObject(4) != null, it.getInt(5) == 1) }
            ?: throw ApiError(HttpStatusCode.NotFound, "Message not found")
        if (row.first != me.id) throw ApiError(HttpStatusCode.Forbidden, "You can only $verb your own messages")
        if (row.second) throw ApiError(HttpStatusCode.BadRequest, "You can't $verb that message")
        return row.third
    }

    /**
     * Deletes a chat you can't message in anymore (the other person left or is no longer your friend,
     * or everyone else left the group). It disappears for you only; once nobody who's still around
     * has it, it's removed for good.
     */
    suspend fun delete(userId: Long, conversationId: Long) = db.tx {
        requireMember(conversationId, userId)
        if (conversation(conversationId, userId).canMessage) {
            throw ApiError(HttpStatusCode.BadRequest, "You can only delete chats you can't message in anymore")
        }
        val lastId = queryOne("SELECT max(id) FROM messages WHERE conversation_id = ?", conversationId) { it.getLong(1) } ?: 0
        update(
            "UPDATE conversation_members SET hidden = 1, cleared_id = ?, last_read_id = max(last_read_id, ?) WHERE conversation_id = ? AND user_id = ?",
            lastId, lastId, conversationId, userId,
        )
        val stillShown = queryOne(
            """SELECT count(*) FROM conversation_members cm JOIN users u ON u.id = cm.user_id
               WHERE cm.conversation_id = ? AND cm.hidden = 0 AND u.deleted_at IS NULL""",
            conversationId,
        ) { it.getInt(1) } ?: 0
        if (stillShown == 0) {
            update("DELETE FROM conversations WHERE id = ?", conversationId)
            removeFiles(conversationId)
        }
    }

    /**
     * Leaves a group. The others see "… left the group". If the owner leaves, the longest-standing
     * member becomes the owner; when the last person leaves, the group is deleted.
     */
    suspend fun leave(me: UserDto, conversationId: Long) {
        val (message, members) = db.tx {
            requireMember(conversationId, me.id)
            requireGroup(conversationId, "You can only leave group chats")
            update("DELETE FROM conversation_members WHERE conversation_id = ? AND user_id = ?", conversationId, me.id)
            val remaining = memberIds(conversationId)
            if (remaining.isEmpty()) {
                update("DELETE FROM conversations WHERE id = ?", conversationId)
                removeFiles(conversationId)
                return@tx null to emptyList()
            }
            update(
                """UPDATE conversations SET created_by = (SELECT user_id FROM conversation_members WHERE conversation_id = ? ORDER BY rowid LIMIT 1)
                   WHERE id = ? AND created_by = ?""",
                conversationId, conversationId, me.id,
            )
            val id = insert(
                "INSERT INTO messages (conversation_id, sender_id, body, created_at, system) VALUES (?, ?, 'left the group', ?, 1)",
                conversationId, me.id, now(),
            )
            queryOne("$MESSAGE_SELECT WHERE m.id = ?", id) { it.toMessage() } to remaining
        }
        onLeft(me.id, conversationId)
        message?.let { hub.send(members, MessageEvent(it)) }
    }

    /**
     * Sets (or with null, removes) a group's photo. Anyone in the group can, like in WhatsApp; the chat
     * shows who did it.
     */
    suspend fun setGroupPicture(me: UserDto, conversationId: Long, picture: Picture?): ConversationDto {
        db.tx {
            requireMember(conversationId, me.id)
            requireGroup(conversationId, "Only group chats have a photo")
        }
        if (picture == null) groupPictures.remove(conversationId) else groupPictures.save(conversationId, picture)
        val (message, members, conversation) = db.tx {
            update("UPDATE conversations SET picture_at = ? WHERE id = ?", picture?.let { now() }, conversationId)
            val id = insert(
                "INSERT INTO messages (conversation_id, sender_id, body, created_at, system) VALUES (?, ?, ?, ?, 1)",
                conversationId, me.id, if (picture == null) "removed the group photo" else "changed the group photo", now(),
            )
            Triple(queryOne("$MESSAGE_SELECT WHERE m.id = ?", id) { it.toMessage() }!!, memberIds(conversationId), conversation(conversationId, me.id))
        }
        hub.send(members, MessageEvent(message))
        return conversation
    }

    /**
     * The group's owner adds friends of theirs to it. Each new member sees the chat from the
     * "… added …" line on, not the history before they joined.
     */
    suspend fun addMembers(me: UserDto, conversationId: Long, userIds: List<Long>): ConversationDto {
        val myFriends = friends.friendIds(me.id).toSet()
        val (messages, members, conversation) = db.tx {
            requireOwner(conversationId, me.id)
            val current = memberIds(conversationId).toSet()
            val adding = userIds.toSet() - current
            if (adding.isEmpty()) throw ApiError(HttpStatusCode.BadRequest, "They're already in the group")
            if (!myFriends.containsAll(adding)) throw ApiError(HttpStatusCode.Forbidden, "You can only add friends to a group")
            val lastId = queryOne("SELECT max(id) FROM messages WHERE conversation_id = ?", conversationId) { it.getLong(1) } ?: 0
            val lines = adding.map { userId ->
                update(
                    "INSERT INTO conversation_members (conversation_id, user_id, cleared_id, last_read_id) VALUES (?, ?, ?, ?)",
                    conversationId, userId, lastId, lastId,
                )
                val name = queryOne("SELECT display_name FROM users WHERE id = ?", userId) { it.getString(1) }
                val id = insert(
                    "INSERT INTO messages (conversation_id, sender_id, body, created_at, system) VALUES (?, ?, ?, ?, 1)",
                    conversationId, me.id, "added $name", now(),
                )
                queryOne("$MESSAGE_SELECT WHERE m.id = ?", id) { it.toMessage() }!!
            }
            Triple(lines, memberIds(conversationId), conversation(conversationId, me.id))
        }
        // Everyone (the new members included) gets the lines, which also brings the group to the new members' chat list.
        messages.forEach { hub.send(members, MessageEvent(it)) }
        return conversation
    }

    /** The group's owner takes someone out of it. The chat disappears for them; the others see "… removed …". */
    suspend fun removeMember(me: UserDto, conversationId: Long, userId: Long): ConversationDto {
        if (userId == me.id) throw ApiError(HttpStatusCode.BadRequest, "Leave the group instead")
        val (message, members, conversation) = db.tx {
            requireOwner(conversationId, me.id)
            requireMember(conversationId, userId)
            update("DELETE FROM conversation_members WHERE conversation_id = ? AND user_id = ?", conversationId, userId)
            val name = queryOne("SELECT display_name FROM users WHERE id = ?", userId) { it.getString(1) }
            val id = insert(
                "INSERT INTO messages (conversation_id, sender_id, body, created_at, system) VALUES (?, ?, ?, ?, 1)",
                conversationId, me.id, "removed $name", now(),
            )
            Triple(queryOne("$MESSAGE_SELECT WHERE m.id = ?", id) { it.toMessage() }!!, memberIds(conversationId), conversation(conversationId, me.id))
        }
        onLeft(userId, conversationId) // out of the group's jam too
        hub.send(listOf(userId), ConversationRemovedEvent(conversationId))
        hub.send(members, MessageEvent(message))
        return conversation
    }

    /** The group's owner renames it; everyone sees "… renamed the group to …". */
    suspend fun renameGroup(me: UserDto, conversationId: Long, newName: String): ConversationDto {
        val name = groupName(newName)
        val (message, members, conversation) = db.tx {
            requireOwner(conversationId, me.id, "Only the group's owner can rename it")
            val old = queryOne("SELECT name FROM conversations WHERE id = ?", conversationId) { it.getString(1) }
            if (old == name) throw ApiError(HttpStatusCode.BadRequest, "The group already has that name")
            update("UPDATE conversations SET name = ? WHERE id = ?", name, conversationId)
            val id = insert(
                "INSERT INTO messages (conversation_id, sender_id, body, created_at, system) VALUES (?, ?, ?, ?, 1)",
                conversationId, me.id, "renamed the group to \u201c$name\u201d", now(),
            )
            Triple(queryOne("$MESSAGE_SELECT WHERE m.id = ?", id) { it.toMessage() }!!, memberIds(conversationId), conversation(conversationId, me.id))
        }
        hub.send(members, MessageEvent(message))
        return conversation
    }

    /** The picture of a message, for the chat's members who can see that message; null if it has none. */
    suspend fun image(userId: Long, conversationId: Long, messageId: Long): java.io.File? {
        val visible = db.tx {
            requireMember(conversationId, userId)
            queryOne(
                "SELECT 1 FROM messages WHERE id = ? AND conversation_id = ? AND image_kind IS NOT NULL AND id > ?",
                messageId, conversationId, clearedId(conversationId, userId),
            ) { true } != null
        }
        return if (visible) imagesOf(conversationId).get(messageId) else null
    }

    /**
     * Pins a message to the top of the chat for [hours] (24 hours, 7 days or 30 days). Anyone in the
     * chat can. A chat has at most [MAX_PINS]; pinning another unpins the oldest. Pinning a pinned
     * message again starts its time over. Everyone sees "… pinned a message".
     */
    suspend fun pin(me: UserDto, conversationId: Long, messageId: Long, hours: Int): ConversationDto {
        if (hours !in PIN_HOURS) throw ApiError(HttpStatusCode.BadRequest, "Pin it for 24 hours, 7 days or 30 days")
        val (message, members, conversation) = db.tx {
            requireMember(conversationId, me.id)
            val ok = queryOne(
                "SELECT 1 FROM messages WHERE id = ? AND conversation_id = ? AND system = 0 AND id > ?",
                messageId, conversationId, clearedId(conversationId, me.id),
            ) { true }
            if (ok == null) throw ApiError(HttpStatusCode.BadRequest, "That message can't be pinned")
            val at = now()
            update("DELETE FROM pins WHERE conversation_id = ? AND (expires_at <= ? OR message_id = ?)", conversationId, at, messageId)
            update(
                """DELETE FROM pins WHERE conversation_id = ? AND message_id IN
                   (SELECT message_id FROM pins WHERE conversation_id = ? ORDER BY pinned_at DESC LIMIT -1 OFFSET ?)""",
                conversationId, conversationId, MAX_PINS - 1,
            )
            update(
                "INSERT INTO pins (conversation_id, message_id, pinned_by, pinned_at, expires_at) VALUES (?, ?, ?, ?, ?)",
                conversationId, messageId, me.id, at, at + hours * 3_600_000L,
            )
            // The line points at the pinned message (like a reply), so tapping it can jump there.
            val id = insert(
                "INSERT INTO messages (conversation_id, sender_id, body, created_at, system, reply_to) VALUES (?, ?, 'pinned a message', ?, 1, ?)",
                conversationId, me.id, at, messageId,
            )
            val line = queryOne("$MESSAGE_SELECT WHERE m.id = ?", id) { it.toMessage() }!!
            val cleared = clearedIds(conversationId)
            Triple(line, cleared, conversation(conversationId, me.id))
        }
        members.entries.groupBy({ message.seenAfter(it.value) }, { it.key }).forEach { (copy, ids) -> hub.send(ids, MessageEvent(copy)) }
        return conversation
    }

    /** Unpins a message (anyone in the chat can). There's no line about it; the others' apps just refresh the chat. */
    suspend fun unpin(me: UserDto, conversationId: Long, messageId: Long): ConversationDto {
        val (members, conversation) = db.tx {
            requireMember(conversationId, me.id)
            update("DELETE FROM pins WHERE conversation_id = ? AND message_id = ?", conversationId, messageId)
            memberIds(conversationId) to conversation(conversationId, me.id)
        }
        hub.send(members, ConversationUpdatedEvent(conversationId))
        return conversation
    }

    /** The recording of a voice message, for the chat's members who can see that message. */
    suspend fun voice(userId: Long, conversationId: Long, messageId: Long): java.io.File? {
        val visible = db.tx {
            requireMember(conversationId, userId)
            queryOne(
                "SELECT 1 FROM messages WHERE id = ? AND conversation_id = ? AND voice_ms IS NOT NULL AND id > ?",
                messageId, conversationId, clearedId(conversationId, userId),
            ) { true } != null
        }
        return if (visible) voicesOf(conversationId).get(messageId) else null
    }

    /**
     * Forwards a message you can see to up to [MAX_FORWARD] chats of yours: its text, song, picture or
     * recording is sent there by you, marked "Forwarded". Returns the new messages.
     */
    suspend fun forward(me: UserDto, fromId: Long, messageId: Long, to: List<Long>): List<MessageDto> {
        val targets = to.distinct()
        if (targets.isEmpty() || targets.size > MAX_FORWARD) throw ApiError(HttpStatusCode.BadRequest, "Pick 1 to $MAX_FORWARD chats")
        val original = db.tx {
            requireMember(fromId, me.id)
            queryOne(
                "SELECT * FROM messages WHERE id = ? AND conversation_id = ? AND system = 0 AND deleted_at IS NULL AND id > ?",
                messageId, fromId, clearedId(fromId, me.id),
            ) {
                Forwardable(
                    body = it.getString("body"),
                    song = it.getString("song_json")?.let { song -> json.decodeFromString(SongRef.serializer(), song) },
                    imageKind = it.getString("image_kind"),
                    imageWidth = it.getInt("image_width"),
                    imageHeight = it.getInt("image_height"),
                    voiceMs = it.getObject("voice_ms")?.let { ms -> (ms as Number).toLong() },
                )
            } ?: throw ApiError(HttpStatusCode.BadRequest, "That message can't be forwarded")
        }
        // Check every chat first, so it's forwarded everywhere or nowhere.
        db.tx { targets.forEach { requireMember(it, me.id) } }
        val image = original.imageKind?.let { kind ->
            val file = imagesOf(fromId).get(messageId) ?: throw ApiError(HttpStatusCode.Gone, "The picture is gone")
            ChatImage(file.readBytes(), kind, original.imageWidth, original.imageHeight)
        }
        val voice = original.voiceMs?.let { ms ->
            val file = voicesOf(fromId).get(messageId) ?: throw ApiError(HttpStatusCode.Gone, "The recording is gone")
            VoiceNote(file.readBytes(), ms)
        }
        return targets.map { target ->
            send(me, target, SendMessageRequest(original.body, original.song), image = image, voice = voice, forwarded = true)
        }
    }

    private class Forwardable(
        val body: String, val song: SongRef?, val imageKind: String?, val imageWidth: Int, val imageHeight: Int, val voiceMs: Long?,
    )

    /**
     * Messages of a chat whose text, or shared song's title or artist, contains [text] (ignoring case
     * for English letters), newest first, at most 50. Only what you can see, not deleted ones.
     */
    suspend fun search(userId: Long, conversationId: Long, text: String): List<MessageDto> {
        val q = text.trim()
        if (q.isEmpty() || q.length > 100) throw ApiError(HttpStatusCode.BadRequest, "Type something to search for")
        val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return db.tx {
            requireMember(conversationId, userId)
            val cleared = clearedId(conversationId, userId)
            query(
                """$MESSAGE_SELECT WHERE m.conversation_id = ? AND m.id > ? AND m.system = 0 AND m.deleted_at IS NULL
                   AND (m.body LIKE ? ESCAPE '\' OR json_extract(m.song_json, '$.title') LIKE ? ESCAPE '\'
                        OR json_extract(m.song_json, '$.artist') LIKE ? ESCAPE '\')
                   ORDER BY m.id DESC LIMIT 50""",
                conversationId, cleared, like, like, like,
            ) { it.toMessage().seenAfter(cleared) }.let { withReactions(it) }
        }
    }

    /** Removes the pictures and recordings of chats that no longer exist (deleted, or from before a restore). */
    suspend fun sweepImages() {
        val existing = db.tx { query("SELECT id FROM conversations") { it.getLong(1) }.toSet() }
        for (name in listOf("chat-images", "chat-voice")) {
            val root = java.io.File(java.io.File(dbPath).absoluteFile.parentFile, name)
            root.listFiles().orEmpty().filter { it.isDirectory && it.name.toLongOrNull() !in existing }.forEach { it.deleteRecursively() }
        }
    }

    private fun imagesOf(conversationId: Long) = PictureFolder(dbPath, "chat-images/$conversationId")
    private fun voicesOf(conversationId: Long) = PictureFolder(dbPath, "chat-voice/$conversationId")

    /** A deleted chat's pictures and recordings. */
    private fun removeFiles(conversationId: Long) {
        imagesOf(conversationId).removeAll()
        voicesOf(conversationId).removeAll()
    }

    /** Which members of a chat are online right now (for the member list). */
    suspend fun onlineMembers(userId: Long, conversationId: Long): List<Long> = db.tx {
        requireMember(conversationId, userId)
        memberIds(conversationId)
    }.filter { hub.isOnline(it) }

    /** A group's photo file, for its members only; null if it has none. */
    suspend fun groupPicture(userId: Long, conversationId: Long): java.io.File? {
        db.tx { requireMember(conversationId, userId) }
        return groupPictures.get(conversationId)
    }

    /** Deletes a group and all its messages for every member. Only its owner can. */
    suspend fun deleteForEveryone(me: UserDto, conversationId: Long) {
        val members = db.tx {
            requireMember(conversationId, me.id)
            requireGroup(conversationId, "Only group chats can be deleted for everyone")
            val owner = queryOne("SELECT created_by FROM conversations WHERE id = ?", conversationId) { it.getLong(1) }
            if (owner != me.id) throw ApiError(HttpStatusCode.Forbidden, "Only the group's owner can delete it for everyone")
            memberIds(conversationId).also { update("DELETE FROM conversations WHERE id = ?", conversationId) }
        }
        removeFiles(conversationId)
        onRemoved(conversationId, members)
        hub.send(members, ConversationRemovedEvent(conversationId))
    }

    /** Only a group's owner (who made it, or took over when they left) can change who's in it, or its name. */
    private fun Connection.requireOwner(conversationId: Long, userId: Long, error: String = "Only the group's owner can add or remove people") {
        requireMember(conversationId, userId)
        requireGroup(conversationId, "Only group chats can be changed like that")
        val owner = queryOne("SELECT created_by FROM conversations WHERE id = ?", conversationId) { it.getLong(1) }
        if (owner != userId) throw ApiError(HttpStatusCode.Forbidden, error)
    }

    private fun groupName(name: String) = name.trim().also {
        if (it.isEmpty() || it.length > 50) throw ApiError(HttpStatusCode.BadRequest, "Give the group a name (up to 50 characters)")
    }

    private fun Connection.requireGroup(conversationId: Long, error: String) {
        val kind = queryOne("SELECT kind FROM conversations WHERE id = ?", conversationId) { it.getString(1) }
        if (kind != "group") throw ApiError(HttpStatusCode.BadRequest, error)
    }

    private fun checkClip(song: SongRef) {
        if (song.clipStartMs == null && song.clipEndMs == null) return
        val start = song.clipStartMs ?: -1
        val end = song.clipEndMs ?: -1
        val fits = start >= 0 && end - start >= 1_000 && (song.duration <= 0 || end <= song.duration * 1000L + 1_000)
        if (!fits) throw ApiError(HttpStatusCode.BadRequest, "Pick a part of the song at least a second long")
    }

    /** One message, with its reactions. */
    private fun Connection.loadMessage(id: Long): MessageDto? =
        queryOne("$MESSAGE_SELECT WHERE m.id = ?", id) { it.toMessage() }?.let { withReactions(listOf(it)).single() }

    /** Adds the reactions to [messages] (one query for all of them). */
    private fun Connection.withReactions(messages: List<MessageDto>): List<MessageDto> {
        if (messages.isEmpty()) return messages
        val ids = messages.map { it.id }
        val rows = query(
            "SELECT message_id, emoji, user_id FROM reactions WHERE message_id IN (${ids.joinToString { "?" }}) ORDER BY reacted_at",
            *ids.toTypedArray(),
        ) { Triple(it.getLong(1), it.getString(2), it.getLong(3)) }
        if (rows.isEmpty()) return messages
        val byMessage = rows.groupBy { it.first }
        return messages.map { m ->
            val mine = byMessage[m.id] ?: return@map m
            m.copy(reactions = mine.groupBy({ it.second }, { it.third }).map { (emoji, users) -> ReactionDto(emoji, users) })
        }
    }

    /** Each member's cleared_id (where their history starts). */
    private fun Connection.clearedIds(conversationId: Long): Map<Long, Long> =
        query("SELECT user_id, cleared_id FROM conversation_members WHERE conversation_id = ?", conversationId) {
            it.getLong(1) to it.getLong(2)
        }.toMap()

    private fun Connection.clearedId(conversationId: Long, userId: Long) =
        queryOne("SELECT cleared_id FROM conversation_members WHERE conversation_id = ? AND user_id = ?", conversationId, userId) { it.getLong(1) } ?: 0

    private fun Connection.conversation(id: Long, viewerId: Long): ConversationDto {
        val (kind, name, owner) = queryOne("SELECT kind, name, created_by FROM conversations WHERE id = ?", id) {
            Triple(it.getString(1), it.getString(2), it.getLong(3))
        }!!
        val picture = queryOne("SELECT picture_at FROM conversations WHERE id = ?", id) { rs -> rs.getObject(1)?.let { (it as Number).toLong() } }
        val members = query(
            "SELECT u.* FROM conversation_members cm JOIN users u ON u.id = cm.user_id WHERE cm.conversation_id = ?", id,
        ) { it.toUser() }
        val cleared = clearedId(id, viewerId)
        val last = queryOne("$MESSAGE_SELECT WHERE m.conversation_id = ? AND m.id > ? ORDER BY m.id DESC LIMIT 1", id, cleared) { it.toMessage().seenAfter(cleared) }
        val unread = queryOne(
            """SELECT count(*) FROM messages m JOIN conversation_members cm
               ON cm.conversation_id = m.conversation_id AND cm.user_id = ?
               WHERE m.conversation_id = ? AND m.id > cm.last_read_id AND m.sender_id != ?""",
            viewerId, id, viewerId,
        ) { it.getInt(1) } ?: 0
        val unreadMentions = if (unread == 0) 0 else queryOne(
            """SELECT count(*) FROM messages m JOIN conversation_members cm
               ON cm.conversation_id = m.conversation_id AND cm.user_id = ?
               WHERE m.conversation_id = ? AND m.id > cm.last_read_id AND m.deleted_at IS NULL
               AND (',' || m.mentions || ',') LIKE ?""",
            viewerId, id, "%,$viewerId,%",
        ) { it.getInt(1) } ?: 0
        val others = members.filter { it.id != viewerId }
        val canMessage = if (kind == "dm") {
            others.any { queryOne("SELECT 1 FROM friendships WHERE user_id = ? AND friend_id = ?", viewerId, it.id) { true } != null }
        } else {
            others.isNotEmpty()
        }
        // Pins that haven't run out, of messages this person can see.
        val pins = query(
            """SELECT p.pinned_at, p.expires_at, m.id, m.body, m.song_json, m.image_kind, m.voice_ms,
                      u.id AS sender_id, u.username AS sender_username, u.display_name AS sender_display_name, u.avatar_at AS sender_avatar_at,
                      pu.id AS by_id, pu.username AS by_username, pu.display_name AS by_display_name, pu.avatar_at AS by_avatar_at
               FROM pins p JOIN messages m ON m.id = p.message_id JOIN users u ON u.id = m.sender_id JOIN users pu ON pu.id = p.pinned_by
               WHERE p.conversation_id = ? AND p.expires_at > ? AND m.id > ? ORDER BY p.pinned_at DESC""",
            id, now(), cleared,
        ) {
            PinDto(
                message = ReplyDto(
                    id = it.getLong("id"),
                    sender = it.toUser(prefix = "sender_"),
                    body = it.getString("body"),
                    song = it.getString("song_json")?.let { song -> json.decodeFromString(SongRef.serializer(), song) },
                    imageKind = it.getString("image_kind"),
                    voiceMs = it.getObject("voice_ms")?.let { ms -> (ms as Number).toLong() },
                ),
                pinnedBy = it.toUser(prefix = "by_"),
                pinnedAt = it.getLong("pinned_at"),
                expiresAt = it.getLong("expires_at"),
            )
        }
        return ConversationDto(
            id, kind, name, members, last, unread, canMessage, listenersOf(id), listenOwnerOf(id), createdBy = owner, picture = picture, pins = pins,
            readMarks = query(
                "SELECT user_id, last_read_id FROM conversation_members WHERE conversation_id = ? AND user_id != ?", id, viewerId,
            ) { ReadMarkDto(it.getLong(1), it.getLong(2)) },
            unreadMentions = unreadMentions,
        )
    }

    /** Who is in a chat (for listen-together). */
    suspend fun members(conversationId: Long): List<Long> = db.tx { memberIds(conversationId) }

    private fun Connection.memberIds(conversationId: Long) =
        query("SELECT user_id FROM conversation_members WHERE conversation_id = ?", conversationId) { it.getLong(1) }

    private fun Connection.requireMember(conversationId: Long, userId: Long) {
        queryOne("SELECT 1 FROM conversation_members WHERE conversation_id = ? AND user_id = ?", conversationId, userId) { true }
            ?: throw ApiError(HttpStatusCode.NotFound, "Conversation not found")
    }

    private fun ResultSet.toMessage() = MessageDto(
        id = getLong("id"),
        conversationId = getLong("conversation_id"),
        sender = toUser(prefix = "sender_"),
        body = getString("body"),
        song = getString("song_json")?.let { json.decodeFromString(SongRef.serializer(), it) },
        createdAt = getLong("created_at"),
        system = getInt("system") == 1,
        request = getString("request"),
        requestMode = getString("request_mode"),
        replyTo = getObject("reply_id")?.let {
            ReplyDto(
                id = (it as Number).toLong(),
                sender = toUser(prefix = "reply_sender_"),
                body = getString("reply_body"),
                song = getString("reply_song_json")?.let { song -> json.decodeFromString(SongRef.serializer(), song) },
                imageKind = getString("reply_image_kind"),
                deleted = getObject("reply_deleted_at") != null,
                voiceMs = getObject("reply_voice_ms")?.let { ms -> (ms as Number).toLong() },
            )
        },
        image = getString("image_kind")?.let { ImageDto(it, getInt("image_width"), getInt("image_height")) },
        editedAt = getObject("edited_at")?.let { (it as Number).toLong() },
        deleted = getObject("deleted_at") != null,
        voiceMs = getObject("voice_ms")?.let { (it as Number).toLong() },
        forwarded = getInt("forwarded") == 1,
        mentions = getString("mentions")?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty(),
    )

    /** How [this] looks to someone whose history starts after message [clearedId]. */
    private fun MessageDto.seenAfter(clearedId: Long) =
        if (replyTo != null && replyTo.id <= clearedId) copy(replyTo = ReplyDto(replyTo.id, replyTo.sender, hidden = true)) else this

    private companion object {
        /** How many unanswered song requests one listener can have in a chat at once. */
        const val MAX_PENDING_REQUESTS = 3
        /** How many messages a chat can have pinned at once, and for how long (24 hours, 7 days, 30 days). */
        const val MAX_PINS = 3
        val PIN_HOURS = setOf(24, 24 * 7, 24 * 30)
        /** How many chats one message can be forwarded to at once. */
        const val MAX_FORWARD = 10
        const val MESSAGE_SELECT = """SELECT m.*, u.id AS sender_id, u.username AS sender_username, u.display_name AS sender_display_name, u.avatar_at AS sender_avatar_at,
                r.id AS reply_id, r.body AS reply_body, r.song_json AS reply_song_json, r.image_kind AS reply_image_kind, r.deleted_at AS reply_deleted_at, r.voice_ms AS reply_voice_ms,
                ru.id AS reply_sender_id, ru.username AS reply_sender_username, ru.display_name AS reply_sender_display_name, ru.avatar_at AS reply_sender_avatar_at
            FROM messages m JOIN users u ON u.id = m.sender_id
            LEFT JOIN messages r ON r.id = m.reply_to LEFT JOIN users ru ON ru.id = r.sender_id"""
    }
}
