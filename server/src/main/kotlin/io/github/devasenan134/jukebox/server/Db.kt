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
        val migrations = listOf(SCHEMA_V1, SCHEMA_V2, SCHEMA_V3, SCHEMA_V4, SCHEMA_V5, SCHEMA_V6, SCHEMA_V7, SCHEMA_V8, SCHEMA_V9, SCHEMA_V10, SCHEMA_V11, SCHEMA_V12, SCHEMA_V13, SCHEMA_V14, SCHEMA_V15, SCHEMA_V16, SCHEMA_V17, SCHEMA_V18, SCHEMA_V19, SCHEMA_V20, SCHEMA_V21, SCHEMA_V22, SCHEMA_V23)
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

        // The catalog (docs/milestone-1.md). An album (a film, or any album) has releases (soundtrack, score,
        // single...), a release has tracks, and a track is a recording appearing on it. A recording is one
        // performance: likes, plays and lyrics belong to it, and its id never changes when files move.
        // A song groups the versions of a recording (karaoke, remix...). files are what's on disk.
        // merged_into: a recording found to be the same as another (same sound) points to the one kept.
        // group_key: how the scanner recognizes the same album or release on the next scan.
        val SCHEMA_V19 = """
            CREATE TABLE libraries (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL UNIQUE,
                path TEXT NOT NULL,
                kind TEXT NOT NULL,
                language TEXT
            );
            CREATE TABLE artwork (
                id TEXT PRIMARY KEY,
                hash TEXT NOT NULL UNIQUE,
                source TEXT NOT NULL,
                mime TEXT NOT NULL,
                width INTEGER,
                height INTEGER
            );
            CREATE TABLE people (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                sort_name TEXT NOT NULL,
                sound_key TEXT NOT NULL UNIQUE,
                aliases TEXT
            );
            CREATE TABLE songs (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                song_key TEXT NOT NULL UNIQUE
            );
            CREATE TABLE albums (
                id TEXT PRIMARY KEY,
                library_id INTEGER NOT NULL REFERENCES libraries(id),
                title TEXT NOT NULL,
                sort_title TEXT NOT NULL,
                year INTEGER,
                kind TEXT NOT NULL,
                cover_id TEXT REFERENCES artwork(id),
                group_key TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            );
            CREATE TABLE releases (
                id TEXT PRIMARY KEY,
                album_id TEXT NOT NULL REFERENCES albums(id),
                title TEXT NOT NULL,
                kind TEXT NOT NULL,
                year INTEGER,
                source TEXT,
                source_id TEXT,
                cover_id TEXT REFERENCES artwork(id),
                group_key TEXT NOT NULL UNIQUE
            );
            CREATE INDEX releases_by_album ON releases(album_id);
            CREATE TABLE recordings (
                id TEXT PRIMARY KEY,
                song_id TEXT REFERENCES songs(id),
                title TEXT NOT NULL,
                title_key TEXT NOT NULL,
                version TEXT NOT NULL,
                duration_ms INTEGER NOT NULL,
                isrc TEXT,
                saavn_id TEXT,
                mbid TEXT,
                fingerprint BLOB,
                merged_into TEXT REFERENCES recordings(id),
                created_at INTEGER NOT NULL
            );
            CREATE INDEX recordings_by_isrc ON recordings(isrc);
            CREATE INDEX recordings_by_saavn ON recordings(saavn_id);
            CREATE INDEX recordings_by_mbid ON recordings(mbid);
            CREATE INDEX recordings_by_song ON recordings(song_id);
            CREATE INDEX recordings_by_title ON recordings(title_key, duration_ms);
            CREATE TABLE tracks (
                id TEXT PRIMARY KEY,
                release_id TEXT NOT NULL REFERENCES releases(id),
                recording_id TEXT NOT NULL REFERENCES recordings(id),
                disc INTEGER NOT NULL,
                number INTEGER NOT NULL,
                title TEXT NOT NULL,
                disc_subtitle TEXT
            );
            CREATE INDEX tracks_by_release ON tracks(release_id);
            CREATE INDEX tracks_by_recording ON tracks(recording_id);
            CREATE TABLE recording_credits (
                recording_id TEXT NOT NULL REFERENCES recordings(id) ON DELETE CASCADE,
                person_id TEXT NOT NULL REFERENCES people(id),
                role TEXT NOT NULL,
                position INTEGER NOT NULL,
                PRIMARY KEY (recording_id, person_id, role)
            );
            CREATE INDEX recording_credits_by_person ON recording_credits(person_id, role);
            CREATE TABLE album_credits (
                album_id TEXT NOT NULL REFERENCES albums(id) ON DELETE CASCADE,
                person_id TEXT NOT NULL REFERENCES people(id),
                role TEXT NOT NULL,
                position INTEGER NOT NULL,
                PRIMARY KEY (album_id, person_id, role)
            );
            CREATE INDEX album_credits_by_person ON album_credits(person_id, role);
            CREATE TABLE files (
                id INTEGER PRIMARY KEY,
                library_id INTEGER NOT NULL REFERENCES libraries(id),
                path TEXT NOT NULL,
                size INTEGER NOT NULL,
                mtime INTEGER NOT NULL,
                recording_id TEXT NOT NULL REFERENCES recordings(id),
                track_id TEXT REFERENCES tracks(id),
                format TEXT NOT NULL,
                codec TEXT,
                bitrate INTEGER,
                sample_rate INTEGER,
                channels INTEGER,
                duration_ms INTEGER NOT NULL,
                audio_md5 TEXT,
                cover_id TEXT REFERENCES artwork(id),
                lyrics_mtime INTEGER,
                missing_since INTEGER,
                scanned_at INTEGER NOT NULL,
                UNIQUE (library_id, path)
            );
            CREATE INDEX files_by_recording ON files(recording_id);
            CREATE INDEX files_by_md5 ON files(audio_md5);
            CREATE TABLE lyrics (
                recording_id TEXT NOT NULL REFERENCES recordings(id) ON DELETE CASCADE,
                source TEXT NOT NULL,
                script TEXT NOT NULL,
                synced INTEGER NOT NULL,
                text TEXT NOT NULL,
                PRIMARY KEY (recording_id, source, script)
            );
            CREATE TABLE scans (
                id INTEGER PRIMARY KEY,
                started_at INTEGER NOT NULL,
                finished_at INTEGER,
                info TEXT
            )
        """.trimIndent()

        // One person spelled several ways ("S.P. Balasubrahmanyam", "S.P.Balasubramaniam"): the variants point to
        // the main spelling, which keeps their names as aliases.
        val SCHEMA_V20 = """
            ALTER TABLE people ADD COLUMN merged_into TEXT REFERENCES people(id);
            CREATE INDEX people_by_merged ON people(merged_into)
        """.trimIndent()

        // Jukebox signs people in itself (docs/milestone-2.md). password_enc: the password encrypted with the
        // server's key (Passwords), which Subsonic sign-in needs; NULL while the account still lives in Navidrome.
        val SCHEMA_V21 = """
            ALTER TABLE users ADD COLUMN password_enc TEXT;
            ALTER TABLE users ADD COLUMN is_admin INTEGER NOT NULL DEFAULT 0;
            ALTER TABLE users ADD COLUMN email TEXT
        """.trimIndent()

        // What people do with the music (docs/milestone-2.md). likes: a recording, album (an album view's id) or
        // person. play_counts: songs played to the end (Subsonic scrobbles), like Navidrome's play counts; the
        // detailed plays (how long, skipped, from where) stay in plays. playlists and their songs in order.
        // events: everything that happened, numbered, for the recommendation engine to follow.
        val SCHEMA_V22 = """
            CREATE TABLE likes (
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                item_type TEXT NOT NULL,
                item_id TEXT NOT NULL,
                liked_at INTEGER NOT NULL,
                PRIMARY KEY (user_id, item_type, item_id)
            );
            CREATE INDEX likes_by_item ON likes(item_type, item_id);
            CREATE TABLE play_counts (
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                recording_id TEXT NOT NULL,
                count INTEGER NOT NULL,
                last_played INTEGER NOT NULL,
                PRIMARY KEY (user_id, recording_id)
            );
            CREATE TABLE playlists (
                id TEXT PRIMARY KEY,
                owner_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                name TEXT NOT NULL,
                comment TEXT,
                public INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                changed_at INTEGER NOT NULL
            );
            CREATE INDEX playlists_by_owner ON playlists(owner_id);
            CREATE TABLE playlist_entries (
                playlist_id TEXT NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
                position INTEGER NOT NULL,
                recording_id TEXT NOT NULL,
                added_at INTEGER NOT NULL,
                PRIMARY KEY (playlist_id, position)
            );
            CREATE TABLE events (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER,
                type TEXT NOT NULL,
                at INTEGER NOT NULL,
                payload TEXT NOT NULL
            );
            CREATE INDEX events_by_user ON events(user_id, at)
        """.trimIndent()

        // Old ids from Navidrome (songs, albums, artists, playlists) and the Jukebox ids they became in the import,
        // so a queue saved by the app, a shared link or an old message still finds its song.
        val SCHEMA_V23 = """
            CREATE TABLE id_map (
                old_id TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                new_id TEXT NOT NULL
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
