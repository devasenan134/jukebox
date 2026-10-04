package io.github.devasenan134.jukebox.server

import io.github.devasenan134.jukebox.server.library.AudioTools
import io.github.devasenan134.jukebox.server.library.LibraryDef
import io.github.devasenan134.jukebox.server.library.Scanner
import io.github.devasenan134.jukebox.server.library.TestAudio
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.DriverManager
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ImporterTest {
    private val root = Files.createTempDirectory("jukebox-music").toFile()
    private val data = Files.createTempDirectory("jukebox-data").toFile()
    private val db = Db(File(data, "jukebox.db").path)
    private val passwords = Passwords(File(data, "secret.key"))

    /** How Navidrome stores a password when no PasswordEncryptionKey is set. */
    private fun navidromePassword(password: String): String {
        val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest("just for obfuscation".toByteArray()), "AES")
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce)) }
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(password.toByteArray()))
    }

    private fun sql(path: String, vararg statements: String) = DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
        c.createStatement().use { st -> statements.forEach(st::execute) }
    }

    @Test
    fun `accounts, passwords, likes, plays, playlists and the social data come over, and old ids keep working`() = runBlocking {
        TestAudio.airaa(root, 1, "Kaariga", 300)
        TestAudio.airaa(root, 2, "Megathoodham", 500)
        Scanner(db, AudioTools(), File(data, "artwork"), threads = 2).scan(LibraryDef("tamil", root.path, "film"))
        val kaariga = db.tx { queryOne("SELECT recording_id FROM files WHERE path LIKE '%Kaariga%'") { it.getString(1) } }!!

        // A Navidrome with two users (one admin), a liked and played song, a playlist, and an album.
        val nd = File(data, "navidrome.db").path
        sql(nd,
            "CREATE TABLE user (id TEXT, user_name TEXT, name TEXT, email TEXT, password TEXT, is_admin BOOL, created_at TEXT)",
            "CREATE TABLE media_file (id TEXT, path TEXT, title TEXT, album_id TEXT, duration REAL, missing BOOL)",
            "CREATE TABLE artist (id TEXT, name TEXT)",
            "CREATE TABLE annotation (user_id TEXT, item_id TEXT, item_type TEXT, play_count INT, play_date TEXT, starred BOOL, starred_at TEXT)",
            "CREATE TABLE playlist (id TEXT, name TEXT, comment TEXT, owner_id TEXT, public BOOL, created_at TEXT, updated_at TEXT, uploaded_image TEXT)",
            "CREATE TABLE playlist_tracks (id INT, playlist_id TEXT, media_file_id TEXT)",
            "CREATE TABLE scrobbles (user_id TEXT, media_file_id TEXT, submission_time INT)",
            "INSERT INTO user VALUES ('ndAliceAAAAAAAAAAAAAAA', 'alice', 'Alice', 'a@x', '${navidromePassword("alice-secret")}', 1, '2026-09-01 10:00:00')",
            "INSERT INTO user VALUES ('ndBobBBBBBBBBBBBBBBBBB', 'bob', 'Bob', '', '${navidromePassword("bob-secret")}', 0, '2026-09-01 10:00:00')",
            "INSERT INTO media_file VALUES ('ndSongKaarigaXXXXXXXXX', 'Airaa (2019)/01 - Kaariga.m4a', 'Kaariga', 'ndAlbumAiraaXXXXXXXXXX', 20, 0)",
            "INSERT INTO artist VALUES ('ndArtistSundaramXXXXXX', 'Sundaramurthy K.S.')",
            "INSERT INTO annotation VALUES ('ndBobBBBBBBBBBBBBBBBBB', 'ndSongKaarigaXXXXXXXXX', 'media_file', 3, '2026-09-20 08:00:00', 1, '2026-09-20 08:00:00')",
            "INSERT INTO annotation VALUES ('ndBobBBBBBBBBBBBBBBBBB', 'ndAlbumAiraaXXXXXXXXXX', 'album', 0, NULL, 1, '2026-09-20 08:00:00')",
            "INSERT INTO playlist VALUES ('ndPlaylistRoadXXXXXXXX', 'Road', NULL, 'ndBobBBBBBBBBBBBBBBBBB', 1, '2026-09-20 08:00:00', '2026-09-21 08:00:00', 'ndPlaylistRoadXXXXXXXX_road.jpeg')",
            "INSERT INTO playlist_tracks VALUES (1, 'ndPlaylistRoadXXXXXXXX', 'ndSongKaarigaXXXXXXXXX')",
            "INSERT INTO scrobbles VALUES ('ndBobBBBBBBBBBBBBBBBBB', 'ndSongKaarigaXXXXXXXXX', 1790000000)",
        )
        // Bob gave the playlist a picture; Navidrome keeps it next to its database.
        File(data, "artwork/playlist").mkdirs()
        TestAudio.jpeg(File(data, "artwork/playlist/ndPlaylistRoadXXXXXXXX_road.jpeg"))
        // The old social server: Bob (logged in on a phone) shared the song with Alice in a chat.
        val socialPath = File(data, "social.db").path
        Db(socialPath)
        sql(socialPath,
            "INSERT INTO users (id, username, display_name, created_at, navidrome_id) VALUES (7, 'bob', 'Bobby', 1, 'ndBobBBBBBBBBBBBBBBBBB')",
            "INSERT INTO users (id, username, display_name, created_at) VALUES (8, 'alice', 'Alice', 1)",
            "INSERT INTO sessions VALUES ('hashed-token', 7, 1)",
            "INSERT INTO conversations (id, kind, dm_key, created_by, created_at) VALUES (1, 'dm', '7-8', 7, 1)",
            "INSERT INTO conversation_members (conversation_id, user_id) VALUES (1, 7), (1, 8)",
            """INSERT INTO messages (id, conversation_id, sender_id, body, song_json, created_at) VALUES (1, 1, 7, 'listen',
               '{"id":"ndSongKaarigaXXXXXXXXX","title":"Kaariga","coverArt":"mf-ndSongKaarigaXXXXXXXXX_abc"}', 2)""",
            "INSERT INTO plays (user_id, song_id, at, played_ms, duration_ms, skipped, source) VALUES (7, 'ndSongKaarigaXXXXXXXXX', 3, 1000, 20000, 1, 'album:ndAlbumAiraaXXXXXXXXXX')",
        )

        // (This social database has every Jukebox table, empty: the import must still never touch the catalog.)
        val importer = Importer(db, passwords)
        repeat(2) { // running it again gives the same result
            val report = importer.run(nd, socialPath, null, data)
            assertEquals(2, report.users)
            assertEquals(2, report.passwordsCarried)
            assertEquals(1, report.admins)
        }

        val signIn = SignIn(db, passwords, navidrome = null)
        assertTrue(signIn.checkPassword("bob", "bob-secret"), "Bob's Navidrome password works on Jukebox")
        assertTrue(signIn.checkPassword("alice", "alice-secret"))
        db.tx {
            // Social ids are kept; Bob is still signed in; Alice is an admin.
            assertEquals("Bobby", queryOne("SELECT display_name FROM users WHERE id = 7") { it.getString(1) })
            assertNotNull(queryOne("SELECT 1 FROM sessions WHERE user_id = 7") { 1 })
            assertEquals(1, queryOne("SELECT is_admin FROM users WHERE username = 'alice'") { it.getInt(1) })
            // The shared song and the play name the recording now.
            val shared = queryOne("SELECT song_json FROM messages WHERE id = 1") { it.getString(1) }!!
            assertTrue(kaariga in shared && "ndSong" !in shared, shared)
            assertEquals(kaariga, queryOne("SELECT song_id FROM plays") { it.getString(1) })
            // Likes, play counts, the timed play and the playlist.
            assertEquals(listOf("album", "recording"), query("SELECT item_type FROM likes WHERE user_id = 7 ORDER BY item_type") { it.getString(1) })
            assertEquals(3, queryOne("SELECT count FROM play_counts WHERE user_id = 7 AND recording_id = ?", kaariga) { it.getInt(1) })
            assertEquals(1, queryOne("SELECT count(*) FROM events WHERE type = 'played'") { it.getInt(1) })
            assertEquals(listOf(kaariga), query("SELECT e.recording_id FROM playlist_entries e JOIN playlists p ON p.id = e.playlist_id WHERE p.name = 'Road'") { it.getString(1) })
            assertEquals("playlist", queryOne("SELECT w.source FROM playlists p JOIN artwork w ON w.id = p.cover_id WHERE p.name = 'Road'") { it.getString(1) })
            // The catalog is untouched.
            assertEquals(2, queryOne("SELECT count(*) FROM files") { it.getInt(1) })
            // An old id still finds its song.
            assertEquals(kaariga, queryOne("SELECT new_id FROM id_map WHERE old_id = 'ndSongKaarigaXXXXXXXXX'") { it.getString(1) })
        }
    }
}
