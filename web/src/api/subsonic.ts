import { md5 } from 'js-md5'
import type {
  Album, Artist, LyricLine, Playlist, SearchResult, Song, SongDetails, Starred, StructuredLyrics,
} from './types'
import { session, type Credentials } from '../state/session'

// Talks to Jukebox through the Subsonic API: /rest/<endpoint>?<auth>&<params>, on the same
// address the web app is served from.
// Docs: https://opensubsonic.netlify.app/docs/  Same calls as the Android app's SubsonicApi.kt.

export class SubsonicError extends Error {
  code?: string
  constructor(message: string, code?: string) {
    super(message)
    this.code = code
  }
}

const API_VERSION = '1.16.1'
const CLIENT_NAME = 'jukebox-web'

type Params = Record<string, string | number | boolean | (string | number)[] | undefined | null>

/** Builds login credentials from a password, without keeping the password. */
export function credentialsFor(username: string, password: string): Credentials {
  const bytes = crypto.getRandomValues(new Uint8Array(8))
  const salt = [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('')
  return { username: username.trim(), salt, token: md5(password + salt) }
}

function url(endpoint: string, params: Params = {}, creds?: Credentials): string {
  const c = creds ?? session().credentials
  if (!c) throw new SubsonicError('Not logged in')
  const q = new URLSearchParams({ u: c.username, t: c.token, s: c.salt, v: API_VERSION, c: CLIENT_NAME, f: 'json' })
  // A list becomes the same parameter repeated (e.g. several songIdToAdd).
  for (const [key, value] of Object.entries(params)) {
    if (value == null) continue
    if (Array.isArray(value)) value.forEach((v) => q.append(key, String(v)))
    else q.append(key, String(value))
  }
  return `/rest/${endpoint}?${q}`
}

/** Called when Navidrome rejects the saved login (e.g. the password was changed on another device). */
let onLoginRejected: () => void = () => {}
export const setLoginRejectedHandler = (f: () => void) => (onLoginRejected = f)

// Makes a request and returns the body of `subsonic-response`, or throws with the server's error message.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
async function get(endpoint: string, params: Params = {}, creds?: Credentials): Promise<any> {
  let res: Response
  try {
    res = await fetch(url(endpoint, params, creds))
  } catch {
    throw new SubsonicError("Can't reach the music server")
  }
  if (!res.ok) throw new SubsonicError(`Server returned HTTP ${res.status}`)
  let body
  try {
    body = (await res.json())['subsonic-response']
  } catch {
    body = undefined
  }
  if (!body) throw new SubsonicError("That doesn't look like a Navidrome server")
  if (body.status !== 'ok') {
    const code = body.error?.code != null ? String(body.error.code) : undefined
    // Error 40 = wrong username or password. Only react when using the saved login.
    if (!creds && code === '40') onLoginRejected()
    throw new SubsonicError(body.error?.message ?? 'Request failed', code)
  }
  return body
}

/**
 * Navidrome keeps a playlist's cover id ("pl-<id>") the same when its picture changes, so browsers
 * would keep showing the old one. Adding when it last changed ("pl-<id>_<hex>") gives a new picture a new address.
 */
function withFreshCover(p: Playlist): Playlist {
  const art = p.coverArt
  if (!art || !art.startsWith('pl-') || art.includes('_') || !p.changed) return p
  const t = Date.parse(p.changed)
  if (Number.isNaN(t)) return p
  return { ...p, coverArt: art + '_' + Math.floor(t / 1000).toString(16) }
}

export const subsonic = {
  ping: (creds: Credentials) => get('ping', {}, creds),

  /** type is one of: newest, recent, frequent, random, alphabeticalByName, byYear, starred. */
  albumList: async (type: string, size = 50, offset = 0, extra: Params = {}): Promise<Album[]> =>
    (await get('getAlbumList2', { type, size, offset, ...extra })).albumList2?.album ?? [],

  album: async (id: string): Promise<Album> => {
    const a = (await get('getAlbum', { id })).album
    if (!a) throw new SubsonicError('Album not found')
    return a
  },

  /** Album artists, which in this library are the music directors. */
  artists: async (): Promise<Artist[]> =>
    ((await get('getArtists')).artists?.index ?? []).flatMap((i: { artist?: Artist[] }) => i.artist ?? []),

  artist: async (id: string): Promise<Artist> => {
    const a = (await get('getArtist', { id })).artist
    if (!a) throw new SubsonicError('Artist not found')
    return a
  },

  search: async (query: string): Promise<SearchResult> => {
    const r = (await get('search3', { query, artistCount: 10, albumCount: 20, songCount: 50 })).searchResult3 ?? {}
    return { artist: r.artist ?? [], album: r.album ?? [], song: r.song ?? [] }
  },

  /** Singers (artists with the "artist" role), a page at a time, A to Z. Goes through search, which covers everyone. */
  singers: async (offset: number, count = 200): Promise<Artist[]> =>
    (await get('search3', { query: '', artistCount: count, artistOffset: offset, albumCount: 0, songCount: 0 })).searchResult3?.artist ?? [],

  /** A singer's songs: search by their name, then keep the songs they're actually credited on. */
  songsBy: async (artistId: string, name: string): Promise<Song[]> =>
    ((await get('search3', { query: name, artistCount: 0, albumCount: 0, songCount: 500 })).searchResult3?.song ?? [])
      .filter((s: Song) => s.artists?.some((a) => a.id === artistId)),

  /** Makes a playlist with name (and optionally a first song). Returns it. */
  createPlaylist: async (name: string, songId?: string): Promise<Playlist> => {
    const p = (await get('createPlaylist', { name, songId })).playlist
    if (!p) throw new SubsonicError("Couldn't create the playlist")
    return p
  },

  /** Changes a playlist you own: add songs, remove songs (by position), rename, or make it public or private. */
  updatePlaylist: (id: string, o: { add?: string[]; removeIndexes?: number[]; name?: string; public?: boolean; comment?: string }) =>
    get('updatePlaylist', {
      playlistId: id,
      name: o.name,
      public: o.public,
      comment: o.comment,
      songIdToAdd: o.add?.length ? o.add : undefined,
      songIndexToRemove: o.removeIndexes?.length ? o.removeIndexes : undefined,
    }),

  /** Puts a playlist's songs in this order (createPlaylist with the playlist's id replaces its songs). */
  replacePlaylist: (id: string, songIds: string[]) => get('createPlaylist', { playlistId: id, songId: songIds }),

  deletePlaylist: (id: string) => get('deletePlaylist', { id }),

  playlists: async (): Promise<Playlist[]> => ((await get('getPlaylists')).playlists?.playlist ?? []).map(withFreshCover),

  playlist: async (id: string): Promise<Playlist> => {
    const p = (await get('getPlaylist', { id })).playlist
    if (!p) throw new SubsonicError('Playlist not found')
    return withFreshCover(p)
  },

  songDetails: async (id: string): Promise<SongDetails> => {
    const s = (await get('getSong', { id })).song
    if (!s) throw new SubsonicError('Song not found')
    return s
  },

  /** Your liked songs and movies (Navidrome's "starred" items), newest likes first. */
  starred: async (): Promise<Starred> => {
    const s = (await get('getStarred2')).starred2 ?? {}
    const byNewest = (a: { starred?: string }, b: { starred?: string }) => (b.starred ?? '').localeCompare(a.starred ?? '')
    return { album: [...(s.album ?? [])].sort(byNewest), song: [...(s.song ?? [])].sort(byNewest) }
  },

  /** Likes (stars) a song or a movie in Navidrome, so it's liked on every device. */
  like: (o: { songId?: string; albumId?: string }, liked: boolean) =>
    get(liked ? 'star' : 'unstar', { id: o.songId, albumId: o.albumId }),

  lyrics: async (songId: string): Promise<StructuredLyrics[]> =>
    ((await get('getLyricsBySongId', { id: songId })).lyricsList?.structuredLyrics ?? []).map(
      (l: StructuredLyrics) => ({ ...l, line: (l.line ?? []) as LyricLine[] }),
    ),

  /** Records a play. submission=false means "now playing"; true adds it to play counts and history. */
  scrobble: (songId: string, submission: boolean) => get('scrobble', { id: songId, submission, time: Date.now() }),

  /**
   * Changes the password through Navidrome's own API, as the user themselves (no admin involved).
   * Navidrome checks `current` and only lets normal users change their own password, name and email.
   */
  changePassword: async (current: string, next: string) => {
    const creds = session().credentials
    if (!creds) throw new SubsonicError('Not logged in')
    // 1. Log in to Navidrome with the current password (this is what proves it's really you).
    const login = await fetch('/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: creds.username, password: current }),
    })
    if (login.status === 401) throw new SubsonicError('Your current password is wrong')
    if (login.status === 429) throw new SubsonicError('Too many attempts. Wait a minute and try again')
    if (!login.ok) throw new SubsonicError(`Navidrome returned ${login.status}`)
    const { token, id } = await login.json()
    if (!token || !id) throw new SubsonicError("Navidrome didn't accept the login")
    const auth = { 'X-ND-Authorization': `Bearer ${token}` }
    // 2. Read your own account record, so the unchanged fields are sent back as they are.
    const rec = await fetch(`/api/user/${id}`, { headers: auth })
    if (!rec.ok) throw new SubsonicError(`Couldn't read your account (${rec.status})`)
    const record = await rec.json()
    // 3. Save it with the new password.
    const update = await fetch(`/api/user/${id}`, {
      method: 'PUT',
      headers: { ...auth, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        id: record.id, userName: record.userName, name: record.name, email: record.email,
        currentPassword: current, password: next,
      }),
    })
    if (update.status === 400) throw new SubsonicError('Navidrome refused the change. Check your current password')
    if (!update.ok) throw new SubsonicError(`Couldn't change the password (${update.status})`)
  },

  streamUrl: (songId: string) => url('stream', { id: songId }),

  coverUrl: (coverArtId: string | undefined | null, size = 300): string | undefined =>
    coverArtId ? url('getCoverArt', { id: coverArtId, size }) : undefined,
}
