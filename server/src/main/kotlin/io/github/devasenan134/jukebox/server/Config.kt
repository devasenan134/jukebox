package io.github.devasenan134.jukebox.server

import java.io.File

/** Settings come from environment variables (set in the server's .env file, never in the code). */
data class Config(
    val port: Int,
    val dbPath: String,
    /** Firebase service-account key (JSON file). Without it, push notifications are off. */
    val firebaseKeyFile: String? = null,
    /** The Firebase project's google-services.json. The app gets its settings from here, so none are built into the app. */
    val firebaseAppConfigFile: String? = null,
    /** "owner/repo" for bug reports, and a token that may only open issues there. Without them, bug reports are off. */
    val githubRepo: String? = null,
    val githubToken: String? = null,
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
    /** Music folders to scan: "name=path:kind:language;..." (docs/milestone-1.md). Empty: no music, only accounts and chat. */
    val libraries: String? = null,
    /** Where cover art is kept (by default next to the database). */
    val artworkDir: String? = null,
    /** A CSV of path,saavn_id for files whose tags have no JioSaavn id (optional). */
    val saavnIdMap: String? = null,
    /** Fingerprint recordings in the background (finds the same song twice). */
    val fingerprints: Boolean = true,
    val rescanEveryMinutes: Long = 60,
    /** The built web app (web/dist), served at /. Empty: no web app. */
    val webDir: String? = null,
    /**
     * A Spotify app's id and secret (free at developer.spotify.com), for importing whole Spotify playlists with
     * their ISRCs. Without them, imports read Spotify's public page (the first 100 songs).
     */
    val spotifyClientId: String? = null,
    val spotifyClientSecret: String? = null,
    /** Social features: friends, chat, listen-together jams, push notifications. */
    val socialEnabled: Boolean = true,
) {
    companion object {
        fun fromEnv(): Config {
            fun env(name: String, default: String? = null) =
                System.getenv(name) ?: default ?: error("Missing environment variable $name")
            return Config(
                port = env("PORT", "8095").toInt(),
                dbPath = env("DB_PATH", "data/jukebox.db"),
                firebaseKeyFile = System.getenv("FIREBASE_KEY_FILE")?.takeIf { File(it).isFile },
                firebaseAppConfigFile = System.getenv("FIREBASE_APP_CONFIG")?.takeIf { File(it).isFile },
                githubRepo = System.getenv("GITHUB_REPO")?.takeIf { it.isNotBlank() },
                githubToken = System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() },
                featuresDb = System.getenv("FEATURES_DB")?.takeIf { it.isNotBlank() },
                castFile = System.getenv("CAST_FILE")?.takeIf { it.isNotBlank() },
                timeZone = env("MIX_TIMEZONE", "Asia/Kolkata"),
                libraries = System.getenv("LIBRARIES")?.takeIf { it.isNotBlank() },
                artworkDir = System.getenv("ARTWORK_DIR")?.takeIf { it.isNotBlank() },
                saavnIdMap = System.getenv("SAAVN_ID_MAP")?.takeIf { it.isNotBlank() },
                fingerprints = System.getenv("FINGERPRINTS")?.lowercase() != "off",
                rescanEveryMinutes = System.getenv("RESCAN_EVERY_MINUTES")?.toLongOrNull() ?: 60,
                webDir = System.getenv("WEB_DIR")?.takeIf { File(it, "index.html").isFile },
                spotifyClientId = System.getenv("SPOTIFY_CLIENT_ID")?.takeIf { it.isNotBlank() },
                spotifyClientSecret = System.getenv("SPOTIFY_CLIENT_SECRET")?.takeIf { it.isNotBlank() },
                socialEnabled = System.getenv("JUKEBOX_SOCIAL")?.lowercase() != "off" && System.getenv("JUKEBOX_SOCIAL")?.lowercase() != "false",
            )
        }
    }
}
