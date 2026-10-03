import { useEffect, useRef, useState } from 'react'
import type { Album } from '../api/types'
import { subsonic } from '../api/subsonic'
import { AlbumCard, ScreenHeader } from '../ui/components'
import { ErrorBox, Loading } from '../ui/kit'
import { useNav } from '../ui/nav'

const PAGE = 60
const SORTS = [
  { label: 'A to Z', type: 'alphabeticalByName', extra: {} },
  { label: 'Newest', type: 'byYear', extra: { fromYear: 2100, toYear: 1900 } },
  { label: 'Oldest', type: 'byYear', extra: { fromYear: 1900, toYear: 2100 } },
  { label: 'Recently added', type: 'newest', extra: {} },
] as const

/** Every album, a page at a time as you scroll. */
export function AlbumsScreen() {
  const nav = useNav()
  const [sort, setSort] = useState(0)
  const [albums, setAlbums] = useState<Album[]>([])
  const [done, setDone] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const loading = useRef(false)
  const end = useRef<HTMLDivElement>(null)

  const more = async (reset = false) => {
    if (loading.current) return
    loading.current = true
    try {
      const s = SORTS[sort]
      const page = await subsonic.albumList(s.type, PAGE, reset ? 0 : albums.length, { ...s.extra })
      setAlbums((a) => (reset ? page : [...a, ...page]))
      setDone(page.length < PAGE)
    } catch (e) {
      setError((e as Error).message)
    } finally {
      loading.current = false
    }
  }

  useEffect(() => {
    setAlbums([])
    setDone(false)
    void more(true)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sort])

  useEffect(() => {
    const el = end.current
    if (!el || done) return
    const io = new IntersectionObserver((e) => e[0].isIntersecting && void more())
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
      {error && <ErrorBox message={error} onRetry={() => { setError(null); void more(true) }} />}
      <div className="grid">
        {albums.map((a) => (
          <AlbumCard key={a.id} album={a} onClick={() => nav.openAlbum(a.id)} />
        ))}
      </div>
      {!done && <div ref={end}><Loading /></div>}
    </div>
  )
}
