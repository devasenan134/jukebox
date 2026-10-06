import { useEffect, useRef, useState } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import type { Artist } from '../api/types'
import { subsonic } from '../api/subsonic'
import { PlaylistCard, ScreenHeader, useLoad } from '../ui/components'
import { CardsSkeleton } from '../ui/collection'
import { ErrorBox, Icon, Loading } from '../ui/kit'
import { useNav } from '../ui/nav'
import { PersonCard } from './SearchScreen'

// Browsing everyone who sings and every playlist on the server (ui/library/SingersScreen.kt, PlaylistsScreen.kt).

const PAGE = 200

/** Every singer, A to Z, a page at a time as you scroll, with a filter box for the ones loaded. */
export function SingersScreen() {
  const nav = useNav()
  const [filter, setFilter] = useState('')
  const end = useRef<HTMLDivElement>(null)
  const q = useInfiniteQuery({
    queryKey: ['singers'],
    queryFn: ({ pageParam }) => subsonic.singers(pageParam, PAGE),
    initialPageParam: 0,
    getNextPageParam: (last: Artist[], pages) => (last.length < PAGE ? undefined : pages.length * PAGE),
  })
  const f = filter.trim().toLowerCase()
  const singers = (q.data?.pages.flat() ?? []).filter((a) => !f || a.name.toLowerCase().includes(f))
  const done = !q.hasNextPage && !q.isPending
  useEffect(() => {
    const el = end.current
    if (!el || done) return
    const io = new IntersectionObserver((e) => e[0].isIntersecting && !q.isFetching && void q.fetchNextPage())
    io.observe(el)
    return () => io.disconnect()
  })
  return (
    <div className="page">
      <ScreenHeader title="Singers" onBack={nav.back} />
      <div style={{ padding: '0 16px 12px' }}>
        <div className="search-box">
          <Icon name="filter_list" />
          <input value={filter} placeholder="Find a singer" onChange={(e) => setFilter(e.target.value)} aria-label="Find a singer" />
        </div>
      </div>
      {q.error && <ErrorBox message={q.error.message} onRetry={() => void q.refetch()} />}
      {q.isPending ? <CardsSkeleton rows={3} /> : (
        <div className="grid">
          {singers.map((a) => <PersonCard key={a.id} artist={a} className="" onClick={() => nav.openSinger(a)} />)}
        </div>
      )}
      {!done && <div ref={end}><Loading /></div>}
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
