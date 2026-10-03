import { create } from 'zustand'
import type { HomeMixes, Mix } from '../api/types'
import { subsonic } from '../api/subsonic'
import { social } from '../api/social'
import { session } from './session'
import { load, save } from './storage'

// ---- Which of your own playlists each song is in (the check mark on saved songs) ----

interface MyPlaylistsState {
  /** Song id -> names of your playlists it's in. */
  songs: Record<string, string[]>
  refresh: () => void
}

let myPlaylistsRun = 0

export const useMyPlaylists = create<MyPlaylistsState>((set) => ({
  songs: {},
  refresh: () => {
    const username = session().credentials?.username
    if (!username) return
    const run = ++myPlaylistsRun
    ;(async () => {
      const mine = (await subsonic.playlists()).filter((p) => p.owner === username)
      const full = await Promise.all(mine.map((p) => subsonic.playlist(p.id).catch(() => p)))
      const index: Record<string, string[]> = {}
      for (const p of full) for (const id of new Set((p.entry ?? []).map((s) => s.id))) (index[id] ??= []).push(p.name)
      if (run === myPlaylistsRun) set({ songs: index })
    })().catch(() => {})
  },
}))

// ---- Mixes on Home and the ones you saved to Your Library ----

interface MixesState {
  /** Null until loaded, or if the friends server has no mixes. */
  home: HomeMixes | null
  followed: Mix[]
  refresh: () => void
  isFollowed: (id: string) => boolean
  /** Saves a mix to Your Library (it keeps updating there), or removes it. */
  toggleFollow: (m: Mix) => Promise<void>
}

export const useMixes = create<MixesState>((set, get) => ({
  home: null,
  followed: [],
  refresh: () => {
    if (!session().social) return
    social.mixes().then((home) => set({ home })).catch(() => {})
    social.followedMixes().then((followed) => set({ followed })).catch(() => {})
  },
  isFollowed: (id) => get().followed.some((m) => m.id === id),
  toggleFollow: async (mix) => {
    if (get().isFollowed(mix.id)) {
      await social.unfollowMix(mix.id)
      set({ followed: get().followed.filter((m) => m.id !== mix.id) })
    } else {
      await social.followMix(mix.id)
      set({ followed: [{ ...mix, songs: [] }, ...get().followed] })
    }
  },
}))

// ---- Appearance: light, dark, or whatever the device is set to ----

export type ThemeMode = 'System' | 'Light' | 'Dark'
export const THEME_LABELS: Record<ThemeMode, string> = { System: 'Auto', Light: 'Light', Dark: 'Dark' }

interface AppearanceState {
  mode: ThemeMode
  setMode: (m: ThemeMode) => void
}

export const useAppearance = create<AppearanceState>((set) => ({
  mode: load<ThemeMode>('appearance.mode', 'System'),
  setMode: (mode) => {
    save('appearance.mode', mode)
    set({ mode })
  },
}))
