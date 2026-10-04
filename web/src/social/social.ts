import { create } from 'zustand'
import type { Song, SongRef } from '../api/types'
import { songToRef } from '../api/types'
import { social as api, SocialError } from '../api/social'
import type { ChatMessage, ClientEvent, Conversation, Friend, FriendRequests, SocialEvent } from '../api/socialTypes'
import { session, useSession } from '../state/session'
import { useListen, setListenSender } from './listen'
import { onPlayback } from '../player/player'
import { toast } from '../ui/kit'

/**
 * Everything friends-related the UI needs, kept up to date live (port of social/Social.kt).
 *
 * The app stays connected to the friends server while the page is visible or playing music (so
 * friends see you online and what you're listening to), and disconnects 30 seconds after both
 * stop. If the connection drops, it reconnects by itself with growing pauses.
 */

export type Status = 'Offline' | 'Connecting' | 'Online' | 'Unavailable'

interface SocialStore {
  status: Status
  friends: Friend[]
  requests: FriendRequests
  conversations: Conversation[]
  /** Who is typing where: chat -> person -> when we last heard. */
  typing: Record<number, Record<number, number>>
}

export const useSocial = create<SocialStore>(() => ({
  status: 'Offline',
  friends: [],
  requests: { incoming: [], outgoing: [] },
  conversations: [],
  typing: {},
}))

const setS = (s: Partial<SocialStore>) => useSocial.setState(s)

export const me = () => session().social?.user

// New messages (and changed ones) as they arrive, for the open chat screen.
type MessageListener = (m: ChatMessage) => void
const messageListeners = new Set<MessageListener>()
export const onMessage = (f: MessageListener) => {
  messageListeners.add(f)
  return () => void messageListeners.delete(f)
}

// Chats deleted for everyone (by their owner), so an open chat screen can close.
const removedListeners = new Set<(id: number) => void>()
export const onRemoved = (f: (id: number) => void) => {
  removedListeners.add(f)
  return () => void removedListeners.delete(f)
}

// Any other live event, for code that needs to react to more (listen together, notifications).
const eventListeners = new Set<(e: SocialEvent) => void>()
export const onEvent = (f: (e: SocialEvent) => void) => {
  eventListeners.add(f)
  return () => void eventListeners.delete(f)
}

/** The chat currently on screen; its new messages are marked read right away. */
export let openConversationId: number | null = null
export const setOpenConversation = (id: number | null) => (openConversationId = id)

let socket: WebSocket | null = null

export function sendEvent(e: ClientEvent) {
  if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(e))
}
setListenSender(sendEvent)

let lastTypingSent = 0
let lastTypingChat = 0
/** Tells the others you're typing in a chat, at most every 3 seconds. */
export function sendTyping(conversationId: number) {
  const now = Date.now()
  if (conversationId === lastTypingChat && now - lastTypingSent < 3000) return
  lastTypingSent = now
  lastTypingChat = conversationId
  sendEvent({ type: 'typing', conversationId })
}

// ---- Refreshing ----

const byDisplay = (list: Friend[]) =>
  [...list].sort((a, b) => Number(b.online) - Number(a.online) || a.user.displayName.toLowerCase().localeCompare(b.user.displayName.toLowerCase()))

async function quietly(f: () => Promise<void>) {
  try {
    await f()
  } catch {
    // The next refresh tries again.
  }
}

const refreshFriends = () => quietly(async () => setS({ friends: byDisplay(await api.friends()) }))
const refreshRequests = () => quietly(async () => setS({ requests: await api.friendRequests() }))
const refreshConversations = () =>
  quietly(async () => {
    const conversations = await api.conversations()
    setS({ conversations })
    useListen.getState().onConversations(conversations)
  })

/** Reloads friends, requests and chats from the server. */
export function refresh() {
  void refreshFriends()
  void refreshRequests()
  void refreshConversations()
}
export const refreshConversationsSoon = () => void refreshConversations()

export function markRead(conversationId: number, messageId: number) {
  setS({ conversations: useSocial.getState().conversations.map((c) => (c.id === conversationId ? { ...c, unread: 0, unreadMentions: 0 } : c)) })
  void quietly(() => api.markRead(conversationId, messageId))
}

export async function leaveGroup(conversationId: number) {
  if (useListen.getState().joined === conversationId) useListen.getState().leave()
  await api.leaveGroup(conversationId)
  setS({ conversations: useSocial.getState().conversations.filter((c) => c.id !== conversationId) })
}

export async function deleteForEveryone(conversationId: number) {
  await api.deleteForEveryone(conversationId)
  setS({ conversations: useSocial.getState().conversations.filter((c) => c.id !== conversationId) })
}

export async function deleteConversation(conversationId: number) {
  await api.deleteConversation(conversationId)
  setS({ conversations: useSocial.getState().conversations.filter((c) => c.id !== conversationId) })
}

// ---- Staying connected ----

let wanted = false
let loopRunning = false
let stopTimer: ReturnType<typeof setTimeout> | undefined
let wake: (() => void) | null = null

const visible = () => document.visibilityState === 'visible'
let playing = false
let nowPlaying: SongRef | null = null

/** Logs in to the friends server with the saved Navidrome login, if not done yet. */
async function ensureLoggedIn() {
  const s = session()
  if (s.social) return
  if (!s.credentials) throw new SocialError('Not logged in')
  const r = await api.login(s.credentials)
  s.saveSocial({ token: r.sessionToken, user: r.user })
}

async function refreshAll() {
  try {
    setS({ friends: byDisplay(await api.friends()) })
  } catch (e) {
    if (e instanceof SocialError && e.code === 401) session().saveSocial(null) // expired; next attempt logs in again
    throw e
  }
  await refreshRequests()
  await refreshConversations()
}

/** Opens the WebSocket and resolves when it closes. */
function runSocket(): Promise<void> {
  return new Promise((resolve) => {
    const url = api.eventsUrl()
    if (!url) return resolve()
    const ws = new WebSocket(url)
    socket = ws
    ws.onopen = () => {
      setS({ status: 'Online' })
      sendEvent({ type: 'appState', visible: visible() })
      if (nowPlaying) sendEvent({ type: 'nowPlaying', song: nowPlaying })
      useListen.getState().onConnected()
    }
    ws.onmessage = (m) => {
      let event: SocialEvent
      try {
        event = JSON.parse(m.data)
      } catch {
        return
      }
      handle(event)
    }
    const done = () => {
      if (socket === ws) socket = null
      resolve()
    }
    ws.onclose = done
    ws.onerror = () => ws.close()
    wake = () => ws.close()
  })
}

async function stayConnected() {
  if (loopRunning) return
  loopRunning = true
  let backoff = 2000
  while (wanted && session().credentials) {
    setS({ status: 'Connecting' })
    try {
      await ensureLoggedIn()
      await refreshAll()
      await runSocket()
      backoff = 2000
    } catch {
      setS({ status: 'Unavailable' })
    }
    if (!wanted) break
    await new Promise<void>((r) => {
      const t = setTimeout(r, backoff)
      wake = () => {
        clearTimeout(t)
        r()
      }
    })
    backoff = Math.min(backoff * 2, 60_000)
  }
  loopRunning = false
  setS({ status: 'Offline' })
}

/** Connect while the page is visible or music plays; let go 30 seconds after both stop. */
function reconsider() {
  const want = !!session().credentials && (visible() || playing)
  if (want) {
    clearTimeout(stopTimer)
    if (!wanted) {
      wanted = true
      void stayConnected()
    }
  } else if (wanted) {
    clearTimeout(stopTimer)
    stopTimer = setTimeout(() => {
      wanted = false
      socket?.close()
      wake?.()
    }, 30_000)
  }
}

document.addEventListener('visibilitychange', () => {
  sendEvent({ type: 'appState', visible: visible() })
  reconsider()
  // Coming back after a long time: catch up at once instead of waiting out a pause.
  if (visible() && useSocial.getState().status === 'Unavailable') wake?.()
})
useSession.subscribe((s, prev) => {
  if (s.credentials !== prev.credentials) reconsider()
})

// Tell friends what's playing (only while it's actually playing).
onPlayback((song, isPlaying) => {
  playing = isPlaying
  const shown = isPlaying ? song : null
  if (shown?.id !== nowPlaying?.id || (shown == null) !== (nowPlaying == null)) {
    nowPlaying = shown
    sendEvent({ type: 'nowPlaying', song: shown })
  }
  reconsider()
})

export function startSocial() {
  reconsider()
}

/** Logs out of the friends server. */
export async function logoutSocial() {
  useListen.getState().leave()
  await quietly(() => api.logout())
  wanted = false
  socket?.close()
  setS({ friends: [], requests: { incoming: [], outgoing: [] }, conversations: [], status: 'Offline' })
}

// ---- Live events ----

function handle(event: SocialEvent) {
  eventListeners.forEach((f) => f(event))
  const st = useSocial.getState()
  switch (event.type) {
    case 'presence':
      setS({
        friends: byDisplay(st.friends.map((f) => (f.user.id === event.userId ? { ...f, online: event.online, nowPlaying: event.nowPlaying } : f))),
      })
      break
    case 'message': {
      const m = event.message
      // Their message is sent: they've stopped typing.
      const t = { ...st.typing }
      if (t[m.conversationId]) {
        t[m.conversationId] = { ...t[m.conversationId] }
        delete t[m.conversationId][m.sender.id]
        setS({ typing: t })
      }
      messageListeners.forEach((f) => f(m))
      if (m.conversationId === openConversationId && visible()) markRead(m.conversationId, m.id)
      void refreshConversations()
      break
    }
    case 'friendRequest':
      void refreshRequests()
      break
    case 'conversationUpdated':
      void refreshConversations()
      break
    case 'typing':
      setS({ typing: { ...st.typing, [event.conversationId]: { ...(st.typing[event.conversationId] ?? {}), [event.userId]: Date.now() } } })
      break
    case 'read':
      // Someone read up to a message: "Seen" can change without asking the server.
      setS({
        conversations: st.conversations.map((c) =>
          c.id !== event.conversationId
            ? c
            : { ...c, readMarks: [...(c.readMarks ?? []).filter((r) => r.userId !== event.userId), { userId: event.userId, lastReadId: event.messageId }] },
        ),
      })
      break
    case 'conversationRemoved':
      setS({ conversations: st.conversations.filter((c) => c.id !== event.conversationId) })
      removedListeners.forEach((f) => f(event.conversationId))
      break
    case 'messageUpdated':
      messageListeners.forEach((f) => f(event.message))
      void refreshConversations()
      break
    case 'listenSession':
      useListen.getState().handleSession(event.conversationId, event.listeners, event.owner)
      break
    case 'listenState':
      useListen.getState().handleState(event.conversationId, event.state, event.serverTime)
      break
    case 'friendAdded':
    case 'friendRemoved':
      void refreshFriends()
      void refreshRequests()
      break
  }
}

// ---- Song requests while listening together ----

/** The name of whoever controls the jam we're in, for messages like "Only Alice can…". */
export function jamOwnerName(): string | undefined {
  const l = useListen.getState()
  if (l.joined == null) return undefined
  const owner = l.owners[l.joined]
  return useSocial.getState().conversations.find((c) => c.id === l.joined)?.members.find((m) => m.id === owner)?.displayName
}

/** When we last asked for a song, to space requests out (the server enforces it too). */
let lastRequestAt = 0
const REQUEST_EVERY_MS = 10_000

/** Asks the owner of the jam we're in to play song next, or right away if playNow. Returns what to tell the user. */
export async function requestSong(song: SongRef, playNow: boolean): Promise<string> {
  const id = useListen.getState().joined
  if (id == null) return "You're not listening together"
  const wait = lastRequestAt + REQUEST_EVERY_MS - Date.now()
  if (wait > 0) return `Wait ${Math.ceil(wait / 1000)} s before asking again`
  lastRequestAt = Date.now()
  try {
    await api.requestSong(id, song, playNow)
    return `Asked ${jamOwnerName() ?? 'the host'} to play ${song.title} ${playNow ? 'now' : 'next'}`
  } catch (e) {
    // Nothing reached the owner (unless the server said to slow down): the user can try again at once.
    if (!(e instanceof SocialError && e.code === 429)) lastRequestAt = 0
    return (e as Error).message || "Couldn't send the request"
  }
}

useListen.setState({
  requestSong: (song: Song, playNow: boolean) => {
    void requestSong(songToRef(song), playNow).then((m) => toast(m))
  },
})

/** Chats with something new, plus friend requests waiting: the number on the Friends tab. */
export function useFriendsBadge() {
  const chats = useSocial((s) => s.conversations.filter((c) => c.unread > 0).length)
  const requests = useSocial((s) => s.requests.incoming.length)
  return chats + requests
}
