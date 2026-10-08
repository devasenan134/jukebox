import { useMemo, useState } from 'react'
import { useParams } from 'react-router-dom'
import type { Artist, Song } from '../api/types'
import { isComposer } from '../api/types'
import { catalog, albumSummaryToAlbum, recordingToSong } from '../api/catalog'
import * as player from '../player/player'
import { AlbumCard, PlayShuffleRow, ScreenHeader, SectionTitle, songCount, SongRow, startStation, useLoad } from '../ui/components'
import { CardsSkeleton, Collection, Meta, PageSkeleton, PersonAvatar, TrackHead } from '../ui/collection'
import { PersonCard } from './SearchScreen'
import { ErrorBox, Icon, MoreMenu } from '../ui/kit'
import { useNav } from '../ui/nav'

/** Music directors, A to Z, with a filter box. Uses native /api/v2/people. */
export function ArtistsScreen() {
  const nav = useNav()
  const [filter, setFilter] = useState('')
  const data = useLoad(['artists-v2'], async () => {
    const res = await catalog.people({ role: 'composer', limit: 300 })
    return res.people.map((p) => ({
      id: p.id,
      name: p.name,
      roles: p.roles,
      albumCount: p.movieCount,
      coverArt: p.coverArt,
    }))
  })

  const composers = useMemo(
    () => (data.data ?? []).filter((a) => a.name.toLowerCase().includes(filter.trim().toLowerCase())),
    [data.data, filter],
  )

  if (data.loading && !data.data) return <div className="page"><CardsSkeleton rows={3} /></div>
  if (data.error) return <ErrorBox message={data.error} onRetry={data.retry} />

  return (
    <div className="page">
      <ScreenHeader title="Music directors" onBack={nav.back} />
      <div style={{ padding: '0 16px 12px' }}>
        <div className="search-box">
          <Icon name="filter_list" />
          <input value={filter} placeholder="Find a music director" onChange={(e) => setFilter(e.target.value)} />
        </div>
      </div>
      <div className="grid">
        {composers.map((a) => (
          <PersonCard key={a.id} artist={a} className="" onClick={() => nav.openArtist(a.id)} />
        ))}
      </div>
    </div>
  )
}

export function PersonRow({ artist, onClick }: { artist: Artist; onClick: () => void }) {
  return (
    <div className="list-row" onClick={onClick} style={{ cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 16, padding: '10px 16px' }}>
      <div style={{ width: 44, height: 44, borderRadius: '50%', background: 'var(--secondary-container)', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
        <Icon name={isComposer(artist) ? 'piano' : 'mic'} />
      </div>
      <div style={{ minWidth: 0 }}>
        <div className="body-large ellipsis">{artist.name}</div>
        <div className="body-small muted">{isComposer(artist) ? `${artist.albumCount ?? 0} albums` : 'Singer'}</div>
      </div>
    </div>
  )
}

function roleLabel(roles: string[]): string {
  if (roles.includes('composer')) return 'Music director'
  if (roles.includes('singer')) return 'Singer'
  if (roles.includes('lyricist')) return 'Lyricist'
  if (roles.includes('actor')) return 'Actor'
  if (roles.includes('director')) return 'Director'
  return 'Artist'
}

/** Unified Person view for music directors, singers, lyricists, and actors using /api/v2/people/{id}. */
export function ArtistScreen() {
  const { id = '' } = useParams()
  const nav = useNav()
  const data = useLoad(['person-v2', id], () => catalog.person(id))

  if (data.loading && !data.data) return <PageSkeleton />
  if (data.error) return <ErrorBox message={data.error} onRetry={data.retry} />

  const person = data.data!
  const albums = person.albums.map(albumSummaryToAlbum)
  const movies = person.movies.map(albumSummaryToAlbum)
  const songs: Song[] = person.songs.map((s) => recordingToSong(s))
  const source = `person:${person.id}`
  const kind = roleLabel(person.roles)

  const metaParts: (string | false | null | undefined)[] = []
  if (albums.length > 0) metaParts.push(`${albums.length} albums`)
  if (movies.length > 0 && movies.length !== albums.length) metaParts.push(`${movies.length} films`)
  if (songs.length > 0) metaParts.push(songCount(songs.length))

  return (
    <Collection
      art={<PersonAvatar name={person.name} size={232} />}
      round
      kind={kind}
      title={person.name}
      meta={<Meta parts={metaParts} />}
    >
      <PlayShuffleRow
        onPlay={() => {
          if (songs.length > 0) player.play(songs, 0, false, source)
          else void startStation('composer', person.id)
        }}
        onShuffle={() => {
          if (songs.length > 0) player.play(songs, 0, true, source)
        }}
      >
        <MoreMenu
          items={[
            {
              label: 'Start radio',
              onClick: () => void startStation(person.roles.includes('composer') ? 'composer' : 'singer', person.id),
            },
          ]}
        />
      </PlayShuffleRow>

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

      {movies.length > 0 && (
        <>
          <SectionTitle>Filmography</SectionTitle>
          <div className="row-scroll">
            {movies.map((m) => (
              <AlbumCard key={m.id} album={m} onClick={() => nav.openAlbum(m.id)} className="tile" />
            ))}
          </div>
        </>
      )}

      {songs.length > 0 && (
        <>
          <SectionTitle>Songs</SectionTitle>
          <div className="tracks">
            <TrackHead />
            {songs.map((s, i) => (
              <SongRow
                key={s.id}
                song={s}
                index={i}
                showCover
                onOpenAlbum={nav.openAlbum}
                onClick={() => player.play(songs, i, false, source)}
              />
            ))}
          </div>
        </>
      )}
    </Collection>
  )
}

/** Person screen forwards to the unified ArtistScreen. */
export function PersonScreen() {
  return <ArtistScreen />
}
