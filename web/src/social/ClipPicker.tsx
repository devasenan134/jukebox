import { useEffect, useRef, useState } from 'react'
import type { SongRef } from '../api/types'
import { subsonic } from '../api/subsonic'
import { clockTime } from '../api/socialTypes'
import * as player from '../player/player'
import { usePlayer, currentItem } from '../player/player'
import { useListen } from './listen'
import { waveform, BARS, MIN_BAR } from './waveforms'
import { Icon, Switch } from '../ui/kit'

/**
 * The "Share only a part" switch and, when it's on, the clip picker (ui/social/SocialComponents.kt
 * clipOptions). Reports what to send: the whole song, or the chosen part of it.
 */
export function ClipOptions({ song, onChange }: { song: SongRef; onChange: (shared: SongRef) => void }) {
  const durationMs = song.duration * 1000
  const [clipping, setClipping] = useState(false)
  // Start where the song is now if it's playing (whole seconds), and take 30 seconds.
  const [clip, setClip] = useState<[number, number]>(() => {
    const start = currentItem()?.song.id === song.id ? Math.floor(player.positionMs() / 1000) * 1000 : 0
    const from = Math.min(start, Math.max(0, durationMs - 1000))
    return [from, Math.min(from + 30_000, durationMs)]
  })
  useEffect(() => {
    onChange(clipping ? { ...song, clipStartMs: clip[0], clipEndMs: clip[1] } : song)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [clipping, clip[0], clip[1], song.id])
  if (durationMs < 2000) return null
  return (
    <>
      <div className="list-row" onClick={() => setClipping(!clipping)}>
        <div className="text body-large">Share only a part</div>
        <Switch checked={clipping} onChange={setClipping} />
      </div>
      {clipping && <ClipPicker song={song} durationMs={durationMs} clip={clip} onChange={setClip} />}
    </>
  )
}

/**
 * Pick the start and end of a clip on the song's waveform, and preview it. A small pointer above
 * the waveform shows where the song is and can be dragged anywhere in it ("Start here" and
 * "End here" use it). Preview plays from the start of the part and stops at its end.
 *
 * Sharing the song that's playing right now (outside listen-together) uses the real player, so the
 * pointer follows it live. Any other song, or any song while listening together, plays on a
 * separate preview player, so the queue and the session are left alone.
 */
function ClipPicker({ song, durationMs, clip, onChange }: { song: SongRef; durationMs: number; clip: [number, number]; onChange: (c: [number, number]) => void }) {
  const isCurrent = usePlayer((s) => currentItem(s)?.song.id === song.id)
  const mainPlaying = usePlayer((s) => s.isPlaying)
  const joined = useListen((s) => s.joined)
  // In a listen-together session the music is everyone's: playing over it would clash, so it waits for a pause.
  const sessionPlaying = joined != null && mainPlaying
  const useMain = isCurrent && joined == null

  const preview = useRef<HTMLAudioElement | null>(null)
  if (!preview.current) {
    preview.current = new Audio(subsonic.streamUrl(song.id))
    preview.current.preload = 'auto'
  }
  const [previewPlaying, setPreviewPlaying] = useState(false)
  const resumeOnClose = useRef(false)
  const playing = useMain ? mainPlaying : previewPlaying

  const position = () => (useMain ? player.positionMs() : Math.round((preview.current?.currentTime ?? 0) * 1000))
  const seek = (to: number) => (useMain ? player.seekTo(to) : preview.current && (preview.current.currentTime = to / 1000))
  const pause = () => {
    if (useMain) {
      if (usePlayer.getState().isPlaying) player.togglePlay()
    } else {
      preview.current?.pause()
      setPreviewPlaying(false)
    }
  }
  const play = () => {
    if (useMain) {
      if (!usePlayer.getState().isPlaying) player.togglePlay()
      return
    }
    // Your own music (another song) pauses while you listen here, and plays again when the sheet closes.
    if (joined == null && usePlayer.getState().isPlaying) {
      player.togglePlay()
      resumeOnClose.current = true
    }
    void preview.current?.play()
    setPreviewPlaying(true)
  }

  const [pointer, setPointer] = useState(() => (isCurrent ? Math.min(durationMs, player.positionMs()) : clip[0]))
  const dragging = useRef(false)
  const stopAt = useRef<number | null>(null)

  // Move the pointer with whatever is playing, and stop a preview at the end of the part.
  useEffect(() => {
    const t = setInterval(() => {
      const following = useMain || previewPlaying || (isCurrent && usePlayer.getState().isPlaying)
      if (following && !dragging.current) {
        const at = Math.min(durationMs, Math.max(0, useMain || previewPlaying ? position() : player.positionMs()))
        const end = stopAt.current
        if (end != null && at >= end) {
          pause()
          stopAt.current = null
          seek(end)
          setPointer(end)
        } else setPointer(at)
      }
      if (!useMain && previewPlaying && preview.current?.ended) {
        setPreviewPlaying(false)
        stopAt.current = null
      }
    }, 50)
    return () => clearInterval(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [useMain, isCurrent, previewPlaying])
  useEffect(() => {
    if (sessionPlaying && previewPlaying) {
      preview.current?.pause()
      setPreviewPlaying(false)
    }
  }, [sessionPlaying, previewPlaying])
  useEffect(
    () => () => {
      preview.current?.pause()
      if (preview.current) preview.current.src = ''
      if (resumeOnClose.current && !usePlayer.getState().isPlaying) player.togglePlay()
    },
    [],
  )

  // The song's waveform is the slider's track, like picking a part of a song on Instagram.
  const [bars, setBars] = useState<number[] | null>(null)
  useEffect(() => {
    let alive = true
    waveform(song.id).then((b) => alive && setBars(b)).catch(() => {})
    return () => {
      alive = false
    }
  }, [song.id])

  const trackRef = useRef<HTMLDivElement>(null)
  const fractionAt = (clientX: number) => {
    const r = trackRef.current!.getBoundingClientRect()
    return Math.min(1, Math.max(0, (clientX - r.left) / r.width))
  }
  const dragHandle = (which: 0 | 1) => (e: React.PointerEvent) => {
    e.preventDefault()
    const el = e.currentTarget as HTMLElement
    el.setPointerCapture(e.pointerId)
    const move = (ev: PointerEvent) => {
      const ms = Math.floor((fractionAt(ev.clientX) * durationMs) / 1000) * 1000
      if (which === 0) {
        const start = Math.min(ms, clip[1] - 1000)
        if (start >= 0) onChange([start, clip[1]])
      } else {
        const end = Math.min(durationMs, Math.max(ms, clip[0] + 1000))
        onChange([clip[0], end])
      }
    }
    const up = () => {
      el.removeEventListener('pointermove', move)
      el.removeEventListener('pointerup', up)
    }
    el.addEventListener('pointermove', move)
    el.addEventListener('pointerup', up)
  }
  const dragPointer = (e: React.PointerEvent) => {
    const el = e.currentTarget as HTMLElement
    el.setPointerCapture(e.pointerId)
    dragging.current = true
    setPointer(fractionAt(e.clientX) * durationMs)
    const move = (ev: PointerEvent) => setPointer(fractionAt(ev.clientX) * durationMs)
    const up = (ev: PointerEvent) => {
      const at = fractionAt(ev.clientX) * durationMs
      dragging.current = false
      seek(at)
      // Dragged past the end of the part: a preview just keeps playing.
      if (stopAt.current != null && at >= stopAt.current) stopAt.current = null
      el.removeEventListener('pointermove', move)
      el.removeEventListener('pointerup', up)
    }
    el.addEventListener('pointermove', move)
    el.addEventListener('pointerup', up)
  }

  const from = clip[0] / durationMs
  const to = clip[1] / durationMs
  const count = bars?.length ?? BARS
  return (
    <div style={{ padding: '0 16px' }}>
      <div ref={trackRef} style={{ position: 'relative', margin: '0 3px' }}>
        {/* The pointer: a small triangle at where the song is. Tap or drag along it to move it. */}
        <div onPointerDown={dragPointer} style={{ height: 24, position: 'relative', cursor: 'pointer', touchAction: 'none' }}>
          <div
            style={{
              position: 'absolute', bottom: 0, left: `${(pointer / durationMs) * 100}%`, transform: 'translateX(-50%)', width: 0, height: 0,
              borderLeft: '7px solid transparent', borderRight: '7px solid transparent', borderTop: '14px solid var(--on-surface)',
            }}
          />
        </div>
        <div style={{ position: 'relative', height: 64, display: 'flex', alignItems: 'center' }}>
          <div style={{ display: 'flex', alignItems: 'center', width: '100%', height: 56 }}>
            {Array.from({ length: count }, (_, i) => {
              const level = bars ? bars[i] : MIN_BAR
              const centre = (i + 0.5) / count
              return (
                <div key={i} style={{ flex: 1, display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100%' }}>
                  <div
                    style={{
                      width: '60%', height: `${level * 100}%`, borderRadius: 4, transition: 'height 0.5s',
                      background: centre >= from && centre <= to ? 'var(--primary)' : 'color-mix(in srgb, var(--on-surface-variant) 35%, transparent)',
                    }}
                  />
                </div>
              )
            })}
          </div>
          <div style={{ position: 'absolute', top: 4, bottom: 4, width: 2, background: 'var(--on-surface)', left: `${(pointer / durationMs) * 100}%` }} />
          {[0, 1].map((which) => (
            <div
              key={which}
              onPointerDown={dragHandle(which as 0 | 1)}
              style={{
                position: 'absolute', top: 0, left: `${(which ? to : from) * 100}%`, transform: 'translateX(-50%)', width: 16, height: 64,
                display: 'flex', justifyContent: 'center', cursor: 'ew-resize', touchAction: 'none',
              }}
            >
              <div style={{ width: 6, height: 64, borderRadius: 3, background: 'var(--primary)' }} />
            </div>
          ))}
        </div>
      </div>
      <div style={{ display: 'flex', alignItems: 'center' }} className="body-small">
        <span>{clockTime(clip[0])}</span>
        <span className="muted" style={{ flex: 1, textAlign: 'center' }}>
          {Math.round((clip[1] - clip[0]) / 1000)} seconds · pointer at {clockTime(pointer)}
        </span>
        <span>{clockTime(clip[1])}</span>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 4, padding: '4px 0' }}>
        {/* Jump to the start of the part and play it; it stops at the end of the part. */}
        <button
          className="btn outlined"
          style={{ padding: '0 12px' }}
          disabled={sessionPlaying}
          onClick={() => {
            stopAt.current = clip[1]
            seek(clip[0])
            setPointer(clip[0])
            play()
          }}
        >
          <Icon name="play_arrow" filled size={18} />
          Preview
        </button>
        <button
          className="btn text"
          onClick={() => {
            const at = Math.floor(pointer / 1000) * 1000
            if (at + 1000 <= durationMs) onChange([at, Math.min(durationMs, Math.max(clip[1], at + 1000))])
          }}
        >
          Start here
        </button>
        <button
          className="btn text"
          onClick={() => {
            const at = Math.floor(pointer / 1000) * 1000
            if (at >= 1000) onChange([Math.min(clip[0], at - 1000), at])
          }}
        >
          End here
        </button>
        <div style={{ flex: 1 }} />
        {/* Play on from the pointer (past the end of the part too), or pause. */}
        <button
          className="icon-btn"
          style={{ border: '1px solid var(--outline)' }}
          disabled={!playing && sessionPlaying}
          aria-label={playing ? 'Pause' : 'Play'}
          onClick={() => {
            stopAt.current = null
            if (playing) pause()
            else {
              if (!useMain && preview.current) preview.current.currentTime = pointer / 1000
              play()
            }
          }}
        >
          <Icon name={playing ? 'pause' : 'play_arrow'} filled size={20} />
        </button>
      </div>
      {sessionPlaying && <div className="body-small muted">Pause the listen-together music to preview</div>}
    </div>
  )
}
