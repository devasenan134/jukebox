import { create } from 'zustand'
import type { Album, Playlist, Song } from '../api/types'
import { subsonic } from '../api/subsonic'
import { social } from '../api/social'
import { session } from './session'
import { load, save } from './storage'

/**
 * What you've liked, for the hearts everywhere and the Your Library page (as data/Likes.kt).
 *
 * Songs and movies are liked in Navidrome itself ("starred"), so they're the same on every device.
 * Navidrome can't like playlists, so liked playlists are saved with your account on the friends
 * server. A copy is kept in the browser so the library shows right away. Changes show immediately
 * and are undone if refused.
 */
interface LikesState {
  songs: Song[]
  albums: Album[]
  playlists: Playlist[]
  refresh: () => void
  syncPlaylists: () => Promise<void>
  toggleSong: (s: Song) => void
  toggleAlbum: (a: Album) => void
  togglePlaylist: (p: Playlist) => void
  clear: () => void
}

const now = () => new Date().toISOString()

export const useLikes = create<LikesState>((set, get) => {
  const savePlaylists = (list: Playlist[]) => {
    save('likes.playlists', list)
    set({ playlists: list })
  }
  return {
    songs: [],
    albums: [],
    playlists: load<Playlist[]>('likes.playlists', []),

    refresh: () => {
      subsonic.starred().then((s) => set({ songs: s.song, albums: s.album })).catch(() => {})
      if (session().social) get().syncPlaylists()
    },

    syncPlaylists: async () => {
      try {
        savePlaylists(await social.likedPlaylists())
      } catch {
        // Keep the saved copy.
      }
    },

    toggleSong: (song) => {
      const before = get().songs
      const like = !before.some((s) => s.id === song.id)
      set({ songs: like ? [{ ...song, starred: now() }, ...before] : before.filter((s) => s.id !== song.id) })
      subsonic.like({ songId: song.id }, like).catch(() => set({ songs: before }))
    },

    toggleAlbum: (album) => {
      const before = get().albums
      const like = !before.some((a) => a.id === album.id)
      set({ albums: like ? [{ ...album, song: undefined, starred: now() }, ...before] : before.filter((a) => a.id !== album.id) })
      subsonic.like({ albumId: album.id }, like).catch(() => set({ albums: before }))
    },

    togglePlaylist: (playlist) => {
      const before = get().playlists
      const like = !before.some((p) => p.id === playlist.id)
      savePlaylists(like ? [{ ...playlist, entry: undefined }, ...before] : before.filter((p) => p.id !== playlist.id))
      if (!session().social) return
      ;(like ? social.likePlaylist(playlist) : social.unlikePlaylist(playlist.id)).catch(() => savePlaylists(before))
    },

    clear: () => {
      set({ songs: [], albums: [] })
      savePlaylists([])
    },
  }
})

export const likes = () => useLikes.getState()
