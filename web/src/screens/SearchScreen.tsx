import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import type { SearchResult } from '../api/types'
import { subsonic } from '../api/subsonic'
import * as player from '../player/player'
import { AlbumCard, SectionTitle, SongRow } from '../ui/components'
import { ErrorBox, Icon, IconButton, Loading } from '../ui/kit'
import { useNav } from '../ui/nav'
import { PersonRow } from './ArtistScreens'

/** Search songs, albums, music directors and singers. Spelling is forgiven by the server; the query is in the URL. */
export function SearchScreen() {
  const nav = useNav()
  const [params, setParams] = useSearchParams()
  const [query, setQuery] = useState(params.get('q') ?? '')
  const [result, setResult] = useState<SearchResult | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    const q = query.trim()
    setParams(q ? { q } : {}, { replace: true })
    if (q.length < 2) return setResult(null)
    let alive = true
    setBusy(true)
    const t = setTimeout(() => {
      subsonic.search(q)
        .then((r) => alive && (setResult(r), setError(null)))
        .catch((e) => alive && setError((e as Error).message))
        .finally(() => alive && setBusy(false))
    }, 250)
    return () => {
      alive = false
      clearTimeout(t)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query])

  const songs = result?.song ?? []
  const people = result?.artist ?? []
  const albums = result?.album ?? []
  return (
    <div className="page">
      <div style={{ padding: '16px 16px 8px', position: 'sticky', top: 0, background: 'var(--background)', zIndex: 2 }}>
        <div className="search-box">
          <Icon name="search" />
          <input autoFocus value={query} placeholder="Songs, albums, music directors, singers" onChange={(e) => setQuery(e.target.value)} />
          {query && <IconButton icon="close" label="Clear" onClick={() => setQuery('')} />}
        </div>
      </div>
      {error && <ErrorBox message={error} />}
      {busy && !result && <Loading />}
      {result && !songs.length && !people.length && !albums.length && <div className="center-box muted">Nothing found for “{query.trim()}”</div>}
      {songs.length > 0 && (
        <>
          <SectionTitle>Songs</SectionTitle>
          {songs.slice(0, 20).map((s, i) => (
            <SongRow key={s.id} song={s} showCover onOpenAlbum={nav.openAlbum} onClick={() => player.play(songs, i, false, 'search')} />
          ))}
        </>
      )}
      {people.length > 0 && (
        <>
          <SectionTitle>People</SectionTitle>
          {people.slice(0, 8).map((a) => (
            <PersonRow key={a.id} artist={a} onClick={() => nav.openArtist(a.id)} />
          ))}
        </>
      )}
      {albums.length > 0 && (
        <>
          <SectionTitle>Albums</SectionTitle>
          <div className="grid">
            {albums.map((a) => (
              <AlbumCard key={a.id} album={a} onClick={() => nav.openAlbum(a.id)} />
            ))}
          </div>
        </>
      )}
      {!result && !busy && !query && (
        <div className="center-box muted" style={{ padding: 32, textAlign: 'center' }}>
          Try a song, a film, or someone like Ilaiyaraaja or Chinmayi. Spelling doesn't have to be exact.
        </div>
      )}
    </div>
  )
}
