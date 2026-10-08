import { useMemo, useState } from 'react'
import { useParams } from 'react-router-dom'
import type { Artist, Song } from '../api/types'
import { isComposer } from '../api/types'
import { catalog, albumSummaryToAlbum, recordingToSong } from '../api/catalog'
import * as player from '../player/player'
import { AlbumCard, PlayShuffleRow, ScreenHeader, SectionTitle, songCount, SongRow, startStation, useLoad } from '../ui/components'
import { CardsSkeleton, Collection, Meta, PageSkeleton, PersonAvatar, TrackHead } from '../ui/collection'
import { ErrorBox, Icon, MoreMenu } from '../ui/kit'
import { useNav } from '../ui/nav'

/** Composers, sorted by people with more albums, with a filter box. Uses native /api/v2/people. */
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
    () =>
      (data.data ?? [])
        .slice()
        .sort((a, b) => (b.albumCount ?? 0) - (a.albumCount ?? 0) || a.name.localeCompare(b.name))
        .filter((a) => a.name.toLowerCase().includes(filter.trim().toLowerCase())),
    [data.data, filter],
  )

  if (data.loading && !data.data) return <div className="page"><CardsSkeleton rows={3} /></div>
  if (data.error) return <ErrorBox message={data.error} onRetry={data.retry} />

  return (
    <div className="page">
      <ScreenHeader title="Composers" onBack={nav.back} />
      <div style={{ padding: '0 16px 12px' }}>
        <div className="search-box">
          <Icon name="filter_list" />
          <input value={filter} placeholder="Find a composer" onChange={(e) => setFilter(e.target.value)} />
        </div>
      </div>
      <div className="list" style={{ padding: '0 16px' }}>
        {composers.map((a) => (
          <div
            key={a.id}
            className="list-row"
            onClick={() => nav.openArtist(a.id)}
            style={{ cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '12px 16px', borderRadius: 8 }}
          >
            <div style={{ minWidth: 0 }}>
              <div className="body-large ellipsis" style={{ fontWeight: 600 }}>{a.name}</div>
              <div className="body-small muted">{a.albumCount ?? 0} {(a.albumCount ?? 0) === 1 ? 'album' : 'albums'}</div>
            </div>
            <Icon name="chevron_right" size={20} className="muted" />
          </div>
        ))}
      </div>
    </div>
  )
}

export function PersonRow({ artist, onClick }: { artist: Artist; onClick: () => void }) {
  return (
    <div className="list-row" onClick={onClick} style={{ cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 16, padding: '10px 16px' }}>
      <div style={{ minWidth: 0 }}>
        <div className="body-large ellipsis">{artist.name}</div>
        <div className="body-small muted">{isComposer(artist) ? `${artist.albumCount ?? 0} albums` : 'Artist'}</div>
      </div>
    </div>
  )
}

function roleLabel(roles: string[]): string {
  if (roles.includes('composer')) return 'Composer'
  return 'Artist'
}

/** Unified Person view for composers and artists using /api/v2/people/{id}. */
export function ArtistScreen() {
  const { id = '' } = useParams()
  const nav = useNav()
  const data = useLoad(['person-v2', id], () => catalog.person(id))

  if (data.loading && !data.data) return <PageSkeleton />
  if (data.error) return <ErrorBox message={data.error} onRetry={data.retry} />

  const person = data.data!
  const albums = person.albums.map(albumSummaryToAlbum)
  const songs: Song[] = person.songs.map((s) => recordingToSong(s))
  const source = `person:${person.id}`
  const kind = roleLabel(person.roles)

  const metaParts: (string | false | null | undefined)[] = []
  if (albums.length > 0) metaParts.push(`${albums.length} ${albums.length === 1 ? 'album' : 'albums'}`)
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
