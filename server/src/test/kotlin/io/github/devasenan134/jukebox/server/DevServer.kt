package io.github.devasenan134.jukebox.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * Local test server (test code only, never deployed). It makes an admin "dev" and two friends to test chat
 * with, "bot_1" and "bot_2", all with the password "dev-password". Run with: ./gradlew runDev
 */
fun main() {
    val config = Config.fromEnv()
    addAccount(config.dbPath, "dev", admin = true, password = "dev-password")
    listOf("bot_1", "bot_2").forEach { addAccount(config.dbPath, it, password = "dev-password") }
    embeddedServer(Netty, port = config.port) { jukeboxServer(config) }.start(wait = true)
}
