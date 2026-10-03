import { create } from 'zustand'
import type { SocialUser } from '../api/types'
import { load, save, remove } from './storage'

/**
 * Login details for Navidrome. The password itself is never stored: Subsonic accepts
 * token = md5(password + salt), so only the salt and token are kept (like the Android app).
 * The web app is served from the same address as the servers, so no server address is needed.
 */
export interface Credentials {
  username: string
  salt: string
  token: string
}

/** Logged in to the friends server as user. */
export interface SocialSession {
  token: string
  user: SocialUser
}

interface SessionState {
  credentials: Credentials | null
  social: SocialSession | null
  /** Why the app logged out by itself, shown on the login screen. */
  logoutReason: string | null
  saveCredentials: (c: Credentials) => void
  saveSocial: (s: SocialSession | null) => void
  clear: (reason?: string) => void
}

export const useSession = create<SessionState>((set) => ({
  credentials: load<Credentials | null>('session.credentials', null),
  social: load<SocialSession | null>('session.social', null),
  logoutReason: null,
  saveCredentials: (c) => {
    save('session.credentials', c)
    set({ credentials: c, logoutReason: null })
  },
  saveSocial: (s) => {
    if (s) save('session.social', s)
    else remove('session.social')
    set({ social: s })
  },
  clear: (reason) => {
    remove('session.credentials')
    remove('session.social')
    set({ credentials: null, social: null, logoutReason: reason ?? null })
  },
}))

export const session = () => useSession.getState()
