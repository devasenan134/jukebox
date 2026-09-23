package io.github.devasenan134.isaipetti.playback

import android.net.Uri
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.devasenan134.isaipetti.data.Song
import io.github.devasenan134.isaipetti.data.SongRef
import io.github.devasenan134.isaipetti.data.SubsonicApi

// Converting between our Song and Media3's MediaItem (what the player queue holds).
// Navidrome ids ride along in "extras" so a queued song can be shared with friends.

const val EXTRA_ALBUM_ID = "albumId"

/**
 * Queue items point at "isaipetti://song/<id>" instead of a real stream URL. The player turns it
 * into a URL with the current login only when the song starts loading (see PlaybackService), so
 * a queue keeps working after the password (and with it the login token) changes.
 */
const val SONG_SCHEME = "isaipetti"

fun songUri(id: String): Uri = "$SONG_SCHEME://song/$id".toUri()
private const val EXTRA_COVER_ART = "coverArt"

/** For a shared clip: where to pause (ms). The player starts the song at the clip's start. */
const val EXTRA_CLIP_END_MS = "clipEndMs"

fun Song.toMediaItem(api: SubsonicApi, clipEndMs: Long? = null): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setUri(songUri(id))
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(api.coverUrl(coverArt, 600)?.toUri())
            .setDurationMs(duration * 1000L)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setExtras(bundleOf(EXTRA_ALBUM_ID to albumId, EXTRA_COVER_ART to coverArt).apply { clipEndMs?.let { putLong(EXTRA_CLIP_END_MS, it) } })
            .build()
    )
    .build()

fun MediaItem.toSongRef(): SongRef {
    val meta = mediaMetadata
    return SongRef(
        id = mediaId,
        title = meta.title?.toString().orEmpty(),
        artist = meta.artist?.toString(),
        album = meta.albumTitle?.toString(),
        albumId = meta.extras?.getString(EXTRA_ALBUM_ID),
        coverArt = meta.extras?.getString(EXTRA_COVER_ART),
        duration = ((meta.durationMs ?: 0) / 1000).toInt(),
    )
}
