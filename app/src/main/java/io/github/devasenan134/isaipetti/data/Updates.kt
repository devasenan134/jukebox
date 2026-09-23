package io.github.devasenan134.isaipetti.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.edit
import io.github.devasenan134.isaipetti.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** A newer release on GitHub. */
data class AppUpdate(val version: String, val notes: String, val apkUrl: String, val pageUrl: String)

/**
 * Checks GitHub Releases for a newer version of the app, downloads it and hands it to Android's
 * installer. Android only accepts it if it's signed with the same key as the installed app, so a
 * tampered download can't replace it.
 */
class Updates(private val context: Context, private val http: OkHttpClient) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _available = MutableStateFlow<AppUpdate?>(null)

    /** The newer release, once a check found one. */
    val available: StateFlow<AppUpdate?> = _available

    val currentVersion: String = BuildConfig.VERSION_NAME

    /** Checks at most every few hours (on app start). Never throws. */
    suspend fun checkNowAndThen() {
        if (System.currentTimeMillis() - prefs.getLong(LAST_CHECK, 0) < CHECK_EVERY_MS) return
        runCatching { check() }
    }

    /** Asks GitHub for the latest release. Returns it if it's newer than this app, else null. */
    suspend fun check(): AppUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        val release = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw SocialException("Couldn't check for updates (${response.code})")
            json.decodeFromString(Release.serializer(), response.body.string())
        }
        prefs.edit { putLong(LAST_CHECK, System.currentTimeMillis()) }
        val version = release.tagName.removePrefix("v")
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk") }
        val update = if (apk != null && isNewer(version, currentVersion)) {
            AppUpdate(version, release.body.orEmpty(), apk.url, release.pageUrl)
        } else {
            null
        }
        _available.value = update
        update
    }

    /** The patch notes of a released version (from its GitHub release), saved once fetched. Null if there are none. */
    suspend fun notesFor(version: String): String? = withContext(Dispatchers.IO) {
        prefs.getString("$NOTES$version", null)?.let { return@withContext it }
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/tags/v$version")
            .header("Accept", "application/vnd.github+json")
            .build()
        runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                json.decodeFromString(Release.serializer(), response.body.string()).body?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()?.also { prefs.edit { putString("$NOTES$version", it) } }
    }

    /** Whether the "update available" pop-up was already closed for this version. */
    fun isDismissed(update: AppUpdate) = prefs.getString(DISMISSED, null) == update.version
    fun dismiss(update: AppUpdate) = prefs.edit { putString(DISMISSED, update.version) }

    /** Downloads the new version, reporting progress from 0 to 1. */
    suspend fun download(update: AppUpdate, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only keep the one being downloaded
        val file = File(dir, "isaipetti-${update.version}.apk")
        http.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw SocialException("Download failed (${response.code})")
            val total = response.body.contentLength().takeIf { it > 0 }
            response.body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        total?.let { onProgress(done.toFloat() / it) }
                    }
                }
            }
        }
        file
    }

    /** Android asks once whether Isaipetti may install apps; until then, [install] can't work. */
    fun canInstall() = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Opens Android's installer for the downloaded file. */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tagName: String,
        val body: String? = null,
        @SerialName("html_url") val pageUrl: String,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(val name: String, @SerialName("browser_download_url") val url: String)

    companion object {
        private const val LAST_CHECK = "last_check"
        private const val DISMISSED = "dismissed_version"
        private const val NOTES = "notes_"
        private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

        /** "0.3.10" is newer than "0.3.9". */
        fun isNewer(candidate: String, current: String): Boolean {
            val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
            val b = current.split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
