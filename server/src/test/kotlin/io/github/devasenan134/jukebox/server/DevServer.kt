package io.github.devasenan134.jukebox.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * Local test server (test code only, never deployed): real Navidrome logins work as usual,
 * and pretend users named "bot_<name>" can log in with token "ok-bot_<name>", so one person
 * can test friends and chat from a script. Run with: ./gradlew runDev
 */
fun main() {
    val config = Config.fromEnv()
    val navidrome = object : Navidrome(config) {
        override suspend fun checkLogin(username: String, salt: String, token: String) =
            if (username.startsWith("bot_")) token == "ok-$username" else super.checkLogin(username, salt, token)
    }
    embeddedServer(Netty, port = config.port) { jukeboxServer(config, navidrome) }.start(wait = true)
}
