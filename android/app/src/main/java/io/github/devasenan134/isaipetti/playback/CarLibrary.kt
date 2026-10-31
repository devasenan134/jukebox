package io.github.devasenan134.isaipetti.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import io.github.devasenan134.isaipetti.IsaipettiApp
import io.github.devasenan134.isaipetti.data.Mix
import io.github.devasenan134.isaipetti.data.RecentActivity
import io.github.devasenan134.isaipetti.data.Song
import java.util.concurrent.ConcurrentHashMap

/**
 * What Android Auto shows: four tabs to browse (Home, Recent, Stations, Library), search (on screen and by voice),
 * and playing what's picked there. Its media ids start with "car/"; the app's own queue uses plain song ids.
 *
 *  - Home: Made for you and Your top mixes. Recent: what you played (Jump back in), which carries on where you left off.
 *  - Stations: yours, then composer and artist stations. Library: Liked songs, your playlists, saved mixes.
 *  - A mix, station or recent item plays at once; a playlist opens its songs, with Shuffle on top.
 */
@OptIn(UnstableApi::class)
internal class CarLibrary(private val app: IsaipettiApp, private val player: () -> Player?) {
    private val api get() = app.api
    private val social get() = app.social.api.takeIf { app.session.social.value != null }

    /** The songs of each list browsed (by source), so a song picked there plays the rest of the list after it. */
    private val lists = ConcurrentHashMap<String, List<Song>>()

    /** The last search's songs, for playing one of them. */
    @Volatile private var found: List<Song> = emptyList()

    fun root(): MediaItem = folder(ROOT, "Jukebox")

    /** How Android Auto lays out the tabs: mixes and stations as a grid of covers, songs as a list; search is on. */
    val rootExtras: Bundle = bundleOf(
        CONTENT_STYLE_SUPPORTED to true,
        CONTENT_STYLE_BROWSABLE to STYLE_LIST,
        CONTENT_STYLE_PLAYABLE to STYLE_LIST,
        SEARCH_SUPPORTED to true,
    )

    suspend fun children(parentId: String): List<MediaItem> = when {
        parentId == ROOT -> listOf(
            folder(HOME, "Home", playableStyle = STYLE_GRID),
            folder(RECENT, "Recent", playableStyle = STYLE_GRID),
            folder(STATIONS, "Stations", playableStyle = STYLE_GRID),
            folder(LIBRARY, "Library"),
        )
        parentId == HOME -> homeSections("made-for-you", "top-mixes").map(::mixItem)
        parentId == STATIONS -> homeSections("your-stations", "artist-stations").map(::mixItem)
        parentId == RECENT -> app.activity.items.value.mapNotNull(::recentItem)
        parentId == LIBRARY -> buildList {
            add(folder(LIST + LIKED, "Liked songs", artwork = null))
            runCatching { api.playlists() }.getOrDefault(emptyList()).forEach { add(folder(LIST + "playlist:${it.id}", it.name, it.coverArt, subtitle = "Playlist")) }
            runCatching { social?.followedMixes() }.getOrNull().orEmpty().forEach { add(mixItem(it)) }
        }
        parentId.startsWith(LIST) -> {
            val source = parentId.removePrefix(LIST)
            val songs = load(source).songs
            if (songs.isEmpty()) emptyList()
            else listOf(playable(SHUFFLE + source, "Shuffle", subtitle = "${songs.size} songs")) + songs.map { songItem(source, it) }
        }
        else -> emptyList()
    }

    suspend fun search(query: String): List<MediaItem> {
        val result = runCatching { api.search(query) }.getOrNull() ?: return emptyList()
        found = result.song
        return result.album.map { folder(LIST + "album:${it.id}", it.name, it.coverArt, subtitle = it.artist) } +
            result.song.map { songItem(SEARCH, it) }
    }

    /**
     * The queue for what was picked in the car ([items], usually one), or null if it isn't the car's (the app's own
     * songs). A voice request ("play … on Jukebox") comes with a search instead of an id.
     */
    suspend fun resolve(items: List<MediaItem>): MediaSession.MediaItemsWithStartPosition? {
        val item = items.singleOrNull() ?: return null
        val query = item.requestMetadata.searchQuery
        val id = item.mediaId
        return when {
            id.startsWith(SONG) -> {
                val (source, songId) = id.removePrefix(SONG).split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                val songs = if (source == SEARCH) found else lists[source] ?: load(source).songs
                start(songs, songs.indexOfFirst { it.id == songId }.coerceAtLeast(0), source.takeUnless { it == SEARCH })
            }
            id.startsWith(SHUFFLE) -> {
                val source = id.removePrefix(SHUFFLE)
                val loaded = load(source)
                loaded.played()
                // Shuffled here, as the queue's own order (so Resume keeps it, like a shuffled play in the app).
                start(loaded.songs.shuffled(), 0, source)
            }
            id.startsWith(PLAY) -> resume(id.removePrefix(PLAY))
            id.isEmpty() && !query.isNullOrBlank() -> voice(query)
            id.startsWith(PREFIX) -> throw IllegalArgumentException("Can't play $id")
            else -> null
        }
    }

    /**
     * Plays [source] from where you left off in it (the same order, a shuffled one too), else from the start.
     * A station always starts afresh (it keeps adding songs as it plays).
     */
    private suspend fun resume(source: String): MediaSession.MediaItemsWithStartPosition? {
        val loaded = load(source)
        loaded.played()
        val songs = loaded.songs
        val saved = app.queueMemory.get(source)?.takeIf { s -> !source.startsWith(STATION) && songs.any { it.id == s.currentId } }
            ?: return start(songs, 0, source)
        val byId = songs.associateBy { it.id }
        val order = saved.songIds.mapNotNull { byId[it] }.distinctBy { it.id }
        val queue = order + songs.filter { song -> order.none { it.id == song.id } }
        return start(queue, queue.indexOfFirst { it.id == saved.currentId }.coerceAtLeast(0), source)
    }

    /** "Play <something> on Jukebox": a mix or playlist by that name, else the songs a search finds. */
    private suspend fun voice(query: String): MediaSession.MediaItemsWithStartPosition? {
        val q = query.trim().lowercase()
        val mixes = app.mixes.home.value?.sections.orEmpty().flatMap { it.mixes }
        mixes.firstOrNull { it.title.lowercase() == q }?.let { return resume(it.source) }
        runCatching { api.playlists() }.getOrNull()?.firstOrNull { it.name.lowercase() == q }?.let { return resume("playlist:${it.id}") }
        mixes.firstOrNull { q in it.title.lowercase() }?.let { return resume(it.source) }
        val songs = runCatching { api.search(query).song }.getOrDefault(emptyList())
        found = songs
        return start(songs, 0, null)
    }

    private fun start(songs: List<Song>, index: Int, source: String?): MediaSession.MediaItemsWithStartPosition? {
        if (songs.isEmpty()) return null
        // The queue plays in the order given (like PlayerConnection.play without shuffle).
        player()?.shuffleModeEnabled = false
        return MediaSession.MediaItemsWithStartPosition(songs.map { it.toMediaItem(api, source = source) }, index, 0)
    }

    private class Loaded(val songs: List<Song>, val played: () -> Unit = {})

    /** The songs of a playlist, movie, mix or Liked songs; and how to put it in Recently played when it's played. */
    private suspend fun load(source: String): Loaded {
        val loaded = runCatching {
            when {
                source == LIKED -> Loaded(api.starred().song) { app.activity.liked() }
                source.startsWith("playlist:") -> api.playlist(source.removePrefix("playlist:")).let { Loaded(it.entry) { app.activity.playlist(it) } }
                source.startsWith("album:") -> api.album(source.removePrefix("album:")).let { Loaded(it.song) { app.activity.movie(it) } }
                source.startsWith(Mix.SOURCE_PREFIX) -> social?.mix(source.removePrefix(Mix.SOURCE_PREFIX))?.let { mix ->
                    Loaded(mix.songs.map { it.toSong() }) { app.activity.mix(mix) }
                }
                else -> null
            }
        }.getOrNull() ?: Loaded(emptyList())
        if (loaded.songs.isNotEmpty()) lists[source] = loaded.songs
        return loaded
    }

    private suspend fun homeSections(vararg ids: String): List<Mix> {
        val home = app.mixes.home.value ?: runCatching { social?.mixes() }.getOrNull()
        return ids.flatMap { id -> home?.sections?.firstOrNull { it.id == id }?.mixes.orEmpty() }.distinctBy { it.id }
    }

    private fun mixItem(mix: Mix) = playable(PLAY + mix.source, mix.title, mix.art?.picture ?: mix.covers.firstOrNull(), mix.subtitle.ifBlank { null })

    private fun recentItem(item: RecentActivity.Item): MediaItem? {
        val source = when (item.kind) {
            RecentActivity.Kind.Playlist -> "playlist:${item.id}"
            RecentActivity.Kind.Movie -> "album:${item.id}"
            RecentActivity.Kind.Liked -> LIKED
            RecentActivity.Kind.Mix -> Mix.SOURCE_PREFIX + item.id
            else -> return null // songs, composers and artists aren't something to carry on with
        }
        return playable(PLAY + source, item.title, item.coverArt, item.subtitle)
    }

    private fun songItem(source: String, song: Song) =
        item("$SONG$source|${song.id}", song.title, song.coverArt, song.artist, browsable = false, type = MediaMetadata.MEDIA_TYPE_MUSIC)

    private fun playable(id: String, title: String, artwork: String? = null, subtitle: String? = null) =
        item(id, title, artwork, subtitle, browsable = false, type = MediaMetadata.MEDIA_TYPE_PLAYLIST)

    private fun folder(id: String, title: String, artwork: String? = null, subtitle: String? = null, playableStyle: Int? = null) =
        item(id, title, artwork, subtitle, browsable = true, type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, extras = playableStyle?.let { bundleOf(CONTENT_STYLE_PLAYABLE to it) })

    private fun item(id: String, title: String, artwork: String?, subtitle: String?, browsable: Boolean, type: Int, extras: Bundle? = null) =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtist(subtitle)
                    .setArtworkUri(artwork?.let { CoverProvider.uri(app, it) })
                    .setIsBrowsable(browsable)
                    .setIsPlayable(!browsable)
                    .setMediaType(type)
                    .setExtras(extras)
                    .build(),
            )
            .build()

    companion object {
        private const val PREFIX = "car/"
        const val ROOT = "car/root"
        private const val HOME = "car/home"
        private const val RECENT = "car/recent"
        private const val STATIONS = "car/stations"
        private const val LIBRARY = "car/library"
        private const val LIST = "car/list|"
        private const val SONG = "car/song|"
        private const val SHUFFLE = "car/shuffle|"
        private const val PLAY = "car/play|"
        private const val LIKED = "liked"
        private const val SEARCH = "search"
        private const val STATION = Mix.SOURCE_PREFIX + "radio-"

        // Android Auto's layout hints (the androidx.media "content style" extras).
        private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        private const val CONTENT_STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val CONTENT_STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
        private const val STYLE_LIST = 1
        private const val STYLE_GRID = 2
    }
}
