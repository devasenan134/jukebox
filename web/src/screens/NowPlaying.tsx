import { useEffect, useMemo, useRef } from 'react'
import { subsonic } from '../api/subsonic'
import * as player from '../player/player'
import { currentItem, usePlayer } from '../player/player'
import { usePosition } from '../player/MiniPlayer'
import { Cover, formatDuration, useLoad } from '../ui/components'
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

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && close()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  if (!song) return null
  const best = lyrics.data?.find((l) => l.synced) ?? lyrics.data?.[0]
  return (
    <div style={{ position: 'fixed', inset: 0, zIndex: 50, background: 'var(--surface)', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
      <div style={{ display: 'flex', alignItems: 'center', padding: '8px 4px' }}>
        <IconButton icon="keyboard_arrow_down" label="Close" onClick={close} />
        <div className="label-large muted" style={{ flex: 1, textAlign: 'center' }}>Now playing</div>
        <div style={{ width: 48 }} />
      </div>
      <div className="now-playing" style={{ flex: 1, minHeight: 0, display: 'grid', gap: 24, padding: '0 24px 24px', gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))', overflowY: 'auto' }}>
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 16 }}>
          <Cover coverArt={song.coverArt} size={800} corner={14} style={{ width: 'min(420px, 80vw)', aspectRatio: '1', boxShadow: '0 16px 40px rgba(0,0,0,.3)' }} />
          <div style={{ width: 'min(420px, 80vw)' }}>
            <div className="title-large ellipsis" style={{ fontWeight: 700 }}>{song.title}</div>
            <div className="body-medium muted ellipsis">
              {song.artist}
              {song.album && (
                <>
                  {' · '}
                  <a style={{ cursor: 'pointer' }} onClick={() => song.albumId && nav.openAlbum(song.albumId)}>{song.album}</a>
                </>
              )}
            </div>
            <input
              type="range" min={0} max={Math.max(1, durationMs)} value={Math.min(position, durationMs)} aria-label="Seek"
              onChange={(e) => player.seekTo(Number(e.target.value))} style={{ width: '100%', marginTop: 16, accentColor: 'var(--primary)' }}
            />
            <div className="body-small muted" style={{ display: 'flex', justifyContent: 'space-between' }}>
              <span>{formatDuration(position / 1000)}</span>
              <span>{formatDuration(durationMs / 1000)}</span>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 8 }}>
              <IconButton icon="shuffle" label="Shuffle" onClick={player.toggleShuffle} color={shuffle ? 'var(--primary)' : undefined} />
              <IconButton icon="skip_previous" filled label="Previous" size={36} onClick={player.previous} />
              <button className="fab-play" aria-label={isPlaying ? 'Pause' : 'Play'} onClick={player.togglePlay}>
                <Icon name={isPlaying ? 'pause' : 'play_arrow'} filled />
              </button>
              <IconButton icon="skip_next" filled label="Next" size={36} onClick={player.next} />
              <IconButton icon={repeat === 'one' ? 'repeat_one' : 'repeat'} label="Repeat" onClick={player.cycleRepeat} color={repeat !== 'off' ? 'var(--primary)' : undefined} />
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
    <div ref={box} style={{ background: 'var(--surface-container)', borderRadius: 16, padding: '20px 20px', overflowY: 'auto', maxHeight: '70vh', minHeight: 200 }}>
      {loading ? null : lines.length === 0 ? (
        <div className="body-medium muted" style={{ textAlign: 'center', paddingTop: 40 }}>No lyrics for this song yet</div>
      ) : (
        lines.map((l, i) => (
          <div
            key={i} data-line={i}
            onClick={() => synced && l.start != null && player.seekTo(l.start)}
            className="title-medium"
            style={{
              padding: '6px 0', lineHeight: 1.5, cursor: synced ? 'pointer' : 'default', transition: 'color .2s, opacity .2s',
              color: i === current ? 'var(--primary)' : undefined, opacity: synced && i !== current ? 0.55 : 1, fontWeight: i === current ? 700 : 500,
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
