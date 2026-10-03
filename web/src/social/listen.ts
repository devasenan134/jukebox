import { create } from 'zustand'
import type { Song } from '../api/types'
import { songToRef } from '../api/types'
import type { ClientEvent, Conversation, ListenState } from '../api/socialTypes'
import { session } from '../state/session'

/**
 * Listen together (a "jam"): everyone in a chat's session hears the same music. Whoever started it
 * owns it and is the only one who can play, pause, skip, seek or change the queue; the others can
 * ask for a song with a request in the chat. Port of social/ListenTogether.kt.
 *
 * This keeps track of the sessions (for the chat screens) and which one we're in. listenSync.ts
 * does the actual syncing: it applies remote to the player and reports our own changes through update.
 */

/** A state from the session, with where the song should be right when it arrived. */
export interface Remote {
  state: ListenState
  positionMs: number
  receivedAt: number
}

/** Where the song should be now. */
export const positionNow = (r: Remote) => r.positionMs + (r.state.playing ? performance.now() - r.receivedAt : 0)

interface ListenStore {
  /** Chat id -> who is listening together there. */
  sessions: Record<number, number[]>
  /** Chat id -> who controls the session there. */
  owners: Record<number, number>
  /** The chat whose session we're in, if any. */
  joined: number | null
  /** The latest playback state from the session, for the player to follow. */
  remote: Remote | null
  isOwner: () => boolean
  isListener: () => boolean
  start: (conversationId: number) => void
  join: (conversationId: number) => void
  leave: () => void
  update: (state: ListenState) => void
  onConnected: () => void
  onConversations: (list: Conversation[]) => void
  handleSession: (conversationId: number, listeners: number[], owner?: number) => void
  handleState: (conversationId: number, state: ListenState, serverTime: number) => void
  /** Asks the jam's owner to play song next, or right away. Set by the social connection. */
  requestSong: (song: Song, playNow: boolean) => void
}

const me = () => session().social?.user.id

let sendEvent: (e: ClientEvent) => void = () => {}
export const setListenSender = (f: (e: ClientEvent) => void) => (sendEvent = f)

/** The player's current state as a session state. Set by listenSync while it runs. */
let snapshot: (() => ListenState) | null = null
export const setListenSnapshot = (f: (() => ListenState) | null) => (snapshot = f)

export const newQueueId = () => crypto.randomUUID()

const currentState = (): ListenState => snapshot?.() ?? { queue: [], queueId: newQueueId(), index: 0, positionMs: 0, playing: false }

// Until the server confirms our join, session lists without us are from before it.
let confirmed = false

export const useListen = create<ListenStore>((set, get) => ({
  sessions: {},
  owners: {},
  joined: null,
  remote: null,
  isOwner: () => {
    const j = get().joined
    return j != null && get().owners[j] === me()
  },
  isListener: () => {
    const j = get().joined
    if (j == null) return false
    const o = get().owners[j]
    return o != null && o !== me()
  },
  start: (conversationId) => {
    const m = me()
    // We own it unless someone else already started one there (the server's answer corrects this).
    set((s) => ({ joined: conversationId, owners: conversationId in s.owners || m == null ? s.owners : { ...s.owners, [conversationId]: m } }))
    confirmed = false
    sendEvent({ type: 'listenStart', conversationId, state: currentState() })
  },
  join: (conversationId) => {
    set({ joined: conversationId })
    confirmed = false
    sendEvent({ type: 'listenJoin', conversationId })
  },
  /** Leaves the session. The music keeps playing, just not in sync anymore. */
  leave: () => {
    const id = get().joined
    if (id == null) return
    sendEvent({ type: 'listenLeave', conversationId: id })
    set({ joined: null, remote: null })
  },
  update: (state) => {
    const id = get().joined
    if (id != null) sendEvent({ type: 'listenUpdate', conversationId: id, state })
  },
  /** After reconnecting, get back into our session (the owner restarts it; a listener only rejoins). */
  onConnected: () => {
    const id = get().joined
    if (id == null) return
    confirmed = false
    sendEvent(get().isOwner() ? { type: 'listenStart', conversationId: id, state: currentState() } : { type: 'listenJoin', conversationId: id })
  },
  onConversations: (list) => {
    set({
      sessions: Object.fromEntries(list.filter((c) => c.listeners?.length).map((c) => [c.id, c.listeners!])),
      owners: Object.fromEntries(list.filter((c) => c.listeners?.length && c.listenOwner != null).map((c) => [c.id, c.listenOwner!])),
    })
  },
  handleSession: (conversationId, listeners, owner) => {
    set((s) => {
      const sessions = { ...s.sessions }
      const owners = { ...s.owners }
      if (!listeners.length) delete sessions[conversationId]
      else sessions[conversationId] = listeners
      if (!listeners.length || owner == null) delete owners[conversationId]
      else owners[conversationId] = owner
      const dropped = conversationId === s.joined && confirmed && !listeners.includes(me() ?? -1)
      return dropped ? { sessions, owners, joined: null, remote: null } : { sessions, owners }
    })
  },
  handleState: (conversationId, state, serverTime) => {
    if (conversationId !== get().joined) return
    confirmed = true
    const elapsed = state.playing ? Math.max(0, serverTime - (state.updatedAt ?? serverTime)) : 0
    set({ remote: { state, positionMs: state.positionMs + elapsed, receivedAt: performance.now() } })
  },
  requestSong: () => {},
}))

export { songToRef }
