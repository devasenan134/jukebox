import { useEffect, useMemo, useState, type CSSProperties, type ReactNode } from 'react'
import type { Album, Mix } from '../api/types'
import { refToSong } from '../api/types'
import { subsonic } from '../api/subsonic'
import * as player from '../player/player'
import { currentItem, usePlayer } from '../player/player'
import { activity, useActivity, useRecentPlaylists, useRecentSongs, type ActivityItem, type ActivityKind } from '../state/history'
import { useDevices } from '../state/devices'
import { useMixes } from '../state/library'
import { AlbumCard, CardPlay, Cover, playAlbum, SectionTitle, SongRow, SpinningDisc, useLoad } from '../ui/components'
import { CardsSkeleton } from '../ui/collection'
import { useCoverColor } from '../ui/coverColor'
import { likes, useLikes } from '../state/likes'
import { ErrorBox, IconButton } from '../ui/kit'
import { LikedTile, MixCover, MixSections } from '../ui/mixes'
import { useNav, type Nav } from '../ui/nav'
import { keys, queryClient } from '../state/queries'

// Home (ui/home/HomeScreen.kt): Made for you, the songs you played lately, "Jump back in", the other
// mixes, then rows of albums: most played, recently added and random picks.

const RECENT_SONGS = 6

interface EffectiveNowPlaying {
  songId?: string | null
  title?: string
  artist?: string
  album?: string
  albumId?: string
  coverArt?: string
  isPlaying: boolean
  source?: string | null
}

export function HomeScreen() {
  const nav = useNav()
  const mixes = useMixes((s) => s.home)
  const followed = useMixes((s) => s.followed)
  const items = useActivity((s) => s.items)
  const recentSongs = useRecentSongs((s) => s.songs)
  const likedCount = useLikes((s) => s.songs.length)

  const localPlayer = usePlayer()
  const localItem = currentItem(localPlayer)
  const devices = useDevices((s) => s.devices)
  const activeDeviceId = useDevices((s) => s.activeDeviceId)
  const currentDeviceId = useDevices((s) => s.currentDeviceId)

  const remotePlaying = devices.find((d) => d.id === activeDeviceId && d.playing && d.id !== currentDeviceId)
  const effectiveNowPlaying: EffectiveNowPlaying | null = remotePlaying
    ? {
        songId: remotePlaying.song?.id,
        title: remotePlaying.song?.title,
        artist: remotePlaying.song?.artist,
        album: remotePlaying.song?.album,
        albumId: remotePlaying.song?.albumId,
        coverArt: remotePlaying.song?.coverArt,
        isPlaying: remotePlaying.playing,
        source: remotePlaying.source,
      }
    : localItem?.song
    ? {
        songId: localItem.song.id,
        title: localItem.song.title,
        artist: localItem.song.artist,
        album: localItem.song.album,
        albumId: localItem.song.albumId,
        coverArt: localItem.song.coverArt,
        isPlaying: localPlayer.isPlaying,
        source: localItem.source,
      }
    : null

  const [hovered, setHovered] = useState<string | undefined>()
  const [tint, setTint] = useState('hsl(28 45% 30%)')
  useEffect(() => useMixes.getState().refresh(), [])
  const lists = useLoad(['home', 'lists'], async () => {
    const [recent, frequent, newest] = await Promise.all([
      subsonic.albumList('recent', 20),
      subsonic.albumList('frequent', 20),
      subsonic.albumList('newest', 20),
    ])
    return { recent, frequent, newest }
  })
  // Random picks stay the same until you ask for new ones (Refresh), not every time you come back.
  const random = useLoad(['home', 'random'], () => subsonic.albumList('random', 20), Infinity)
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['home'] })
    useMixes.getState().refresh(true)
  }

  const sections = mixes?.sections ?? []
  const knownMixes = useMemo(
    () => new Map([...sections.flatMap((s) => s.mixes), ...followed].map((m) => [m.id, m])),
    [sections, followed]
  )

  const playingFrom = useMemo(
    () => getPlayingFrom(effectiveNowPlaying, items, knownMixes),
    [effectiveNowPlaying?.songId, effectiveNowPlaying?.source, items, knownMixes]
  )

  if (lists.loading && !lists.data) return <div className="page"><div className="skel" style={{ width: 260, height: 32, margin: '24px 16px 16px' }} /><CardsSkeleton rows={3} /></div>
  if (lists.error) return <ErrorBox message={lists.error} onRetry={lists.retry} />
  const d = { ...lists.data!, random: random.data ?? [] }
  const songs = recentSongs.slice(0, RECENT_SONGS).map(refToSong)
  const collections = items.filter((i) => i.kind !== 'Song')

  const restCollections = collections.filter(
    (c) => !(playingFrom && c.kind === playingFrom.kind && c.id === playingFrom.id)
  )
  const jumpBackItems = playingFrom ? [playingFrom, ...restCollections] : collections

  const quick = quickPicks(collections, likedCount, [...d.recent, ...d.newest], knownMixes, nav)
  const tintCover = hovered ?? quick.find((q) => q.coverArt)?.coverArt
  return (
    <div className="page tint-top" style={{ '--tint': tint } as CSSProperties}>
      <TintFrom coverArt={tintCover} onColor={setTint} />
      <div style={{ padding: '20px 12px 4px 16px', display: 'flex', alignItems: 'center', gap: 8 }}>
        <h1 className="headline-medium" style={{ margin: 0, flex: 1 }}>{greeting()}</h1>
        <IconButton icon="refresh" label="Refresh" onClick={refresh} />
        <IconButton icon="settings" label="Settings" onClick={nav.openSettings} />
      </div>
      <div style={{ display: 'flex', gap: 8, padding: '6px 16px 16px' }}>
        <button className="chip" onClick={nav.openAlbums}>Albums</button>
        <button className="chip" onClick={nav.openArtists}>Music directors</button>
      </div>
      <div className="quick-grid" onMouseLeave={() => setHovered(undefined)}>
        {quick.map((q) => (
          <div key={q.key} className="quick" onClick={q.open} onMouseEnter={() => setHovered(q.coverArt)} data-testid="quick-pick">
            {q.art}
            <div className="label clamp2">{q.title}</div>
            {q.play && <CardPlay label={`Play ${q.title}`} play={q.play} />}
          </div>
        ))}
      </div>
      <MixSections sections={sections.filter((s) => s.id === 'made-for-you')} nav={nav} />
      {songs.length > 0 && (
        <>
          <SectionTitle>Recently played</SectionTitle>
          <div style={{ padding: '0 8px' }}>
            {songs.map((s) => (
              <SongRow
                key={s.id}
                song={s}
                isCurrent={s.id === effectiveNowPlaying?.songId}
                showCover
                onOpenAlbum={nav.openAlbum}
                onClick={() => { activity.song(s); player.play([s]) }}
              />
            ))}
          </div>
        </>
      )}
      {jumpBackItems.length > 0 ? (
        <JumpBackIn
          items={jumpBackItems}
          mixes={knownMixes}
          nav={nav}
          playingItem={playingFrom}
          isPlaying={effectiveNowPlaying?.isPlaying ?? false}
        />
      ) : (
        <AlbumRow title="Jump back in" albums={d.recent} nav={nav} />
      )}
      <MixSections sections={sections.filter((s) => s.id !== 'made-for-you')} nav={nav} />
      <AlbumRow title="Most played" albums={d.frequent} nav={nav} />
      <AlbumRow title="Recently added" albums={d.newest} nav={nav} action={{ label: 'Show all', onClick: nav.openAlbums }} />
      <AlbumRow title="Random picks" albums={d.random} nav={nav} />
    </div>
  )
}

function getPlayingFrom(now: EffectiveNowPlaying | null, items: ActivityItem[], knownMixes: Map<string, Mix>): ActivityItem | null {
  if (!now || !now.songId) return null
  const source = now.source ?? ''
  let from: { kind: ActivityKind; id: string } | null = null
  if (source === 'liked') {
    from = { kind: 'Liked', id: 'liked' }
  } else if (source.startsWith('playlist:')) {
    from = { kind: 'Playlist', id: source.slice('playlist:'.length) }
  } else if (source.startsWith('album:')) {
    from = { kind: 'Movie', id: source.slice('album:'.length) }
  } else if (source.startsWith('mix:')) {
    from = { kind: 'Mix', id: source.slice('mix:'.length) }
  } else if (source.startsWith('composer:')) {
    from = { kind: 'Composer', id: source.slice('composer:'.length) }
  } else if (source.startsWith('singer:')) {
    from = { kind: 'Artist', id: source.slice('singer:'.length) }
  }

  if (from) {
    const existing = items.find((i) => i.kind === from!.kind && i.id === from!.id)
    if (existing) return existing
    const mix = from.kind === 'Mix' ? knownMixes.get(from.id) : undefined
    const recentPlaylist = from.kind === 'Playlist' ? useRecentPlaylists.getState().playlists.find((p) => p.id === from!.id) : undefined
    const title =
      from.kind === 'Playlist' ? (recentPlaylist?.name || 'Playlist')
      : from.kind === 'Liked' ? 'Liked songs'
      : from.kind === 'Composer' ? 'Composer'
      : from.kind === 'Artist' ? (now.artist || 'Artist')
      : from.kind === 'Mix' ? (mix?.title || 'Mix')
      : (now.album || now.title || 'Album')
    return {
      kind: from.kind,
      id: from.id,
      title,
      coverArt: mix ? mix.covers[0] : (recentPlaylist?.coverArt || now.coverArt),
      playedAt: Date.now(),
    }
  }

  if (now.albumId) {
    const existing = items.find((i) => i.kind === 'Movie' && i.id === now.albumId)
    if (existing) return existing
    return {
      kind: 'Movie',
      id: now.albumId,
      title: now.album || now.title || 'Album',
      subtitle: now.artist,
      coverArt: now.coverArt,
      playedAt: Date.now(),
    }
  }

  return null
}

/** "Good morning" / "Good afternoon" / "Good evening", by the clock here. */
function greeting(): string {
  const h = new Date().getHours()
  return h < 5 ? 'Good evening' : h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening'
}

/** Picks up a cover's colour for the top of Home (it follows the tile under the pointer). */
function TintFrom({ coverArt, onColor }: { coverArt?: string; onColor: (c: string) => void }) {
  const color = useCoverColor(coverArt, 'hsl(28 45% 30%)')
  useEffect(() => onColor(color), [color, onColor])
  return null
}

const QUICK = 8

interface Quick {
  key: string
  title: string
  coverArt?: string
  art: ReactNode
  open: () => void
  play?: () => void
}

/** The tiles at the top of Home: Liked songs, then what you played as a whole lately, then recent albums. */
function quickPicks(items: ActivityItem[], likedCount: number, albums: Album[], mixes: Map<string, Mix>, nav: Nav): Quick[] {
  const out: Quick[] = []
  if (likedCount > 0) {
    out.push({
      key: 'liked', title: 'Liked songs', art: <div className="thumb"><LikedTile size={64} fill /></div>, open: nav.openLikedSongs,
      play: () => { activity.liked(); player.play(likes().songs, 0, false, 'liked') },
    })
  }
  for (const i of items) {
    if (out.length >= QUICK) break
    if (i.kind === 'Liked') continue
    const mix = i.kind === 'Mix' ? mixes.get(i.id) : undefined
    const round = i.kind === 'Composer' || i.kind === 'Artist'
    out.push({
      key: `${i.kind}-${i.id}`, title: i.title, coverArt: i.coverArt,
      art: mix ? <div className="thumb"><MixCover mix={mix} size={64} fill /></div> : <Cover coverArt={i.coverArt} size={150} round={round} />,
      open: () => openActivity(i, nav),
      play: i.kind === 'Movie' ? () => void playAlbum(i.id) : i.kind === 'Playlist' ? () => void playPlaylist(i.id) : undefined,
    })
  }
  for (const a of albums) {
    if (out.length >= QUICK) break
    if (out.some((q) => q.key === `Movie-${a.id}` || q.key === `album-${a.id}`)) continue
    out.push({ key: `album-${a.id}`, title: a.name, coverArt: a.coverArt, art: <Cover coverArt={a.coverArt} size={150} />, open: () => nav.openAlbum(a.id), play: () => void playAlbum(a.id) })
  }
  return out
}

async function playPlaylist(id: string) {
  const p = await queryClient.fetchQuery({ queryKey: keys.playlist(id), queryFn: () => subsonic.playlist(id) })
  activity.playlist(p)
  player.play(p.entry ?? [], 0, false, `playlist:${p.id}`)
}

function openActivity(i: ActivityItem, nav: Nav) {
  switch (i.kind) {
    case 'Song':
      if (i.song) {
        activity.song(refToSong(i.song))
        player.play([refToSong(i.song)])
      }
      return
    case 'Movie': return nav.openAlbum(i.id)
    case 'Playlist': return nav.openPlaylist(i.id)
    case 'Composer': return nav.openArtist(i.id)
    case 'Artist': return nav.openSinger({ id: i.id, name: i.title, coverArt: i.coverArt, roles: ['artist'] })
    case 'Liked': return nav.openLikedSongs()
    case 'Mix': return nav.openMix(i.id)
  }
}

function AlbumRow({ title, albums, nav, action }: { title: string; albums: Album[]; nav: Nav; action?: { label: string; onClick: () => void } }) {
  if (!albums.length) return null
  return (
    <>
      <SectionTitle action={action}>{title}</SectionTitle>
      <div className="row-scroll">
        {albums.map((a) => (
          <AlbumCard key={a.id} album={a} onClick={() => nav.openAlbum(a.id)} className="tile" />
        ))}
      </div>
    </>
  )
}

/** Albums, playlists, mixes and Liked songs as squares; composers and singers as circles. */
function JumpBackIn({
  items,
  mixes,
  nav,
  playingItem,
  isPlaying,
}: {
  items: ActivityItem[]
  mixes: Map<string, Mix>
  nav: Nav
  playingItem?: ActivityItem | null
  isPlaying?: boolean
}) {
  const open = (i: ActivityItem) => openActivity(i, nav)
  return (
    <>
      <SectionTitle>Jump back in</SectionTitle>
      <div className="row-scroll">
        {items.map((i) => {
          const isCurrent = Boolean(playingItem && i.kind === playingItem.kind && i.id === playingItem.id)
          const round = i.kind === 'Composer' || i.kind === 'Artist'
          const mix = i.kind === 'Mix' ? mixes.get(i.id) : undefined
          const kindLabel = i.kind === 'Movie' ? 'Movie' : i.kind === 'Song' ? 'Song' : undefined
          const subtitleText = isCurrent
            ? (isPlaying ? 'Now playing' : 'Paused')
            : [kindLabel, i.subtitle].filter(Boolean).join(' · ')
          return (
            <div key={`${i.kind}-${i.id}`} className="card tile" onClick={() => open(i)} data-testid="jump-back-in">
              <div style={{ position: 'relative', width: '100%', aspectRatio: '1' }}>
                {i.kind === 'Liked' ? (
                  <LikedTile size={192} fill />
                ) : mix ? (
                  <MixCover mix={mix} size={192} fill />
                ) : (
                  <Cover coverArt={i.coverArt} round={round} style={{ width: '100%', aspectRatio: '1', boxShadow: '0 8px 24px rgba(0,0,0,0.5)' }} />
                )}
                {isCurrent && (
                  <div style={{ position: 'absolute', top: 8, right: 8, zIndex: 2 }}>
                    <SpinningDisc spinning={Boolean(isPlaying)} size={48} />
                  </div>
                )}
              </div>
              <div className="name title-small ellipsis" style={{ textAlign: round ? 'center' : undefined, marginTop: 6 }}>{i.title}</div>
              <div
                className="body-small ellipsis"
                style={{
                  textAlign: round ? 'center' : undefined,
                  color: isCurrent ? 'var(--primary)' : 'var(--muted)',
                }}
              >
                {subtitleText}
              </div>
            </div>
          )
        })}
      </div>
    </>
  )
}
