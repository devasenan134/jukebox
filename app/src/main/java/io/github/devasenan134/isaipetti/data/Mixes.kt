package io.github.devasenan134.isaipetti.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

// Mixes, playlists and stations made by Isai Pettai on the friends server (server/.../MixMaker.kt).

/** Shown as the author of everything the app made rather than a person. */
const val MIX_AUTHOR = "Isai Pettai"

@Serializable
data class MixSong(
    val id: String,
    val title: String = "",
    val artist: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val coverArt: String? = null,
    val duration: Int = 0,
    val year: Int? = null,
    val artists: List<ArtistRef> = emptyList(),
) {
    fun toSong() = Song(
        id = id, title = title, album = album, albumId = albumId, artist = artist, year = year,
        duration = duration, coverArt = coverArt, artists = artists,
    )
}

@Serializable
data class Mix(
    val id: String,
    /** "daily", "discover", "repeat", "rewind", "new", "friends", "popular", "mood", "decade", "composer", "singer" or "radio". */
    val kind: String = "",
    val title: String = "",
    val subtitle: String = "",
    val description: String = "",
    val author: String = MIX_AUTHOR,
    val covers: List<String> = emptyList(),
    /** A round picture (composer and singer mixes and stations). */
    val round: Boolean = false,
    val color: String = "#7A3E9D",
    val songCount: Int = 0,
    /** "daily", "weekly" or "live". */
    val refresh: String = "live",
    val updatedAt: Long = 0,
    /** A station: more songs are added as it plays. */
    val endless: Boolean = false,
    val songs: List<MixSong> = emptyList(),
) {
    /** What the player remembers a queue was started from. Stations keep growing while this is playing. */
    val source get() = "$SOURCE_PREFIX$id"

    companion object {
        const val SOURCE_PREFIX = "mix:"
    }
}

@Serializable data class MixSection(val id: String, val title: String, val mixes: List<Mix> = emptyList())

@Serializable data class HomeMixes(val sections: List<MixSection> = emptyList(), val analyzedSongs: Int = 0, val totalSongs: Int = 0)

/** One song the app played, and whether it was skipped early (mixes learn from skips). */
@Serializable
data class PlayEvent(
    val songId: String,
    val at: Long,
    val playedMs: Long,
    val durationMs: Long,
    val skipped: Boolean,
    val source: String? = null,
)

/**
 * The mixes on Home and the ones you saved to Your Library. They're worked out on the friends server,
 * which keeps them up to date; the app just asks again when a screen opens.
 */
class Mixes(private val api: () -> SocialApi, private val scope: CoroutineScope) {
    private val _home = MutableStateFlow<HomeMixes?>(null)
    /** Null until loaded, or if the friends server has no mixes. */
    val home: StateFlow<HomeMixes?> = _home

    private val _followed = MutableStateFlow<List<Mix>>(emptyList())
    val followed: StateFlow<List<Mix>> = _followed

    fun refresh() {
        scope.launch {
            // No friends server, or mixes are off there: Home just doesn't show them.
            _home.value = runCatching { api().mixes() }.getOrNull() ?: _home.value
            _followed.value = runCatching { api().followedMixes() }.getOrNull() ?: _followed.value
        }
    }

    fun isFollowed(id: String) = _followed.value.any { it.id == id }

    /** Saves a mix to Your Library (it keeps updating there), or removes it. */
    suspend fun toggleFollow(mix: Mix) {
        if (isFollowed(mix.id)) {
            api().unfollowMix(mix.id)
            _followed.value = _followed.value.filterNot { it.id == mix.id }
        } else {
            api().followMix(mix.id)
            _followed.value = listOf(mix.copy(songs = emptyList())) + _followed.value
        }
    }

    fun clear() {
        _home.value = null
        _followed.value = emptyList()
    }
}
