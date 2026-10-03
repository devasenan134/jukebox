package io.github.devasenan134.jukebox.server.subsonic

import io.github.devasenan134.jukebox.server.Navidrome
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
 * Sign-in is still checked with Navidrome in milestone 1 (Navidrome has the passwords). A good check is
 * remembered for a few minutes, so streaming and covers don't ask Navidrome every time.
 */
class SubsonicApi(private val library: SubsonicLibrary, private val navidrome: Navidrome, private val clock: () -> Long = System::currentTimeMillis) {
    private val passed = ConcurrentHashMap<String, Long>()

    class Failure(val code: Int, message: String) : Exception(message)

    private suspend fun signIn(p: Parameters) {
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
        if ((passed[key] ?: 0) > clock()) return
        if (!navidrome.checkLogin(user, salt, token)) throw Failure(40, "Wrong username or password")
        passed[key] = clock() + REMEMBER_MS
        if (passed.size > 10_000) passed.entries.removeIf { it.value < clock() }
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
            signIn(p)
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
                else -> ok(call, answer(name, p))
            }
        } catch (e: Failure) {
            fail(call, e.code, e.message ?: "")
        }
    }

    /** The body of a JSON answer for [name]: the keys that go next to "status". */
    private suspend fun answer(name: String, p: Parameters): Map<String, JsonElement> = when (name) {
        "ping" -> emptyMap()
        "getLicense" -> mapOf("license" to buildJsonObject { put("valid", true) })
        "getOpenSubsonicExtensions" -> mapOf("openSubsonicExtensions" to kotlinx.serialization.json.JsonArray(emptyList()))
        "getMusicFolders" -> mapOf("musicFolders" to buildJsonObject { putJsonArray("musicFolder") {} })
        "getAlbumList2", "getAlbumList" -> mapOf(
            (if (name == "getAlbumList") "albumList" else "albumList2") to buildJsonObject {
                put("album", kotlinx.serialization.json.JsonArray(library.albumList(p.need("type"), p.int("size") ?: 10, p.int("offset") ?: 0, p.int("fromYear"), p.int("toYear"))))
            },
        )
        "getAlbum" -> mapOf("album" to (library.album(p.need("id")) ?: throw Failure(70, "Album not found")))
        "getArtists", "getIndexes" -> mapOf((if (name == "getIndexes") "indexes" else "artists") to library.artists())
        "getArtist" -> mapOf("artist" to (library.artist(p.need("id")) ?: throw Failure(70, "Artist not found")))
        "getSong" -> mapOf("song" to (library.song(p.need("id")) ?: throw Failure(70, "Song not found")))
        "search3", "search2" -> mapOf(
            (if (name == "search2") "searchResult2" else "searchResult3") to library.search(
                p["query"].orEmpty(), p.int("artistCount") ?: 20, p.int("artistOffset") ?: 0, p.int("albumCount") ?: 20,
                p.int("albumOffset") ?: 0, p.int("songCount") ?: 20, p.int("songOffset") ?: 0,
            ),
        )
        "getLyricsBySongId" -> mapOf("lyricsList" to buildJsonObject { put("structuredLyrics", library.lyrics(p.need("id"))) })
        // Likes, plays and playlists come with milestone 2 (docs/milestone-1.md): empty for now.
        "getStarred2", "getStarred" -> mapOf((if (name == "getStarred") "starred" else "starred2") to buildJsonObject {})
        "getPlaylists" -> mapOf("playlists" to buildJsonObject { putJsonArray("playlist") {} })
        "star", "unstar", "scrobble", "getPlaylist", "createPlaylist", "updatePlaylist", "deletePlaylist" ->
            throw Failure(0, "Likes, plays and playlists aren't on Jukebox yet")
        else -> throw Failure(0, "Jukebox doesn't do $name yet")
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
        private const val REMEMBER_MS = 10 * 60_000L

        fun md5(text: String) = MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
