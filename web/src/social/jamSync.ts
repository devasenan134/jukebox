import type { ListenState } from '../api/socialTypes'
import { refToSong } from '../api/types'
import * as player from '../player/player'
import { onSeek, positionMs, setJam, setListeningTogether, usePlayer } from '../player/player'
import { toast } from '../ui/kit'
import { newQueueId, positionNow, setListenSnapshot, songToRef, useListen, type Remote } from './listen'
import { jamOwnerName } from './social'

/**
 * Keeps the player in step with a listen-together session (playback/ListenSync.kt in the app):
 *  - if we own the session, changes made here (a new queue, skip, seek, play/pause) are sent to the others;
 *  - if someone else owns it, their changes are applied here, and our controls ask them instead.
 * Shuffle is off during a session, so everyone's queue plays in the same order.
 */

/** Which queue the player holds, as the session knows it. */
let queueId: string = newQueueId()
/** Set while applying the owner's state, so those changes aren't sent back. */
let applying = false
let started = false

function snapshot(includeQueue: boolean): ListenState {
  // In play order: songs added with "play next" or moved in the queue play where the host put them.
  const { entries, currentPos } = player.queueInOrder()
  return {
    queue: includeQueue ? entries.map((i) => songToRef(i.song)) : undefined,
    queueId,
    index: Math.max(0, currentPos),
    positionMs: positionMs(),
    playing: usePlayer.getState().isPlaying,
  }
}

function sendIfOwner(includeQueue: boolean) {
  const l = useListen.getState()
  if (applying || l.joined == null || !l.isOwner()) return
  l.update(snapshot(includeQueue))
}

function apply(remote: Remote) {
  const state = remote.state
  // An update for a queue we don't have: wait for the next full one.
  if (!state.queue && state.queueId !== queueId) return
  applying = true
  try {
    if (state.queue) queueId = state.queueId
    const songs = state.queue ? state.queue.map(refToSong) : usePlayer.getState().items.map((i) => i.song)
    player.followState(songs, state.index, positionNow(remote), state.playing)
  } finally {
    applying = false
  }
}

export function startJamSync() {
  if (started) return
  started = true
  setJam({
    isListener: () => useListen.getState().isListener(),
    request: (song, playNow) => useListen.getState().requestSong(song, playNow),
    explain: () => toast(`${jamOwnerName() ?? 'The host'} controls the music in this jam. Ask them from a song's menu`),
  })
  setListeningTogether(() => useListen.getState().joined != null)
  setListenSnapshot(() => snapshot(true))
  usePlayer.subscribe((s, prev) => {
    if (applying) return
    const queueChanged = s.items !== prev.items || s.order !== prev.order
    if (queueChanged) queueId = newQueueId()
    if (queueChanged || s.current !== prev.current || s.isPlaying !== prev.isPlaying) sendIfOwner(queueChanged)
    // Shuffle stays off while listening together.
    if (s.shuffle && useListen.getState().joined != null) player.toggleShuffle()
  })
  onSeek(() => sendIfOwner(false))
  useListen.subscribe((l, prev) => {
    if (l.remote && l.remote !== prev.remote && l.isListener()) apply(l.remote)
  })
}
