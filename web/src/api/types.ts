// These mirror the JSON the server returns (the Subsonic / OpenSubsonic API and the friends API),
// the same shapes as the Android app's data/Models.kt, data/Mixes.kt and data/SocialModels.kt.
// For this library: Album = movie, album artist = music director, song artist = singers.

export interface ArtistRef {
  id: string
  name: string
}

export interface Song {
  id: string
  title: string
  album?: string
  albumId?: string
  artist?: string
  artistId?: string
  track?: number
  discNumber?: number
  year?: number
  duration: number
  coverArt?: string
  /** When you liked it (the Subsonic API calls it "starred"); undefined if you haven't. */
  starred?: string
  /** Everyone credited as the song's artist (the singers), with their ids. */
  artists?: ArtistRef[]
}

export interface Album {
  id: string
  name: string
  artist?: string
  artistId?: string
  coverArt?: string
  songCount: number
  duration: number
  year?: number
  song?: Song[]
  starred?: string
}

export interface Artist {
  id: string
  name: string
  albumCount?: number
  coverArt?: string
  /** What they do in the library: "albumartist" (a composer here), "artist" (a singer), "composer", ... */
  roles?: string[]
  album?: Album[]
}

/** Composers have movies of their own; singers only appear on songs. */
export const isComposer = (a: Artist) => !a.roles?.length || a.roles.includes('albumartist')

export interface Playlist {
  id: string
  name: string
  comment?: string
  songCount: number
  duration?: number
  coverArt?: string
  /** Who made it (their username). */
  owner?: string
  public?: boolean
  /** The server won't change its songs (Subsonic's "readonly"; Jukebox has none of these yet). */
  readonly?: boolean
  /** When it was last changed (ISO date and time). */
  changed?: string
  entry?: Song[]
}

export interface SearchResult {
  artist: Artist[]
  album: Album[]
  song: Song[]
}

/** One line of lyrics. start is in milliseconds and only set for synced lyrics. */
export interface LyricLine {
  start?: number
  value: string
}

export interface StructuredLyrics {
  lang?: string
  synced: boolean
  offset?: number
  line: LyricLine[]
}

export interface Contributor {
  role: string
  subRole?: string
  artist: ArtistRef
}

/** Everything the server knows about one song (getSong, with OpenSubsonic's extra fields). */
export interface SongDetails extends Song {
  displayAlbumArtist?: string
  albumArtists?: ArtistRef[]
  displayComposer?: string
  contributors?: Contributor[]
  genre?: string
  genres?: { name: string }[]
  bitRate?: number
  suffix?: string
  samplingRate?: number
  bitDepth?: number
  channelCount?: number
  size?: number
  playCount?: number
  played?: string
  created?: string
  bpm?: number
  comment?: string
}

export const credited = (d: SongDetails, role: string) =>
  [...new Set((d.contributors ?? []).filter((c) => c.role.toLowerCase() === role.toLowerCase()).map((c) => c.artist.name))]

export interface Starred {
  album: Album[]
  song: Song[]
}

// ---- Friends server ----

export interface SocialUser {
  id: number
  username: string
  displayName: string
  /** Version of their profile picture; undefined when they have none. */
  avatar?: number
}

/** A song as friends see it (shared in chats, now playing, listen together). */
export interface SongRef {
  id: string
  title: string
  artist?: string
  album?: string
  albumId?: string
  coverArt?: string
  duration: number
  clipStartMs?: number
  clipEndMs?: number
}

export const isClip = (s: SongRef) => s.clipStartMs != null && s.clipEndMs != null

export const songToRef = (s: Song): SongRef => ({
  id: s.id,
  title: s.title,
  artist: s.artist,
  album: s.album,
  albumId: s.albumId,
  coverArt: s.coverArt,
  duration: s.duration,
})

export const refToSong = (r: SongRef): Song => ({
  id: r.id,
  title: r.title,
  artist: r.artist,
  album: r.album,
  albumId: r.albumId,
  coverArt: r.coverArt,
  duration: r.duration,
})

export interface SessionResponse {
  sessionToken: string
  user: SocialUser
}

// ---- Mixes by Jukebox ----

/** Shown as the author of everything the app made rather than a person. */
export const MIX_AUTHOR = 'Jukebox'

export interface MixSong {
  id: string
  title: string
  artist?: string
  album?: string
  albumId?: string
  coverArt?: string
  duration: number
  year?: number
  artists?: ArtistRef[]
}

export const mixSongToSong = (m: MixSong): Song => ({ ...m })

export interface Mix {
  id: string
  /** "daily", "discover", "repeat", "rewind", "new", "friends", "popular", "mood", "decade", "composer", "singer" or "radio". */
  kind: string
  title: string
  subtitle: string
  description: string
  author: string
  covers: string[]
  /** A round picture (composer and singer mixes and stations). */
  round: boolean
  color: string
  songCount: number
  /** "daily", "weekly" or "live". */
  refresh: string
  updatedAt: number
  /** A station: more songs are added as it plays. */
  endless: boolean
  /** Picked for your taste: shown as "Made for <your name>". */
  personal: boolean
  songs: MixSong[]
}

export const MIX_SOURCE_PREFIX = 'mix:'
export const mixSource = (m: Mix) => MIX_SOURCE_PREFIX + m.id

export interface MixSection {
  id: string
  title: string
  mixes: Mix[]
}

export interface HomeMixes {
  sections: MixSection[]
  analyzedSongs: number
  totalSongs: number
}

/** One song the app played, and whether it was skipped early (mixes learn from skips). */
export interface PlayEvent {
  songId: string
  at: number
  playedMs: number
  durationMs: number
  skipped: boolean
  source?: string
}

// ---- Search on the friends server ----

export interface PersonHit {
  id: string
  name: string
  roles: string[]
  coverArt?: string
  songCount: number
  movieCount: number
}

export interface MovieHit {
  id: string
  name: string
  year?: number
  composer?: string
  coverArt?: string
  songCount: number
  reason?: string
}

export interface SongHit {
  song: MixSong
  reason?: string
}

export interface LibrarySearchResults {
  people: PersonHit[]
  movies: MovieHit[]
  songs: SongHit[]
}

export interface PersonPage {
  person: PersonHit
  movies: MovieHit[]
  songs: MixSong[]
}

export interface CatalogItem {
  id: string
  kind: string
  title: string
  movie: string
  year?: number
  artist?: string
  artworkUrl?: string
  trackCount: number
  durationMs?: number
}

export interface MusicRequest {
  id: number
  item: CatalogItem
  status: string
  requestedAt: number
  closedAt?: number
  note?: string
  albumId?: string
  songId?: string
  mine: boolean
  askedBy: string[]
}

export interface CatalogHit {
  item: CatalogItem
  request?: MusicRequest
}

export interface CatalogResults {
  movies: CatalogHit[]
  songs: CatalogHit[]
}
