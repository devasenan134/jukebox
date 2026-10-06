import { useEffect, useState, type ReactNode } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import type { Album, Artist, SearchResult, Song } from '../api/types'
import { isComposer, refToSong } from '../api/types'
import { subsonic } from '../api/subsonic'
import * as player from '../player/player'
import { AlbumCard, CardPlay, Cover, playAlbum, SectionTitle, SongRow, useLoad } from '../ui/components'
import { PersonAvatar } from '../ui/collection'
import { ErrorBox, Icon, IconButton, Spinner } from '../ui/kit'
import { useNav, type Nav } from '../ui/nav'
import { useSearchHistory } from '../state/history'
import { CatalogResults } from './RequestsScreen'

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
  // A search counts for the history once you use it: press Enter, or open a result.
  const history = useSearchHistory()
  const used = () => history.add(query)
  const q = query.trim().toLowerCase()
  const past = q ? history.queries.filter((h) => h.toLowerCase().includes(q) && h.toLowerCase() !== q).slice(0, 3) : []
  return (
    <div className="page">
      <div style={{ padding: '16px 16px 8px', position: 'sticky', top: 0, background: 'color-mix(in srgb, var(--background) 92%, transparent)', backdropFilter: 'blur(12px)', zIndex: 4 }}>
        <div className="search-box">
          <Icon name="search" />
          <input
            autoFocus value={query} placeholder="What do you want to listen to?" aria-label="Search"
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && used()}
          />
          {busy && <Spinner size={18} />}
          {query && <IconButton icon="close" label="Clear" onClick={() => setQuery('')} />}
        </div>
        {past.length > 0 && (
          <div className="chips" style={{ marginTop: 8 }}>
            {past.map((p) => (
              <button key={p} className="chip" onClick={() => { setQuery(p); history.add(p) }}><Icon name="history" size={16} />{p}</button>
            ))}
          </div>
        )}
      </div>
      {error && <ErrorBox message={error} />}
      {!query.trim() && <Browse nav={nav} onPick={setQuery} />}
      {result && !songs.length && !people.length && !albums.length && <div className="center-box muted">Nothing found for “{query.trim()}”</div>}
      {(top || songs.length > 0) && (
        <div className="search-top" style={{ display: 'grid', gap: 8, padding: '0 8px', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 380px), 1fr))' }}>
          {top && (
            <div>
              <h2 className="section-title" style={{ margin: '20px 8px 10px' }}>Top result</h2>
              <TopCard top={top} nav={nav} onPicked={used} />
            </div>
          )}
          {songs.length > 0 && (
            <div style={{ minWidth: 0 }}>
              <h2 className="section-title" style={{ margin: '20px 8px 10px' }}>Songs</h2>
              {songs.slice(0, 5).map((s, i) => (
                <SongRow key={s.id} song={s} showCover onOpenAlbum={nav.openAlbum} onClick={() => { used(); history.pickedSong(s); player.play(songs, i, false, 'search') }} />
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
              <PersonCard key={a.id} artist={a} onClick={() => { used(); history.pickedArtist(a); if (isComposer(a)) nav.openArtist(a.id); else nav.openSinger(a) }} />
            ))}
          </div>
        </>
      )}
      {albums.length > 0 && (
        <>
          <SectionTitle>Albums</SectionTitle>
          <div className="grid">
            {albums.map((a) => (
              <AlbumCard key={a.id} album={a} onClick={() => { used(); history.pickedAlbum(a); nav.openAlbum(a.id) }} />
            ))}
          </div>
        </>
      )}
      {songs.length > 5 && (
        <>
          <SectionTitle>More songs</SectionTitle>
          <div style={{ padding: '0 8px' }}>
            {songs.slice(5, 30).map((s, i) => (
              <SongRow key={s.id} song={s} showCover onOpenAlbum={nav.openAlbum} onClick={() => { used(); history.pickedSong(s); player.play(songs, i + 5, false, 'search') }} />
            ))}
          </div>
        </>
      )}
      {typed.length >= 3 && query.trim() && <CatalogResults query={typed} />}
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

function TopCard({ top, nav, onPicked }: { top: Top; nav: Nav; onPicked: () => void }) {
  const box = (art: ReactNode, title: string, sub: string, open: () => void, play?: () => void) => (
    <div className="card" onClick={() => { onPicked(); open() }} style={{ background: 'var(--surface-container-low)', padding: 20, minHeight: 220 }}>
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
  { label: 'Singers', color: 'hsl(20 75% 42%)', go: (n: Nav) => n.openSingers() },
  { label: 'Playlists', color: 'hsl(180 55% 30%)', go: (n: Nav) => n.openPlaylists() },
  { label: 'Liked songs', color: 'hsl(255 50% 46%)', go: (n: Nav) => n.openLikedSongs() },
  { label: 'Your Library', color: 'hsl(150 55% 30%)', go: (n: Nav) => n.openLibrary() },
]

/** Before you type: recent searches, places to browse (each with a tilted album cover), your requests, recent picks. */
function Browse({ nav, onPick }: { nav: Nav; onPick: (q: string) => void }) {
  const covers = useLoad(['albums', 'browse-covers'], () => subsonic.albumList('random', TILES.length), Infinity).data ?? []
  const history = useSearchHistory()
  return (
    <>
      {history.queries.length > 0 && (
        <>
          <SectionTitle action={{ label: 'Clear', onClick: history.clear }}>Recent searches</SectionTitle>
          <div style={{ padding: '0 8px' }}>
            {history.queries.slice(0, 8).map((q) => (
              <div key={q} className="list-row" style={{ display: 'flex', alignItems: 'center', gap: 14, padding: '0 4px 0 12px', minHeight: 44, cursor: 'pointer' }} onClick={() => onPick(q)}>
                <Icon name="history" className="muted" />
                <span className="body-large ellipsis" style={{ flex: 1 }}>{q}</span>
                <IconButton icon="close" label="Remove from history" size={18} onClick={(e) => { e.stopPropagation(); history.remove(q) }} />
              </div>
            ))}
          </div>
        </>
      )}
      <SectionTitle>Browse all</SectionTitle>
      <div className="browse-grid">
        {TILES.map((t, i) => (
          <div key={t.label} className="browse-tile" style={{ background: t.color }} onClick={() => t.go(nav)}>
            {covers[i] && <Cover coverArt={covers[i].coverArt} size={300} />}
            <span style={{ position: 'relative', display: 'block', maxWidth: '70%' }}>{t.label}</span>
          </div>
        ))}
      </div>
      <div className="list-row" style={{ display: 'flex', alignItems: 'center', gap: 16, padding: '14px 16px', margin: '8px 8px 0', cursor: 'pointer' }} onClick={nav.openRequests}>
        <Icon name="playlist_add" style={{ color: 'var(--primary)' }} />
        <div>
          <div className="body-large">Your requests</div>
          <div className="body-small muted">Songs and movies you asked to be added</div>
        </div>
      </div>
      {history.songs.length > 0 && (
        <>
          <SectionTitle>Your recent songs</SectionTitle>
          <div style={{ padding: '0 8px' }}>
            {history.songs.slice(0, 5).map(refToSong).map((s, i, all) => (
              <SongRow key={s.id} song={s} showCover onOpenAlbum={nav.openAlbum} onClick={() => player.play(all, i, false, 'search')} />
            ))}
          </div>
        </>
      )}
      {history.albums.length > 0 && (
        <>
          <SectionTitle>Your recent albums</SectionTitle>
          <div className="row-scroll">
            {history.albums.map((a) => <AlbumCard key={a.id} album={a} className="tile" onClick={() => nav.openAlbum(a.id)} />)}
          </div>
        </>
      )}
      {history.artists.length > 0 && (
        <>
          <SectionTitle>Your recent artists</SectionTitle>
          <div className="row-scroll">
            {history.artists.map((a) => <PersonCard key={a.id} artist={a} onClick={() => (isComposer(a) ? nav.openArtist(a.id) : nav.openSinger(a))} />)}
          </div>
        </>
      )}
    </>
  )
}
