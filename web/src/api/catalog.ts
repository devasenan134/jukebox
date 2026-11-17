import type { Album, Artist, ArtistRef, Song } from './types'
import { session } from '../state/session'
import { load } from '../state/storage'
import { subsonic } from './subsonic'

// Jukebox API v2: Film-first catalog endpoints
// All v2 endpoints authenticate with the standard Jukebox Bearer session token.

export class CatalogError extends Error {
  code: number
  constructor(message: string, code = 0) {
    super(message)
    this.code = code
  }
}

export interface PersonRef {
  id: string
  name: string
  role?: string
}

export interface AlbumSummary {
  id: string
  title: string
  year?: number
  kind: string
  coverArt?: string
  composers: PersonRef[]
  cast: string[]
  songCount: number
  durationMs: number
  starred?: string
}

export interface AlbumsResponse {
  total: number
  albums: AlbumSummary[]
}

export interface Recording {
  id: string
  title: string
  version: string
  durationMs: number
  singers: PersonRef[]
  composers: PersonRef[]
  lyricists: PersonRef[]
  coverArt?: string
  hasSyncedLyrics: boolean
  playCount: number
  starred?: string
}

export interface Track {
  id: string
  disc: number
  number: number
  title: string
  recording: Recording
}

export interface Release {
  id: string
  title: string
  kind: string // "soundtrack", "score", "single", "rerelease", "other"
  year?: number
  coverArt?: string
  tracks: Track[]
}

export interface AlbumDetail {
  id: string
  title: string
  year?: number
  kind: string
  coverArt?: string
  composers: PersonRef[]
  directors: string[]
  cast: PersonRef[]
  starred?: string
  releases: Release[]
}

export interface PersonSummary {
  id: string
  name: string
  roles: string[]
  coverArt?: string
  songCount: number
  movieCount: number
  hasPhoto?: boolean
}

export interface PeopleResponse {
  total: number
  people: PersonSummary[]
}

export interface PersonDetail {
  id: string
  name: string
  roles: string[]
  coverArt?: string
  songCount: number
  movieCount: number
  hasPhoto?: boolean
  albums: AlbumSummary[]
  songs: Recording[]
  movies: AlbumSummary[]
}

export interface LyricLine {
  startMs: number
  text: string
}

export interface Lyrics {
  script: string
  synced: boolean
  lines: LyricLine[]
}

export interface SongVersion {
  id: string
  title: string
  version: string
}

export interface SongDetail {
  id: string
  title: string
  version: string
  durationMs: number
  albumId: string
  albumTitle: string
  coverArt?: string
  composers: PersonRef[]
  singers: PersonRef[]
  lyricists: PersonRef[]
  versions: SongVersion[]
  lyrics: Lyrics[]
  playCount: number
  starred?: string
}

export interface LyricsMatch {
  recordingId: string
  songTitle: string
  albumId: string
  albumTitle: string
  coverArt?: string
  matchedLine: string
  startMs: number
}

export interface LyricsSearchResponse {
  query: string
  total: number
  matches: LyricsMatch[]
}

export interface UnifiedSearch {
  query: string
  people: { id: string; name: string; roles: string[]; coverArt?: string; songCount: number; movieCount: number }[]
  movies: { id: string; title: string; year?: number; coverArt?: string; composers: PersonRef[]; cast: string[]; songCount: number }[]
  songs: { id: string; title: string; version: string; albumId: string; albumTitle: string; durationMs: number; coverArt?: string; singers: PersonRef[]; composers: PersonRef[]; hasSyncedLyrics: boolean }[]
  lyrics: LyricsMatch[]
}

// ---- Adapter helpers to bridge native v2 objects with existing player/views ----

export function trackToSong(track: Track, release: Release, album: AlbumDetail): Song {
  const r = track.recording
  const singers = r.singers.length > 0 ? r.singers : album.composers
  const artistText = singers.map((s) => s.name).join(', ')
  return {
    id: r.id,
    title: track.title || r.title,
    album: album.title,
    albumId: album.id,
    artist: artistText || (album.composers[0]?.name ?? ''),
    artistId: r.singers[0]?.id ?? album.composers[0]?.id,
    track: track.number,
    discNumber: track.disc,
    year: release.year ?? album.year,
    duration: Math.max(1, Math.round(r.durationMs / 1000)),
    coverArt: r.coverArt ?? release.coverArt ?? album.coverArt,
    starred: r.starred,
    artists: r.singers.map((s) => ({ id: s.id, name: s.name })),
  }
}

export function recordingToSong(r: Recording, album?: { id: string; title: string; coverArt?: string; year?: number }): Song {
  const singers = r.singers.length > 0 ? r.singers : r.composers
  const artistText = singers.map((s) => s.name).join(', ')
  return {
    id: r.id,
    title: r.title,
    album: album?.title,
    albumId: album?.id,
    artist: artistText,
    artistId: r.singers[0]?.id ?? r.composers[0]?.id,
    year: album?.year,
    duration: Math.max(1, Math.round(r.durationMs / 1000)),
    coverArt: r.coverArt ?? album?.coverArt,
    starred: r.starred,
    artists: r.singers.map((s) => ({ id: s.id, name: s.name })),
  }
}

export function albumDetailToAlbum(detail: AlbumDetail): Album {
  const songs: Song[] = []
  for (const rel of detail.releases) {
    for (const trk of rel.tracks) {
      songs.push(trackToSong(trk, rel, detail))
    }
  }
  const totalDuration = songs.reduce((sum, s) => sum + s.duration, 0)
  return {
    id: detail.id,
    name: detail.title,
    artist: detail.composers.map((c) => c.name).join(', '),
    artistId: detail.composers[0]?.id,
    coverArt: detail.coverArt,
    songCount: songs.length,
    duration: totalDuration,
    year: detail.year,
    starred: detail.starred,
    song: songs,
  }
}

export function albumSummaryToAlbum(s: AlbumSummary): Album {
  return {
    id: s.id,
    name: s.title,
    artist: s.composers.map((c) => c.name).join(', '),
    artistId: s.composers[0]?.id,
    coverArt: s.coverArt,
    songCount: s.songCount,
    duration: Math.max(1, Math.round(s.durationMs / 1000)),
    year: s.year,
    starred: s.starred,
  }
}

export function personDetailToArtist(p: PersonDetail): Artist {
  return {
    id: p.id,
    name: p.name,
    albumCount: p.albums.length,
    coverArt: p.coverArt,
    roles: p.roles,
    album: p.albums.map(albumSummaryToAlbum),
  }
}

// ---- HTTP client for /api/v2 ----

const enc = encodeURIComponent

type QueryParams = Record<string, string | number | boolean | undefined | null>

async function get<T>(path: string, params: QueryParams = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  const token = session().social?.token
  if (token) headers.Authorization = `Bearer ${token}`

  const q = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v != null) q.append(k, String(v))
  }
  const qs = q.toString()
  const url = path + (qs ? `?${qs}` : '')

  let res: Response
  try {
    res = await fetch(url, { headers })
  } catch {
    throw new CatalogError("Can't reach the server")
  }
  const text = await res.text()
  if (!res.ok) {
    let message: string | undefined
    try {
      message = JSON.parse(text).error
    } catch {
      message = undefined
    }
    throw new CatalogError(message ?? `Server error (${res.status})`, res.status)
  }
  return (text.trim() ? JSON.parse(text) : undefined) as T
}

export const catalog = {
  albums: async (o: {
    sort?: 'name' | 'newest' | 'recent' | 'frequent' | 'byYear' | 'starred' | 'random'
    kind?: 'film' | 'album' | 'compilation'
    fromYear?: number
    toYear?: number
    offset?: number
    limit?: number
  } = {}): Promise<AlbumsResponse> => {
    try {
      return await get<AlbumsResponse>('/api/v2/albums', o)
    } catch (e) {
      // Graceful fallback to Subsonic if v2 is not reached or 401
      const subSort = o.sort === 'newest' ? 'newest' : o.sort === 'recent' ? 'recent' : o.sort === 'frequent' ? 'frequent' : o.sort === 'byYear' ? 'byYear' : o.sort === 'starred' ? 'starred' : 'alphabeticalByName'
      const list = await subsonic.albumList(subSort, o.limit ?? 50, o.offset ?? 0)
      return {
        total: list.length,
        albums: list.map((a) => ({
          id: a.id,
          title: a.name,
          year: a.year,
          kind: 'album',
          coverArt: a.coverArt,
          composers: a.artist ? [{ id: a.artistId ?? '', name: a.artist, role: 'composer' }] : [],
          cast: [],
          songCount: a.songCount,
          durationMs: (a.duration ?? 0) * 1000,
          starred: a.starred,
        })),
      }
    }
  },

  album: async (id: string): Promise<AlbumDetail> => {
    try {
      return await get<AlbumDetail>(`/api/v2/albums/${enc(id)}`)
    } catch (e) {
      // Fallback to Subsonic getAlbum if not authenticated or not supported
      const a = await subsonic.album(id)
      const tracks: Track[] = (a.song ?? []).map((s, idx) => ({
        id: s.id,
        disc: s.discNumber ?? 1,
        number: s.track ?? idx + 1,
        title: s.title,
        recording: {
          id: s.id,
          title: s.title,
          version: 'original',
          durationMs: (s.duration ?? 0) * 1000,
          singers: (s.artists ?? []).map((ar: ArtistRef) => ({ id: ar.id, name: ar.name, role: 'singer' })),
          composers: a.artist ? [{ id: a.artistId ?? '', name: a.artist, role: 'composer' }] : [],
          lyricists: [],
          coverArt: s.coverArt,
          hasSyncedLyrics: false,
          playCount: 0,
          starred: s.starred,
        },
      }))
      return {
        id: a.id,
        title: a.name,
        year: a.year,
        kind: 'album',
        coverArt: a.coverArt,
        composers: a.artist ? [{ id: a.artistId ?? '', name: a.artist, role: 'composer' }] : [],
        directors: [],
        cast: [],
        starred: a.starred,
        releases: [
          {
            id: `rel-${a.id}`,
            title: a.name,
            kind: 'soundtrack',
            year: a.year,
            coverArt: a.coverArt,
            tracks,
          },
        ],
      }
    }
  },

  people: async (o: {
    role?: 'composer' | 'singer' | 'lyricist' | 'actor' | 'artist' | 'all'
    q?: string
    offset?: number
    limit?: number
  } = {}): Promise<PeopleResponse> => {
    try {
      return await get<PeopleResponse>('/api/v2/people', o)
    } catch {
      const artists = await subsonic.artists()
      return {
        total: artists.length,
        people: artists.map((ar) => ({
          id: ar.id,
          name: ar.name,
          roles: ar.roles ?? ['composer'],
          coverArt: ar.coverArt,
          songCount: 0,
          movieCount: ar.albumCount ?? 0,
        })),
      }
    }
  },

  person: async (id: string): Promise<PersonDetail> => {
    try {
      return await get<PersonDetail>(`/api/v2/people/${enc(id)}`)
    } catch {
      const ar = await subsonic.artist(id)
      return {
        id: ar.id,
        name: ar.name,
        roles: ar.roles ?? ['composer'],
        coverArt: ar.coverArt,
        songCount: 0,
        movieCount: ar.album?.length ?? 0,
        albums: (ar.album ?? []).map((a) => ({
          id: a.id,
          title: a.name,
          year: a.year,
          kind: 'album',
          coverArt: a.coverArt,
          composers: [{ id: ar.id, name: ar.name, role: 'composer' }],
          cast: [],
          songCount: a.songCount,
          durationMs: (a.duration ?? 0) * 1000,
        })),
        songs: [],
        movies: [],
      }
    }
  },

  song: async (id: string): Promise<SongDetail> => {
    return get<SongDetail>(`/api/v2/songs/${enc(id)}`)
  },

  searchLyrics: async (query: string, limit = 30): Promise<LyricsSearchResponse> => {
    return get<LyricsSearchResponse>('/api/v2/search/lyrics', { q: query, limit })
  },

  search: async (query: string): Promise<UnifiedSearch> => {
    return get<UnifiedSearch>('/api/v2/search', { q: query })
  },

  streamUrl: (songId: string, quality?: StreamingQuality): string => {
    const s = session()
    const tok = s.social?.token
    const q = quality ?? load<StreamingQuality>('player.quality', 'auto')
    if (tok) {
      const params = new URLSearchParams()
      if (q && q !== 'auto') params.set('quality', q)
      params.set('token', tok)
      return `/api/v2/songs/${enc(songId)}/stream?${params.toString()}`
    }
    return subsonic.streamUrl(songId)
  },
}

export type StreamingQuality = 'auto' | 'mobile' | 'original'
