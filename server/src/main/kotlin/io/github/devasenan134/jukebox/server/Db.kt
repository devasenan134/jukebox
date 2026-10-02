package io.github.devasenan134.jukebox.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * A single SQLite file holds everything: users, sessions, invites, friends and chat.
 * For a group of friends one connection is plenty; [tx] runs one piece of work at a time.
 */
class Db(path: String) {
    private val connection: Connection

    init {
        File(path).absoluteFile.parentFile?.mkdirs()
        connection = DriverManager.getConnection("jdbc:sqlite:$path")
        connection.createStatement().use {
            it.execute("PRAGMA journal_mode = WAL")
            it.execute("PRAGMA foreign_keys = ON")
        }
        migrate()
    }

    /** Runs [block] inside a transaction on the IO thread pool. */
    suspend fun <T> tx(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.autoCommit = false
            try {
                connection.block().also { connection.commit() }
            } catch (e: Throwable) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun migrate() {
        val version = connection.createStatement().use { it.executeQuery("PRAGMA user_version").run { next(); getInt(1) } }
        val migrations = listOf(SCHEMA_V1, SCHEMA_V2, SCHEMA_V3, SCHEMA_V4, SCHEMA_V5, SCHEMA_V6, SCHEMA_V7, SCHEMA_V8, SCHEMA_V9, SCHEMA_V10, SCHEMA_V11, SCHEMA_V12, SCHEMA_V13, SCHEMA_V14, SCHEMA_V15, SCHEMA_V16, SCHEMA_V17, SCHEMA_V18)
        migrations.drop(version).forEachIndexed { i, sql ->
            connection.createStatement().use { st -> sql.split(";").filter { it.isNotBlank() }.forEach(st::execute) }
            connection.createStatement().use { it.execute("PRAGMA user_version = ${version + i + 1}") }
        }
    }

    private companion object {
        val SCHEMA_V1 = """
            CREATE TABLE users (
                id INTEGER PRIMARY KEY,
                username TEXT NOT NULL UNIQUE COLLATE NOCASE,
                display_name TEXT NOT NULL,
                created_at INTEGER NOT NULL
            );
            CREATE TABLE sessions (
                token_hash TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                created_at INTEGER NOT NULL
            );
            CREATE TABLE invites (
                code TEXT PRIMARY KEY,
                created_by INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                used_by INTEGER REFERENCES users(id),
                used_at INTEGER
            );
            CREATE TABLE friend_requests (
                from_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                to_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                created_at INTEGER NOT NULL,
                PRIMARY KEY (from_id, to_id)
            );
            CREATE TABLE friendships (
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                friend_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                created_at INTEGER NOT NULL,
                PRIMARY KEY (user_id, friend_id)
            );
            CREATE TABLE conversations (
                id INTEGER PRIMARY KEY,
                kind TEXT NOT NULL,
                name TEXT,
                dm_key TEXT UNIQUE,
                created_by INTEGER NOT NULL REFERENCES users(id),
                created_at INTEGER NOT NULL
            );
            CREATE TABLE conversation_members (
                conversation_id INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                last_read_id INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (conversation_id, user_id)
            );
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY,
                conversation_id INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                sender_id INTEGER NOT NULL REFERENCES users(id),
                body TEXT NOT NULL,
                song_json TEXT,
                created_at INTEGER NOT NULL
            );
            CREATE INDEX messages_by_conversation ON messages(conversation_id, id);
            CREATE TABLE devices (
                token TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent()

        // Users removed from Navidrome are kept (renamed) so old chat messages still have a sender.
        val SCHEMA_V2 = "ALTER TABLE users ADD COLUMN deleted_at INTEGER"

        // Navidrome's permanent user id, so a renamed account is recognised instead of treated as deleted.
        val SCHEMA_V3 = """
            ALTER TABLE users ADD COLUMN navidrome_id TEXT;
            CREATE UNIQUE INDEX users_by_navidrome_id ON users(navidrome_id)
        """.trimIndent()

        // Deleting a chat hides it for you and clears its history (for you only) up to [cleared_id].
        val SCHEMA_V4 = """
            ALTER TABLE conversation_members ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0;
            ALTER TABLE conversation_members ADD COLUMN cleared_id INTEGER NOT NULL DEFAULT 0
        """.trimIndent()

        // Lines like "Alice left the group", shown in the chat but not as someone's message.
        val SCHEMA_V5 = "ALTER TABLE messages ADD COLUMN system INTEGER NOT NULL DEFAULT 0"

        // Playlists people liked (Navidrome can't like playlists itself).
        val SCHEMA_V6 = """
            CREATE TABLE liked_playlists (
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                playlist_id TEXT NOT NULL,
                playlist_json TEXT NOT NULL,
                liked_at INTEGER NOT NULL,
                PRIMARY KEY (user_id, playlist_id)
            )
        """.trimIndent()

        // Profile pictures (the image is a file next to the database; this is when it was set).
        val SCHEMA_V8 = "ALTER TABLE users ADD COLUMN avatar_at INTEGER"

        // Group photos, the same way.
        val SCHEMA_V9 = "ALTER TABLE conversations ADD COLUMN picture_at INTEGER"

        // Song requests in a listening session: "pending", "accepted" or "declined".
        val SCHEMA_V10 = "ALTER TABLE messages ADD COLUMN request TEXT"

        // What a song request asks for: "next" (after the current song) or "now" (skip to it).
        val SCHEMA_V11 = "ALTER TABLE messages ADD COLUMN request_mode TEXT"

        // Replies: the message (in the same chat) a message answers.
        val SCHEMA_V12 = "ALTER TABLE messages ADD COLUMN reply_to INTEGER"

        // Photos, GIFs and stickers in chats (the files sit in chat-images/<chat>/<message>.<type>),
        // and pinned messages, each until it expires.
        val SCHEMA_V13 = """
            ALTER TABLE messages ADD COLUMN image_kind TEXT;
            ALTER TABLE messages ADD COLUMN image_width INTEGER;
            ALTER TABLE messages ADD COLUMN image_height INTEGER;
            CREATE TABLE pins (
                conversation_id INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                message_id INTEGER NOT NULL,
                pinned_by INTEGER NOT NULL REFERENCES users(id),
                pinned_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                PRIMARY KEY (conversation_id, message_id)
            )
        """.trimIndent()

        // Emoji reactions (one per person per message), and edited or deleted messages.
        val SCHEMA_V14 = """
            CREATE TABLE reactions (
                message_id INTEGER NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
                user_id INTEGER NOT NULL REFERENCES users(id),
                emoji TEXT NOT NULL,
                reacted_at INTEGER NOT NULL,
                PRIMARY KEY (message_id, user_id)
            );
            ALTER TABLE messages ADD COLUMN edited_at INTEGER;
            ALTER TABLE messages ADD COLUMN deleted_at INTEGER
        """.trimIndent()

        // Voice messages (how long, in ms; the file sits in chat-voice/<chat>/<message>.m4a), and
        // messages forwarded from another chat.
        val SCHEMA_V15 = """
            ALTER TABLE messages ADD COLUMN voice_ms INTEGER;
            ALTER TABLE messages ADD COLUMN forwarded INTEGER NOT NULL DEFAULT 0
        """.trimIndent()

        // @mentions: the ids of the people a message mentions, like "3,7" (null for none).
        val SCHEMA_V16 = "ALTER TABLE messages ADD COLUMN mentions TEXT"

        // Requests for music that isn't in the library: one row per song or movie (match_key is the
        // same however the catalog spells it), and who asked. status is "open", "done" or "declined".
        val SCHEMA_V17 = """
            CREATE TABLE music_requests (
                id INTEGER PRIMARY KEY,
                match_key TEXT NOT NULL UNIQUE,
                catalog_id TEXT NOT NULL,
                item_json TEXT NOT NULL,
                status TEXT NOT NULL,
                note TEXT,
                album_id TEXT,
                song_id TEXT,
                created_at INTEGER NOT NULL,
                closed_at INTEGER
            );
            CREATE TABLE music_request_askers (
                request_id INTEGER NOT NULL REFERENCES music_requests(id) ON DELETE CASCADE,
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                asked_at INTEGER NOT NULL,
                PRIMARY KEY (request_id, user_id)
            )
        """.trimIndent()

        // What Jukebox suggested: every list of songs a mix, a station batch or "more like this" handed
        // out, and each song's place in it (first_position: a station batch continues after what was
        // played). Joined with plays, whose source names the mix, it says which suggestions were played,
        // skipped or never reached. The recommendation engine learns from it.
        val SCHEMA_V18 = """
            CREATE TABLE suggestion_lists (
                id INTEGER PRIMARY KEY,
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                mix_id TEXT NOT NULL,
                kind TEXT NOT NULL,
                songs_hash INTEGER NOT NULL,
                first_position INTEGER NOT NULL,
                served_at INTEGER NOT NULL
            );
            CREATE INDEX suggestion_lists_by_user ON suggestion_lists(user_id, mix_id, served_at);
            CREATE TABLE suggestions (
                list_id INTEGER NOT NULL REFERENCES suggestion_lists(id) ON DELETE CASCADE,
                position INTEGER NOT NULL,
                song_id TEXT NOT NULL,
                PRIMARY KEY (list_id, position)
            )
        """.trimIndent()

        // Mixes by Jukebox: what the app played (with skips), mixes saved to Your Library,
        // and when each mix's songs last changed.
        val SCHEMA_V7 = """
            CREATE TABLE plays (
                id INTEGER PRIMARY KEY,
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                song_id TEXT NOT NULL,
                at INTEGER NOT NULL,
                played_ms INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                skipped INTEGER NOT NULL,
                source TEXT
            );
            CREATE INDEX plays_by_user ON plays(user_id, at);
            CREATE TABLE followed_mixes (
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                mix_id TEXT NOT NULL,
                mix_json TEXT NOT NULL,
                followed_at INTEGER NOT NULL,
                PRIMARY KEY (user_id, mix_id)
            );
            CREATE TABLE mix_state (
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                mix_id TEXT NOT NULL,
                songs_hash INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (user_id, mix_id)
            )
        """.trimIndent()
    }
}

// Small helpers so queries stay short and readable.

fun Connection.update(sql: String, vararg args: Any?): Int = prepare(sql, args).use { it.executeUpdate() }

fun Connection.insert(sql: String, vararg args: Any?): Long = prepare(sql, args).use {
    it.executeUpdate()
    createStatement().use { st -> st.executeQuery("SELECT last_insert_rowid()").run { next(); getLong(1) } }
}

fun <T> Connection.query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
    prepare(sql, args).use { st -> st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } } }

fun <T> Connection.queryOne(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? = query(sql, *args, map = map).firstOrNull()

private fun Connection.prepare(sql: String, args: Array<out Any?>): PreparedStatement =
    prepareStatement(sql).apply { args.forEachIndexed { i, arg -> setObject(i + 1, arg) } }

fun now(): Long = System.currentTimeMillis()
