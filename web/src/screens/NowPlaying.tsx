import { useEffect, useMemo, useRef, useState } from 'react'
import { subsonic } from '../api/subsonic'
import * as player from '../player/player'
import { currentItem, usePlayer } from '../player/player'
import { usePosition } from '../player/MiniPlayer'
import { Cover, formatDuration, LikeButton, useLoad } from '../ui/components'
import { useCoverColor } from '../ui/coverColor'
import { Slider } from '../player/PlayerBar'
import { QueueSheet } from '../player/QueuePanel'
import { likes, useLikes } from '../state/likes'
import { Icon, IconButton } from '../ui/kit'
import { useNav, usePlayerOpen } from '../ui/nav'

/** The full player: cover, seek bar, controls, and the lyrics (synced ones follow the song). */
export function NowPlaying() {
  const nav = useNav()
  const close = () => usePlayerOpen.getState().setOpen(false)
  const item = usePlayer((s) => currentItem(s))
  const isPlaying = usePlayer((s) => s.isPlaying)
  const durationMs = usePlayer((s) => s.durationMs)
  const shuffle = usePlayer((s) => s.shuffle)
  const repeat = usePlayer((s) => s.repeat)
  const position = usePosition(250)
  const song = item?.song
  const lyrics = useLoad(['lyrics', song?.id], async () => (song ? subsonic.lyrics(song.id) : []))
  const tint = useCoverColor(song?.coverArt)
  const liked = useLikes((s) => !!song && s.songs.some((x) => x.id === song.id))
  const [queue, setQueue] = useState(false)

  useEffect(() => {
    // Escape closes the queue sheet first (it listens itself), then the player.
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && !document.querySelector('.sheet') && close()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  if (!song) return null
  const best = lyrics.data?.find((l) => l.synced) ?? lyrics.data?.[0]
  return (
    <div
      style={{
        position: 'fixed', inset: 0, zIndex: 50, display: 'flex', flexDirection: 'column', overflow: 'hidden',
        background: `linear-gradient(180deg, ${tint} 0%, color-mix(in srgb, ${tint} 45%, #000) 60%, #000 100%)`,
        animation: 'slide-up 0.3s cubic-bezier(0.2, 0.9, 0.3, 1)', transition: 'background 0.5s',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', padding: '8px 8px 0' }}>
        <IconButton icon="keyboard_arrow_down" label="Close" onClick={close} size={30} style={{ color: '#fff' }} />
        <div style={{ flex: 1, textAlign: 'center', minWidth: 0 }}>
          <div className="label-medium" style={{ opacity: 0.75, letterSpacing: '0.08em' }}>PLAYING FROM</div>
          <div className="body-medium ellipsis" style={{ fontWeight: 700 }}>{song.album ?? 'Your queue'}</div>
        </div>
        <IconButton icon="queue_music" label="Queue" onClick={() => setQueue(true)} style={{ color: '#fff' }} />
      </div>
      {queue && <QueueSheet onClose={() => setQueue(false)} />}
      <div className="now-playing" style={{ flex: 1, minHeight: 0, display: 'grid', gap: 32, padding: '16px 24px 24px', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 340px), 1fr))', overflowY: 'auto', alignItems: 'center', maxWidth: 1200, width: '100%', margin: '0 auto' }}>
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 20 }}>
          <Cover coverArt={song.coverArt} size={800} corner={8} style={{ width: 'min(460px, 84vw, 52vh)', aspectRatio: '1', boxShadow: '0 24px 64px rgba(0,0,0,0.6)' }} />
          <div style={{ width: 'min(460px, 84vw)' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div className="headline-small ellipsis">{song.title}</div>
                <div className="body-large ellipsis" style={{ color: 'rgba(255,255,255,0.75)' }}>
                  {song.artist}
                  {song.album && (
                    <>
                      {' • '}
                      <a style={{ cursor: 'pointer' }} onClick={() => song.albumId && nav.openAlbum(song.albumId)}>{song.album}</a>
                    </>
                  )}
                </div>
              </div>
              <LikeButton liked={liked} onToggle={() => likes().toggleSong(song)} big />
            </div>
            <div style={{ marginTop: 16 }}>
              <Slider value={position} max={durationMs} onChange={player.seekTo} label="Seek" style={{ width: '100%' }} />
            </div>
            <div className="body-small" style={{ display: 'flex', justifyContent: 'space-between', opacity: 0.7, fontVariantNumeric: 'tabular-nums' }}>
              <span>{formatDuration(position / 1000)}</span>
              <span>{formatDuration(durationMs / 1000)}</span>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 8 }}>
              <IconButton icon="shuffle" label="Shuffle" onClick={player.toggleShuffle} color={shuffle ? 'var(--primary)' : '#fff'} />
              <IconButton icon="skip_previous" filled label="Previous" size={40} onClick={player.previous} className="big" style={{ color: '#fff' }} />
              <button className="play-circle" style={{ width: 68, height: 68 }} aria-label={isPlaying ? 'Pause' : 'Play'} onClick={player.togglePlay}>
                <Icon name={isPlaying ? 'pause' : 'play_arrow'} filled size={38} />
              </button>
              <IconButton icon="skip_next" filled label="Next" size={40} onClick={player.next} className="big" style={{ color: '#fff' }} />
              <IconButton icon={repeat === 'one' ? 'repeat_one' : 'repeat'} label="Repeat" onClick={player.cycleRepeat} color={repeat !== 'off' ? 'var(--primary)' : '#fff'} />
            </div>
          </div>
        </div>
        <Lyrics lines={best?.line ?? []} synced={!!best?.synced} position={position} loading={lyrics.loading} />
      </div>
    </div>
  )
}

function Lyrics({ lines, synced, position, loading }: { lines: { start?: number; value: string }[]; synced: boolean; position: number; loading: boolean }) {
  const box = useRef<HTMLDivElement>(null)
  const current = useMemo(() => {
    if (!synced) return -1
    let at = -1
    lines.forEach((l, i) => { if ((l.start ?? Infinity) <= position) at = i })
    return at
  }, [lines, synced, position])

  useEffect(() => {
    const el = box.current?.querySelector<HTMLElement>(`[data-line="${current}"]`)
    el?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  }, [current])

  return (
    <div ref={box} style={{ background: 'rgba(0,0,0,0.25)', borderRadius: 12, padding: '24px 24px', overflowY: 'auto', maxHeight: '72vh', minHeight: 220 }}>
      <div className="label-large" style={{ marginBottom: 12, opacity: 0.8 }}>Lyrics</div>
      {loading ? null : lines.length === 0 ? (
        <div className="body-medium muted" style={{ textAlign: 'center', paddingTop: 40 }}>No lyrics for this song yet</div>
      ) : (
        lines.map((l, i) => (
          <div
            key={i} data-line={i}
            onClick={() => synced && l.start != null && player.seekTo(l.start)}
            style={{
              padding: '6px 0', lineHeight: 1.4, fontSize: 22, letterSpacing: '-0.01em', cursor: synced ? 'pointer' : 'default', transition: 'color .2s, opacity .2s',
              color: '#fff', opacity: synced && i !== current ? (i < current ? 0.45 : 0.6) : 1, fontWeight: 800,
              minHeight: l.value ? undefined : 12,
            }}
          >
            {l.value}
          </div>
        ))
      )}
    </div>
  )
}
