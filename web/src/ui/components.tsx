import { useEffect, useRef, useState, type ReactNode } from 'react'
import type { Album, Playlist, Song } from '../api/types'
import { subsonic } from '../api/subsonic'
import { social } from '../api/social'
import { mixSource, mixSongToSong } from '../api/types'
import { useLikes, likes } from '../state/likes'
import { useQuery } from '@tanstack/react-query'
import { keys, playlistChanged, prefetchAlbum, queryClient } from '../state/queries'
import { useMyPlaylists } from '../state/library'
import { activity, queueMemory } from '../state/history'
import { session } from '../state/session'
import * as player from '../player/player'
import { usePlayer, currentItem } from '../player/player'
import { useListen } from '../social/listen'
import { Checkbox, Icon, IconButton, Menu, NameDialog, Sheet, toast, type MenuItem } from './kit'
import { ShareSongSheet } from '../social/ShareSongSheet'

// Components shared by every screen, as the Android app's ui/components/Components.kt.

export function formatDuration(seconds: number): string {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = Math.floor(seconds % 60)
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}` : `${m}:${String(s).padStart(2, '0')}`
}

/** A total playing time: "45 min", "2 hr 15 min", "3 days 4 hr". */
export function formatTotalDuration(seconds: number): string {
  const days = Math.floor(seconds / 86_400)
  const hours = Math.floor((seconds % 86_400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  if (days > 0) return `${days} ${days === 1 ? 'day' : 'days'}` + (hours > 0 ? ` ${hours} hr` : '')
  if (hours > 0) return `${hours} hr` + (minutes > 0 ? ` ${minutes} min` : '')
  if (minutes > 0) return `${minutes} min`
  return `${seconds} sec`
}

/** "1 song", "3,264 songs" */
export const songCount = (n: number) => (n === 1 ? '1 song' : `${n.toLocaleString('en')} songs`)

/** "1 like", "12 likes", or "No likes yet". */
export const likeCount = (n: number) => (n === 0 ? 'No likes yet' : n === 1 ? '1 like' : `${n.toLocaleString('en')} likes`)

/** Album art from the server, with a plain placeholder behind it while loading or if missing. */
export function Cover({ coverArt, size = 300, corner = 8, className, style, round, fallbacks }: {
  coverArt?: string | null
  size?: number
  corner?: number
  className?: string
  style?: React.CSSProperties
  round?: boolean
  /** Other pictures to try, in order, if this one is missing (a mix's person, then its albums). */
  fallbacks?: string[]
}) {
  const [attempt, setAttempt] = useState(0)
  const choices = [coverArt, ...(fallbacks ?? [])]
  const src = subsonic.coverUrl(choices[attempt], size)
  const [failed, setFailed] = useState(false)
  const key = choices.join('|')
  useEffect(() => {
    setAttempt(0)
    setFailed(false)
  }, [key])
  const onError = () => (attempt + 1 < choices.length ? setAttempt(attempt + 1) : setFailed(true))
  const s: React.CSSProperties = { borderRadius: round ? '50%' : corner, ...style }
  return src && !failed ? (
    <img className={`cover${className ? ' ' + className : ''}`} src={src} loading="lazy" alt="" style={s} onError={onError} draggable={false} />
  ) : (
    <div className={`cover${className ? ' ' + className : ''}`} style={s} />
  )
}

export function AlbumCard({ album, onClick, className }: { album: Album; onClick: () => void; className?: string }) {
  return (
    <div className={`card${className ? ' ' + className : ''}`} onClick={onClick} onPointerEnter={() => prefetchAlbum(album.id)} onPointerDown={() => prefetchAlbum(album.id)}>
      <div className="art">
        <Cover coverArt={album.coverArt} />
        <CardPlay label={`Play ${album.name}`} play={() => playAlbum(album.id)} />
      </div>
      <div className="name title-small ellipsis">{album.name}</div>
      <div className="body-small muted ellipsis">{[album.year, album.artist].filter(Boolean).join(' • ')}</div>
    </div>
  )
}

/** Plays an album from the start (from a card, without opening it). */
export async function playAlbum(id: string) {
  try {
    const album = await queryClient.fetchQuery({ queryKey: keys.album(id), queryFn: () => subsonic.album(id) })
    activity.movie(album)
    player.play(album.song ?? [], 0, false, `album:${album.id}`)
  } catch (e) {
    toast((e as Error).message || "Couldn't play it")
  }
}

/** The round play button that rises onto a card's picture on hover. */
export function CardPlay({ label, play }: { label: string; play: () => void }) {
  return (
    <button
      className="card-play"
      aria-label={label}
      onClick={(e) => {
        e.stopPropagation()
        play()
      }}
    >
      <Icon name="play_arrow" filled />
    </button>
  )
}

export function PlaylistCard({ playlist, onClick, className = 'tile' }: { playlist: Playlist; onClick: () => void; className?: string }) {
  return (
    <div className={`card ${className}`} onClick={onClick}>
      <Cover coverArt={playlist.coverArt} />
      <div className="name title-small ellipsis">{playlist.name}</div>
      <div className="body-small muted">{playlist.songCount} songs</div>
    </div>
  )
}

/**
 * Three bars bouncing up and down, like a level meter: marks the song that's playing. They rest
 * (at different heights) while it's paused.
 */
export function PlayingBars({ playing, size = 16, color }: { playing: boolean; size?: number; color?: string }) {
  return (
    <span className={`bars${playing ? ' playing' : ''}`} style={{ width: size, height: size }}>
      {[0, 1, 2].map((i) => (
        <span key={i} style={color ? { background: color } : undefined} />
      ))}
    </span>
  )
}

/** A vinyl record that turns while music plays: marks what's playing now (Home's "Now playing" tile). */
export function SpinningDisc({ spinning, size = 56 }: { spinning: boolean; size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 100 100"
      style={{ animation: 'spin 3s linear infinite', animationPlayState: spinning ? 'running' : 'paused', flexShrink: 0 }}
    >
      <circle cx="50" cy="50" r="50" fill="#141217" />
      {[0.92, 0.8, 0.68, 0.56].map((f) => (
        <circle key={f} cx="50" cy="50" r={50 * f} fill="none" stroke="rgba(255,255,255,0.10)" strokeWidth="1" />
      ))}
      <path d="M50 50 L73.8 8.8 A47.5 47.5 0 0 1 91.1 26.2 Z" fill="rgba(255,255,255,0.16)" />
      <circle cx="50" cy="50" r="17" fill="var(--primary)" />
      <circle cx="50" cy="40" r="3.5" fill="rgba(20,18,23,0.35)" />
      <circle cx="50" cy="50" r="3" fill="#141217" />
    </svg>
  )
}

/** A heart: filled when liked. */
export function LikeButton({ liked, onToggle, big }: { liked: boolean; onToggle: () => void; big?: boolean }) {
  return (
    <IconButton
      icon="favorite"
      filled={liked}
      label={liked ? 'Remove from liked' : 'Like'}
      onClick={onToggle}
      color={liked ? 'var(--primary)' : undefined}
      size={big ? 32 : undefined}
      className={big ? 'big' : undefined}
    />
  )
}

/** Screen title with an optional back arrow. */
export function ScreenHeader({ title, onBack, note, onTitleClick, actions, color, titleColor }: {
  title: string
  onBack?: () => void
  /** A short status between the title and the actions, like "Jamming with Alice". */
  note?: string
  onTitleClick?: () => void
  actions?: ReactNode
  color?: string
  titleColor?: string
}) {
  return (
    <div className={`screen-header${onBack ? ' with-back' : ''}`} style={color ? { background: color } : undefined}>
      {onBack && <IconButton icon="arrow_back" label="Back" onClick={onBack} />}
      <div
        className="title title-large ellipsis"
        style={{ color: titleColor, cursor: onTitleClick ? 'pointer' : undefined, ...(note ? { flex: '0 1 auto', maxWidth: 200 } : {}) }}
        onClick={onTitleClick}
      >
        {title}
      </div>
      {note && (
        <div className="body-small primary-text clamp2" style={{ flex: 1, textAlign: 'right', padding: '0 4px 0 12px' }}>
          {note}
        </div>
      )}
      {actions}
    </div>
  )
}

/** Section title used above rows and lists, with an optional link on the right ("Show all"). */
export function SectionTitle({ children, action }: { children: ReactNode; action?: { label: string; onClick: () => void } }) {
  if (!action) return <h2 className="section-title">{children}</h2>
  return (
    <div className="section-head">
      <h2 className="section-title">{children}</h2>
      <button className="see-all" onClick={action.onClick}>{action.label}</button>
    </div>
  )
}

/** Whether you're listening along in someone else's jam (then queue actions ask its owner). */
export function useJamListener() {
  const joined = useListen((s) => s.joined)
  const owners = useListen((s) => s.owners)
  const me = session().social?.user.id
  return joined != null && owners[joined] != null && owners[joined] !== me
}

/** Whether this song is the one playing (and whether it's actually playing). */
export function useIsCurrent(songId: string) {
  const isCurrent = usePlayer((s) => currentItem(s)?.song.id === songId)
  const isPlaying = usePlayer((s) => s.isPlaying)
  return { isCurrent, playing: isCurrent && isPlaying }
}

/**
 * One song in a list. Shows the track number, or a small cover when showCover is true (useful when
 * songs come from different albums, like search results). Swipe right: play next; swipe left: add
 * to the end of the queue (in someone else's jam: ask its owner to play it now / next).
 */
export function SongRow({ song, onClick, isCurrent, showCover, index, onOpenAlbum, onRemoveFromPlaylist, inLikedSongs, inOwnPlaylist, note }: {
  song: Song
  onClick: () => void
  isCurrent?: boolean
  showCover?: boolean
  /** Its place in a playlist or list (shown instead of the track number). */
  index?: number
  onOpenAlbum?: (id: string) => void
  /** Set on a playlist you own: removes this song from it. */
  onRemoveFromPlaylist?: () => void
  /** On the Liked songs page every song is liked, so the ✓ only means "also in one of your playlists". */
  inLikedSongs?: boolean
  /** On a playlist you made (its name) every song is in it, so the ✓ only means "also liked, or in another playlist". */
  inOwnPlaylist?: string
  /** Replaces the line under the title, e.g. "Lyrics by Vairamuthu · Guru" in search. */
  note?: string
}) {
  const liked = useLikes((s) => s.songs.some((x) => x.id === song.id))
  const inPlaylists = useMyPlaylists((s) => s.songs[song.id])
  const saved = (liked && !inLikedSongs) || (inPlaylists ?? []).some((n) => n !== inOwnPlaylist)
  const current = useIsCurrent(song.id)
  const showAsCurrent = isCurrent ?? current.isCurrent
  // Hooks run on every render, the same ones in the same order (React needs that): read, then decide.
  const isPlaying = usePlayer((s) => s.isPlaying)
  const playing = showAsCurrent && isPlaying
  const jamListener = useJamListener()
  const [menu, setMenu] = useState<DOMRect | null>(null)
  const [sharing, setSharing] = useState(false)
  const [saving, setSaving] = useState(false)

  const swipe = useSwipe((dir) => {
    const listen = useListen.getState()
    if (listen.isListener()) listen.requestSong(song, dir === 'right')
    else {
      if (dir === 'right') player.playNext(song)
      else player.addToQueue(song)
      toast(dir === 'right' ? 'Playing next' : 'Added to queue')
    }
  })

  const items: MenuItem[] = [
    { label: liked ? 'Remove from liked songs' : 'Like', onClick: () => likes().toggleSong(song) },
    { label: 'Play next', onClick: () => player.playNext(song) },
    { label: 'Add to queue', onClick: () => player.addToQueue(song) },
    { label: 'Add to playlist', onClick: () => setSaving(true) },
    { label: 'Remove from this playlist', onClick: () => onRemoveFromPlaylist?.(), hidden: !onRemoveFromPlaylist },
    { label: 'Start song radio', onClick: () => void startStation('song', song.id) },
    { label: 'Share with friends', onClick: () => setSharing(true) },
    { label: 'Go to movie', onClick: () => song.albumId && onOpenAlbum?.(song.albumId), hidden: !onOpenAlbum || !song.albumId },
  ]

  return (
    <div className="swipe-wrap">
      {swipe.dir && (
        <div className={`swipe-hint ${swipe.dir === 'right' ? 'next' : 'queue'}`}>
          <Icon name="queue_music" />
          {jamListener ? (swipe.dir === 'right' ? 'Ask to play now' : 'Ask to play next') : swipe.dir === 'right' ? 'Play next' : 'Add to queue'}
        </div>
      )}
      <div
        className={`song-row${showAsCurrent ? ' current' : ''}`}
        {...swipe.handlers}
        style={{ transform: swipe.dx ? `translateX(${swipe.dx}px)` : undefined, transition: swipe.dragging ? 'none' : 'transform 0.2s' }}
        onClick={() => !swipe.moved() && onClick()}
        onContextMenu={(e) => {
          e.preventDefault()
          setMenu(new DOMRect(e.clientX, e.clientY, 0, 0))
        }}
      >
        {(!showCover || index != null) && (
          <div className="num body-medium">
            {showAsCurrent && !showCover ? (
              <PlayingBars playing={playing} />
            ) : (
              <>
                <span className="n">{index != null ? index + 1 : (song.track ?? '')}</span>
                <span className="on-hover"><Icon name={playing ? 'pause' : 'play_arrow'} filled size={18} /></span>
              </>
            )}
          </div>
        )}
        {showCover && (
          <div style={{ position: 'relative', width: 'var(--song-thumb)', height: 'var(--song-thumb)', flexShrink: 0, marginLeft: index != null ? 8 : 0 }}>
            <Cover coverArt={song.coverArt} size={150} corner={4} style={{ width: '100%', height: '100%' }} />
            {showAsCurrent && (
              <div style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.55)', borderRadius: 4, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                <PlayingBars playing={playing} size={16} />
              </div>
            )}
          </div>
        )}
        <div className="text">
          <div className="title body-large ellipsis">{song.title}</div>
          <div className="body-small muted ellipsis">{note ?? song.artist}</div>
        </div>
        {showCover && (
          <div className="album-col ellipsis">
            {song.album && (onOpenAlbum && song.albumId ? (
              <a href={`/album/${song.albumId}`} onClick={(e) => { e.preventDefault(); e.stopPropagation(); onOpenAlbum(song.albumId!) }}>{song.album}</a>
            ) : song.album)}
          </div>
        )}
        {saved && (
          <span
            title="Saved. Tap to see where"
            onClick={(e) => {
              e.stopPropagation()
              setSaving(true)
            }}
            style={{ marginRight: 8, cursor: 'pointer', display: 'inline-flex' }}
          >
            <Icon name="check_circle" filled size={20} style={{ color: 'var(--primary)' }} />
          </span>
        )}
        <span className="dur body-small">{formatDuration(song.duration)}</span>
        <IconButton icon="more_horiz" label="More" className="row-more" onClick={(e) => setMenu((e.currentTarget as HTMLElement).getBoundingClientRect())} />
      </div>
      {menu && <Menu items={items} anchor={menu} onClose={() => setMenu(null)} />}
      {sharing && <ShareSongSheet song={song} onClose={() => setSharing(false)} />}
      {saving && <AddToPlaylistSheet song={song} onClose={() => setSaving(false)} />}
    </div>
  )
}

/** Horizontal swipe on a row (touch or mouse): past 80 px acts once, then springs back. */
function useSwipe(onSwipe: (dir: 'left' | 'right') => void) {
  const [dx, setDx] = useState(0)
  const [dragging, setDragging] = useState(false)
  const start = useRef<{ x: number; y: number; id: number } | null>(null)
  const horizontal = useRef(false)
  const movedRef = useRef(false)
  const end = () => {
    if (start.current && Math.abs(dx) > 80) onSwipe(dx > 0 ? 'right' : 'left')
    start.current = null
    horizontal.current = false
    setDragging(false)
    setDx(0)
  }
  return {
    dx,
    dragging,
    dir: dx > 12 ? ('right' as const) : dx < -12 ? ('left' as const) : null,
    moved: () => {
      const m = movedRef.current
      movedRef.current = false
      return m
    },
    handlers: {
      onPointerDown: (e: React.PointerEvent) => {
        if (e.button !== 0 || (e.target as HTMLElement).closest('button')) return
        start.current = { x: e.clientX, y: e.clientY, id: e.pointerId }
        movedRef.current = false
      },
      onPointerMove: (e: React.PointerEvent) => {
        const s = start.current
        if (!s || s.id !== e.pointerId) return
        const x = e.clientX - s.x
        const y = e.clientY - s.y
        if (!horizontal.current) {
          if (Math.abs(y) > 10) {
            start.current = null
            return
          }
          if (Math.abs(x) > 10) {
            horizontal.current = true
            movedRef.current = true
            setDragging(true)
            ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
          }
        }
        if (horizontal.current) setDx(Math.max(-160, Math.min(160, x)))
      },
      onPointerUp: end,
      onPointerCancel: end,
    },
  }
}

/**
 * "Save to", like Spotify: Liked songs and each of your playlists, ticked where the song already is.
 * Tick or untick, then Done adds or removes it everywhere at once.
 */
export function AddToPlaylistSheet({ song, onClose }: { song: Song; onClose: () => void }) {
  const username = session().credentials?.username
  const [playlists, setPlaylists] = useState<Playlist[] | null>(null)
  const [checked, setChecked] = useState<Record<string, boolean>>({})
  const [liked, setLiked] = useState(() => likes().songs.some((s) => s.id === song.id))
  const [creating, setCreating] = useState(false)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    ;(async () => {
      const mine = (await subsonic.playlists().catch(() => [] as Playlist[]))
        .filter((p) => p.owner === username)
        .sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()))
      const full = await Promise.all(mine.map((p) => subsonic.playlist(p.id).catch(() => p)))
      setChecked(Object.fromEntries(full.map((p) => [p.id, (p.entry ?? []).some((s) => s.id === song.id)])))
      setPlaylists(full)
    })()
  }, [song.id, username])

  async function done() {
    setBusy(true)
    let changed = 0
    if (liked !== likes().songs.some((s) => s.id === song.id)) {
      likes().toggleSong(song)
      changed++
    }
    const failed: string[] = []
    for (const p of playlists ?? []) {
      const entries = p.entry ?? []
      const had = entries.some((s) => s.id === song.id)
      const want = !!checked[p.id]
      if (had === want) continue
      try {
        if (want) await subsonic.updatePlaylist(p.id, { add: [song.id] })
        else await subsonic.updatePlaylist(p.id, { removeIndexes: entries.map((s, i) => (s.id === song.id ? i : -1)).filter((i) => i >= 0) })
        changed++
      } catch {
        failed.push(p.name)
      }
    }
    if (failed.length) toast(`Couldn't change ${failed.join(', ')}`)
    else if (changed > 0) toast('Saved')
    if (changed > 0) {
      useMyPlaylists.getState().refresh()
      for (const p of playlists ?? []) playlistChanged(p.id)
    }
    onClose()
  }

  const row = (key: string, title: string, subtitle: string | null, leading: ReactNode, on: boolean, set: (v: boolean) => void, enabled = true) => (
    <div key={key} className="list-row" style={{ opacity: enabled ? 1 : 0.5, paddingRight: 8 }} onClick={() => enabled && set(!on)}>
      {leading}
      <div className="text">
        <div className="body-large ellipsis">{title}</div>
        {subtitle && <div className="body-small muted">{subtitle}</div>}
      </div>
      <Checkbox checked={on} disabled={!enabled} onChange={set} />
    </div>
  )
  const square = (bg: string, icon: string, fg: string, filled = false) => (
    <div style={{ width: 48, height: 48, borderRadius: 6, background: bg, display: 'flex', alignItems: 'center', justifyContent: 'center', color: fg, flexShrink: 0 }}>
      <Icon name={icon} filled={filled} />
    </div>
  )

  return (
    <Sheet onClose={onClose}>
      <div style={{ display: 'flex', alignItems: 'center', padding: '0 8px 0 16px' }}>
        <div className="title-medium" style={{ flex: 1 }}>Save to</div>
        <button className="btn" disabled={busy} onClick={done}>{busy ? 'Saving…' : 'Done'}</button>
      </div>
      <div className="body-medium muted ellipsis" style={{ padding: '0 16px' }}>{song.title}</div>
      <div style={{ padding: '8px 0 16px' }}>
        <div className="list-row" onClick={() => setCreating(true)}>
          {square('var(--primary-container)', 'add', 'var(--on-primary-container)')}
          <div className="body-large" style={{ paddingLeft: 2 }}>New playlist</div>
        </div>
        {row('liked', 'Liked songs', null, square('var(--primary)', 'favorite', 'var(--on-primary)', true), liked, setLiked)}
        {playlists == null && <div className="muted" style={{ padding: 16 }}>Loading your playlists…</div>}
        {(playlists ?? []).map((p) =>
          row(
            p.id,
            p.name,
            // The server won't change the songs of a read-only playlist.
            p.readonly ? "Can't be changed here (smart or file playlist)" : songCount(p.songCount),
            <Cover coverArt={p.coverArt} size={150} corner={6} style={{ width: 48, height: 48, flexShrink: 0 }} />,
            !!checked[p.id],
            (v) => setChecked((c) => ({ ...c, [p.id]: v })),
            !p.readonly,
          ),
        )}
      </div>
      {creating && (
        <NameDialog
          title="New playlist"
          confirm="Create"
          onClose={() => setCreating(false)}
          onConfirm={async (name) => {
            try {
              const created = await subsonic.createPlaylist(name, song.id)
              playlistChanged()
              // It's made with the song already in it: show it ticked.
              setPlaylists((l) => [{ ...created, entry: [song], songCount: 1 }, ...(l ?? [])])
              setChecked((c) => ({ ...c, [created.id]: true }))
              toast(`Created "${created.name}"`)
            } catch (e) {
              toast((e as Error).message || "Couldn't create it")
            }
            setCreating(false)
          }}
        />
      )}
    </Sheet>
  )
}

/**
 * Starts a station by Jukebox: kind is "song", "album", "composer" or "singer". It plays at once
 * and keeps going (the player asks for more songs as it goes).
 */
export async function startStation(kind: 'song' | 'album' | 'composer' | 'singer', id: string) {
  try {
    const station = await social.mix(`radio-${kind}-${id}`)
    activity.mix(station)
    player.play(station.songs.map(mixSongToSong), 0, false, mixSource(station))
    toast(`Playing ${station.title}`)
  } catch (e) {
    toast((e as Error).message || "Couldn't start the station")
  }
}

/** Under a page's header: the big play button, shuffle, the page's own buttons (like, more), then Resume. */
export function PlayShuffleRow({ onPlay, onShuffle, children, resume }: {
  onPlay: () => void
  onShuffle?: () => void
  children?: ReactNode
  /** Offers "Resume · song" when this page was played before (see ResumeButton). */
  resume?: { source: string; songs: Song[]; onResume?: () => void }
}) {
  return (
    <div className="hero-bar">
      <button className="fab-play" aria-label="Play" onClick={onPlay}>
        <Icon name="play_arrow" filled />
      </button>
      {onShuffle && <IconButton icon="shuffle" label="Shuffle" onClick={onShuffle} size={30} className="big" />}
      {children}
      {resume && <ResumeButton {...resume} />}
    </div>
  )
}

/**
 * "Resume · Song name" (ResumeButton in DetailScreens.kt): continue a playlist, album or Liked songs from the
 * song you were on last time, in the same order as then (a shuffled order stays as it was). Songs added since
 * join at the end. Hidden while you're already playing from this page.
 */
function ResumeButton({ source, songs, onResume }: { source: string; songs: Song[]; onResume?: () => void }) {
  const playingFrom = usePlayer((s) => currentItem(s)?.source)
  if (playingFrom === source) return null
  const saved = queueMemory.get(source)
  const byId = new Map(songs.map((s) => [s.id, s]))
  const current = saved && byId.get(saved.currentId)
  if (!saved || !current) return null
  const resume = () => {
    const order = [...new Set(saved.songIds)].map((id) => byId.get(id)).filter((s): s is Song => !!s)
    const queue = [...order, ...songs.filter((s) => !order.some((o) => o.id === s.id))]
    onResume?.()
    player.play(queue, Math.max(0, queue.findIndex((s) => s.id === current.id)), false, source)
  }
  return (
    <button className="btn tonal resume" onClick={resume} title={`Resume · ${current.title}`}>
      <Icon name="play_arrow" filled size={18} />
      <span className="ellipsis">Resume · {current.title}</span>
    </button>
  )
}

/**
 * A value loaded from the server and kept in the website's cache under [key] (state/queries.ts): the first
 * visit waits for it, later visits show it at once and refresh it in the background when it's old.
 */
export function useLoad<T>(key: unknown[], load: () => Promise<T>, staleTime?: number) {
  const q = useQuery({ queryKey: key, queryFn: load, staleTime })
  return {
    data: q.data,
    error: q.error ? q.error.message || 'Something went wrong' : undefined,
    loading: q.isPending,
    retry: () => void q.refetch(),
    set: (data: T) => queryClient.setQueryData(key, data),
  }
}
