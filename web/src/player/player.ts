import { create } from 'zustand'
import type { PlayEvent, Song, SongRef } from '../api/types'
import { MIX_SOURCE_PREFIX, songToRef } from '../api/types'
import { subsonic } from '../api/subsonic'
import { social } from '../api/social'
import { session } from '../state/session'
import { queueMemory, useRecentSongs } from '../state/history'
import { queryClient } from '../state/queries'
import { load, save, remove } from '../state/storage'

// The web app's player: one <audio> element plus a queue that works like the Android app's
// Media3 player (PlaybackService.kt, PlayerConnection.kt, MixPlayback.kt): shuffle keeps a play
// order, repeat is off -> all -> one, "previous" restarts a song past 3 seconds, plays are
// scrobbled (Subsonic), skips are reported to the friends API, stations keep adding songs,
// and queues started from a playlist remember where you left off.

export type RepeatMode = 'off' | 'all' | 'one'

export interface QueueItem {
  /** Unique within the queue (the same song can be queued twice). */
  uid: number
  song: Song
  /** What the queue was started from, e.g. "playlist:<id>" or "mix:<id>". */
  source?: string
  /** For a shared clip: where to pause (ms). */
  clipEndMs?: number
}

interface PlayerState {
  items: QueueItem[]
  /** Play order: indices into items (follows shuffle). */
  order: number[]
  /** Index into items of the current song, or -1. */
  current: number
  isPlaying: boolean
  isBuffering: boolean
  durationMs: number
  shuffle: boolean
  repeat: RepeatMode
  /** 0 to 1, remembered in this browser. */
  volume: number
}

export const usePlayer = create<PlayerState>(() => ({
  items: [],
  order: [],
  current: -1,
  isPlaying: false,
  isBuffering: false,
  durationMs: 0,
  shuffle: false,
  repeat: 'off',
  volume: savedVolume(),
}))

function savedVolume(): number {
  try {
    const v = Number(localStorage.getItem('player.volume'))
    return Number.isFinite(v) && localStorage.getItem('player.volume') != null ? Math.min(1, Math.max(0, v)) : 1
  } catch {
    return 1
  }
}

const st = () => usePlayer.getState()
const setSt = (s: Partial<PlayerState>) => usePlayer.setState(s)

export const currentItem = (s: PlayerState = st()): QueueItem | undefined => s.items[s.current]

let nextUid = 1
const toItems = (songs: Song[], source?: string): QueueItem[] => songs.map((song) => ({ uid: nextUid++, song, source }))

const audio = new Audio()
audio.preload = 'auto'
audio.volume = st().volume

/** Sets the volume (0 to 1) and remembers it. */
export function setVolume(v: number) {
  const volume = Math.min(1, Math.max(0, v))
  audio.volume = volume
  setSt({ volume })
  try {
    localStorage.setItem('player.volume', String(volume))
  } catch {
    // Private mode: it just isn't remembered.
  }
}

/** Current position in ms. Read it often (e.g. every 200 ms) for progress bars and lyrics. */
export const positionMs = () => Math.round(audio.currentTime * 1000)

/**
 * Listening along in someone else's jam: the music follows its owner, so controls do nothing (and
 * say why), and "play next" / "add to queue" ask the owner instead. Set by the listen-together code.
 */
export interface Jam {
  isListener: () => boolean
  request: (song: Song, playNow: boolean) => void
  explain: () => void
}
let jam: Jam | null = null
export const setJam = (j: Jam | null) => (jam = j)

function locked(): boolean {
  if (!jam?.isListener()) return false
  jam.explain()
  return true
}

// ---- Loading and playing ----

let loadedUid = -1
let clipStoppedUid = -1

function load_(index: number, startMs = 0, autoplay = true) {
  const item = st().items[index]
  if (!item) return
  const previous = currentItem()
  if (previous && previous.uid !== item.uid) reportTransition(previous, false)
  setSt({ current: index, durationMs: item.song.duration * 1000 })
  if (loadedUid !== item.uid) {
    loadedUid = item.uid
    audio.src = subsonic.streamUrl(item.song.id)
    onTransition(item)
  }
  audio.currentTime = startMs / 1000
  if (autoplay) void audio.play().catch(() => setSt({ isPlaying: false }))
  afterChange()
}

/** Plays songs. source (e.g. "playlist:<id>") lets the app remember where you left off in it. */
export function play(songs: Song[], startIndex = 0, shuffle = false, source?: string) {
  if (locked() || !songs.length) return
  const items = toItems(songs, source)
  const start = shuffle ? Math.floor(Math.random() * items.length) : Math.min(startIndex, items.length - 1)
  setSt({ items, shuffle, order: makeOrder(items.length, start, shuffle) })
  loadedUid = -1
  load_(start)
}

/** Plays queued songs in a remembered order, starting at currentId (Resume on a playlist). */
export function playInOrder(songs: Song[], currentId: string, source?: string) {
  if (locked() || !songs.length) return
  const items = toItems(songs, source)
  const start = Math.max(0, items.findIndex((i) => i.song.id === currentId))
  setSt({ items, shuffle: false, order: items.map((_, i) => i) })
  loadedUid = -1
  load_(start)
}

/** Plays just the shared part of a song: starts at the clip's start and pauses at its end. Play again to hear the rest. */
export function playClip(clip: SongRef) {
  if (locked()) return
  const song: Song = { id: clip.id, title: clip.title, artist: clip.artist, album: clip.album, albumId: clip.albumId, coverArt: clip.coverArt, duration: clip.duration }
  if (clip.clipStartMs == null) return play([song])
  const items: QueueItem[] = [{ uid: nextUid++, song, clipEndMs: clip.clipEndMs }]
  setSt({ items, shuffle: false, order: [0] })
  loadedUid = -1
  clipStoppedUid = -1
  load_(0, clip.clipStartMs)
}

function makeOrder(n: number, start: number, shuffle: boolean): number[] {
  const order = [...Array(n).keys()]
  if (!shuffle) return order
  const rest = order.filter((i) => i !== start)
  for (let i = rest.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1))
    ;[rest[i], rest[j]] = [rest[j], rest[i]]
  }
  return [start, ...rest]
}

/** Inserts items right after the current song in the play order (or at the end). */
function insert(newItems: QueueItem[], afterCurrent: boolean) {
  const s = st()
  const base = s.items.length
  const items = [...s.items, ...newItems]
  const ids = newItems.map((_, i) => base + i)
  const pos = afterCurrent ? s.order.indexOf(s.current) + 1 : s.order.length
  const order = [...s.order.slice(0, pos), ...ids, ...s.order.slice(pos)]
  setSt({ items, order })
  afterChange()
}

export function playNext(song: Song) {
  if (jam?.isListener()) return jam.request(song, false)
  if (!st().items.length) return play([song])
  insert(toItems([song]), true)
}

export function addToQueue(song: Song) {
  if (jam?.isListener()) return jam.request(song, false)
  if (!st().items.length) return play([song])
  insert(toItems([song]), false)
}

/** Accepted song requests still waiting to play, so they play in the order they were accepted. */
let acceptedRequests: number[] = []

/** The jam's owner accepted a song request: it plays after the current song and earlier accepted requests. */
export function queueRequested(song: Song) {
  const s = st()
  if (!s.items.length) return play([song])
  const pos = s.order.indexOf(s.current)
  const upcoming = s.order.slice(pos + 1).map((i) => s.items[i].uid)
  acceptedRequests = acceptedRequests.filter((u) => upcoming.includes(u))
  const lastWaiting = [...s.order.slice(pos + 1)].reverse().find((i) => acceptedRequests.includes(s.items[i].uid))
  const [item] = toItems([song])
  const items = [...s.items, item]
  const at = lastWaiting != null ? s.order.indexOf(lastWaiting) + 1 : pos + 1
  setSt({ items, order: [...s.order.slice(0, at), items.length - 1, ...s.order.slice(at)] })
  acceptedRequests.push(item.uid)
  afterChange()
}

/** The jam's owner accepted a "play it now" request: it goes right after the current song, and we skip to it. */
export function playRequestedNow(song: Song) {
  if (!st().items.length) return play([song])
  insert(toItems([song]), true)
  const s = st()
  load_(s.order[s.order.indexOf(s.current) + 1])
}

/** Takes an entry out of the queue (by its uid). */
export function removeFromQueue(uid: number) {
  if (locked()) return
  const s = st()
  const index = s.items.findIndex((i) => i.uid === uid)
  if (index < 0 || index === s.current) return
  const items = s.items.filter((_, i) => i !== index)
  const order = s.order.filter((i) => i !== index).map((i) => (i > index ? i - 1 : i))
  setSt({ items, order, current: s.current > index ? s.current - 1 : s.current })
  afterChange()
}

/** Moves the entry at play-order position from to position to. */
export function moveInQueue(from: number, to: number) {
  if (locked()) return
  const order = [...st().order]
  if (from === to || from < 0 || to < 0 || from >= order.length || to >= order.length) return
  const [x] = order.splice(from, 1)
  order.splice(to, 0, x)
  setSt({ order })
  afterChange()
}

export function togglePlay() {
  if (locked()) return
  if (!audio.src) {
    const s = st()
    if (s.current >= 0) load_(s.current, restoredPosition)
    return
  }
  if (audio.paused) {
    if (audio.ended) audio.currentTime = 0
    void audio.play().catch(() => {})
  } else audio.pause()
}

export function pause() {
  audio.pause()
}

/** Stops playback and empties the queue (when logging out). */
export function stop() {
  const item = currentItem()
  if (item) reportTransition(item, false)
  audio.pause()
  audio.removeAttribute('src')
  audio.load()
  loadedUid = -1
  setSt({ items: [], order: [], current: -1, isPlaying: false, durationMs: 0 })
  remove('player.queue')
  onPlaybackListeners.forEach((f) => f(null, false))
}

function step(delta: number, wrap: boolean): number | undefined {
  const s = st()
  const pos = s.order.indexOf(s.current) + delta
  if (pos >= 0 && pos < s.order.length) return s.order[pos]
  if (!wrap || !s.order.length) return undefined
  return s.order[(pos + s.order.length) % s.order.length]
}

/** The next button: always moves on (wrapping round with repeat all). */
export function next() {
  if (locked()) return
  const i = step(1, st().repeat !== 'off')
  if (i != null) load_(i, 0, true)
}

/** The previous button: restarts the song if it's past 3 seconds, otherwise the previous song. */
export function previous() {
  if (locked()) return
  if (audio.currentTime > 3) {
    audio.currentTime = 0
    return
  }
  previousSong()
}

/** Always the previous song. */
export function previousSong() {
  if (locked()) return
  const i = step(-1, st().repeat !== 'off')
  if (i != null) load_(i, 0, !audio.paused || !audio.src)
  else audio.currentTime = 0
}

export function nextSong() {
  if (locked()) return
  const i = step(1, st().repeat !== 'off')
  if (i != null) load_(i, 0, !audio.paused)
}

export function seekTo(ms: number) {
  if (locked()) return
  audio.currentTime = ms / 1000
  updatePositionState()
  seekListeners.forEach((f) => f())
}

/** Seeks made here (not by a jam's owner): the listen-together code passes them on. */
const seekListeners = new Set<() => void>()
export function onSeek(f: () => void) {
  seekListeners.add(f)
  return () => seekListeners.delete(f)
}

/** Plays the entry at play-order position pos. */
export function jumpTo(pos: number) {
  if (locked()) return
  const i = st().order[pos]
  if (i != null) load_(i)
}

/** Moves to another song in the queue, keeping play/pause (used by swiping the cover). */
export function skipTo(pos: number) {
  if (locked()) return
  const i = st().order[pos]
  if (i != null) load_(i, 0, !audio.paused)
}

export function toggleShuffle() {
  if (locked()) return
  const s = st()
  const shuffle = !s.shuffle
  setSt({ shuffle, order: makeOrder(s.items.length, s.current < 0 ? 0 : s.current, shuffle) })
  afterChange()
}

/** off -> repeat all -> repeat one -> off */
export function cycleRepeat() {
  if (locked()) return
  const repeat: RepeatMode = st().repeat === 'off' ? 'all' : st().repeat === 'all' ? 'one' : 'off'
  setSt({ repeat })
  persist()
}

/** Listening together: replace the queue with the session's, at a song and position, playing or not. */
export function followState(songs: Song[], index: number, positionMsAt: number, playing: boolean) {
  const s = st()
  const same = s.items.length === songs.length && s.items.every((it, i) => it.song.id === songs[i]?.id)
  if (!same) {
    setSt({ items: toItems(songs), order: songs.map((_, i) => i), shuffle: false })
    loadedUid = -1
  }
  if (index !== st().current || loadedUid !== st().items[index]?.uid) load_(index, positionMsAt, playing)
  else {
    if (Math.abs(positionMs() - positionMsAt) > 1500) audio.currentTime = positionMsAt / 1000
    if (playing && audio.paused) void audio.play().catch(() => {})
    if (!playing && !audio.paused) audio.pause()
  }
}

// ---- Audio events ----

audio.addEventListener('playing', () => setSt({ isPlaying: true, isBuffering: false }))
audio.addEventListener('play', () => {
  setSt({ isPlaying: true })
  const item = currentItem()
  // Remember songs that actually play, for "recently played" when sharing in a chat.
  if (item) useRecentSongs.getState().played(songToRef(item.song))
  notifyPlayback()
})
audio.addEventListener('pause', () => {
  setSt({ isPlaying: false })
  persist()
  notifyPlayback()
})
audio.addEventListener('waiting', () => setSt({ isBuffering: true }))
audio.addEventListener('canplay', () => setSt({ isBuffering: false }))
audio.addEventListener('durationchange', () => {
  if (Number.isFinite(audio.duration) && audio.duration > 0) setSt({ durationMs: Math.round(audio.duration * 1000) })
  updatePositionState()
})
audio.addEventListener('ended', () => {
  const s = st()
  const item = currentItem()
  if (s.repeat === 'one') {
    audio.currentTime = 0
    void audio.play()
    return
  }
  if (item) reportTransition(item, true)
  const i = step(1, s.repeat === 'all')
  if (i != null) load_(i)
  else setSt({ isPlaying: false })
})
audio.addEventListener('timeupdate', () => {
  const item = currentItem()
  // A shared clip pauses once at its end point; pressing play afterwards carries on with the rest.
  if (item?.clipEndMs && item.uid !== clipStoppedUid && positionMs() >= item.clipEndMs) {
    clipStoppedUid = item.uid
    audio.pause()
  }
  scrobbleCheck()
})

// ---- Things that happen when the song changes ----

let submittedId: string | null = null

function onTransition(item: QueueItem) {
  submittedId = null
  reportedUid = -1
  subsonic.scrobble(item.song.id, false).catch(() => {})
  updateMediaSession(item)
}

/**
 * Reports plays (Subsonic scrobble): a real play after half the song (or 4 minutes). This is the
 * listening history the mixes use.
 */
function scrobbleCheck() {
  const item = currentItem()
  const duration = st().durationMs
  if (!item || item.song.id === submittedId || duration <= 0) return
  if (positionMs() >= Math.min(duration / 2, 240_000)) {
    submittedId = item.song.id
    // Counted: Home's Jump back in and Most played load again next time they're shown.
    subsonic.scrobble(item.song.id, true).then(() => queryClient.invalidateQueries({ queryKey: ['home', 'lists'] })).catch(() => {})
  }
}

// Skips teach the mixes what you don't want: moving on to another song in the first 30 seconds.
// Nothing is reported while listening together (someone else may be skipping) or for shared clips.
let pending: PlayEvent[] = []
let listeningTogether: () => boolean = () => false
export const setListeningTogether = (f: () => boolean) => (listeningTogether = f)

/** The entry last reported, so a song that ended isn't reported again as skipped when the next one loads. */
let reportedUid = -1

function reportTransition(item: QueueItem, finished: boolean) {
  if (item.uid === reportedUid) return
  reportedUid = item.uid
  if (listeningTogether() || item.clipEndMs) return
  const durationMs = item.song.duration * 1000
  const playedMs = finished ? durationMs : positionMs()
  pending.push({
    songId: item.song.id,
    at: Date.now(),
    playedMs,
    durationMs,
    skipped: !finished && playedMs < 30_000,
    source: item.source,
  })
  if (pending.length > 300) pending = pending.slice(-300)
  if (pending.length >= 10) void flushPlays()
}

async function flushPlays() {
  if (!session().social || !pending.length) return
  const batch = pending
  try {
    await social.recordPlays(batch)
    pending = pending.filter((e) => !batch.includes(e))
  } catch {
    // Try again next time.
  }
}
setInterval(() => void flushPlays(), 60_000)
addEventListener('pagehide', () => void flushPlays())

/**
 * Keeps a station (a mix whose endless is set) playing forever: when fewer than 5 of its songs are
 * left in the queue, it asks the friends server for more like it and adds them to the end.
 */
let fetchingStation = false
function topUpStation() {
  if (fetchingStation || !session().social) return
  const s = st()
  const source = currentItem(s)?.source
  if (!source?.startsWith(MIX_SOURCE_PREFIX + 'radio-') || s.repeat === 'one') return
  const pos = s.order.indexOf(s.current)
  const left = s.order.slice(pos + 1).filter((i) => s.items[i].source === source).length
  if (left >= 5) return
  fetchingStation = true
  const stationId = source.slice(MIX_SOURCE_PREFIX.length)
  const queued = s.items.map((i) => i.song.id)
  social
    .radio(stationId, queued.slice(-500), 20)
    .then((more) => {
      // Still the same station? (You may have started something else meanwhile.)
      if (currentItem()?.source === source) insert(toItems(more.songs.map((m) => ({ ...m })), source), false)
    })
    .catch(() => {})
    .finally(() => (fetchingStation = false))
}

/** For queues started from a playlist: saves the play order and the current song, to offer Resume later. */
function rememberQueue() {
  const s = st()
  const item = currentItem(s)
  if (!item?.source) return
  const ids = s.order.map((i) => s.items[i]).filter((i) => i.source === item.source).map((i) => i.song.id)
  queueMemory.save(item.source, ids, item.song.id)
}

function afterChange() {
  rememberQueue()
  topUpStation()
  persist()
  notifyPlayback()
}

// ---- Keeping the queue across page reloads (paused where you were) ----

interface Persisted {
  items: QueueItem[]
  order: number[]
  current: number
  shuffle: boolean
  repeat: RepeatMode
  positionMs: number
}

function persist() {
  const s = st()
  if (!s.items.length) return
  // Only the first 500 entries, to stay well inside the browser's storage limit.
  const keep = s.items.slice(0, 500)
  save('player.queue', {
    items: keep,
    order: s.order.filter((i) => i < keep.length),
    current: s.current < keep.length ? s.current : 0,
    shuffle: s.shuffle,
    repeat: s.repeat,
    positionMs: positionMs(),
  } satisfies Persisted)
}
setInterval(() => {
  if (!audio.paused) persist()
}, 10_000)

let restoredPosition = 0

/** At start: the queue you had before the page was closed, paused where you were. */
export function restoreQueue() {
  const p = load<Persisted | null>('player.queue', null)
  if (!p?.items?.length) return
  nextUid = Math.max(...p.items.map((i) => i.uid)) + 1
  restoredPosition = p.positionMs ?? 0
  setSt({
    items: p.items, order: p.order, current: p.current, shuffle: p.shuffle, repeat: p.repeat,
    durationMs: (p.items[p.current]?.song.duration ?? 0) * 1000,
  })
  const item = currentItem()
  if (item) updateMediaSession(item)
}

// ---- Telling others (friends see what you're listening to) ----

type PlaybackListener = (song: SongRef | null, playing: boolean) => void
const onPlaybackListeners = new Set<PlaybackListener>()
export function onPlayback(f: PlaybackListener) {
  onPlaybackListeners.add(f)
  return () => onPlaybackListeners.delete(f)
}
function notifyPlayback() {
  const item = currentItem()
  const ref = item ? songToRef(item.song) : null
  onPlaybackListeners.forEach((f) => f(ref, st().isPlaying))
}

// ---- The browser's media controls (lock screen, headphones, keyboard media keys) ----

const ms = 'mediaSession' in navigator ? navigator.mediaSession : null

function updateMediaSession(item: QueueItem) {
  if (!ms) return
  const art = (size: number) => ({ src: new URL(subsonic.coverUrl(item.song.coverArt, size) ?? '/icon-512.png', location.href).href, sizes: `${size}x${size}`, type: 'image/jpeg' })
  ms.metadata = new MediaMetadata({
    title: item.song.title,
    artist: item.song.artist ?? '',
    album: item.song.album ?? '',
    artwork: item.song.coverArt ? [art(300), art(600)] : [],
  })
}

function updatePositionState() {
  if (!ms?.setPositionState) return
  const d = st().durationMs / 1000
  if (!d) return
  try {
    ms.setPositionState({ duration: d, position: Math.min(audio.currentTime, d), playbackRate: 1 })
  } catch {
    // ignore
  }
}

if (ms) {
  ms.setActionHandler('play', () => togglePlay())
  ms.setActionHandler('pause', () => togglePlay())
  ms.setActionHandler('previoustrack', () => previous())
  ms.setActionHandler('nexttrack', () => next())
  ms.setActionHandler('seekto', (d) => d.seekTime != null && seekTo(d.seekTime * 1000))
  try {
    ms.setActionHandler('seekbackward', (d) => seekTo(Math.max(0, positionMs() - (d.seekOffset ?? 10) * 1000)))
    ms.setActionHandler('seekforward', (d) => seekTo(positionMs() + (d.seekOffset ?? 10) * 1000))
  } catch {
    // Not every browser has these.
  }
}

/** The queue in play order, with each entry's position, and the current position. */
export function queueInOrder(s: PlayerState = st()) {
  return { entries: s.order.map((i) => s.items[i]), currentPos: s.order.indexOf(s.current) }
}
