import { useEffect, useState, type ReactNode } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import type { Album, Artist, Song } from '../api/types'
import { isComposer, refToSong } from '../api/types'
import { catalog, type LyricsMatch } from '../api/catalog'
import { subsonic } from '../api/subsonic'
import * as player from '../player/player'
import { AlbumCard, CardPlay, Cover, formatDuration, playAlbum, SectionTitle, SongRow, useLoad } from '../ui/components'
import { PersonAvatar } from '../ui/collection'
import { ErrorBox, Icon, IconButton, Spinner } from '../ui/kit'
import { useNav, type Nav } from '../ui/nav'
import { useSearchHistory } from '../state/history'
import { CatalogResults } from './RequestsScreen'

interface CombinedSearchResults {
  songs: Song[]
  people: Artist[]
  albums: Album[]
  lyrics: LyricsMatch[]
}

/** Search songs, albums, music directors, singers and lyrics lines. Unified native search. */
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

  const search = useQuery<CombinedSearchResults>({
    queryKey: ['search-v2', typed],
    queryFn: async () => {
      try {
        const u = await catalog.search(typed)
        const songs: Song[] = u.songs.map((s) => ({
          id: s.id,
          title: s.title,
          album: s.albumTitle,
          albumId: s.albumId,
          duration: Math.max(1, Math.round(s.durationMs / 1000)),
          coverArt: s.coverArt,
          artist: s.singers.map((a) => a.name).join(', ') || s.composers.map((c) => c.name).join(', '),
          artists: s.singers.map((a) => ({ id: a.id, name: a.name })),
        }))
        const albums: Album[] = u.movies.map((m) => ({
          id: m.id,
          name: m.title,
          year: m.year,
          coverArt: m.coverArt,
          artist: m.composers.map((c) => c.name).join(', '),
          artistId: m.composers[0]?.id,
          songCount: m.songCount,
          duration: 0,
        }))
        const people: Artist[] = u.people.map((p) => ({
          id: p.id,
          name: p.name,
          roles: p.roles,
          coverArt: p.coverArt,
          albumCount: p.movieCount,
          songCount: p.songCount,
        }))
        return { songs, people, albums, lyrics: u.lyrics }
      } catch {
        // Fallback to Subsonic search3
        const r = await subsonic.search(typed)
        return { songs: r.song, people: r.artist, albums: r.album, lyrics: [] }
      }
    },
    enabled: typed.length >= 2,
    placeholderData: keepPreviousData,
  })

  const result: CombinedSearchResults | null = query.trim().length >= 2 ? (search.data ?? null) : null
  const error = search.error?.message
  const busy = search.isFetching

  const songs = result?.songs ?? []
  const albums = result?.albums ?? []
  const lyrics = result?.lyrics ?? []
  const top = result ? topResult(query.trim(), albums, songs) : null

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
      {result && !songs.length && !albums.length && !lyrics.length && <div className="center-box muted">Nothing found for “{query.trim()}”</div>}
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
      {lyrics.length > 0 && (
        <>
          <SectionTitle>Lyrics matches</SectionTitle>
          <div className="lyrics-results" style={{ padding: '0 16px', display: 'flex', flexDirection: 'column', gap: 8 }}>
            {lyrics.map((m) => {
              const matchedSong: Song = {
                id: m.recordingId,
                title: m.songTitle,
                album: m.albumTitle,
                albumId: m.albumId,
                duration: 0,
                coverArt: m.coverArt,
              }
              return (
                <div
                  key={`${m.recordingId}-${m.startMs}`}
                  className="list-row"
                  style={{
                    cursor: 'pointer',
                    display: 'flex',
                    alignItems: 'center',
                    gap: 14,
                    padding: '10px 14px',
                    borderRadius: 10,
                    background: 'var(--surface-container-low)',
                  }}
                  onClick={() => {
                    used()
                    player.play([matchedSong], 0, false, 'search:lyrics')
                    if (m.startMs > 0) {
                      setTimeout(() => player.seekTo(m.startMs), 200)
                    }
                  }}
                >
                  <Cover coverArt={m.coverArt} size={100} style={{ width: 44, height: 44, flexShrink: 0, borderRadius: 6 }} />
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div className="body-medium" style={{ fontWeight: 600, color: 'var(--primary)' }}>
                      “{m.matchedLine}”
                    </div>
                    <div className="body-small muted ellipsis" style={{ marginTop: 2 }}>
                      {m.songTitle} • {m.albumTitle}
                    </div>
                  </div>
                  {m.startMs > 0 && (
                    <span className="chip" style={{ fontSize: 11, padding: '2px 8px', height: 22 }}>
                      {formatDuration(Math.round(m.startMs / 1000))}
                    </span>
                  )}
                  <Icon name="play_arrow" size={20} />
                </div>
              )
            })}
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

type Top = { kind: 'album'; album: Album } | { kind: 'song'; song: Song; all: Song[] }

const norm = (s: string) => s.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, '')

/** What the search most likely meant: either the song or the strongly matched album (movie) at the top. Never people. */
function topResult(q: string, albums: Album[], songs: Song[]): Top | null {
  const n = norm(q)
  if (!n) return null
  const exactAlbum = albums.find((a) => norm(a.name) === n)
  const exactSong = songs.find((s) => norm(s.title) === n)
  if (exactAlbum && !exactSong) return { kind: 'album', album: exactAlbum }
  if (exactSong && !exactAlbum) return { kind: 'song', song: exactSong, all: songs }

  const startsAlbum = albums.find((a) => norm(a.name).startsWith(n))
  const startsSong = songs.find((s) => norm(s.title).startsWith(n))
  if (startsAlbum && (!startsSong || norm(startsAlbum.name).length <= norm(startsSong.title).length)) {
    return { kind: 'album', album: startsAlbum }
  }
  if (startsSong) return { kind: 'song', song: startsSong, all: songs }
  if (startsAlbum) return { kind: 'album', album: startsAlbum }

  if (songs[0]) return { kind: 'song', song: songs[0], all: songs }
  if (albums[0]) return { kind: 'album', album: albums[0] }
  return null
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

/** A person as a round card (search, a list of composers). */
export function PersonCard({ artist, onClick, className = 'tile' }: { artist: Artist; onClick: () => void; className?: string }) {
  return (
    <div className={`card ${className}`} onClick={onClick}>
      <PersonAvatar name={artist.name} fill coverArt={artist.coverArt} />
      <div className="name title-small ellipsis" style={{ marginTop: 10 }}>{artist.name}</div>
      <div className="body-small muted">{isComposer(artist) ? 'Composer' : 'Artist'}</div>
    </div>
  )
}

const TILES = [
  { label: 'Albums', color: 'hsl(330 70% 42%)', go: (n: Nav) => n.openAlbums() },
  { label: 'Composers', color: 'hsl(205 70% 38%)', go: (n: Nav) => n.openArtists() },
  { label: 'Artists', color: 'hsl(20 75% 42%)', go: (n: Nav) => n.openSingers() },
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
