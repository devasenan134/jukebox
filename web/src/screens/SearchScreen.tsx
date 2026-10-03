import { useEffect, useState } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
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
  const [typed, setTyped] = useState(query.trim())
  useEffect(() => {
    const q = query.trim()
    setParams(q ? { q } : {}, { replace: true })
    const t = setTimeout(() => setTyped(q), 180)
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query])
  // Results stay cached: coming back to a search shows them at once; while typing, the last results stay up.
  const search = useQuery({
    queryKey: ['search', typed],
    queryFn: () => subsonic.search(typed),
    enabled: typed.length >= 2,
    placeholderData: keepPreviousData,
  })
  const result: SearchResult | null = query.trim().length >= 2 ? (search.data ?? null) : null
  const error = search.error?.message
  const busy = search.isFetching

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
