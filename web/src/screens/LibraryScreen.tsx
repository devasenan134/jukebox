import { useEffect, useState, type ReactNode } from 'react'
import type { Playlist } from '../api/types'
import { MIX_AUTHOR } from '../api/types'
import { subsonic } from '../api/subsonic'
import { social } from '../api/social'
import { useMixes, useMyPlaylists } from '../state/library'
import { useLikes } from '../state/likes'
import { useSession } from '../state/session'
import { load, save } from '../state/storage'
import { Cover, likeCount, ScreenHeader, songCount } from '../ui/components'
import { IconButton, NameDialog, toast } from '../ui/kit'
import { LikedTile, madeForName, MixCover } from '../ui/mixes'
import { useNav } from '../ui/nav'

/** The sections of Your Library, in the order of the chips. */
const FILTERS = ['All', 'Playlists', 'Albums', 'My Playlists'] as const
type Filter = (typeof FILTERS)[number]

/** One thing in Your Library, shown as a row or a tile. art draws its picture at a size (or filling a tile). */
interface Entry {
  key: string
  title: string
  subtitle: string
  open: () => void
  round?: boolean
  art: (size: number, fill?: boolean) => ReactNode
}

/** Your Library: liked songs, saved mixes, liked albums, liked playlists and playlists you made (ui/library/LibraryScreen.kt). */
export function LibraryScreen() {
  const nav = useNav()
  const username = useSession((s) => s.credentials?.username)
  const likedSongs = useLikes((s) => s.songs)
  const likedAlbums = useLikes((s) => s.albums)
  const likedPlaylists = useLikes((s) => s.playlists)
  const savedMixes = useMixes((s) => s.followed)
  const [own, setOwn] = useState<Playlist[]>([])
  const [ownLikes, setOwnLikes] = useState<Record<string, number>>({})
  const [filter, setFilter] = useState<Filter>(() => load<Filter>('library.filter', 'All'))
  const [grid, setGrid] = useState(() => load('library.grid', false))
  const [creating, setCreating] = useState(false)
  const [reload, setReload] = useState(0)

  useEffect(() => {
    useLikes.getState().refresh()
    useMixes.getState().refresh()
  }, [])
  useEffect(() => {
    let alive = true
    subsonic.playlists()
      .then((all) => {
        if (!alive) return
        const mine = all.filter((p) => p.owner === username)
        setOwn(mine)
        // How many friends liked each playlist you made (shown only when someone has).
        if (mine.length) social.playlistLikeCounts(mine.map((p) => p.id)).then((c) => alive && setOwnLikes(c)).catch(() => {})
      })
      .catch(() => {})
    return () => {
      alive = false
    }
  }, [username, reload])

  const choose = (f: Filter) => {
    setFilter(f)
    save('library.filter', f)
  }
  const toggleGrid = () => {
    setGrid(!grid)
    save('library.grid', !grid)
  }
  const create = async (name: string) => {
    setCreating(false)
    try {
      const p = await subsonic.createPlaylist(name)
      setReload((r) => r + 1)
      useMyPlaylists.getState().refresh()
      nav.openPlaylist(p.id)
    } catch (e) {
      toast((e as Error).message || "Couldn't create it")
    }
  }

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
      key: 'liked', title: 'Liked songs', subtitle: `Playlist · ${songCount(likedSongs.length)}`, open: nav.openLikedSongs,
      art: (size, fill) => <LikedTile size={size} fill={fill} />,
    })
  }
  if (showSaved) {
    for (const mix of savedMixes) {
      const subtitle = [
        mix.personal && madeFor ? `Made for ${madeFor}` : mix.endless ? 'Station' : 'Mix',
        `by ${MIX_AUTHOR}`,
        mix.endless || !mix.songCount ? null : songCount(mix.songCount),
      ].filter(Boolean).join(' · ')
      entries.push({ key: `mix-${mix.id}`, title: mix.title, subtitle, open: () => nav.openMix(mix.id), round: mix.round, art: (size, fill) => <MixCover mix={mix} size={size} fill={fill} /> })
    }
  }
  for (const p of playlists) {
    if (mine(p) ? !showMine : !showSaved) continue
    const likes = mine(p) && ownLikes[p.id] > 0 ? likeCount(ownLikes[p.id]) : null
    entries.push({
      key: `playlist-${p.id}`, title: p.name,
      subtitle: ['Playlist', p.owner && `by ${p.owner}`, songCount(p.songCount), likes].filter(Boolean).join(' · '),
      open: () => nav.openPlaylist(p.id),
      art: (size, fill) => <Art coverArt={p.coverArt} size={size} fill={fill} />,
    })
  }
  if (filter === 'All' || filter === 'Albums') {
    for (const a of likedAlbums) {
      entries.push({
        key: `album-${a.id}`, title: a.name, subtitle: ['Album', a.artist].filter(Boolean).join(' · '), open: () => nav.openAlbum(a.id),
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

  return (
    <div className="page">
      <ScreenHeader
        title="Your Library"
        actions={
          <>
            <IconButton icon={grid ? 'view_list' : 'grid_view'} label={grid ? 'Show as list' : 'Show as grid'} onClick={toggleGrid} />
            <IconButton icon="add" label="New playlist" onClick={() => setCreating(true)} />
          </>
        }
      />
      <div className="chips" style={{ display: 'flex', gap: 8, padding: '0 16px 8px', overflowX: 'auto' }}>
        {FILTERS.map((f) => (
          <button key={f} className={`chip${filter === f ? ' selected' : ''}`} onClick={() => choose(f)}>{f}</button>
        ))}
      </div>
      {grid ? (
        <div className="grid" style={{ padding: 8 }}>
          {entries.map((e) => (
            <div key={e.key} className="card" onClick={e.open} data-testid="library-entry">
              {e.art(192, true)}
              <div className="name title-small ellipsis" style={{ marginTop: 6 }}>{e.title}</div>
              <div className="body-small muted ellipsis">{e.subtitle}</div>
            </div>
          ))}
        </div>
      ) : (
        <div style={{ padding: '4px 0' }}>
          {entries.map((e) => (
            <div key={e.key} className="list-row" onClick={e.open} data-testid="library-entry">
              {e.art(56)}
              <div style={{ minWidth: 0, flex: 1 }}>
                <div className="title-medium ellipsis">{e.title}</div>
                <div className="body-medium muted ellipsis">{e.subtitle}</div>
              </div>
            </div>
          ))}
        </div>
      )}
      {empty && <div className="muted" style={{ padding: 16 }}>{hint}</div>}
      {creating && <NameDialog title="New playlist" confirm="Create" onClose={() => setCreating(false)} onConfirm={create} />}
    </div>
  )
}

function Art({ coverArt, size, fill }: { coverArt?: string; size: number; fill?: boolean }) {
  return (
    <Cover
      coverArt={coverArt}
      size={300}
      corner={6}
      style={fill ? { width: '100%', aspectRatio: '1' } : { width: size, height: size, flexShrink: 0 }}
    />
  )
}
