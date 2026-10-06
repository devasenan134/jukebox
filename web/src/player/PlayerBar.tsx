import type { CSSProperties } from 'react'
import * as player from './player'
import { currentItem, usePlayer } from './player'
import { usePosition, useJamRole } from './MiniPlayer'
import { likes, useLikes } from '../state/likes'
import { Cover, formatDuration, LikeButton } from '../ui/components'
import { Icon, IconButton } from '../ui/kit'
import { useNav, usePlayerOpen } from '../ui/nav'
import { useQueuePanel } from './QueuePanel'

/** A slider (seek or volume) that fills up to its value. */
export function Slider({ value, max, onChange, label, style }: { value: number; max: number; onChange: (v: number) => void; label: string; style?: CSSProperties }) {
  const fill = max > 0 ? Math.min(100, (value / max) * 100) : 0
  return (
    <input
      type="range"
      className="slider"
      min={0}
      max={Math.max(1, max)}
      step={max <= 1 ? 0.01 : 1}
      value={Math.min(value, max)}
      aria-label={label}
      onChange={(e) => onChange(Number(e.target.value))}
      style={{ ...style, ['--fill' as string]: `${fill}%` }}
    />
  )
}

/** Wide screens: what's playing, the controls and seek bar, and the volume, along the bottom. */
export function PlayerBar() {
  const nav = useNav()
  const item = usePlayer((s) => currentItem(s))
  const isPlaying = usePlayer((s) => s.isPlaying)
  const durationMs = usePlayer((s) => s.durationMs)
  const shuffle = usePlayer((s) => s.shuffle)
  const repeat = usePlayer((s) => s.repeat)
  const volume = usePlayer((s) => s.volume)
  const position = usePosition(500)
  const { listening } = useJamRole()
  const song = item?.song
  const liked = useLikes((s) => !!song && s.songs.some((x) => x.id === song.id))
  const open = () => usePlayerOpen.getState().setOpen(true)
  const queueOpen = useQueuePanel((s) => s.open)

  return (
    <footer className="player-bar" aria-label="Player">
      <div className="now">
        {song ? (
          <>
            <button onClick={open} aria-label="Open the player" style={{ border: 0, padding: 0, background: 'none', cursor: 'pointer', position: 'relative' }}>
              <Cover coverArt={song.coverArt} size={150} corner={4} style={{ width: 56, height: 56 }} />
            </button>
            <div style={{ minWidth: 0 }}>
              <div className="body-medium ellipsis" style={{ fontWeight: 600 }}>
                {song.albumId ? (
                  <a href={`/album/${song.albumId}`} onClick={(e) => { e.preventDefault(); nav.openAlbum(song.albumId!) }}>{song.title}</a>
                ) : song.title}
              </div>
              <div className="body-small muted ellipsis">{song.artist}</div>
            </div>
            <LikeButton liked={liked} onToggle={() => likes().toggleSong(song)} />
          </>
        ) : null}
      </div>
      <div className="controls">
        <div className="buttons">
          <IconButton icon="shuffle" label="Shuffle" onClick={player.toggleShuffle} disabled={!song || listening} color={shuffle ? 'var(--primary)' : undefined} size={20} />
          <IconButton icon="skip_previous" filled label="Previous" onClick={player.previous} disabled={!song || listening} />
          <button className="play-circle" aria-label={isPlaying ? 'Pause' : 'Play'} onClick={player.togglePlay} disabled={!song || listening}>
            <Icon name={isPlaying ? 'pause' : 'play_arrow'} filled size={26} />
          </button>
          <IconButton icon="skip_next" filled label="Next" onClick={player.next} disabled={!song || listening} />
          <IconButton
            icon={repeat === 'one' ? 'repeat_one' : 'repeat'}
            label="Repeat"
            onClick={player.cycleRepeat}
            disabled={!song || listening}
            color={repeat !== 'off' ? 'var(--primary)' : undefined}
            size={20}
          />
        </div>
        <div className="seek">
          <span>{formatDuration(position / 1000)}</span>
          <Slider value={position} max={durationMs} onChange={player.seekTo} label="Seek" />
          <span>{formatDuration(durationMs / 1000)}</span>
        </div>
      </div>
      <div className="extra">
        <IconButton icon="lyrics" label="Lyrics" onClick={open} disabled={!song} size={20} />
        <IconButton icon="queue_music" label="Queue" onClick={useQueuePanel.getState().toggle} size={20} color={queueOpen ? 'var(--primary)' : undefined} />
        <IconButton
          icon={volume === 0 ? 'volume_off' : volume < 0.5 ? 'volume_down' : 'volume_up'}
          label={volume === 0 ? 'Unmute' : 'Mute'}
          onClick={() => player.setVolume(volume === 0 ? 0.7 : 0)}
          size={20}
        />
        <Slider value={volume} max={1} onChange={player.setVolume} label="Volume" style={{ flex: '0 1 110px' }} />
        <IconButton icon="open_in_full" label="Full screen player" onClick={open} disabled={!song} size={18} />
      </div>
    </footer>
  )
}
