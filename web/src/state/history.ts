import { create } from 'zustand'
import type { Album, Artist, Mix, Playlist, Song, SongRef } from '../api/types'
import { MIX_AUTHOR, songToRef } from '../api/types'
import { load, save } from './storage'

// What you did recently, kept in the browser (as the Android app keeps it on the phone):
// RecentActivity, RecentSongs, RecentPlaylists, SearchHistory and QueueMemory.

// ---- Recently played (Home) ----

export type ActivityKind = 'Song' | 'Movie' | 'Playlist' | 'Composer' | 'Artist' | 'Liked' | 'Mix'

export interface ActivityItem {
  kind: ActivityKind
  id: string
  title: string
  subtitle?: string
  coverArt?: string
  /** For a song: what's needed to play it again. */
  song?: SongRef
  playedAt: number
}

/** Songs and whole movies/playlists share the list, so keep enough of both. */
const MAX_ACTIVITY = 40

interface ActivityState {
  items: ActivityItem[]
  played: (item: Omit<ActivityItem, 'playedAt'>) => void
  forget: (kind: ActivityKind, id: string) => void
}

export const useActivity = create<ActivityState>((set, get) => ({
  items: load<ActivityItem[]>('recent.activity', []),
  played: (item) => {
    const list = [{ ...item, playedAt: Date.now() }, ...get().items.filter((i) => !(i.kind === item.kind && i.id === item.id))].slice(0, MAX_ACTIVITY)
    save('recent.activity', list)
    set({ items: list })
  },
  forget: (kind, id) => {
    const list = get().items.filter((i) => !(i.kind === kind && i.id === id))
    save('recent.activity', list)
    set({ items: list })
  },
}))

export const activity = {
  song: (s: Song) => useActivity.getState().played({ kind: 'Song', id: s.id, title: s.title, subtitle: s.artist, coverArt: s.coverArt, song: songToRef(s) }),
  movie: (a: Album) => useActivity.getState().played({ kind: 'Movie', id: a.id, title: a.name, subtitle: a.artist, coverArt: a.coverArt }),
  playlist: (p: Playlist) => useActivity.getState().played({ kind: 'Playlist', id: p.id, title: p.name, subtitle: 'Playlist', coverArt: p.coverArt }),
  composer: (a: Artist) => useActivity.getState().played({ kind: 'Composer', id: a.id, title: a.name, subtitle: 'Composer', coverArt: a.coverArt }),
  artist: (id: string, name: string, coverArt?: string) => useActivity.getState().played({ kind: 'Artist', id, title: name, subtitle: 'Artist', coverArt }),
  liked: () => useActivity.getState().played({ kind: 'Liked', id: 'liked', title: 'Liked songs', subtitle: 'Playlist' }),
  mix: (m: Mix) =>
    useActivity.getState().played({
      kind: 'Mix', id: m.id, title: m.title, subtitle: (m.endless ? 'Station by ' : 'By ') + MIX_AUTHOR, coverArt: m.covers[0],
    }),
}

// ---- Songs played recently (for quick sharing in chats) ----

interface RecentSongsState {
  songs: SongRef[]
  played: (s: SongRef) => void
}

export const useRecentSongs = create<RecentSongsState>((set, get) => ({
  songs: load<SongRef[]>('recent.songs', []),
  played: (s) => {
    const plain = { ...s, clipStartMs: undefined, clipEndMs: undefined }
    const list = [plain, ...get().songs.filter((x) => x.id !== plain.id)].slice(0, 20)
    save('recent.songs', list)
    set({ songs: list })
  },
}))

// ---- Playlists played from recently ----

interface RecentPlaylistsState {
  playlists: Playlist[]
  played: (p: Playlist) => void
  forget: (id: string) => void
}

export const useRecentPlaylists = create<RecentPlaylistsState>((set, get) => ({
  playlists: load<Playlist[]>('recent.playlists', []),
  played: (p) => {
    const card = { ...p, entry: undefined }
    const list = [card, ...get().playlists.filter((x) => x.id !== card.id)].slice(0, 15)
    save('recent.playlists', list)
    set({ playlists: list })
  },
  forget: (id) => {
    const list = get().playlists.filter((x) => x.id !== id)
    save('recent.playlists', list)
    set({ playlists: list })
  },
}))

// ---- Search history and recents ----

interface SearchHistoryState {
  queries: string[]
  songs: SongRef[]
  albums: Album[]
  artists: Artist[]
  /** Saves a search you actually used (not every half-typed word). */
  add: (q: string) => void
  remove: (q: string) => void
  pickedSong: (s: Song) => void
  pickedAlbum: (a: Album) => void
  pickedArtist: (a: Artist) => void
  /** Clears the searched words (the "Clear" button). */
  clear: () => void
}

const MAX_SEARCH = 15

export const useSearchHistory = create<SearchHistoryState>((set, get) => {
  const put = <K extends 'queries' | 'songs' | 'albums' | 'artists'>(key: K, list: SearchHistoryState[K]) => {
    save('search.' + key, list)
    set({ [key]: list } as Pick<SearchHistoryState, K>)
  }
  return {
    queries: load<string[]>('search.queries', []),
    songs: load<SongRef[]>('search.songs', []),
    albums: load<Album[]>('search.albums', []),
    artists: load<Artist[]>('search.artists', []),
    add: (query) => {
      const q = query.trim()
      if (q.length < 2) return
      put('queries', [q, ...get().queries.filter((x) => x.toLowerCase() !== q.toLowerCase())].slice(0, MAX_SEARCH))
    },
    remove: (q) => put('queries', get().queries.filter((x) => x !== q)),
    pickedSong: (s) => put('songs', [songToRef(s), ...get().songs.filter((x) => x.id !== s.id)].slice(0, MAX_SEARCH)),
    pickedAlbum: (a) => put('albums', [{ ...a, song: undefined }, ...get().albums.filter((x) => x.id !== a.id)].slice(0, MAX_SEARCH)),
    pickedArtist: (a) => put('artists', [{ ...a, album: undefined }, ...get().artists.filter((x) => x.id !== a.id)].slice(0, MAX_SEARCH)),
    clear: () => put('queries', []),
  }
})

// ---- Where you left off in a playlist ----

/** The queue in the order it was playing (a shuffled order is kept) and which song you were on. */
export interface SavedQueue {
  songIds: string[]
  currentId: string
  savedAt: number
}

export const queueMemory = {
  get: (source: string): SavedQueue | undefined => load<Record<string, SavedQueue>>('queues', {})[source],
  save: (source: string, songIds: string[], currentId: string) => {
    if (!songIds.length) return
    const all = load<Record<string, SavedQueue>>('queues', {})
    all[source] = { songIds, currentId, savedAt: Date.now() }
    // Keep the 30 most recent.
    const keep = Object.entries(all).sort((a, b) => b[1].savedAt - a[1].savedAt).slice(0, 30)
    save('queues', Object.fromEntries(keep))
  },
}
