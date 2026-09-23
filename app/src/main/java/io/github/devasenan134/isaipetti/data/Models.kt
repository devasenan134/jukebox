package io.github.devasenan134.isaipetti.data

import kotlinx.serialization.Serializable

// These mirror the JSON that Navidrome returns (the Subsonic / OpenSubsonic API).
// Every field has a default, so a missing field never crashes the app.
// For this library: Album = movie, album artist = music director, song artist = singers.

@Serializable
data class Song(
    val id: String,
    val title: String = "",
    val album: String? = null,
    val albumId: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val duration: Int = 0,
    val coverArt: String? = null,
    /** When you liked it (Navidrome calls it "starred"); null if you haven't. */
    val starred: String? = null,
)

@Serializable
data class Album(
    val id: String,
    val name: String = "",
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val year: Int? = null,
    val song: List<Song> = emptyList(),
    val starred: String? = null,
)

@Serializable
data class Artist(
    val id: String,
    val name: String = "",
    val albumCount: Int = 0,
    val coverArt: String? = null,
    val album: List<Album> = emptyList(),
)

@Serializable
data class Playlist(
    val id: String,
    val name: String = "",
    val comment: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val coverArt: String? = null,
    /** Who made it (their Navidrome username). */
    val owner: String? = null,
    val public: Boolean = false,
    /** When it was last changed (ISO date and time). */
    val changed: String? = null,
    val entry: List<Song> = emptyList(),
)

@Serializable
data class SearchResult(
    val artist: List<Artist> = emptyList(),
    val album: List<Album> = emptyList(),
    val song: List<Song> = emptyList(),
)

/** One line of lyrics. [start] is in milliseconds and is only set for synced lyrics. */
@Serializable
data class LyricLine(val start: Long? = null, val value: String = "")

@Serializable
data class StructuredLyrics(
    val lang: String = "",
    val synced: Boolean = false,
    val offset: Long = 0,
    val line: List<LyricLine> = emptyList(),
)

// Wrapper objects that only exist because of how the API nests its lists.
@Serializable internal data class AlbumList(val album: List<Album> = emptyList())
@Serializable internal data class ArtistIndex(val artist: List<Artist> = emptyList())
@Serializable internal data class Artists(val index: List<ArtistIndex> = emptyList())
@Serializable internal data class Playlists(val playlist: List<Playlist> = emptyList())
/** What you've liked (starred) in Navidrome. */
@Serializable data class Starred(val album: List<Album> = emptyList(), val song: List<Song> = emptyList())
@Serializable internal data class LyricsList(val structuredLyrics: List<StructuredLyrics> = emptyList())
