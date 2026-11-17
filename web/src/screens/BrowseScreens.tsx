import { useMemo, useState } from 'react'
import { catalog } from '../api/catalog'
import { subsonic } from '../api/subsonic'
import { PlaylistCard, ScreenHeader, songCount, useLoad } from '../ui/components'
import { CardsSkeleton, PersonAvatar } from '../ui/collection'
import { ErrorBox, Icon } from '../ui/kit'
import { useNav } from '../ui/nav'

// Browsing artists and playlists on the server.

/** Artists (singers and lyricists), the ones on the most songs first, with a filter box. Uses native /api/v2/people. */
export function SingersScreen() {
  const nav = useNav()
  const [filter, setFilter] = useState('')
  const data = useLoad(['artists-panel-v2'], async () => {
    const res = await catalog.people({ role: 'artist', limit: 10000 })
    return res.people.map((p) => ({
      id: p.id,
      name: p.name,
      roles: p.roles,
      songCount: p.songCount,
      coverArt: p.coverArt,
    }))
  })

  const artists = useMemo(
    () =>
      (data.data ?? [])
        .slice()
        .sort((a, b) => (b.songCount ?? 0) - (a.songCount ?? 0) || a.name.localeCompare(b.name))
        .filter((a) => a.name.toLowerCase().includes(filter.trim().toLowerCase())),
    [data.data, filter],
  )

  if (data.loading && !data.data) return <div className="page"><CardsSkeleton rows={3} /></div>
  if (data.error) return <ErrorBox message={data.error} onRetry={data.retry} />

  return (
    <div className="page">
      <ScreenHeader title="Artists" onBack={nav.back} />
      <div style={{ padding: '0 16px 12px' }}>
        <div className="search-box">
          <Icon name="filter_list" />
          <input value={filter} placeholder="Find an artist" onChange={(e) => setFilter(e.target.value)} aria-label="Find an artist" />
        </div>
      </div>
      <div className="list" style={{ padding: '0 16px' }}>
        {artists.map((a) => (
          <div
            key={a.id}
            className="list-row"
            onClick={() => nav.openSinger(a)}
            style={{ cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 14, padding: '8px 16px', borderRadius: 8 }}
          >
            <PersonAvatar name={a.name} size={52} coverArt={a.coverArt} />
            <div style={{ minWidth: 0, flex: 1 }}>
              <div className="body-large ellipsis" style={{ fontWeight: 600 }}>{a.name}</div>
              <div className="body-small muted">{songCount(a.songCount ?? 0)}</div>
            </div>
            <Icon name="chevron_right" size={20} className="muted" />
          </div>
        ))}
      </div>
    </div>
  )
}

/** Every playlist on the server you can see (yours, and the public ones), A to Z. */
export function PlaylistsScreen() {
  const nav = useNav()
  const data = useLoad(['playlists'], () => subsonic.playlists())
  const playlists = [...(data.data ?? [])].sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()))
  return (
    <div className="page">
      <ScreenHeader title="Playlists" onBack={nav.back} />
      {data.error && <ErrorBox message={data.error} onRetry={data.retry} />}
      {data.loading && !data.data ? <CardsSkeleton rows={2} /> : playlists.length === 0 ? (
        <div className="body-medium muted" style={{ padding: 16 }}>No playlists yet.</div>
      ) : (
        <div className="grid">
          {playlists.map((p) => <PlaylistCard key={p.id} playlist={p} className="" onClick={() => nav.openPlaylist(p.id)} />)}
        </div>
      )}
    </div>
  )
}
