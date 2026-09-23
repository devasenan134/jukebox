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
    /** Everyone credited as the song's artist (the singers), with their ids. */
    val artists: List<ArtistRef> = emptyList(),
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
    /** What they do in the library: "albumartist" (a composer here), "artist" (a singer), "composer", ... */
    val roles: List<String> = emptyList(),
    val album: List<Album> = emptyList(),
) {
    /** Composers have movies of their own; singers only appear on songs. */
    val isComposer get() = roles.isEmpty() || "albumartist" in roles
}

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
/** Everything Navidrome knows about one song (getSong, with OpenSubsonic's extra fields). */
@Serializable
data class SongDetails(
    val id: String,
    val title: String = "",
    val album: String? = null,
    val albumId: String? = null,
    val artist: String? = null,
    val artists: List<ArtistRef> = emptyList(),
    val displayAlbumArtist: String? = null,
    val albumArtists: List<ArtistRef> = emptyList(),
    val displayComposer: String? = null,
    val contributors: List<Contributor> = emptyList(),
    val year: Int? = null,
    val genre: String? = null,
    val genres: List<Genre> = emptyList(),
    val track: Int? = null,
    val discNumber: Int? = null,
    val duration: Int = 0,
    val bitRate: Int? = null,
    val suffix: String? = null,
    val samplingRate: Int? = null,
    val bitDepth: Int? = null,
    val channelCount: Int? = null,
    val size: Long? = null,
    val playCount: Long? = null,
    val played: String? = null,
    val created: String? = null,
    val bpm: Int? = null,
    val comment: String? = null,
) {
    /** People credited in [role] ("composer", "lyricist", ...). */
    fun credited(role: String) = contributors.filter { it.role.equals(role, ignoreCase = true) }.map { it.artist.name }.distinct()
}

@Serializable data class ArtistRef(val id: String = "", val name: String = "")
@Serializable data class Contributor(val role: String = "", val subRole: String? = null, val artist: ArtistRef = ArtistRef())
@Serializable data class Genre(val name: String = "")

/** What you've liked (starred) in Navidrome. */
@Serializable data class Starred(val album: List<Album> = emptyList(), val song: List<Song> = emptyList())
@Serializable internal data class LyricsList(val structuredLyrics: List<StructuredLyrics> = emptyList())
