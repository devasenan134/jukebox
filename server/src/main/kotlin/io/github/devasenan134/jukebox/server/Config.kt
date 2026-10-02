package io.github.devasenan134.jukebox.server

import java.io.File

/** Settings come from environment variables (set in the server's .env file, never in the code). */
data class Config(
    val port: Int,
    val dbPath: String,
    val navidromeUrl: String,
    val navidromeAdminUser: String,
    val navidromeAdminPassword: String,
    /** Firebase service-account key (JSON file). Without it, push notifications are off. */
    val firebaseKeyFile: String? = null,
    /** The Firebase project's google-services.json. The app gets its settings from here, so none are built into the app. */
    val firebaseAppConfigFile: String? = null,
    /** "owner/repo" for bug reports, and a token that may only open issues there. Without them, bug reports are off. */
    val githubRepo: String? = null,
    val githubToken: String? = null,
    /** Navidrome's database file (read-only), for mixes. Without it, mixes are off. */
    val navidromeDb: String? = null,
    /** The audio analyzer's features.db. Without it, mixes use only tags and listening (no moods). */
    val featuresDb: String? = null,
    /** Who acted in each movie (JSON lines from the library tools' cast.py), for search by actor. Optional. */
    val castFile: String? = null,
    /** Where "today" is for Daily Mixes and when Discover Weekly changes. */
    val timeZone: String = "Asia/Kolkata",
    /** How long a listening session waits for its owner to reconnect before it ends. */
    val listenOwnerGraceMs: Long = 60_000,
    /** How long a listener waits between song requests. */
    val songRequestCooldownMs: Long = 10_000,
) {
    companion object {
        fun fromEnv(): Config {
            fun env(name: String, default: String? = null) =
                System.getenv(name) ?: default ?: error("Missing environment variable $name")
            return Config(
                port = env("PORT", "8095").toInt(),
                dbPath = env("DB_PATH", "data/jukebox.db"),
                navidromeUrl = env("NAVIDROME_URL").trimEnd('/'),
                navidromeAdminUser = env("NAVIDROME_ADMIN_USER", ""),
                navidromeAdminPassword = env("NAVIDROME_ADMIN_PASSWORD", ""),
                firebaseKeyFile = System.getenv("FIREBASE_KEY_FILE")?.takeIf { File(it).isFile },
                firebaseAppConfigFile = System.getenv("FIREBASE_APP_CONFIG")?.takeIf { File(it).isFile },
                githubRepo = System.getenv("GITHUB_REPO")?.takeIf { it.isNotBlank() },
                githubToken = System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() },
                navidromeDb = System.getenv("NAVIDROME_DB")?.takeIf { File(it).isFile },
                featuresDb = System.getenv("FEATURES_DB")?.takeIf { it.isNotBlank() },
                castFile = System.getenv("CAST_FILE")?.takeIf { it.isNotBlank() },
                timeZone = env("MIX_TIMEZONE", "Asia/Kolkata"),
            )
        }
    }
}
