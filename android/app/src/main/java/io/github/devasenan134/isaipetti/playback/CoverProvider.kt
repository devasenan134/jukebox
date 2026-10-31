package io.github.devasenan134.isaipetti.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.devasenan134.isaipetti.IsaipettiApp
import java.io.File
import java.io.FileNotFoundException
import java.net.URL

/**
 * Cover art for Android Auto, which only shows pictures from content:// addresses (not the server's): each cover
 * is downloaded once into the cache and handed over from there. Read-only, and only covers.
 */
class CoverProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val id = uri.lastPathSegment?.takeIf { COVER_ID.matches(it) } ?: throw FileNotFoundException(uri.toString())
        val context = context ?: throw FileNotFoundException(uri.toString())
        val file = File(context.cacheDir, "car-covers/$id.jpg")
        if (!file.exists()) {
            val url = runCatching { (context.applicationContext as IsaipettiApp).api.coverUrl(id, SIZE) }.getOrNull()
                ?: throw FileNotFoundException("Not signed in")
            file.parentFile?.mkdirs()
            val part = File(file.path + ".part")
            try {
                URL(url).openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
                part.renameTo(file)
            } catch (e: Exception) {
                part.delete()
                throw FileNotFoundException("Couldn't load cover $id: ${e.message}")
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        private const val SIZE = 400
        private val COVER_ID = Regex("[A-Za-z0-9_-]{1,100}")

        /** The content:// address of a cover (by its cover art id, as the server gives it). */
        fun uri(context: Context, coverArt: String): Uri =
            Uri.Builder().scheme("content").authority("${context.packageName}.covers").appendPath(coverArt).build()
    }
}
