package io.github.devasenan134.jukebox.server

import kotlinx.coroutines.runBlocking
import java.io.File

/** The password test accounts get from [addAccount]. */
fun testPassword(username: String) = "secret-$username"

/** Makes an account in the database at [dbPath], the way `jukebox user add` does (the server may be running). */
fun addAccount(dbPath: String, username: String, admin: Boolean = false, password: String = testPassword(username)): Long = runBlocking {
    val db = Db(dbPath)
    val id = db.tx { queryOne("SELECT id FROM users WHERE username = ? AND deleted_at IS NULL", username) { it.getLong(1) } }
        ?: db.tx { insert("INSERT INTO users (username, display_name, created_at, is_admin) VALUES (?, ?, ?, ?)", username, username, now(), if (admin) 1 else 0) }
    SignIn(db, Passwords(File(File(dbPath).absoluteFile.parentFile, "secret.key"))).setPassword(id, password)
    id
}

/** How an app signs in as [username] (token = md5(password + salt)). */
fun loginRequest(username: String, password: String = testPassword(username), salt: String = "c0ffee") =
    LoginRequest(username, salt, Passwords.md5(password + salt))
