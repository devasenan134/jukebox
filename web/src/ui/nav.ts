import { useNavigate } from 'react-router-dom'
import { create } from 'zustand'
import type { Artist } from '../api/types'

// Navigation actions screens can call, the same set as the Android app's Nav (ui/MainScreen.kt).
// Each screen is a URL, so the browser's Back button and links work.

export const TABS = [
  { label: 'Home', path: '/', icon: 'home' },
  { label: 'Search', path: '/search', icon: 'search' },
  { label: 'Your Library', path: '/library', icon: 'library_music' },
  { label: 'Friends', path: '/friends', icon: 'group' },
] as const

/** Which bottom tab you're in. Screens opened from a tab (a movie, a chat, settings) stay in that tab. */
export const useTab = create<{ tab: number; setTab: (t: number) => void }>((set) => ({ tab: 0, setTab: (tab) => set({ tab }) }))

/** The full-screen player slides up over everything. */
export const usePlayerOpen = create<{ open: boolean; setOpen: (o: boolean) => void }>((set) => ({ open: false, setOpen: (open) => set({ open }) }))

const enc = encodeURIComponent

export function useNav() {
  const navigate = useNavigate()
  const go = (path: string) => {
    usePlayerOpen.getState().setOpen(false)
    navigate(path)
  }
  return {
    openAlbum: (id: string) => go(`/album/${enc(id)}`),
    openArtist: (id: string) => go(`/artist/${enc(id)}`),
    openPlaylist: (id: string) => go(`/playlist/${enc(id)}`),
    openChat: (id: number) => go(`/chat/${id}`),
    openSettings: () => go('/settings'),
    /** Everyone's listening stats (admins only). */
    openStats: () => go('/stats'),
    openAlbums: () => go('/albums'),
    openArtists: () => go('/artists'),
    openLikedSongs: () => go('/liked'),
    openPlaylists: () => go('/playlists'),
    openLibrary: () => go('/library'),
    openSearch: () => go('/search'),
    openSingers: () => go('/singers'),
    /** A singer's songs (a lyricist's or actor's page for them); a composer's movies go to openArtist. */
    openSinger: (a: Artist) => {
      const q = `?name=${enc(a.name)}` + (a.coverArt ? `&cover=${enc(a.coverArt)}` : '')
      if (a.roles?.includes('lyricist') || a.roles?.includes('actor')) go(`/person/${enc(a.id)}${q}`)
      else go(`/singer/${enc(a.id)}${q}`)
    },
    /** A mix, playlist or station by Jukebox. */
    openMix: (id: string) => go(`/mix/${enc(id)}`),
    /** Your requests for music that isn't in the library (admins: everyone's). */
    openRequests: () => go('/requests'),
    /** Import a playlist from Spotify, Apple Music, YouTube or a file. */
    openImport: () => go('/import'),
    back: () => (history.length > 1 ? navigate(-1) : navigate('/')),
  }
}

export type Nav = ReturnType<typeof useNav>
