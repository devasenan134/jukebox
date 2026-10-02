package io.github.devasenan134.jukebox.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLPathPart
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The two things the companion server needs from Navidrome: checking logins and creating users. */
open class Navidrome(private val config: Config) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
    }

    /** True if the Subsonic token login works, i.e. the user proved they know their Navidrome password. */
    open suspend fun checkLogin(username: String, salt: String, token: String): Boolean {
        val response = http.get("${config.navidromeUrl}/rest/ping") {
            parameter("u", username)
            parameter("s", salt)
            parameter("t", token)
            parameter("v", "1.16.1")
            parameter("c", "jukebox")
            parameter("f", "json")
        }
        if (!response.status.isSuccess()) return false
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject["subsonic-response"]?.jsonObject
        return body?.get("status")?.jsonPrimitive?.content == "ok"
    }

    /** Creates a normal (non-admin) Navidrome user and returns its permanent id. */
    open suspend fun createUser(username: String, displayName: String, password: String): String? {
        if (config.navidromeAdminUser.isBlank()) throw ApiError(HttpStatusCode.ServiceUnavailable, "Sign-up isn't set up on this server yet")
        val response = adminCall { token ->
            http.post("${config.navidromeUrl}/api/user") {
                header("X-ND-Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(NewUser(userName = username, name = displayName, password = password))
            }
        }
        if (!response.status.isSuccess()) {
            val text = response.bodyAsText()
            if ("unique" in text.lowercase() || "exists" in text.lowercase()) {
                throw ApiError(HttpStatusCode.Conflict, "That username is taken")
            }
            throw ApiError(HttpStatusCode.BadGateway, "Navidrome refused to create the user (${response.status.value})")
        }
        return runCatching { response.body<JsonObject>()["id"]?.jsonPrimitive?.content }.getOrNull()
    }

    /** Everyone in Navidrome (permanent id + current username), or null if the list couldn't be fetched. */
    open suspend fun users(): List<NavidromeUser>? {
        if (config.navidromeAdminUser.isBlank()) return null
        return runCatching {
            val response = adminCall { token ->
                http.get("${config.navidromeUrl}/api/user") {
                    header("X-ND-Authorization", "Bearer $token")
                    parameter("_start", 0)
                    parameter("_end", 10_000)
                }
            }
            if (!response.status.isSuccess()) return null
            response.body<List<JsonObject>>().mapNotNull { u ->
                val id = u["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val name = u["userName"]?.jsonPrimitive?.content ?: return@mapNotNull null
                NavidromeUser(id, name)
            }
        }.getOrNull()
    }

    /** Who made a playlist (their Navidrome id), or null if there's no such playlist. */
    open suspend fun playlistOwner(playlistId: String): String? {
        requireAdmin()
        val response = adminCall { token -> http.get("${config.navidromeUrl}/api/playlist/${playlistId.encodeURLPathPart()}") { header("X-ND-Authorization", "Bearer $token") } }
        if (!response.status.isSuccess()) return null
        return runCatching { response.body<JsonObject>()["ownerId"]?.jsonPrimitive?.content }.getOrNull()
    }

    /** Uploads a playlist's cover (Navidrome keeps it; getCoverArt then shows it). */
    open suspend fun setPlaylistImage(playlistId: String, picture: Picture) {
        requireAdmin()
        val response = adminCall { token ->
            http.submitFormWithBinaryData(
                "${config.navidromeUrl}/api/playlist/${playlistId.encodeURLPathPart()}/image",
                formData {
                    append("image", picture.bytes, Headers.build {
                        append(HttpHeaders.ContentType, picture.type.toString())
                        append(HttpHeaders.ContentDisposition, "filename=\"cover.${picture.extension}\"")
                    })
                },
            ) { header("X-ND-Authorization", "Bearer $token") }
        }
        if (!response.status.isSuccess()) throw ApiError(HttpStatusCode.BadGateway, "Navidrome didn't take the cover (${response.status.value})")
    }

    /** Back to the automatic cover made from the playlist's songs. */
    open suspend fun removePlaylistImage(playlistId: String) {
        requireAdmin()
        val response = adminCall { token ->
            http.delete("${config.navidromeUrl}/api/playlist/${playlistId.encodeURLPathPart()}/image") { header("X-ND-Authorization", "Bearer $token") }
        }
        if (!response.status.isSuccess()) throw ApiError(HttpStatusCode.BadGateway, "Navidrome didn't remove the cover (${response.status.value})")
    }

    private fun requireAdmin() {
        if (config.navidromeAdminUser.isBlank()) throw ApiError(HttpStatusCode.ServiceUnavailable, "Playlist covers aren't set up on this server yet")
    }

    /** Navidrome's permanent id for [username], or null if unknown / not available. */
    suspend fun idFor(username: String): String? = users()?.firstOrNull { it.userName.equals(username, ignoreCase = true) }?.id

    /**
     * Runs an admin request, logging the bot in only when needed. Navidrome rate-limits logins,
     * so the admin token is reused (and refreshed if Navidrome says it expired).
     */
    private suspend fun adminCall(request: suspend (String) -> HttpResponse): HttpResponse {
        val cached = adminToken
        if (cached != null) {
            val response = request(cached)
            if (response.status != HttpStatusCode.Unauthorized) return response
        }
        return request(adminLogin().also { adminToken = it })
    }

    @Volatile private var adminToken: String? = null

    private suspend fun adminLogin(): String {
        val response = http.post("${config.navidromeUrl}/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(AdminLogin(config.navidromeAdminUser, config.navidromeAdminPassword))
        }
        if (!response.status.isSuccess()) throw ApiError(HttpStatusCode.BadGateway, "The server's Navidrome admin login failed")
        return response.body<JsonObject>()["token"]?.jsonPrimitive?.content
            ?: throw ApiError(HttpStatusCode.BadGateway, "Navidrome didn't return an admin token")
    }

    @Serializable private data class AdminLogin(val username: String, val password: String)

    data class NavidromeUser(val id: String, val userName: String)

    @Serializable
    private data class NewUser(
        val userName: String,
        val name: String,
        val password: String,
        val email: String = "",
        val isAdmin: Boolean = false,
    )
}
