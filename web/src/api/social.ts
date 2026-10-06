import type {
  CatalogResults, HomeMixes, LibrarySearchResults, Mix, MixSong, MusicRequest, PersonPage, PlayEvent, Playlist,
  SessionResponse, SocialUser, SongRef,
} from './types'
import type {
  AddFriendResponse, AdminAccess, BugReport, ChatMessage, Conversation, Friend, FriendRequests, Invite,
  ListeningStats, PushConfig,
} from './socialTypes'
import { session, type Credentials } from '../state/session'

// HTTP calls to the companion server (isaipetti-social): accounts, invites, friends, chat, mixes and
// search. The container forwards /social to it. Same calls as the Android app's SocialApi.kt.

// Jukebox serves the social API at its own root, on the same address as this app.
export const SOCIAL_BASE = ''

export class SocialError extends Error {
  code: number
  constructor(message: string, code = 0) {
    super(message)
    this.code = code
  }
}

const enc = encodeURIComponent

async function send<T>(
  method: string,
  path: string,
  body?: unknown,
  o: { authenticated?: boolean; bytes?: Blob; mime?: string } = {},
): Promise<T> {
  const headers: Record<string, string> = {}
  if (o.authenticated !== false) {
    const token = session().social?.token
    if (!token) throw new SocialError('Not connected to friends', 401)
    headers.Authorization = `Bearer ${token}`
  }
  let payload: BodyInit | undefined
  if (o.bytes) {
    headers['Content-Type'] = o.mime ?? 'image/jpeg'
    payload = o.bytes
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
    payload = JSON.stringify(body)
  }
  let res: Response
  try {
    res = await fetch(SOCIAL_BASE + path, { method, headers, body: payload })
  } catch {
    throw new SocialError("Can't reach the friends server")
  }
  const text = await res.text()
  if (!res.ok) {
    let message: string | undefined
    try {
      message = JSON.parse(text).error
    } catch {
      message = undefined
    }
    throw new SocialError(message ?? `Friends server error (${res.status})`, res.status)
  }
  return (text.trim() ? JSON.parse(text) : undefined) as T
}

const get = <T>(path: string) => send<T>('GET', path)
const post = <T>(path: string, body: unknown = {}, authenticated = true) =>
  send<T>('POST', path, body === undefined ? {} : body, { authenticated })

export const social = {
  login: (c: Credentials) => post<SessionResponse>('/auth/login', { username: c.username, salt: c.salt, token: c.token }, false),
  signup: (inviteCode: string, username: string, password: string, displayName: string) =>
    post<SessionResponse>('/auth/signup', { inviteCode, username, password, displayName }, false),
  logout: () => post<void>('/auth/logout'),
  logoutOthers: () => post<void>('/auth/logout-others'),
  rename: (displayName: string) => send<SocialUser>('PATCH', '/me', { displayName }),
  /** Changes your password (Jukebox keeps it); your other devices are signed out. */
  changePassword: (current: string, next: string) => post<void>('/me/password', { current, new: next }),

  /** Sets your profile picture (a JPEG already cropped and shrunk). Returns you, with its new version. */
  setAvatar: (jpeg: Blob) => send<SocialUser>('PUT', '/me/avatar', undefined, { bytes: jpeg }),
  removeAvatar: () => send<SocialUser>('DELETE', '/me/avatar'),
  avatarPath: (u: SocialUser) => (u.avatar != null ? `/users/${u.id}/avatar?v=${u.avatar}` : undefined),

  setGroupPicture: (conversationId: number, jpeg: Blob) =>
    send<Conversation>('PUT', `/conversations/${conversationId}/picture`, undefined, { bytes: jpeg }),
  removeGroupPicture: (conversationId: number) => send<Conversation>('DELETE', `/conversations/${conversationId}/picture`),
  groupPicturePath: (c: Conversation) => (c.picture != null ? `/conversations/${c.id}/picture?v=${c.picture}` : undefined),

  /** A cover for a playlist you made. */
  setPlaylistCover: (playlistId: string, jpeg: Blob) =>
    send<void>('PUT', `/playlists/${enc(playlistId)}/cover`, undefined, { bytes: jpeg }),
  removePlaylistCover: (playlistId: string) => send<void>('DELETE', `/playlists/${enc(playlistId)}/cover`),

  registerDevice: (token: string) => post<void>('/devices', { token }),
  unregisterDevice: (token: string) => post<void>('/devices/remove', { token }),

  /** Your liked playlists, saved with your account on the friends server (the Subsonic API can't like playlists). */
  likedPlaylists: async (): Promise<Playlist[]> =>
    (await get<{ id: string; name?: string; coverArt?: string; songCount?: number }[]>('/likes/playlists')).map((p) => ({
      id: p.id, name: p.name ?? '', coverArt: p.coverArt, songCount: p.songCount ?? 0,
    })),
  likePlaylist: (p: Playlist) =>
    send<void>('PUT', '/likes/playlists', { id: p.id, name: p.name, coverArt: p.coverArt ?? null, songCount: p.songCount }),
  unlikePlaylist: (id: string) => send<void>('DELETE', `/likes/playlists/${enc(id)}`),
  /** How many other people liked each of these playlists (for showing likes on your own). */
  playlistLikeCounts: (ids: string[]) =>
    ids.length ? get<Record<string, number>>(`/likes/playlists/counts?ids=${ids.map(enc).join(',')}`) : Promise.resolve({}),

  // Mixes, playlists and stations by Jukebox.
  mixes: () => get<HomeMixes>('/mixes'),
  mix: (id: string) => get<Mix>(`/mixes/${enc(id)}`),
  followedMixes: () => get<Mix[]>('/mixes/followed'),
  followMix: (id: string) => send<void>('PUT', `/mixes/${enc(id)}/follow`, ''),
  unfollowMix: (id: string) => send<void>('DELETE', `/mixes/${enc(id)}/follow`),
  /** The next songs of a station, leaving out exclude (what it already played). */
  radio: (id: string, exclude: string[], count = 25) => post<Mix>('/mixes/radio', { id, exclude, count }),
  /** Songs that would fit a playlist made of songIds; page 1, 2... for more. */
  recommend: (songIds: string[], count = 10, page = 0) => post<MixSong[]>('/mixes/recommend', { songIds, count, page }),
  /** What was played and skipped, so mixes can learn. */
  recordPlays: (events: PlayEvent[]) => post<void>('/plays', { events }),

  /** Search that forgives spelling, over songs, movies, artists, composers, lyricists and actors. */
  search: (q: string) => get<LibrarySearchResults>(`/search?q=${enc(q)}`),
  person: (id: string) => get<PersonPage>(`/search/people/${enc(id)}`),
  /** Songs and movies from the music catalog that aren't in the library, to ask for. */
  catalog: (q: string) => get<CatalogResults>(`/search/catalog?q=${enc(q)}`),
  musicRequests: () => get<MusicRequest[]>('/requests'),
  requestMusic: (catalogId: string) => post<MusicRequest>('/requests', { id: catalogId }),
  cancelMusicRequest: (id: number) => send<void>('DELETE', `/requests/${id}`),
  allMusicRequests: () => get<MusicRequest[]>('/admin/requests'),
  completeMusicRequest: (id: number) => post<MusicRequest>(`/admin/requests/${id}/done`),
  declineMusicRequest: (id: number, note: string | null) => post<MusicRequest>(`/admin/requests/${id}/decline`, { note }),

  /** Sends a bug report or a feature request; the server turns it into a public GitHub issue. */
  sendFeedback: (kind: 'bug' | 'feature', title: string, description: string, deviceInfo: string | null) =>
    post<BugReport>('/bug-reports', { title, description, deviceInfo, kind }),

  createInvite: () => post<Invite>('/invites'),
  invites: () => get<Invite[]>('/invites'),
  deleteInvite: (code: string) => send<void>('DELETE', `/invites/${enc(code)}`),

  adminAccess: () => get<AdminAccess>('/admin/access'),
  listeningStats: (timeZone: string) => get<ListeningStats>(`/admin/stats?tz=${enc(timeZone)}`),

  friends: () => get<Friend[]>('/friends'),
  friendRequests: () => get<FriendRequests>('/friends/requests'),
  addFriend: (username: string) => post<AddFriendResponse>('/friends/requests', { username }),
  acceptFriend: (userId: number) => post<void>(`/friends/requests/${userId}/accept`),
  declineFriend: (userId: number) => post<void>(`/friends/requests/${userId}/decline`),
  removeFriend: (userId: number) => send<void>('DELETE', `/friends/${userId}`),

  conversations: () => get<Conversation[]>('/conversations'),
  openDm: (userId: number) => post<Conversation>('/conversations/dm', { userId }),
  createGroup: (name: string, memberIds: number[]) => post<Conversation>('/conversations/group', { name, memberIds }),
  messages: (conversationId: number, before?: number) =>
    get<ChatMessage[]>(`/conversations/${conversationId}/messages` + (before != null ? `?before=${before}` : '')),
  /** Sends a message, which can be a reply to message replyTo of the same chat. */
  sendMessage: (conversationId: number, body: string, song: SongRef | null = null, replyTo: number | null = null, mentions: number[] = []) =>
    post<ChatMessage>(`/conversations/${conversationId}/messages`, { body, song, replyTo, mentions }),
  deleteConversation: (conversationId: number) => send<void>('DELETE', `/conversations/${conversationId}`),
  /** Sends a photo, GIF or sticker with an optional caption, as a reply if replyTo is set. */
  sendImage: (conversationId: number, bytes: Blob, mime: string, kind: string, width: number, height: number, caption = '', replyTo?: number) =>
    send<ChatMessage>(
      'POST',
      `/conversations/${conversationId}/images?kind=${kind}&width=${width}&height=${height}&caption=${enc(caption)}` +
        (replyTo != null ? `&replyTo=${replyTo}` : ''),
      undefined,
      { bytes, mime },
    ),
  imagePath: (m: ChatMessage) => `/conversations/${m.conversationId}/messages/${m.id}/image`,
  /** Sends a voice message (a recording durationMs long), as a reply if replyTo is set. */
  sendVoice: (conversationId: number, bytes: Blob, mime: string, durationMs: number, replyTo?: number) =>
    send<ChatMessage>(
      'POST',
      `/conversations/${conversationId}/voice?durationMs=${durationMs}` + (replyTo != null ? `&replyTo=${replyTo}` : ''),
      undefined,
      { bytes, mime },
    ),
  voicePath: (m: ChatMessage) => `/conversations/${m.conversationId}/messages/${m.id}/voice`,
  /** Forwards a message to other chats of yours; returns the new messages. */
  forward: (conversationId: number, messageId: number, to: number[]) =>
    post<ChatMessage[]>(`/conversations/${conversationId}/messages/${messageId}/forward`, { conversationIds: to }),
  /** Messages of a chat whose text or shared song matches query, newest first. */
  searchChat: (conversationId: number, q: string) => get<ChatMessage[]>(`/conversations/${conversationId}/search?q=${enc(q)}`),
  editMessage: (conversationId: number, messageId: number, body: string, mentions: number[]) =>
    send<ChatMessage>('PATCH', `/conversations/${conversationId}/messages/${messageId}`, { body, mentions }),
  deleteMessage: (conversationId: number, messageId: number) =>
    send<ChatMessage>('DELETE', `/conversations/${conversationId}/messages/${messageId}`),
  /** Reacts to a message with an emoji (replacing your earlier one), or with null takes your reaction away. */
  react: (conversationId: number, messageId: number, emoji: string | null) => {
    const path = `/conversations/${conversationId}/messages/${messageId}/reaction`
    return emoji == null ? send<ChatMessage>('DELETE', path) : send<ChatMessage>('PUT', path, { emoji })
  },
  /** Pins a message to the top of the chat for hours (24, 168 or 720), or unpins it. */
  pin: (conversationId: number, messageId: number, hours: number) =>
    post<Conversation>(`/conversations/${conversationId}/pins`, { messageId, hours }),
  unpin: (conversationId: number, messageId: number) => send<Conversation>('DELETE', `/conversations/${conversationId}/pins/${messageId}`),
  renameGroup: (conversationId: number, name: string) => send<Conversation>('PUT', `/conversations/${conversationId}/name`, { name }),
  addMembers: (conversationId: number, userIds: number[]) => post<Conversation>(`/conversations/${conversationId}/members`, { userIds }),
  removeMember: (conversationId: number, userId: number) =>
    send<Conversation>('DELETE', `/conversations/${conversationId}/members/${userId}`),
  /** Which of a chat's members have the app open right now. */
  onlineMembers: (conversationId: number) => get<number[]>(`/conversations/${conversationId}/online`),
  leaveGroup: (conversationId: number) => post<void>(`/conversations/${conversationId}/leave`),
  deleteForEveryone: (conversationId: number) => send<void>('DELETE', `/conversations/${conversationId}/everyone`),
  markRead: (conversationId: number, messageId: number) => post<void>(`/conversations/${conversationId}/read`, { messageId }),

  /** While listening together: ask the session's owner to play song next, or now (it shows in the chat). */
  requestSong: (conversationId: number, song: SongRef, playNow: boolean) =>
    post<ChatMessage>(`/conversations/${conversationId}/listen/requests`, { song, mode: playNow ? 'now' : 'next' }),
  answerRequest: (conversationId: number, messageId: number, accept: boolean) =>
    post<ChatMessage>(`/conversations/${conversationId}/listen/requests/${messageId}`, { accept }),

  /** WebSocket address for live events. */
  eventsUrl: () => {
    const token = session().social?.token
    if (!token) return undefined
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
    return `${proto}//${location.host}${SOCIAL_BASE}/ws?token=${enc(token)}`
  },

  /** This server's Firebase settings for push notifications, or null if it has none. */
  pushConfig: async (): Promise<PushConfig | null> => {
    try {
      return await get<PushConfig>('/push/config')
    } catch (e) {
      if (e instanceof SocialError && e.code === 404) return null
      throw e
    }
  },
}

// Pictures and recordings on the friends server need the Authorization header, which <img> and
// <audio> can't send: fetch them once and hand out object URLs.
const blobCache = new Map<string, Promise<string>>()

export function authedUrl(path: string): Promise<string> {
  let p = blobCache.get(path)
  if (!p) {
    p = (async () => {
      const token = session().social?.token
      const res = await fetch(SOCIAL_BASE + path, { headers: token ? { Authorization: `Bearer ${token}` } : {} })
      if (!res.ok) throw new SocialError(`Couldn't load (${res.status})`, res.status)
      return URL.createObjectURL(await res.blob())
    })()
    p.catch(() => blobCache.delete(path))
    blobCache.set(path, p)
  }
  return p
}
