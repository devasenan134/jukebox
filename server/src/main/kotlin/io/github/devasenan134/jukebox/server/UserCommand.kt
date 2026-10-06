package io.github.devasenan134.jukebox.server

import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Accounts from the command line, for whoever runs the server. A new server has nobody to hand out invite
 * codes, so the first account is made here:
 *
 *   jukebox user add <username> [--admin] [--name "Display name"]
 *   jukebox user password <username>
 *   jukebox user admin <username> on|off
 *   jukebox user remove <username>
 *   jukebox user list
 *
 * The password is asked for (or read from JUKEBOX_PASSWORD, for scripts). It works while the server runs:
 * a removed person's sessions end at once, though a connected phone notices only on its next request.
 */
class UserCommand(private val db: Db, private val signIn: SignIn) {
    fun run(args: List<String>): Int = runBlocking {
        val command = args.getOrNull(0)
        val username = args.getOrNull(1)?.trim()
        fun arg(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        when {
            command == "list" -> list()
            username == null -> usage()
            command == "add" -> add(username, admin = "--admin" in args, displayName = arg("--name"))
            command == "password" -> password(username)
            command == "admin" && args.getOrNull(2) in setOf("on", "off") -> admin(username, args[2] == "on")
            command == "remove" -> remove(username)
            else -> usage()
        }
    }

    private suspend fun add(username: String, admin: Boolean, displayName: String?): Int {
        if (!Accounts.USERNAME.matches(username)) return fail("Usernames are 3–24 letters, numbers, dots, dashes or underscores")
        if (db.tx { queryOne("SELECT 1 FROM users WHERE username = ?", username) { 1 } } != null) return fail("$username already exists")
        val password = newPassword(username) ?: return 1
        val id = db.tx {
            insert(
                "INSERT INTO users (username, display_name, created_at, is_admin) VALUES (?, ?, ?, ?)",
                username, displayName?.trim()?.takeIf { it.isNotEmpty() } ?: username, now(), if (admin) 1 else 0,
            )
        }
        signIn.setPassword(id, password)
        println("Added $username${if (admin) " (admin)" else ""}")
        return 0
    }

    private suspend fun password(username: String): Int {
        val id = userId(username) ?: return fail("No account $username")
        val password = newPassword(username) ?: return 1
        signIn.setPassword(id, password)
        // Like a password change in the app: phones signed in with the old one have to sign in again.
        db.tx { update("DELETE FROM sessions WHERE user_id = ?", id) }
        println("Changed the password of $username")
        return 0
    }

    private suspend fun admin(username: String, on: Boolean): Int {
        val id = userId(username) ?: return fail("No account $username")
        db.tx { update("UPDATE users SET is_admin = ? WHERE id = ?", if (on) 1 else 0, id) }
        println("$username is ${if (on) "now" else "no longer"} an admin")
        return 0
    }

    private suspend fun remove(username: String): Int {
        val id = userId(username) ?: return fail("No account $username")
        db.tx { retireUser(id) }
        println("Removed $username (their messages stay, marked as left)")
        return 0
    }

    private suspend fun list(): Int {
        db.tx {
            query("SELECT username, display_name, is_admin FROM users WHERE deleted_at IS NULL ORDER BY username") {
                Triple(it.getString(1), it.getString(2), it.getInt(3) == 1)
            }
        }.forEach { (username, name, admin) -> println("$username\t$name${if (admin) "\tadmin" else ""}") }
        return 0
    }

    private suspend fun userId(username: String): Long? =
        db.tx { queryOne("SELECT id FROM users WHERE username = ? AND deleted_at IS NULL", username) { it.getLong(1) } }

    /** The new password, from JUKEBOX_PASSWORD or typed twice; null (after saying why) if it won't do. */
    private fun newPassword(username: String): String? {
        val password = System.getenv("JUKEBOX_PASSWORD")?.takeIf { it.isNotEmpty() } ?: run {
            val console = System.console()
            if (console == null) {
                fail("Set JUKEBOX_PASSWORD, or run this in a terminal (docker exec -it)")
                return null
            }
            val first = String(console.readPassword("Password for %s: ", username) ?: return null)
            val again = String(console.readPassword("Again: ") ?: return null)
            if (first != again) {
                fail("The two passwords are different")
                return null
            }
            first
        }
        val problem = PasswordRules.check(password, username).problem
        if (problem != null) {
            fail(problem)
            return null
        }
        return password
    }

    private fun usage(): Int = fail(
        """Usage: jukebox user add <username> [--admin] [--name "Display name"]
          |       jukebox user password <username>
          |       jukebox user admin <username> on|off
          |       jukebox user remove <username>
          |       jukebox user list""".trimMargin(),
    )

    private fun fail(message: String): Int {
        System.err.println(message)
        return 1
    }

    companion object {
        fun forConfig(config: Config): UserCommand {
            val db = Db(config.dbPath)
            return UserCommand(db, SignIn(db, Passwords(File(File(config.dbPath).absoluteFile.parentFile, "secret.key"))))
        }
    }
}
