import { useEffect, useRef, useState } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import type { Album } from '../api/types'
import { catalog, albumSummaryToAlbum } from '../api/catalog'
import { AlbumCard, ScreenHeader } from '../ui/components'
import { ErrorBox, Loading } from '../ui/kit'
import { useNav } from '../ui/nav'

const PAGE = 60
const SORTS = [
  { label: 'A to Z', sort: 'name' as const },
  { label: 'Newest', sort: 'newest' as const },
  { label: 'Recently played', sort: 'recent' as const },
  { label: 'Most played', sort: 'frequent' as const },
  { label: 'Liked', sort: 'starred' as const },
] as const

/** Every album/film, loaded a page at a time with native Jukebox API v2. */
export function AlbumsScreen() {
  const nav = useNav()
  const [sort, setSort] = useState(0)
  const end = useRef<HTMLDivElement>(null)

  const q = useInfiniteQuery({
    queryKey: ['albums-v2', sort],
    queryFn: async ({ pageParam }) => {
      const res = await catalog.albums({ sort: SORTS[sort].sort, limit: PAGE, offset: pageParam })
      return res.albums.map(albumSummaryToAlbum)
    },
    initialPageParam: 0,
    getNextPageParam: (last: Album[], pages) => (last.length < PAGE ? undefined : pages.length * PAGE),
  })

  const albums = q.data?.pages.flat() ?? []
  const done = !q.hasNextPage && !q.isPending
  const error = q.error?.message

  useEffect(() => {
    const el = end.current
    if (!el || done) return
    const io = new IntersectionObserver((e) => e[0].isIntersecting && !q.isFetching && void q.fetchNextPage())
    io.observe(el)
    return () => io.disconnect()
  })

  return (
    <div className="page">
      <ScreenHeader title="Albums" onBack={nav.back} />
      <div style={{ display: 'flex', gap: 8, padding: '4px 16px 12px', flexWrap: 'wrap' }}>
        {SORTS.map((s, i) => (
          <button key={s.label} className={`chip${i === sort ? ' selected' : ''}`} onClick={() => setSort(i)}>
            {s.label}
          </button>
        ))}
      </div>
      {error && <ErrorBox message={error} onRetry={() => void q.refetch()} />}
      <div className="grid">
        {albums.map((a) => (
          <AlbumCard key={a.id} album={a} onClick={() => nav.openAlbum(a.id)} />
        ))}
      </div>
      {!done && <div ref={end}><Loading /></div>}
    </div>
  )
}
