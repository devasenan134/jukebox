package io.github.devasenan134.jukebox.server

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Keeps passwords encrypted (AES-256-GCM) with a key only this server has.
 *
 * Why not a one-way hash: Subsonic players sign in with token = md5(password + salt) and a new salt each
 * time, so the server must be able to work out md5(password + salt) itself, which needs the password. This
 * is how Navidrome does it too; the difference is the key: Jukebox makes its own on first start and keeps it
 * in [keyFile], readable only by the server's user (docs/milestone-2.md).
 */
class Passwords(private val keyFile: File) {
    private val random = SecureRandom()
    private val key: SecretKeySpec by lazy { SecretKeySpec(loadOrCreateKey(), "AES") }

    fun encrypt(password: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return "v1:" + Base64.getEncoder().encodeToString(iv + cipher.doFinal(password.toByteArray()))
    }

    fun decrypt(stored: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(stored.removePrefix("v1:"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12)) }
        String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }.getOrNull()

    private fun loadOrCreateKey(): ByteArray {
        if (keyFile.isFile) return Base64.getDecoder().decode(keyFile.readText().trim())
        keyFile.parentFile?.mkdirs()
        val bytes = ByteArray(32).also(random::nextBytes)
        keyFile.writeText(Base64.getEncoder().encodeToString(bytes) + "\n")
        runCatching { Files.setPosixFilePermissions(keyFile.toPath(), PosixFilePermissions.fromString("rw-------")) }
        return bytes
    }

    companion object {
        fun md5(text: String): String = MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        /** The same text either way, in time that doesn't depend on where they differ. */
        fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}

/**
 * The one place that decides whether someone is who they say they are: the app's login, the Subsonic API
 * and password changes all ask here.
 *
 * An account with a Jukebox password is checked here. One without (it still lives in Navidrome, before the
 * import) is checked with Navidrome, so Jukebox works beside the old servers until the switch-over.
 */
class SignIn(private val db: Db, private val passwords: Passwords, private val navidrome: Navidrome?) {
    /** Whether token = md5(password + salt) for [username]'s password. */
    suspend fun checkToken(username: String, salt: String, token: String): Boolean {
        val stored = storedPassword(username)
        if (stored != null) return passwords.decrypt(stored)?.let { Passwords.same(Passwords.md5(it + salt), token.lowercase()) } == true
        return navidrome?.checkLogin(username, salt, token) == true
    }

    suspend fun checkPassword(username: String, password: String): Boolean {
        val salt = Passwords.md5(System.nanoTime().toString()).take(12)
        return checkToken(username, salt, Passwords.md5(password + salt))
    }

    suspend fun setPassword(userId: Long, password: String) = db.tx {
        update("UPDATE users SET password_enc = ? WHERE id = ?", passwords.encrypt(password), userId)
    }

    /** Whether [username] signs in with Jukebox (true) or still with Navidrome. */
    suspend fun hasPassword(username: String) = storedPassword(username) != null

    private suspend fun storedPassword(username: String): String? = db.tx {
        queryOne("SELECT password_enc FROM users WHERE username = ? AND deleted_at IS NULL", username.trim()) { it.getString(1) }
    }
}
