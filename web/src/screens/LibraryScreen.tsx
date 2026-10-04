import { useEffect, useState, type ReactNode } from 'react'
import { useLocation } from 'react-router-dom'
import type { Playlist } from '../api/types'
import { MIX_AUTHOR } from '../api/types'
import { subsonic } from '../api/subsonic'
import { social } from '../api/social'
import { currentItem, usePlayer } from '../player/player'
import { useMixes, useMyPlaylists } from '../state/library'
import { useLikes } from '../state/likes'
import { useSession } from '../state/session'
import { load, save } from '../state/storage'
import { Cover, likeCount, PlayingBars, ScreenHeader, songCount, useLoad } from '../ui/components'
import { keys, playlistChanged } from '../state/queries'
import { IconButton, NameDialog, toast } from '../ui/kit'
import { LikedTile, madeForName, MixCover } from '../ui/mixes'
import { useNav } from '../ui/nav'

/** The sections of Your Library, in the order of the chips. */
export const FILTERS = ['All', 'Playlists', 'Albums', 'My Playlists'] as const
export type Filter = (typeof FILTERS)[number]

/** One thing in Your Library, shown as a row or a tile. art draws its picture at a size (or filling a tile). */
export interface Entry {
  key: string
  title: string
  subtitle: string
  open: () => void
  /** Its page, to mark the one that's open. */
  path?: string
  /** What the player calls music started from it ("playlist:<id>"), to mark the one playing. */
  source?: string
  round?: boolean
  art: (size: number, fill?: boolean) => ReactNode
}

/**
 * Everything in Your Library for a filter: Liked songs, saved mixes, liked and own playlists, liked albums
 * (ui/library/LibraryScreen.kt). Shared by the Library page and the side panel on wide screens.
 */
export function useLibraryEntries(filter: Filter) {
  const nav = useNav()
  const username = useSession((s) => s.credentials?.username)
  const likedSongs = useLikes((s) => s.songs)
  const likedAlbums = useLikes((s) => s.albums)
  const likedPlaylists = useLikes((s) => s.playlists)
  const savedMixes = useMixes((s) => s.followed)
  const all = useLoad(keys.playlists, () => subsonic.playlists(), 30_000)
  const own = (all.data ?? []).filter((p) => p.owner === username)
  const ownIds = own.map((p) => p.id)
  // How many friends liked each playlist you made (shown only when someone has).
  const ownLikes: Record<string, number> = useLoad(['playlist-likes', ...ownIds], () => (ownIds.length ? social.playlistLikeCounts(ownIds) : Promise.resolve({})), 30_000).data ?? {}

  // Liked first (newest like first), then the rest of your own. A liked playlist of your own uses your copy.
  const mine = (p: Playlist) => p.owner === username
  const playlists = [
    ...likedPlaylists.map((liked) => own.find((p) => p.id === liked.id) ?? liked),
    ...own.filter((p) => !likedPlaylists.some((l) => l.id === p.id)),
  ]
  const showMine = filter === 'All' || filter === 'My Playlists'
  const showSaved = filter === 'All' || filter === 'Playlists'
  const madeFor = madeForName()
  const entries: Entry[] = []
  if (showMine) {
    entries.push({
      key: 'liked', title: 'Liked songs', subtitle: `Playlist • ${songCount(likedSongs.length)}`, open: nav.openLikedSongs, path: '/liked', source: 'liked',
      art: (size, fill) => <LikedTile size={size} fill={fill} />,
    })
  }
  if (showSaved) {
    for (const mix of savedMixes) {
      const subtitle = [
        mix.personal && madeFor ? `Made for ${madeFor}` : mix.endless ? 'Station' : 'Mix',
        MIX_AUTHOR,
        mix.endless || !mix.songCount ? null : songCount(mix.songCount),
      ].filter(Boolean).join(' • ')
      entries.push({
        key: `mix-${mix.id}`, title: mix.title, subtitle, open: () => nav.openMix(mix.id), path: `/mix/${mix.id}`, source: `mix:${mix.id}`,
        round: mix.round, art: (size, fill) => <MixCover mix={mix} size={size} fill={fill} />,
      })
    }
  }
  for (const p of playlists) {
    if (mine(p) ? !showMine : !showSaved) continue
    const likes = mine(p) && ownLikes[p.id] > 0 ? likeCount(ownLikes[p.id]) : null
    entries.push({
      key: `playlist-${p.id}`, title: p.name,
      subtitle: ['Playlist', p.owner, likes].filter(Boolean).join(' • '),
      open: () => nav.openPlaylist(p.id), path: `/playlist/${p.id}`, source: `playlist:${p.id}`,
      art: (size, fill) => <Art coverArt={p.coverArt} size={size} fill={fill} />,
    })
  }
  if (filter === 'All' || filter === 'Albums') {
    for (const a of likedAlbums) {
      entries.push({
        key: `album-${a.id}`, title: a.name, subtitle: ['Album', a.artist].filter(Boolean).join(' • '),
        open: () => nav.openAlbum(a.id), path: `/album/${a.id}`, source: `album:${a.id}`,
        art: (size, fill) => <Art coverArt={a.coverArt} size={size} fill={fill} />,
      })
    }
  }
  // Liked songs is always there in All and My Playlists, so "empty" means nothing else is.
  const empty = entries.every((e) => e.key === 'liked')
  const hint = {
    Albums: 'Tap ♡ on an album to keep it here.',
    Playlists: 'Tap ♡ on playlists and mixes to keep them here.',
    'My Playlists': 'Playlists you make show up here. Tap + to make one.',
    All: 'Tap ♡ on songs, albums, playlists and mixes to keep them here.',
  }[filter]
  return { entries, empty, hint }
}

/** The New playlist dialog's state and action: makes it, then opens it. */
export function useNewPlaylist() {
  const nav = useNav()
  const [creating, setCreating] = useState(false)
  const create = async (name: string) => {
    setCreating(false)
    try {
      const p = await subsonic.createPlaylist(name)
      playlistChanged()
      useMyPlaylists.getState().refresh()
      nav.openPlaylist(p.id)
    } catch (e) {
      toast((e as Error).message || "Couldn't create it")
    }
  }
  const dialog = creating && <NameDialog title="New playlist" confirm="Create" onClose={() => setCreating(false)} onConfirm={create} />
  return { start: () => setCreating(true), dialog }
}

/** The filter chips over the list (remembered in this browser). Tapping the chosen one again shows everything. */
export function useLibraryFilter(key = 'library.filter') {
  const [filter, setFilter] = useState<Filter>(() => load<Filter>(key, 'All'))
  const chips = (
    <div className="chips" style={{ display: 'flex', gap: 8, overflowX: 'auto', scrollbarWidth: 'none' }}>
      {FILTERS.filter((f) => f !== 'All').map((f) => (
        <button
          key={f}
          className={`chip${filter === f ? ' selected' : ''}`}
          onClick={() => {
            const next = filter === f ? 'All' : f
            setFilter(next)
            save(key, next)
          }}
        >
          {f}
        </button>
      ))}
    </div>
  )
  return { filter, chips }
}

/** Marks for a row: open now, and playing now. */
export function useEntryState() {
  const location = useLocation()
  const source = usePlayer((s) => currentItem(s)?.source)
  const isPlaying = usePlayer((s) => s.isPlaying)
  return (e: Entry) => ({ open: e.path === location.pathname, playing: e.source != null && e.source === source, isPlaying })
}

/** Your Library: liked songs, saved mixes, liked albums, liked playlists and playlists you made. */
export function LibraryScreen() {
  const { filter, chips } = useLibraryFilter()
  const { entries, empty, hint } = useLibraryEntries(filter)
  const newPlaylist = useNewPlaylist()
  const state = useEntryState()
  const nav = useNav()
  const [grid, setGrid] = useState(() => load('library.grid', false))
  useEffect(() => {
    useLikes.getState().refresh()
    useMixes.getState().refresh()
  }, [])
  const toggleGrid = () => {
    setGrid(!grid)
    save('library.grid', !grid)
  }

  return (
    <div className="page">
      <ScreenHeader
        title="Your Library"
        actions={
          <>
            <IconButton icon={grid ? 'view_list' : 'grid_view'} label={grid ? 'Show as list' : 'Show as grid'} onClick={toggleGrid} />
            <IconButton icon="add" label="New playlist" onClick={newPlaylist.start} />
            <IconButton icon="settings" label="Settings" onClick={nav.openSettings} />
          </>
        }
      />
      <div style={{ padding: '0 16px 12px' }}>{chips}</div>
      {grid ? (
        <div className="grid">
          {entries.map((e) => (
            <div key={e.key} className="card" onClick={e.open} data-testid="library-entry">
              {e.art(192, true)}
              <div className="name title-small ellipsis" style={{ marginTop: 10 }}>{e.title}</div>
              <div className="body-small muted ellipsis">{e.subtitle}</div>
            </div>
          ))}
        </div>
      ) : (
        <div style={{ padding: '0 8px' }}>
          {entries.map((e) => {
            const s = state(e)
            return (
              <div key={e.key} className="list-row" onClick={e.open} data-testid="library-entry" style={{ padding: '8px' }}>
                {e.art(56)}
                <div style={{ minWidth: 0, flex: 1 }}>
                  <div className="title-medium ellipsis" style={{ color: s.playing ? 'var(--primary)' : undefined }}>{e.title}</div>
                  <div className="body-small muted ellipsis">{e.subtitle}</div>
                </div>
                {s.playing && <PlayingBars playing={s.isPlaying} />}
              </div>
            )
          })}
        </div>
      )}
      {empty && <div className="muted" style={{ padding: 16 }}>{hint}</div>}
      {newPlaylist.dialog}
    </div>
  )
}

function Art({ coverArt, size, fill }: { coverArt?: string; size: number; fill?: boolean }) {
  return (
    <Cover
      coverArt={coverArt}
      size={300}
      corner={4}
      style={fill ? { width: '100%', aspectRatio: '1' } : { width: size, height: size, flexShrink: 0 }}
    />
  )
}
