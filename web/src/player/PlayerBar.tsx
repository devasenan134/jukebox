import type { CSSProperties } from 'react'
import * as player from './player'
import { currentItem, usePlayer } from './player'
import { usePosition, useJamRole } from './MiniPlayer'
import { likes, useLikes } from '../state/likes'
import { Cover, formatDuration, LikeButton } from '../ui/components'
import { Icon, IconButton } from '../ui/kit'
import { useNav, usePlayerOpen } from '../ui/nav'
import { useQueuePanel } from './QueuePanel'
import { DevicePickerButton, DeviceIcon, useDevicePicker } from './DevicePicker'
import { useDevices } from '../state/devices'
import { refToSong } from '../api/types'

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

  const devices = useDevices((s) => s.devices)
  const currentId = useDevices((s) => s.currentDeviceId)
  const activeId = useDevices((s) => s.activeDeviceId)
  const sendRemoteCommand = useDevices((s) => s.sendRemoteCommand)

  const remotePlaying = devices.find((d) => d.playing && d.id !== currentId)
  const remoteTarget = remotePlaying || devices.find((d) => d.id === activeId && d.id !== currentId)
  const isRemote = (!isPlaying && !!remotePlaying?.song) || (!song && !!remoteTarget?.song)
  const activeRemote = isRemote ? (remotePlaying ?? remoteTarget) : null
  const displaySong = (isRemote && activeRemote?.song ? refToSong(activeRemote.song) : null) || song
  const displayPlaying = isRemote && activeRemote ? !!activeRemote.playing : isPlaying
  const displayPosition = isRemote && activeRemote ? activeRemote.positionMs : position
  const displayDuration = isRemote && activeRemote ? ((activeRemote.song?.duration ? activeRemote.song.duration * 1000 : 0) || 1) : durationMs
  const displayVolume = isRemote && activeRemote ? activeRemote.volume : volume

  const liked = useLikes((s) => !!displaySong && s.songs.some((x) => x.id === displaySong.id))
  const open = () => {
    if (isRemote) {
      useDevicePicker.getState().setOpen(true)
    } else {
      usePlayerOpen.getState().setOpen(true)
    }
  }
  const queueOpen = useQueuePanel((s) => s.open)

  return (
    <footer className="player-bar" aria-label="Player">
      <div className="now">
        {displaySong ? (
          <>
            <button
              onClick={open}
              aria-label={isRemote ? 'Connect to a device' : 'Open the player'}
              style={{ border: 0, padding: 0, background: 'none', cursor: 'pointer', position: 'relative' }}
            >
              <Cover coverArt={displaySong.coverArt} size={150} corner={4} style={{ width: 56, height: 56 }} />
            </button>
            <div style={{ minWidth: 0 }}>
              <div className="body-medium ellipsis" style={{ fontWeight: 600 }}>
                {displaySong.albumId ? (
                  <a href={`/album/${displaySong.albumId}`} onClick={(e) => { e.preventDefault(); nav.openAlbum(displaySong.albumId!) }}>{displaySong.title}</a>
                ) : displaySong.title}
              </div>
              <div className="body-small muted ellipsis">{displaySong.artist}</div>
              {isRemote && activeRemote && (
                <button
                  type="button"
                  onClick={() => useDevicePicker.getState().setOpen(true)}
                  style={{
                    background: 'none',
                    border: 'none',
                    padding: 0,
                    margin: '2px 0 0',
                    cursor: 'pointer',
                    color: 'var(--primary)',
                    display: 'flex',
                    alignItems: 'center',
                    gap: 4,
                    fontSize: 12,
                    textAlign: 'left',
                  }}
                  title="Change device"
                >
                  <DeviceIcon type={activeRemote.type} size={12} />
                  <span className="ellipsis">Listening on {activeRemote.name}</span>
                </button>
              )}
            </div>
            <LikeButton liked={liked} onToggle={() => likes().toggleSong(displaySong)} />
          </>
        ) : null}
      </div>
      <div className="controls">
        <div className="buttons">
          <IconButton
            icon="shuffle"
            label="Shuffle"
            onClick={player.toggleShuffle}
            disabled={!displaySong || listening || isRemote}
            color={shuffle ? 'var(--primary)' : undefined}
            size={20}
          />
          <IconButton
            icon="skip_previous"
            filled
            label="Previous"
            onClick={() => {
              if (isRemote && activeRemote) {
                sendRemoteCommand(activeRemote.id, 'previous')
              } else {
                player.previous()
              }
            }}
            disabled={!displaySong || listening}
          />
          <button
            className="play-circle"
            aria-label={displayPlaying ? 'Pause' : 'Play'}
            onClick={() => {
              if (isRemote && activeRemote) {
                sendRemoteCommand(activeRemote.id, displayPlaying ? 'pause' : 'play')
              } else {
                player.togglePlay()
              }
            }}
            disabled={!displaySong || listening}
          >
            <Icon name={displayPlaying ? 'pause' : 'play_arrow'} filled size={26} />
          </button>
          <IconButton
            icon="skip_next"
            filled
            label="Next"
            onClick={() => {
              if (isRemote && activeRemote) {
                sendRemoteCommand(activeRemote.id, 'next')
              } else {
                player.next()
              }
            }}
            disabled={!displaySong || listening}
          />
          <IconButton
            icon={repeat === 'one' ? 'repeat_one' : 'repeat'}
            label="Repeat"
            onClick={player.cycleRepeat}
            disabled={!displaySong || listening || isRemote}
            color={repeat !== 'off' ? 'var(--primary)' : undefined}
            size={20}
          />
        </div>
        <div className="seek">
          <span>{formatDuration(displayPosition / 1000)}</span>
          <Slider
            value={displayPosition}
            max={displayDuration}
            onChange={(pos) => {
              if (isRemote && activeRemote) {
                sendRemoteCommand(activeRemote.id, 'seek', { positionMs: pos })
              } else {
                player.seekTo(pos)
              }
            }}
            label="Seek"
          />
          <span>{formatDuration(displayDuration / 1000)}</span>
        </div>
      </div>
      <div className="extra">
        <IconButton icon="lyrics" label="Lyrics" onClick={open} disabled={!displaySong || isRemote} size={20} />
        <IconButton icon="queue_music" label="Queue" onClick={useQueuePanel.getState().toggle} size={20} color={queueOpen ? 'var(--primary)' : undefined} />
        <DevicePickerButton />
        <IconButton
          icon={displayVolume === 0 ? 'volume_off' : displayVolume < 0.5 ? 'volume_down' : 'volume_up'}
          label={displayVolume === 0 ? 'Unmute' : 'Mute'}
          onClick={() => {
            const nextVol = displayVolume === 0 ? 0.7 : 0
            if (isRemote && activeRemote) {
              sendRemoteCommand(activeRemote.id, 'volume', { volume: nextVol })
            } else {
              player.setVolume(nextVol)
            }
          }}
          size={20}
        />
        <Slider
          value={displayVolume}
          max={1}
          onChange={(v) => {
            if (isRemote && activeRemote) {
              sendRemoteCommand(activeRemote.id, 'volume', { volume: v })
            } else {
              player.setVolume(v)
            }
          }}
          label="Volume"
          style={{ flex: '0 1 110px' }}
        />
        <IconButton icon="open_in_full" label="Full screen player" onClick={open} disabled={!displaySong || isRemote} size={18} />
      </div>
    </footer>
  )
}
