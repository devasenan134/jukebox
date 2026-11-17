package io.github.devasenan134.isaipetti.data

import kotlinx.serialization.Serializable

// Jukebox API v2 DTOs and models for Android

@Serializable
data class PersonRefDto(
    val id: String = "",
    val name: String = "",
    val role: String? = null,
)

@Serializable
data class AlbumSummaryDto(
    val id: String = "",
    val title: String = "",
    val year: Int? = null,
    val kind: String = "film",
    val coverArt: String? = null,
    val composers: List<PersonRefDto> = emptyList(),
    val cast: List<String> = emptyList(),
    val songCount: Int = 0,
    val durationMs: Long = 0,
    val starred: String? = null,
)

@Serializable
data class AlbumsResponse(
    val total: Int = 0,
    val albums: List<AlbumSummaryDto> = emptyList(),
)

@Serializable
data class RecordingDto(
    val id: String = "",
    val title: String = "",
    val version: String = "original",
    val durationMs: Long = 0,
    val singers: List<PersonRefDto> = emptyList(),
    val composers: List<PersonRefDto> = emptyList(),
    val lyricists: List<PersonRefDto> = emptyList(),
    val coverArt: String? = null,
    val hasSyncedLyrics: Boolean = false,
    val playCount: Int = 0,
    val starred: String? = null,
)

@Serializable
data class TrackDto(
    val id: String = "",
    val disc: Int = 1,
    val number: Int = 1,
    val title: String = "",
    val recording: RecordingDto = RecordingDto(),
)

@Serializable
data class ReleaseDto(
    val id: String = "",
    val title: String = "",
    val kind: String = "soundtrack", // soundtrack, score, single, rerelease, other
    val year: Int? = null,
    val coverArt: String? = null,
    val tracks: List<TrackDto> = emptyList(),
)

@Serializable
data class AlbumDetailDto(
    val id: String = "",
    val title: String = "",
    val year: Int? = null,
    val kind: String = "film",
    val coverArt: String? = null,
    val composers: List<PersonRefDto> = emptyList(),
    val directors: List<String> = emptyList(),
    val cast: List<PersonRefDto> = emptyList(),
    val starred: String? = null,
    val releases: List<ReleaseDto> = emptyList(),
)

@Serializable
data class PersonSummaryDto(
    val id: String = "",
    val name: String = "",
    val roles: List<String> = emptyList(),
    val coverArt: String? = null,
    val songCount: Int = 0,
    val movieCount: Int = 0,
    val hasPhoto: Boolean = false,
) {
    fun toArtist() = Artist(id, name, albumCount = movieCount, songCount = songCount, coverArt = coverArt, roles = roles)
}

@Serializable
data class PeopleResponse(
    val total: Int = 0,
    val people: List<PersonSummaryDto> = emptyList(),
)

@Serializable
data class PersonDetailDto(
    val id: String = "",
    val name: String = "",
    val roles: List<String> = emptyList(),
    val coverArt: String? = null,
    val songCount: Int = 0,
    val movieCount: Int = 0,
    val albums: List<AlbumSummaryDto> = emptyList(),
    val songs: List<RecordingDto> = emptyList(),
    val movies: List<AlbumSummaryDto> = emptyList(),
)

@Serializable
data class LyricLineDto(
    val startMs: Long = 0,
    val text: String = "",
)

@Serializable
data class LyricsDto(
    val script: String = "",
    val synced: Boolean = false,
    val lines: List<LyricLineDto> = emptyList(),
)

@Serializable
data class SongVersionDto(
    val id: String = "",
    val title: String = "",
    val version: String = "",
)

@Serializable
data class SongDetailDto(
    val id: String = "",
    val title: String = "",
    val version: String = "original",
    val durationMs: Long = 0,
    val albumId: String = "",
    val albumTitle: String = "",
    val coverArt: String? = null,
    val composers: List<PersonRefDto> = emptyList(),
    val singers: List<PersonRefDto> = emptyList(),
    val lyricists: List<PersonRefDto> = emptyList(),
    val versions: List<SongVersionDto> = emptyList(),
    val lyrics: List<LyricsDto> = emptyList(),
    val playCount: Int = 0,
    val starred: String? = null,
)

@Serializable
data class LyricsMatchDto(
    val recordingId: String = "",
    val songTitle: String = "",
    val albumId: String = "",
    val albumTitle: String = "",
    val coverArt: String? = null,
    val matchedLine: String = "",
    val startMs: Long = 0,
)

@Serializable
data class LyricsSearchResponse(
    val query: String = "",
    val total: Int = 0,
    val matches: List<LyricsMatchDto> = emptyList(),
)

@Serializable
data class UnifiedSearchResponse(
    val query: String = "",
    val people: List<PersonHit> = emptyList(),
    val movies: List<MovieHit> = emptyList(),
    val songs: List<SongHit> = emptyList(),
    val lyrics: List<LyricsMatchDto> = emptyList(),
)

// ---- Converters to domain models ----

fun TrackDto.toSong(release: ReleaseDto, album: AlbumDetailDto): Song {
    val r = recording
    val singers = if (r.singers.isNotEmpty()) r.singers else album.composers
    val artistText = singers.joinToString(", ") { it.name }
    return Song(
        id = r.id,
        title = title.ifBlank { r.title },
        album = album.title,
        albumId = album.id,
        artist = artistText.ifBlank { album.composers.firstOrNull()?.name },
        artistId = r.singers.firstOrNull()?.id ?: album.composers.firstOrNull()?.id,
        track = number,
        discNumber = disc,
        year = release.year ?: album.year,
        duration = maxOf(1, (r.durationMs / 1000).toInt()),
        coverArt = r.coverArt ?: release.coverArt ?: album.coverArt,
        starred = r.starred,
        artists = r.singers.map { ArtistRef(it.id, it.name) },
    )
}

fun AlbumDetailDto.toAlbum(): Album {
    val allSongs = releases.flatMap { rel -> rel.tracks.map { it.toSong(rel, this) } }
    return Album(
        id = id,
        name = title,
        artist = composers.joinToString(", ") { it.name },
        artistId = composers.firstOrNull()?.id,
        coverArt = coverArt,
        songCount = allSongs.size,
        duration = allSongs.sumOf { it.duration },
        year = year,
        starred = starred,
        song = allSongs,
    )
}

fun AlbumSummaryDto.toAlbum(): Album {
    return Album(
        id = id,
        name = title,
        artist = composers.joinToString(", ") { it.name },
        artistId = composers.firstOrNull()?.id,
        coverArt = coverArt,
        songCount = songCount,
        duration = maxOf(1, (durationMs / 1000).toInt()),
        year = year,
        starred = starred,
        song = emptyList(),
    )
}

fun RecordingDto.toSong(album: AlbumSummaryDto? = null): Song {
    val singers = if (this.singers.isNotEmpty()) this.singers else this.composers
    val artistText = singers.joinToString(", ") { it.name }
    return Song(
        id = id,
        title = title,
        album = album?.title,
        albumId = album?.id,
        artist = artistText,
        artistId = this.singers.firstOrNull()?.id ?: this.composers.firstOrNull()?.id,
        year = album?.year,
        duration = maxOf(1, (durationMs / 1000).toInt()),
        coverArt = coverArt ?: album?.coverArt,
        starred = starred,
        artists = this.singers.map { ArtistRef(it.id, it.name) },
    )
}
