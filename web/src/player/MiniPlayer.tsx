import { useEffect, useRef, useState } from 'react'
import * as player from './player'
import { usePlayer, currentItem, positionMs } from './player'
import { useListen } from '../social/listen'
import { session } from '../state/session'
import { Cover, LikeButton } from '../ui/components'
import { useCoverColor } from '../ui/coverColor'
import { likes, useLikes } from '../state/likes'
import { IconButton } from '../ui/kit'
import { JamIcon } from '../ui/appIcons'
import { useDevices } from '../state/devices'
import { refToSong } from '../api/types'
import { DeviceIcon, useDevicePicker } from './DevicePicker'

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

  const devices = useDevices((s) => s.devices)
  const currentId = useDevices((s) => s.currentDeviceId)
  const activeId = useDevices((s) => s.activeDeviceId)
  const sendRemoteCommand = useDevices((s) => s.sendRemoteCommand)

  const remotePlaying = devices.find((d) => d.playing && d.id !== currentId)
  const remoteTarget = remotePlaying || devices.find((d) => d.id === activeId && d.id !== currentId)
  const isRemote = (!isPlaying && !!remotePlaying?.song) || (!item && !!remoteTarget?.song)
  const activeRemote = isRemote ? (remotePlaying ?? remoteTarget) : null
  const displaySong = (isRemote && activeRemote?.song ? refToSong(activeRemote.song) : null) || item?.song
  const displayPlaying = isRemote && activeRemote ? !!activeRemote.playing : isPlaying
  const displayPosition = isRemote && activeRemote ? activeRemote.positionMs : position
  const displayDuration = isRemote && activeRemote ? ((activeRemote.song?.duration ? activeRemote.song.duration * 1000 : 0) || 1) : durationMs

  const tint = useCoverColor(displaySong?.coverArt)
  const liked = useLikes((s) => !!displaySong && s.songs.some((x) => x.id === displaySong.id))
  // How far the bar is dragged sideways. Swipe past the threshold to skip, then it springs back.
  const [dx, setDx] = useState(0)
  const drag = useRef<{ x: number; y: number; on: boolean } | null>(null)
  const moved = useRef(false)
  if (!displaySong) return null

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
      if (dx < -80) {
        if (isRemote && activeRemote) sendRemoteCommand(activeRemote.id, 'next')
        else player.nextSong()
      } else if (dx > 80) {
        if (isRemote && activeRemote) sendRemoteCommand(activeRemote.id, 'previous')
        else player.previousSong()
      }
    }
    drag.current = null
    setDx(0)
  }

  const progress = displayDuration > 0 ? Math.min(1, displayPosition / displayDuration) : 0
  return (
    <div style={{ background: tint, borderRadius: 8, overflow: 'hidden', position: 'relative', boxShadow: '0 8px 24px rgba(0,0,0,0.5)', transition: 'background 0.4s' }}>
      <div
        onPointerDown={onDown}
        onPointerMove={onMove}
        onPointerUp={onUp}
        onPointerCancel={onUp}
        onClick={() => {
          if (moved.current) return
          if (isRemote) {
            useDevicePicker.getState().setOpen(true)
          } else {
            onOpen()
          }
        }}
        style={{
          display: 'flex', alignItems: 'center', padding: '0 4px 0 8px', cursor: 'pointer', touchAction: 'pan-y', height: 'var(--mini-height)',
          transform: dx ? `translateX(${dx}px)` : undefined, opacity: 1 - Math.min(0.6, Math.abs(dx) / 400),
          transition: drag.current?.on ? 'none' : 'transform 0.2s',
        }}
      >
        <Cover coverArt={displaySong.coverArt} size={150} corner={4} style={{ width: 44, height: 44, flexShrink: 0 }} />
        <div style={{ flex: 1, minWidth: 0, padding: '0 10px' }}>
          <div className="body-medium ellipsis" style={{ fontWeight: 700 }}>{displaySong.title}</div>
          <div className="body-small ellipsis" style={{ color: 'rgba(255,255,255,0.75)' }}>
            {isRemote && activeRemote ? (
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, color: 'var(--primary)', fontWeight: 600 }}>
                <DeviceIcon type={activeRemote.type} size={12} />
                <span>{activeRemote.name}</span>
              </span>
            ) : (
              displaySong.artist
            )}
          </div>
        </div>
        {!listening && <LikeButton liked={liked} onToggle={() => likes().toggleSong(displaySong)} />}
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
                style={{ color: 'var(--primary)', marginRight: 4, animation: 'spin 2.4s linear infinite', animationPlayState: displayPlaying ? 'running' : 'paused' }}
              />
            )}
            <IconButton
              icon={displayPlaying ? 'pause' : 'play_arrow'}
              filled
              label={displayPlaying ? 'Pause' : 'Play'}
              onClick={(e) => {
                e.stopPropagation()
                if (isRemote && activeRemote) {
                  sendRemoteCommand(activeRemote.id, displayPlaying ? 'pause' : 'play')
                } else {
                  player.togglePlay()
                }
              }}
              size={30}
              style={{ color: '#fff' }}
            />
            <IconButton
              icon="skip_next"
              filled
              label="Next"
              onClick={(e) => {
                e.stopPropagation()
                if (isRemote && activeRemote) {
                  sendRemoteCommand(activeRemote.id, 'next')
                } else {
                  player.next()
                }
              }}
              style={{ color: '#fff' }}
            />
          </>
        )}
      </div>
      <div style={{ position: 'absolute', left: 8, right: 8, bottom: 0, height: 2, background: 'rgba(255,255,255,0.25)', borderRadius: 1 }}>
        <div style={{ height: 2, width: `${progress * 100}%`, background: '#fff', borderRadius: 1 }} />
      </div>
    </div>
  )
}
