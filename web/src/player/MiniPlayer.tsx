import { useEffect, useRef, useState } from 'react'
import * as player from './player'
import { usePlayer, currentItem, positionMs } from './player'
import { useListen } from '../social/listen'
import { session } from '../state/session'
import { Cover } from '../ui/components'
import { IconButton } from '../ui/kit'
import { JamIcon } from '../ui/appIcons'

/** The position, re-read every intervalMs while playing (for progress bars and lyrics). */
export function usePosition(intervalMs = 500) {
  const [pos, setPos] = useState(positionMs())
  const playing = usePlayer((s) => s.isPlaying)
  const uid = usePlayer((s) => currentItem(s)?.uid)
  useEffect(() => {
    setPos(positionMs())
    if (!playing) return
    const t = setInterval(() => setPos(positionMs()), intervalMs)
    return () => clearInterval(t)
  }, [playing, uid, intervalMs])
  return pos
}

/** Whether the jam we're in is ours, or someone else's (then only they control the music). */
export function useJamRole() {
  const joined = useListen((s) => s.joined)
  const owners = useListen((s) => s.owners)
  const owner = joined != null ? owners[joined] : undefined
  const ownsJam = owner != null && owner === session().social?.user.id
  return { ownsJam, listening: owner != null && !ownsJam }
}

/** The small "now playing" bar above the bottom tabs. Tap it to open the full player (ui/player/MiniPlayer.kt). */
export function MiniPlayer({ onOpen }: { onOpen: () => void }) {
  const item = usePlayer((s) => currentItem(s))
  const isPlaying = usePlayer((s) => s.isPlaying)
  const durationMs = usePlayer((s) => s.durationMs)
  const position = usePosition(500)
  const { ownsJam, listening } = useJamRole()
  // How far the bar is dragged sideways. Swipe past the threshold to skip, then it springs back.
  const [dx, setDx] = useState(0)
  const drag = useRef<{ x: number; y: number; on: boolean } | null>(null)
  const moved = useRef(false)
  if (!item) return null

  const onDown = (e: React.PointerEvent) => {
    if (listening || (e.target as HTMLElement).closest('button')) return
    drag.current = { x: e.clientX, y: e.clientY, on: false }
    moved.current = false
  }
  const onMove = (e: React.PointerEvent) => {
    const d = drag.current
    if (!d) return
    const x = e.clientX - d.x
    if (!d.on) {
      if (Math.abs(e.clientY - d.y) > 12) return void (drag.current = null)
      if (Math.abs(x) > 10) {
        d.on = true
        moved.current = true
        ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
      }
    }
    if (d.on) setDx(x)
  }
  const onUp = () => {
    if (drag.current?.on) {
      if (dx < -80) player.nextSong()
      else if (dx > 80) player.previousSong()
    }
    drag.current = null
    setDx(0)
  }

  const progress = durationMs > 0 ? Math.min(1, position / durationMs) : 0
  return (
    <div style={{ background: 'var(--surface-container-low)', boxShadow: '0 -1px 0 var(--outline-variant)' }}>
      <div style={{ height: 2, background: 'var(--surface-variant)' }}>
        <div style={{ height: 2, width: `${progress * 100}%`, background: 'var(--primary)' }} />
      </div>
      <div
        onPointerDown={onDown}
        onPointerMove={onMove}
        onPointerUp={onUp}
        onPointerCancel={onUp}
        onClick={() => !moved.current && onOpen()}
        style={{
          display: 'flex', alignItems: 'center', padding: '8px 12px', cursor: 'pointer', touchAction: 'pan-y', height: 'calc(var(--mini-height) - 2px)',
          transform: dx ? `translateX(${dx}px)` : undefined, opacity: 1 - Math.min(0.6, Math.abs(dx) / 400),
          transition: drag.current?.on ? 'none' : 'transform 0.2s',
        }}
      >
        <Cover coverArt={item.song.coverArt} size={150} corner={6} style={{ width: 44, height: 44, flexShrink: 0 }} />
        <div style={{ flex: 1, minWidth: 0, padding: '0 12px' }}>
          <div className="body-large ellipsis">{item.song.title}</div>
          <div className="body-small muted ellipsis">{item.song.artist}</div>
        </div>
        {listening ? (
          // Nothing to control: the jam's owner does. The icon opens the player.
          <button className="icon-btn" aria-label="Listening together" onClick={onOpen} style={{ color: 'var(--primary)' }}>
            <JamIcon />
          </button>
        ) : (
          <>
            {ownsJam && (
              <JamIcon
                size={22}
                title="You're running a jam"
                style={{ color: 'var(--primary)', marginRight: 4, animation: 'spin 2.4s linear infinite', animationPlayState: isPlaying ? 'running' : 'paused' }}
              />
            )}
            <IconButton icon={isPlaying ? 'pause' : 'play_arrow'} filled label={isPlaying ? 'Pause' : 'Play'} onClick={() => player.togglePlay()} />
            <IconButton icon="skip_next" filled label="Next" onClick={() => player.next()} />
          </>
        )}
      </div>
    </div>
  )
}
