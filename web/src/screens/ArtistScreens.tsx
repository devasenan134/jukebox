import { useMemo, useState } from 'react'
import { useParams } from 'react-router-dom'
import type { Artist } from '../api/types'
import { isComposer, mixSongToSong } from '../api/types'
import { social } from '../api/social'
import { subsonic } from '../api/subsonic'
import * as player from '../player/player'
import { AlbumCard, PlayShuffleRow, ScreenHeader, SectionTitle, songCount, SongRow, startStation, useLoad } from '../ui/components'
import { CardsSkeleton, Collection, Meta, PageSkeleton, PersonAvatar, TrackHead } from '../ui/collection'
import { PersonCard } from './SearchScreen'
import { ErrorBox, Icon, MoreMenu } from '../ui/kit'
import { useNav } from '../ui/nav'

/** Music directors, A to Z, with a filter box. */
export function ArtistsScreen() {
  const nav = useNav()
  const [filter, setFilter] = useState('')
  const data = useLoad(['artists'], () => subsonic.artists())
  const composers = useMemo(() => (data.data ?? []).filter(isComposer).filter((a) => a.name.toLowerCase().includes(filter.trim().toLowerCase())), [data.data, filter])
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

/** A music director's albums; a singer's songs. */
export function ArtistScreen() {
  const { id = '' } = useParams()
  const nav = useNav()
  const data = useLoad(['artist', id], async () => {
    const artist = await subsonic.artist(id)
    const songs = isComposer(artist) ? [] : await subsonic.songsBy(artist.id, artist.name)
    return { artist, songs }
  })
  if (data.loading && !data.data) return <PageSkeleton />
  if (data.error) return <ErrorBox message={data.error} onRetry={data.retry} />
  const { artist, songs } = data.data!
  const albums = artist.album ?? []
  const source = `singer:${artist.id}`
  return (
    <Collection
      art={<PersonAvatar name={artist.name} size={232} />}
      round
      kind={songs.length > 0 ? 'Singer' : 'Music director'}
      title={artist.name}
      meta={<Meta parts={[songs.length > 0 ? songCount(songs.length) : `${albums.length} albums`]} />}
    >
      {songs.length > 0 ? (
        <>
          <PlayShuffleRow onPlay={() => player.play(songs, 0, false, source)} onShuffle={() => player.play(songs, 0, true, source)}>
            <MoreMenu items={[{ label: 'Start radio', onClick: () => void startStation('singer', artist.id) }]} />
          </PlayShuffleRow>
          <div className="tracks">
            <TrackHead />
            {songs.map((s, i) => (
              <SongRow key={s.id} song={s} index={i} showCover onOpenAlbum={nav.openAlbum} onClick={() => player.play(songs, i, false, source)} />
            ))}
          </div>
        </>
      ) : (
        <>
          <PlayShuffleRow onPlay={() => void startStation('composer', artist.id)} />
          <SectionTitle>Albums</SectionTitle>
          <div className="grid">
            {albums.map((a) => (
              <AlbumCard key={a.id} album={a} onClick={() => nav.openAlbum(a.id)} />
            ))}
          </div>
        </>
      )}
    </Collection>
  )
}

/** A lyricist or actor (from search): the films they wrote for or acted in, then their songs (ui/library/PersonScreen.kt). */
export function PersonScreen() {
  const { id = '' } = useParams()
  const nav = useNav()
  const data = useLoad(['person', id], () => social.person(id))
  if (data.loading && !data.data) return <PageSkeleton />
  if (data.error) return <ErrorBox message={data.error} onRetry={data.retry} />
  const { person, movies, songs: hits } = data.data!
  const songs = hits.map(mixSongToSong)
  const source = `person:${person.id}`
  const role = person.roles.includes('lyricist') ? 'Lyricist' : person.roles.includes('actor') ? 'Actor' : 'Artist'
  return (
    <Collection
      art={<PersonAvatar name={person.name} size={232} />}
      round
      kind={role}
      title={person.name}
      meta={<Meta parts={[movies.length > 0 && `${movies.length} films`, songCount(songs.length)]} />}
    >
      {songs.length > 0 && <PlayShuffleRow onPlay={() => player.play(songs, 0, false, source)} onShuffle={() => player.play(songs, 0, true, source)} />}
      {movies.length > 0 && (
        <>
          <SectionTitle>Films</SectionTitle>
          <div className="row-scroll">
            {movies.map((m) => (
              <AlbumCard key={m.id} album={{ id: m.id, name: m.name, year: m.year, artist: m.composer, coverArt: m.coverArt, songCount: m.songCount, duration: 0 }} onClick={() => nav.openAlbum(m.id)} className="tile" />
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
              <SongRow key={s.id} song={s} index={i} showCover onOpenAlbum={nav.openAlbum} onClick={() => player.play(songs, i, false, source)} />
            ))}
          </div>
        </>
      )}
    </Collection>
  )
}
