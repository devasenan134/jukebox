// Friends, chat and live events from the companion server (server/src/.../Models.kt and Hub.kt),
// the same shapes as the Android app's data/SocialModels.kt.
import type { SocialUser, SongRef } from './types'

export interface Invite {
  code: string
  expiresAt: number
  usedBy?: SocialUser
}
export interface Friend {
  user: SocialUser
  online: boolean
  nowPlaying?: SongRef
}
export interface FriendRequests {
  incoming: SocialUser[]
  outgoing: SocialUser[]
}
export interface AddFriendResponse {
  status: string
}
export interface BugReport {
  number: number
  url: string
}
/** Firebase settings of a friends server's project. */
export interface PushConfig {
  projectId: string
  appId: string
  apiKey: string
  senderId: string
  vapidKey?: string
}

export interface Reaction {
  emoji: string
  userIds: number[]
}
/** How far one member of a chat has read. */
export interface ReadMark {
  userId: number
  lastReadId: number
}
/** A picture in a message: kind is "photo", "gif" or "sticker". */
export interface ChatImage {
  kind: string
  width: number
  height: number
}

/** The message a reply quotes. hidden when it's from before you joined the group. */
export interface ReplyQuote {
  id: number
  sender: SocialUser
  body: string
  song?: SongRef
  hidden?: boolean
  imageKind?: string
  deleted?: boolean
  voiceMs?: number
}

export interface ChatMessage {
  id: number
  conversationId: number
  sender: SocialUser
  body: string
  song?: SongRef
  createdAt: number
  /** A line about the chat itself, like "left the group". */
  system?: boolean
  /** For a song request while listening together: "pending", "accepted", "declined" or "expired". */
  request?: string
  /** What a song request asks for: "next" or "now". */
  requestMode?: string
  replyTo?: ReplyQuote
  image?: ChatImage
  reactions?: Reaction[]
  editedAt?: number
  deleted?: boolean
  voiceMs?: number
  forwarded?: boolean
  mentions?: number[]
}

/** A message pinned to the top of a chat until expiresAt. */
export interface Pin {
  message: ReplyQuote
  pinnedBy: SocialUser
  pinnedAt: number
  expiresAt: number
}

export interface Conversation {
  id: number
  kind: string
  name?: string
  members: SocialUser[]
  lastMessage?: ChatMessage
  unread: number
  /** False for a DM with someone who left or is no longer a friend. Such chats can be deleted. */
  canMessage?: boolean
  /** Who is listening together in this chat right now. */
  listeners?: number[]
  /** Who started (and controls) the listening session. */
  listenOwner?: number
  /** The group's owner, the only one who can delete it for everyone. */
  createdBy?: number
  /** Version of the group's photo. */
  picture?: number
  pins?: Pin[]
  readMarks?: ReadMark[]
  unreadMentions?: number
}

/** What a listen-together session is playing. Updates leave out queue when it didn't change. */
export interface ListenState {
  queue?: SongRef[]
  queueId: string
  index: number
  positionMs: number
  playing: boolean
  updatedAt?: number
}

/** Live events from the server's WebSocket ("type" tells which). */
export type SocialEvent =
  | { type: 'presence'; userId: number; online: boolean; nowPlaying?: SongRef }
  | { type: 'message'; message: ChatMessage }
  | { type: 'friendRequest'; from: SocialUser }
  | { type: 'friendAdded'; friend: Friend }
  | { type: 'friendRemoved'; userId: number }
  | { type: 'conversationUpdated'; conversationId: number }
  | { type: 'typing'; conversationId: number; userId: number }
  | { type: 'read'; conversationId: number; userId: number; messageId: number }
  | { type: 'conversationRemoved'; conversationId: number }
  | { type: 'listenSession'; conversationId: number; listeners: number[]; owner?: number }
  | { type: 'messageUpdated'; message: ChatMessage }
  | { type: 'listenState'; conversationId: number; state: ListenState; by: number; serverTime: number }

/** What the app sends over the WebSocket. */
export type ClientEvent =
  | { type: 'nowPlaying'; song: SongRef | null }
  | { type: 'typing'; conversationId: number }
  | { type: 'appState'; visible: boolean }
  | { type: 'listenStart'; conversationId: number; state: ListenState }
  | { type: 'listenJoin'; conversationId: number }
  | { type: 'listenLeave'; conversationId: number }
  | { type: 'listenUpdate'; conversationId: number; state: ListenState }

export interface AdminAccess {
  isAdmin: boolean
}
export interface TopItem {
  name: string
  detail?: string
  plays: number
  coverArt?: string
}
export interface RangeStats {
  hours: number
  plays: number
  topSongs: TopItem[]
  topMovies: TopItem[]
  topComposers: TopItem[]
}
export interface UserStats {
  username: string
  displayName: string
  lastPlayedAt?: number
  /** Keys: "today", "7d", "30d", "all". */
  ranges: Record<string, RangeStats>
}
export interface ListeningStats {
  generatedAt: number
  users: UserStats[]
  daily: { date: string; hours: number }[]
  hourOfDay: number[]
}

// ---- Helpers the chat screens share (same wording as the Android app) ----

/** "1:05" */
export const clockTime = (ms: number) => `${Math.floor(ms / 60000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}`

/** " (1:05–1:35)" for a clip, "" for a whole song. */
export const clipLabel = (s: SongRef) =>
  s.clipStartMs != null && s.clipEndMs != null ? ` (${clockTime(s.clipStartMs)}–${clockTime(s.clipEndMs)})` : ''

export const voiceLabel = (ms: number) => `🎤 Voice message (${clockTime(ms)})`

export const imageLabel = (kind: string) => (kind === 'gif' ? 'GIF' : kind === 'sticker' ? 'Sticker' : '📷 Photo')

/** One line about a message for the chat list: its text, song or picture. */
export function summary(m: ChatMessage): string {
  if (m.deleted) return 'This message was deleted'
  if (m.image) return imageLabel(m.image.kind) + (m.body.trim() ? ` – ${m.body}` : '')
  if (m.voiceMs != null) return voiceLabel(m.voiceMs)
  if (m.song) return `♪ ${m.song.title}${clipLabel(m.song)}` + (m.body.trim() ? ` – ${m.body}` : '')
  return m.body
}

/** One line about what a quoted message said. */
export function quotePreview(q: ReplyQuote): string {
  if (q.hidden) return 'Earlier message'
  if (q.deleted) return 'Deleted message'
  if (q.voiceMs != null) return voiceLabel(q.voiceMs)
  if (q.imageKind) return imageLabel(q.imageKind) + (q.body.trim() ? ` – ${q.body}` : '')
  if (q.body.trim()) return q.body
  if (q.song) return `♪ ${q.song.title}${clipLabel(q.song)}`
  return ''
}

export const quoteOf = (m: ChatMessage): ReplyQuote => ({
  id: m.id, sender: m.sender, body: m.body, song: m.song, imageKind: m.image?.kind, voiceMs: m.voiceMs,
})

/** Your own ordinary message: one you can edit and delete. */
export const isOwnEditable = (m: ChatMessage, me?: number) => m.sender.id === me && !m.system && !m.deleted && m.request == null

/** "Alice left the group" / "You left the group". */
export const systemText = (m: ChatMessage, me?: number) => (m.sender.id === me ? 'You' : m.sender.displayName) + ' ' + m.body

export const isGroup = (c: Conversation) => c.kind === 'group'

/** Group name, or the other person's name for a DM. */
export const conversationTitle = (c: Conversation, me?: number) =>
  isGroup(c) ? c.name ?? '' : c.members.find((u) => u.id !== me)?.displayName ?? 'Chat'
