import { useEffect, useState, type ReactNode } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import type { Album, Artist, SearchResult, Song } from '../api/types'
import { isComposer } from '../api/types'
import { subsonic } from '../api/subsonic'
import * as player from '../player/player'
import { AlbumCard, CardPlay, Cover, playAlbum, SectionTitle, SongRow, useLoad } from '../ui/components'
import { PersonAvatar } from '../ui/collection'
import { ErrorBox, Icon, IconButton, Spinner } from '../ui/kit'
import { useNav, type Nav } from '../ui/nav'

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
  const top = result ? topResult(query.trim(), people, albums, songs) : null
  return (
    <div className="page">
      <div style={{ padding: '16px 16px 8px', position: 'sticky', top: 0, background: 'color-mix(in srgb, var(--background) 92%, transparent)', backdropFilter: 'blur(12px)', zIndex: 4 }}>
        <div className="search-box">
          <Icon name="search" />
          <input autoFocus value={query} placeholder="What do you want to listen to?" onChange={(e) => setQuery(e.target.value)} aria-label="Search" />
          {busy && <Spinner size={18} />}
          {query && <IconButton icon="close" label="Clear" onClick={() => setQuery('')} />}
        </div>
      </div>
      {error && <ErrorBox message={error} />}
      {!query.trim() && <Browse nav={nav} />}
      {result && !songs.length && !people.length && !albums.length && <div className="center-box muted">Nothing found for “{query.trim()}”</div>}
      {(top || songs.length > 0) && (
        <div className="search-top" style={{ display: 'grid', gap: 8, padding: '0 8px', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 380px), 1fr))' }}>
          {top && (
            <div>
              <h2 className="section-title" style={{ margin: '20px 8px 10px' }}>Top result</h2>
              <TopCard top={top} nav={nav} />
            </div>
          )}
          {songs.length > 0 && (
            <div style={{ minWidth: 0 }}>
              <h2 className="section-title" style={{ margin: '20px 8px 10px' }}>Songs</h2>
              {songs.slice(0, 5).map((s, i) => (
                <SongRow key={s.id} song={s} showCover onOpenAlbum={nav.openAlbum} onClick={() => player.play(songs, i, false, 'search')} />
              ))}
            </div>
          )}
        </div>
      )}
      {people.length > 0 && (
        <>
          <SectionTitle>People</SectionTitle>
          <div className="row-scroll">
            {people.slice(0, 12).map((a) => (
              <PersonCard key={a.id} artist={a} onClick={() => (isComposer(a) ? nav.openArtist(a.id) : nav.openSinger(a))} />
            ))}
          </div>
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
      {songs.length > 5 && (
        <>
          <SectionTitle>More songs</SectionTitle>
          <div style={{ padding: '0 8px' }}>
            {songs.slice(5, 30).map((s, i) => (
              <SongRow key={s.id} song={s} showCover onOpenAlbum={nav.openAlbum} onClick={() => player.play(songs, i + 5, false, 'search')} />
            ))}
          </div>
        </>
      )}
    </div>
  )
}

type Top = { kind: 'person'; artist: Artist } | { kind: 'album'; album: Album } | { kind: 'song'; song: Song; all: Song[] }

const norm = (s: string) => s.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, '')

/** What the search most likely meant: a name that starts with the words typed, people first, then albums, then songs. */
function topResult(q: string, people: Artist[], albums: Album[], songs: Song[]): Top | null {
  const n = norm(q)
  const person = people.find((p) => norm(p.name).startsWith(n))
  if (person) return { kind: 'person', artist: person }
  const album = albums.find((a) => norm(a.name).startsWith(n))
  if (album) return { kind: 'album', album }
  if (songs[0]) return { kind: 'song', song: songs[0], all: songs }
  if (albums[0]) return { kind: 'album', album: albums[0] }
  return people[0] ? { kind: 'person', artist: people[0] } : null
}

function TopCard({ top, nav }: { top: Top; nav: Nav }) {
  const box = (art: ReactNode, title: string, sub: string, open: () => void, play?: () => void) => (
    <div className="card" onClick={open} style={{ background: 'var(--surface-container-low)', padding: 20, minHeight: 220 }}>
      <div style={{ width: 96 }}>{art}</div>
      <div className="headline-medium ellipsis" style={{ marginTop: 18 }}>{title}</div>
      <div className="body-medium muted ellipsis" style={{ marginTop: 4 }}>{sub}</div>
      {play && <CardPlay label={`Play ${title}`} play={play} />}
    </div>
  )
  if (top.kind === 'person') {
    const a = top.artist
    return box(<PersonAvatar name={a.name} size={96} />, a.name, isComposer(a) ? 'Music director' : 'Singer', () => (isComposer(a) ? nav.openArtist(a.id) : nav.openSinger(a)))
  }
  if (top.kind === 'album') {
    const a = top.album
    return box(
      <Cover coverArt={a.coverArt} size={300} style={{ width: 96, height: 96, boxShadow: '0 8px 24px rgba(0,0,0,0.5)' }} />,
      a.name, ['Album', a.artist].filter(Boolean).join(' • '), () => nav.openAlbum(a.id), () => void playAlbum(a.id),
    )
  }
  const s = top.song
  return box(
    <Cover coverArt={s.coverArt} size={300} style={{ width: 96, height: 96, boxShadow: '0 8px 24px rgba(0,0,0,0.5)' }} />,
    s.title, ['Song', s.artist].filter(Boolean).join(' • '), () => player.play(top.all, 0, false, 'search'), () => player.play(top.all, 0, false, 'search'),
  )
}

/** A person as a round card (search, a list of music directors). */
export function PersonCard({ artist, onClick, className = 'tile' }: { artist: Artist; onClick: () => void; className?: string }) {
  return (
    <div className={`card ${className}`} onClick={onClick}>
      <PersonAvatar name={artist.name} fill />
      <div className="name title-small ellipsis" style={{ marginTop: 10 }}>{artist.name}</div>
      <div className="body-small muted">{isComposer(artist) ? 'Music director' : 'Singer'}</div>
    </div>
  )
}

const TILES = [
  { label: 'Albums', color: 'hsl(330 70% 42%)', go: (n: Nav) => n.openAlbums() },
  { label: 'Music directors', color: 'hsl(205 70% 38%)', go: (n: Nav) => n.openArtists() },
  { label: 'Liked songs', color: 'hsl(255 50% 46%)', go: (n: Nav) => n.openLikedSongs() },
  { label: 'Your Library', color: 'hsl(150 55% 30%)', go: (n: Nav) => n.openLibrary() },
]

/** Before you type: places to browse, each with a tilted album cover. */
function Browse({ nav }: { nav: Nav }) {
  const covers = useLoad(['albums', 'browse-covers'], () => subsonic.albumList('random', TILES.length), Infinity).data ?? []
  return (
    <>
      <SectionTitle>Browse all</SectionTitle>
      <div className="browse-grid">
        {TILES.map((t, i) => (
          <div key={t.label} className="browse-tile" style={{ background: t.color }} onClick={() => t.go(nav)}>
            {covers[i] && <Cover coverArt={covers[i].coverArt} size={300} />}
            <span style={{ position: 'relative', display: 'block', maxWidth: '70%' }}>{t.label}</span>
          </div>
        ))}
      </div>
    </>
  )
}
