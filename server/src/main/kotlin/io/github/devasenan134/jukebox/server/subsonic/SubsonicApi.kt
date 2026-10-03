package io.github.devasenan134.jukebox.server.subsonic

import io.github.devasenan134.jukebox.server.ApiError
import io.github.devasenan134.jukebox.server.library.Listening
import io.github.devasenan134.jukebox.server.library.Personal
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * The Subsonic API (the part the Jukebox app and web app use, docs/milestone-1.md), at /rest/<call> and
 * /rest/<call>.view, answered in JSON.
 *
 * Sign-in is checked by SignIn (Jukebox's own passwords, or Navidrome's for an account not imported yet).
 * A good check is remembered for a few minutes, so streaming and covers don't decrypt it every time.
 */
class SubsonicApi(
    private val library: SubsonicLibrary,
    /** Whether token = md5(password + salt) for the user ([io.github.devasenan134.jukebox.server.SignIn]). */
    private val checkToken: suspend (username: String, salt: String, token: String) -> Boolean,
    /** The account a username signs in with. */
    private val userId: suspend (username: String) -> Long,
    private val listening: Listening,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val passed = ConcurrentHashMap<String, Long>()
    private val ids = ConcurrentHashMap<String, Pair<Long, Long>>()

    /** The account's id, remembered as long as its sign-in is. */
    private suspend fun idOf(username: String): Long =
        ids[username]?.takeIf { it.second > clock() }?.first ?: userId(username).also { ids[username] = it to clock() + REMEMBER_MS }

    class Failure(val code: Int, message: String) : Exception(message)

    private suspend fun signIn(p: Parameters): String {
        val user = p["u"] ?: throw Failure(10, "Required parameter is missing: u")
        val (salt, token) = when {
            p["t"] != null && p["s"] != null -> p["s"]!! to p["t"]!!
            p["p"] != null -> {
                val password = p["p"]!!.let { if (it.startsWith("enc:")) String(it.removePrefix("enc:").chunked(2).map { h -> h.toInt(16).toByte() }.toByteArray()) else it }
                val salt = ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
                salt to md5(password + salt)
            }
            else -> throw Failure(10, "Required parameter is missing: t and s, or p")
        }
        val key = "$user|$salt|$token"
        if ((passed[key] ?: 0) > clock()) return user
        if (!checkToken(user, salt, token)) throw Failure(40, "Wrong username or password")
        passed[key] = clock() + REMEMBER_MS
        if (passed.size > 10_000) passed.entries.removeIf { it.value < clock() }
        return user
    }

    fun routes(route: Route) = route.route("/rest") {
        install(PartialContent)
        for (path in listOf("{call}", "{call}.view")) {
            get(path) { handle(call) }
            post(path) { handle(call) }
        }
    }

    private suspend fun handle(call: ApplicationCall) {
        val name = call.parameters["call"].orEmpty().removeSuffix(".view")
        val p = if (call.request.httpMethod.value == "POST") Parameters.build {
            appendAll(call.request.queryParameters)
            runCatching { call.receiveParameters() }.getOrNull()?.let { appendAll(it) }
        } else call.request.queryParameters
        try {
            val username = signIn(p)
            when (name) {
                "stream", "download" -> {
                    val (file, type) = library.audio(p.need("id")) ?: throw Failure(70, "Song not found")
                    call.respond(LocalFileContent(file, ContentType.parse(type)))
                }
                "getCoverArt" -> {
                    val (file, type) = library.cover(p.need("id"), p["size"]?.toIntOrNull()) ?: throw Failure(70, "Cover not found")
                    // A cover's id names one image for good: browsers can keep it.
                    call.response.headers.append(HttpHeaders.CacheControl, "private, max-age=604800, immutable")
                    call.respond(LocalFileContent(file, ContentType.parse(type)))
                }
                else -> ok(call, answer(name, p, idOf(username)))
            }
        } catch (e: Failure) {
            fail(call, e.code, e.message ?: "")
        }
    }

    /** The body of a JSON answer for [name]: the keys that go next to "status". */
    private suspend fun answer(name: String, p: Parameters, user: Long): Map<String, JsonElement> {
        // Your likes and play counts, to mark songs and albums (not needed for calls that only change things).
        val me = if (name in CHANGES) Personal.NOBODY else listening.personal(user)
        return answer(name, p, user) { me }
    }

    private suspend fun answer(name: String, p: Parameters, user: Long, me: () -> Personal): Map<String, JsonElement> = when (name) {
        "ping" -> emptyMap()
        "getLicense" -> mapOf("license" to buildJsonObject { put("valid", true) })
        "getOpenSubsonicExtensions" -> mapOf("openSubsonicExtensions" to kotlinx.serialization.json.JsonArray(emptyList()))
        "getMusicFolders" -> mapOf("musicFolders" to buildJsonObject { putJsonArray("musicFolder") {} })
        "getAlbumList2", "getAlbumList" -> mapOf(
            (if (name == "getAlbumList") "albumList" else "albumList2") to buildJsonObject {
                put("album", kotlinx.serialization.json.JsonArray(library.albumList(p.need("type"), p.int("size") ?: 10, p.int("offset") ?: 0, p.int("fromYear"), p.int("toYear"), me())))
            },
        )
        "getAlbum" -> mapOf("album" to (library.album(p.need("id"), me()) ?: throw Failure(70, "Album not found")))
        "getArtists", "getIndexes" -> mapOf((if (name == "getIndexes") "indexes" else "artists") to library.artists())
        "getArtist" -> mapOf("artist" to (library.artist(p.need("id"), me()) ?: throw Failure(70, "Artist not found")))
        "getSong" -> mapOf("song" to (library.song(p.need("id"), me()) ?: throw Failure(70, "Song not found")))
        "search3", "search2" -> mapOf(
            (if (name == "search2") "searchResult2" else "searchResult3") to library.search(
                p["query"].orEmpty(), p.int("artistCount") ?: 20, p.int("artistOffset") ?: 0, p.int("albumCount") ?: 20,
                p.int("albumOffset") ?: 0, p.int("songCount") ?: 20, p.int("songOffset") ?: 0, me(),
            ),
        )
        "getLyricsBySongId" -> mapOf("lyricsList" to buildJsonObject { put("structuredLyrics", library.lyrics(p.need("id"))) })
        // Likes, plays and playlists (docs/milestone-2.md).
        "getStarred2", "getStarred" -> mapOf((if (name == "getStarred") "starred" else "starred2") to library.starred(me()))
        "star", "unstar" -> {
            listening.like(user, p.getAll("id").orEmpty(), p.getAll("albumId").orEmpty(), p.getAll("artistId").orEmpty(), liked = name == "star")
            emptyMap()
        }
        "scrobble" -> {
            // submission=false only says what's playing now; a play counts when it's submitted.
            if (p["submission"]?.lowercase() != "false") {
                val times = p.getAll("time").orEmpty().mapNotNull { it.toLongOrNull() }
                p.getAll("id").orEmpty().forEachIndexed { i, id -> listening.played(user, id, times.getOrNull(i) ?: clock()) }
            }
            emptyMap()
        }
        "getPlaylists" -> mapOf("playlists" to buildJsonObject {
            put("playlist", kotlinx.serialization.json.JsonArray(listening.playlists(user).map { library.playlistJson(it, me(), withSongs = false) }))
        })
        "getPlaylist" -> mapOf("playlist" to library.playlistJson(playlist { listening.playlist(user, p.need("id")) }, me(), withSongs = true))
        "createPlaylist" -> {
            val songs = p.getAll("songId").orEmpty()
            val made = playlist {
                p["playlistId"]?.let { listening.replace(user, it, songs) } ?: listening.create(user, p.need("name"), songs)
            }
            mapOf("playlist" to library.playlistJson(made, me(), withSongs = true))
        }
        "updatePlaylist" -> {
            playlist {
                listening.update(user, p.need("playlistId"), p["name"], p["comment"], p["public"]?.toBooleanStrictOrNull(),
                    p.getAll("songIdToAdd").orEmpty(), p.getAll("songIndexToRemove").orEmpty().mapNotNull { it.toIntOrNull() })
            }
            emptyMap()
        }
        "deletePlaylist" -> {
            playlist { listening.delete(user, p.need("id")) }
            emptyMap()
        }
        else -> throw Failure(0, "Jukebox doesn't do $name yet")
    }

    /** Playlist errors as Subsonic errors: 70 not found, 50 not allowed. */
    private suspend fun <T> playlist(block: suspend () -> T): T = try {
        block()
    } catch (e: ApiError) {
        throw Failure(if (e.status == HttpStatusCode.Forbidden) 50 else 70, e.message)
    }

    private suspend fun ok(call: ApplicationCall, body: Map<String, JsonElement>) =
        respond(call, buildJsonObject { header("ok"); body.forEach { (k, v) -> put(k, v) } })

    private suspend fun fail(call: ApplicationCall, code: Int, message: String) =
        respond(call, buildJsonObject { header("failed"); putJsonObject("error") { put("code", code); put("message", message) } })

    private fun kotlinx.serialization.json.JsonObjectBuilder.header(status: String) {
        put("status", status)
        put("version", VERSION)
        put("type", "jukebox")
        put("serverVersion", "0.1.0")
        put("openSubsonic", true)
    }

    private suspend fun respond(call: ApplicationCall, body: JsonObject) =
        call.respondText(JsonObject(mapOf("subsonic-response" to body)).toString(), ContentType.Application.Json, HttpStatusCode.OK)

    private fun Parameters.need(name: String) = this[name]?.takeIf { it.isNotEmpty() } ?: throw Failure(10, "Required parameter is missing: $name")
    private fun Parameters.int(name: String) = this[name]?.toIntOrNull()

    companion object {
        const val VERSION = "1.16.1"
        private val CHANGES = setOf("ping", "star", "unstar", "scrobble", "updatePlaylist", "deletePlaylist")
        private const val REMEMBER_MS = 10 * 60_000L

        fun md5(text: String) = MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
