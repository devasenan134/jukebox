package io.github.devasenan134.jukebox.server

import io.github.devasenan134.jukebox.server.library.Listening
import io.github.devasenan134.jukebox.server.library.storeArtwork
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import java.io.File

/** An uploaded picture: the app sends it already cropped and shrunk (a square JPEG, about 100 KB). */
class Picture(val bytes: ByteArray) {
    val type: ContentType = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> ContentType.Image.JPEG
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG) -> ContentType.Image.PNG
        bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WEBP" -> ContentType("image", "webp")
        else -> throw ApiError(HttpStatusCode.BadRequest, "That isn't a JPEG, PNG or WebP picture")
    }

    init {
        if (bytes.size > MAX_BYTES) throw ApiError(HttpStatusCode.PayloadTooLarge, "The picture is too big")
    }

    val extension get() = type.contentSubtype

    companion object {
        const val MAX_BYTES = 900 * 1024
        private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}

/**
 * A photo, GIF or sticker sent in a chat. Photos come already shrunk by the app; GIFs and stickers
 * (from the keyboard) are kept as they are, so they can be bigger.
 */
class ChatImage(val bytes: ByteArray, val kind: String, val width: Int, val height: Int) {
    val extension: String = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "jpeg"
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && String(bytes, 1, 3) == "PNG" -> "png"
        bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WEBP" -> "webp"
        bytes.size >= 6 && String(bytes, 0, 6).let { it == "GIF87a" || it == "GIF89a" } -> "gif"
        else -> throw ApiError(HttpStatusCode.BadRequest, "That isn't a JPEG, PNG, WebP or GIF picture")
    }

    init {
        if (kind !in KINDS) throw ApiError(HttpStatusCode.BadRequest, "Send a photo, GIF or sticker")
        if (width !in 1..20_000 || height !in 1..20_000) throw ApiError(HttpStatusCode.BadRequest, "Say how big the picture is")
        if (bytes.size > MAX_BYTES) throw ApiError(HttpStatusCode.PayloadTooLarge, "The picture is too big (5 MB at most)")
    }

    companion object {
        const val MAX_BYTES = 5 * 1024 * 1024
        val KINDS = setOf("photo", "gif", "sticker")
    }
}

/** A voice message: an AAC recording in an MP4 (.m4a) file, as Android's recorder makes it. */
class VoiceNote(val bytes: ByteArray, val durationMs: Long) {
    init {
        if (bytes.size < 12 || String(bytes, 4, 4) != "ftyp") throw ApiError(HttpStatusCode.BadRequest, "That isn't a voice recording")
        if (durationMs !in 300..MAX_MS) throw ApiError(HttpStatusCode.BadRequest, "A voice message is up to 5 minutes long")
        if (bytes.size > ChatImage.MAX_BYTES) throw ApiError(HttpStatusCode.PayloadTooLarge, "The recording is too big")
    }

    companion object {
        /** 5 minutes, and a little for the recorder stopping late. */
        const val MAX_MS = 5 * 60_000L + 5_000
    }
}

/** Picture files kept next to the database, one per id, in the folder [name] (e.g. "avatars"). */
class PictureFolder(dbPath: String, name: String) {
    private val dir = File(File(dbPath).absoluteFile.parentFile, name).apply { mkdirs() }

    fun save(id: Long, picture: Picture) = save(id, picture.bytes, picture.extension)

    fun save(id: Long, bytes: ByteArray, extension: String) {
        remove(id)
        // Write next to it, then swap, so nobody ever gets half a picture.
        File(dir, "$id.tmp").apply { writeBytes(bytes) }.renameTo(File(dir, "$id.$extension"))
    }

    /** Removes the whole folder (a deleted chat's pictures). */
    fun removeAll() = dir.deleteRecursively()

    fun remove(id: Long) = files(id).forEach { it.delete() }

    /** The picture of [id], or null if there's none. */
    fun get(id: Long): File? = files(id).firstOrNull()

    private fun files(id: Long) = dir.listFiles { f -> f.nameWithoutExtension == id.toString() && f.extension != "tmp" }.orEmpty().toList()
}

/**
 * Profile pictures (kept as files next to the database, one per person) and playlist covers (with the rest of
 * the artwork).
 */
class Pictures(
    private val db: Db,
    dbPath: String,
    /** The playlists and the artwork folder; without a music library there are no playlists to give covers. */
    private val listening: Listening? = null,
    private val artworkDir: File? = null,
) {
    private val avatars = PictureFolder(dbPath, "avatars")

    suspend fun setAvatar(userId: Long, picture: Picture): UserDto {
        avatars.save(userId, picture)
        return db.tx {
            update("UPDATE users SET avatar_at = ? WHERE id = ?", now(), userId)
            queryOne("SELECT * FROM users WHERE id = ?", userId) { it.toUser() }!!
        }
    }

    suspend fun removeAvatar(userId: Long): UserDto {
        avatars.remove(userId)
        return db.tx {
            update("UPDATE users SET avatar_at = NULL WHERE id = ?", userId)
            queryOne("SELECT * FROM users WHERE id = ?", userId) { it.toUser() }!!
        }
    }

    /** The picture file of [userId], or null if they have none. */
    fun avatar(userId: Long): File? = avatars.get(userId)

    /** Sets (or with null, removes) the cover of a playlist [user] made. */
    suspend fun setPlaylistCover(user: UserDto, playlistId: String, picture: Picture?) {
        if (listening == null || artworkDir == null || !listening.exists(playlistId)) throw ApiError(HttpStatusCode.NotFound, "Playlist not found")
        listening.setCover(user.id, playlistId) { picture?.let { storeArtwork(artworkDir, it.bytes, "playlist") } }
    }
}
