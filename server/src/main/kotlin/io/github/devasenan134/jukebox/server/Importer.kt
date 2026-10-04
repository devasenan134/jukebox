package io.github.devasenan134.jukebox.server

import io.github.devasenan134.jukebox.server.library.Names
import io.github.devasenan134.jukebox.server.library.newId
import io.github.devasenan134.jukebox.server.library.storeArtwork
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class ImportReport(
    val users: Int,
    val passwordsCarried: Int,
    val admins: Int,
    val socialTables: Map<String, Int>,
    val songsMapped: Int,
    val songsNotFound: Int,
    val albumsMapped: Int,
    val peopleMapped: Int,
    val likes: Int,
    val playCounts: Int,
    val plays: Int,
    val playlists: Int,
    val idsReplacedInSocialData: Int,
    val files: Int,
)

/**
 * Brings everything over from the old servers (docs/milestone-2.md): Navidrome's accounts (passwords
 * included), likes, play counts, plays and playlists, and all of the social server's data with its ids.
 * Every Navidrome id is turned into the Jukebox id it became, wherever it appears, and kept in id_map.
 *
 * It reads both old databases read-only and replaces everything a previous import brought (the catalog
 * stays), all in one transaction, so it can be run beside the live servers as often as needed and once
 * more at the switch-over. Needs the library scanned first.
 */
class Importer(private val db: Db, private val passwords: Passwords) {
    private val log = LoggerFactory.getLogger("jukebox.import")

    /**
     * [oldNavidromeDbs]: copies of Navidrome's database from before a library switch, read only to find what
     * their song ids became (plays and shared songs from back then still name them; matched by title,
     * album and length, since the files are gone).
     */
    suspend fun run(
        navidromeDb: String, socialDb: String, socialData: File?, jukeboxData: File, oldNavidromeDbs: List<String> = emptyList(),
        artworkDir: File = File(jukeboxData, "artwork"),
    ): ImportReport {
        // Pictures people gave their playlists, which Navidrome keeps next to its database.
        val playlistPictures = File(File(navidromeDb).absoluteFile.parentFile, "artwork/playlist")
        val nd = DriverManager.getConnection("jdbc:sqlite:file:$navidromeDb?mode=ro")
        val social = DriverManager.getConnection("jdbc:sqlite:file:$socialDb?mode=ro")
        val old = oldNavidromeDbs.map { DriverManager.getConnection("jdbc:sqlite:file:$it?mode=ro") }
        try {
            val report = db.tx { import(nd, social, old, playlistPictures, artworkDir) }
            val files = socialData?.let { copyFiles(it, jukeboxData) } ?: 0
            return report.copy(files = files).also { log.info("Import: {}", it) }
        } finally {
            nd.close()
            social.close()
            old.forEach { it.close() }
        }
    }

    private fun Connection.import(nd: Connection, social: Connection, old: List<Connection>, playlistPictures: File, artworkDir: File): ImportReport {
        // ---- what each Navidrome id became ----
        val byPath = query("SELECT path, recording_id FROM files") { it.getString(1) to it.getString(2) }.toMap()
        val byTitle = query("SELECT id, title_key, duration_ms FROM recordings WHERE merged_into IS NULL") { Triple(it.getString(1), it.getString(2), it.getLong(3)) }
            .groupBy { it.second }
        val albumOf = query("SELECT r.id, CASE WHEN rl.kind = 'score' THEN rl.id ELSE rl.album_id END FROM recordings r JOIN tracks t ON t.recording_id = r.id JOIN releases rl ON rl.id = t.release_id") {
            it.getString(1) to it.getString(2)
        }.toMap()
        fun resolve(id: String): String {
            var current = id
            repeat(10) { current = queryOne("SELECT merged_into FROM recordings WHERE id = ?", current) { it.getString(1) } ?: return current }
            return current
        }
        val songs = HashMap<String, String>()
        val ndAlbumSongs = HashMap<String, MutableList<String>>()
        var notFound = 0
        for (source in listOf(nd) + old) source.query("SELECT id, path, title, album_id, duration FROM media_file") { rs ->
            if (rs.getString(1) in songs) return@query
            val title = rs.getString(3).orEmpty()
            // By the path both keep (the same music folder); else (an older library) by title and length.
            val rec = byPath[rs.getString(2)]
                ?: byTitle[io.github.devasenan134.jukebox.server.Fuzzy.key(Names.version(title).first)]
                    ?.minByOrNull { kotlin.math.abs(it.third - (rs.getDouble(5) * 1000).toLong()) }
                    ?.takeIf { kotlin.math.abs(it.third - (rs.getDouble(5) * 1000).toLong()) <= 3000 }?.first
            if (rec != null) {
                songs[rs.getString(1)] = resolve(rec)
                ndAlbumSongs.getOrPut(rs.getString(4).orEmpty()) { mutableListOf() } += resolve(rec)
            } else if (source === nd) notFound++
        }
        val albums = ndAlbumSongs.mapNotNull { (ndAlbum, recs) -> recs.firstNotNullOfOrNull { albumOf[it] }?.let { ndAlbum to it } }.toMap()
        val peopleByKey = query("SELECT coalesce(merged_into, id), sound_key FROM people") { it.getString(2) to it.getString(1) }.toMap()
        val people = (listOf(nd) + old).flatMap { c ->
            c.query("SELECT id, name FROM artist") { rs -> peopleByKey[Names.personKey(rs.getString(2).orEmpty())]?.let { rs.getString(1) to it } }.filterNotNull()
        }.toMap()
        val playlistIds = nd.query("SELECT id FROM playlist") { it.getString(1) }.associateWith { newId() }
        val idMap: Map<String, String> = songs + albums + people + playlistIds

        // ---- clear what a previous import brought (the catalog stays) ----
        val ourTables = query("SELECT name FROM sqlite_master WHERE type = 'table'") { it.getString(1) }.toSet()
        // Only the social server's own tables, by name: never the catalog or anything else that's Jukebox's.
        val theirs = social.query("SELECT name FROM sqlite_master WHERE type = 'table'") { it.getString(1) }.toSet()
        val copyOrder = SOCIAL_TABLES.filter { it in theirs && it in ourTables }
        for (t in (copyOrder + listOf("likes", "play_counts", "playlist_entries", "playlists", "events", "id_map", "suggestions", "suggestion_lists")).reversed().distinct()) {
            if (t in ourTables) update("DELETE FROM $t")
        }

        // ---- the social server's data, ids kept, Navidrome ids replaced ----
        var replaced = 0
        val token = Regex("""(?<![A-Za-z0-9])[A-Za-z0-9]{22}(?![A-Za-z0-9])""")
        fun remap(v: Any?): Any? = if (v is String && v.length >= 22) token.replace(v) { m -> idMap[m.value]?.also { replaced++ } ?: m.value } else v
        val counts = LinkedHashMap<String, Int>()
        for (t in copyOrder) {
            val ours = query("PRAGMA table_info($t)") { it.getString(2) }.toSet()
            val cols = social.query("PRAGMA table_info($t)") { it.getString(2) }.filter { it in ours }
            val rows = social.query("SELECT ${cols.joinToString(",")} FROM $t") { rs -> cols.indices.map { remap(rs.getObject(it + 1)) } }
            // Two old ids can become one (two copies of a song merged into one recording): the first row stays.
            counts[t] = rows.sumOf { r -> update("INSERT OR IGNORE INTO $t (${cols.joinToString(",")}) VALUES (${cols.joinToString(",") { "?" }})", *r.toTypedArray()) }
        }
        // Mixes and their saved song hashes are worked out again from the new ids.
        if ("mix_state" in ourTables) update("DELETE FROM mix_state")

        // ---- Navidrome's accounts: passwords (re-encrypted with Jukebox's key), admin flag, email ----
        val ndKey = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(NAVIDROME_DEFAULT_KEY.toByteArray()), "AES")
        val userOf = HashMap<String, Long>()
        var carried = 0
        var admins = 0
        nd.query("SELECT id, user_name, name, email, password, is_admin, created_at FROM user") { rs ->
            listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6), rs.getString(7))
        }.forEach { u ->
            val (ndId, userName, name, email, enc) = u.map { it as? String }
            val admin = u[5] as Boolean
            val id = queryOne("SELECT id FROM users WHERE navidrome_id = ?", ndId) { it.getLong(1) }
                ?: queryOne("SELECT id FROM users WHERE username = ? COLLATE NOCASE", userName) { it.getLong(1) }
                ?: insert("INSERT INTO users (username, display_name, created_at, navidrome_id) VALUES (?, ?, ?, ?)",
                    userName, name?.takeIf { it.isNotBlank() } ?: userName, System.currentTimeMillis(), ndId)
            val plain = enc?.let { decryptNavidrome(ndKey, it) }
            if (plain != null) carried++
            if (admin) admins++
            update("UPDATE users SET password_enc = coalesce(?, password_enc), is_admin = ?, email = coalesce(?, email), navidrome_id = coalesce(navidrome_id, ?) WHERE id = ?",
                plain?.let(passwords::encrypt), if (admin) 1 else 0, email?.takeIf { it.isNotBlank() }, ndId, id)
            userOf[ndId!!] = id
        }

        // ---- likes, play counts, plays, playlists ----
        var likes = 0
        var playCounts = 0
        nd.query("SELECT user_id, item_id, item_type, play_count, play_date, starred, starred_at FROM annotation") { rs ->
            listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5), rs.getBoolean(6), rs.getString(7))
        }.forEach { a ->
            val user = userOf[a[0] as String] ?: return@forEach
            val type = a[2] as String
            val target = when (type) { "media_file" -> songs; "album" -> albums; "artist" -> people; else -> emptyMap() }[a[1] as String] ?: return@forEach
            if (a[5] as Boolean) {
                likes += update("INSERT OR IGNORE INTO likes VALUES (?, ?, ?, ?)", user,
                    mapOf("media_file" to "recording", "album" to "album", "artist" to "person").getValue(type), target, time(a[6] as String?) ?: 0)
            }
            if (type == "media_file" && (a[3] as Int) > 0) {
                playCounts += update(
                    """INSERT INTO play_counts VALUES (?, ?, ?, ?) ON CONFLICT (user_id, recording_id)
                       DO UPDATE SET count = count + excluded.count, last_played = max(last_played, excluded.last_played)""",
                    user, target, a[3] as Int, time(a[4] as String?) ?: 0,
                )
            }
        }
        // Each play with its time (Navidrome 0.58+), as "played" events, oldest first.
        val plays = runCatching {
            nd.query("SELECT user_id, media_file_id, submission_time FROM scrobbles ORDER BY submission_time") { rs ->
                Triple(userOf[rs.getString(1)], songs[rs.getString(2)], rs.getLong(3) * 1000)
            }
        }.getOrDefault(emptyList()).filter { it.first != null && it.second != null }
        for ((user, rec, at) in plays) update("INSERT INTO events (user_id, type, at, payload) VALUES (?, 'played', ?, ?)", user, at, """{"recording":"$rec","imported":true}""")
        var playlists = 0
        nd.query("SELECT id, name, comment, owner_id, public, created_at, updated_at FROM playlist") { rs ->
            listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getString(6), rs.getString(7))
        }.forEach { p ->
            val owner = userOf[p[3] as String] ?: return@forEach
            val id = playlistIds.getValue(p[0] as String)
            update("INSERT INTO playlists (id, owner_id, name, comment, public, created_at, changed_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, owner, p[1], p[2], if (p[4] as Boolean) 1 else 0, time(p[5] as String?) ?: 0, time(p[6] as String?) ?: 0)
            nd.query("SELECT media_file_id FROM playlist_tracks WHERE playlist_id = ? ORDER BY id", p[0]) { songs[it.getString(1)] }
                .filterNotNull().forEachIndexed { i, rec -> update("INSERT INTO playlist_entries VALUES (?, ?, ?, ?)", id, i, rec, time(p[6] as String?) ?: 0) }
            // Its own picture, if one was uploaded (Navidrome versions before uploaded_image had none).
            runCatching { nd.queryOne("SELECT uploaded_image FROM playlist WHERE id = ?", p[0]) { it.getString(1) } }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { File(playlistPictures, it) }?.takeIf { it.isFile }
                ?.let { update("UPDATE playlists SET cover_id = ? WHERE id = ?", storeArtwork(artworkDir, it.readBytes(), "playlist"), id) }
            playlists++
        }

        // ---- the map, for old ids that turn up later ----
        for ((kind, map) in listOf("song" to songs, "album" to albums, "person" to people, "playlist" to playlistIds)) {
            map.forEach { (old, new) -> update("INSERT OR REPLACE INTO id_map VALUES (?, ?, ?)", old, kind, new) }
        }
        return ImportReport(
            users = query("SELECT count(*) FROM users WHERE deleted_at IS NULL") { it.getInt(1) }.first(),
            passwordsCarried = carried, admins = admins, socialTables = counts, songsMapped = songs.size, songsNotFound = notFound,
            albumsMapped = albums.size, peopleMapped = people.size, likes = likes, playCounts = playCounts, plays = plays.size,
            playlists = playlists, idsReplacedInSocialData = replaced, files = 0,
        )
    }

    /** Chat photos, voice notes, avatars and group pictures sit next to the social server's database. */
    private fun copyFiles(from: File, to: File): Int {
        var n = 0
        from.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                val target = File(to, f.relativeTo(from).path)
                if (!target.exists() || target.length() != f.length()) {
                    target.parentFile.mkdirs()
                    f.copyTo(target, overwrite = true)
                    n++
                }
            }
        }
        return n
    }

    private fun decryptNavidrome(key: SecretKeySpec, stored: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(stored)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12)) }
        String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }.getOrNull()

    /** Navidrome's times: "2026-09-23 04:48:32.098666609+00:00" (UTC). */
    private fun time(text: String?): Long? = text?.takeIf { it.length >= 19 }?.let {
        runCatching {
            java.time.LocalDateTime.parse(it.substring(0, 19).replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    }

    private companion object {
        /** The old social server's tables, in an order that respects their references. (mix_state isn't
         * copied: mixes are worked out again from the new ids.) */
        val SOCIAL_TABLES = listOf(
            "users", "sessions", "invites", "friend_requests", "friendships", "conversations", "conversation_members", "messages",
            "pins", "reactions", "devices", "plays", "followed_mixes", "liked_playlists", "music_requests", "music_request_askers",
            "suggestion_lists", "suggestions",
        )

        /** Navidrome's built-in key (consts.DefaultEncryptionKey), used when no PasswordEncryptionKey is set. */
        const val NAVIDROME_DEFAULT_KEY = "just for obfuscation"
    }
}
