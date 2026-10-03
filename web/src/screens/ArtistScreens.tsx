import { useMemo, useState } from 'react'
import { useParams } from 'react-router-dom'
import type { Artist } from '../api/types'
import { isComposer } from '../api/types'
import { subsonic } from '../api/subsonic'
import * as player from '../player/player'
import { AlbumCard, PlayShuffleRow, ScreenHeader, SongRow, useLoad } from '../ui/components'
import { ErrorBox, Icon, Loading } from '../ui/kit'
import { useNav } from '../ui/nav'

/** Music directors, A to Z, with a filter box. */
export function ArtistsScreen() {
  const nav = useNav()
  const [filter, setFilter] = useState('')
  const data = useLoad(['artists'], () => subsonic.artists())
  const composers = useMemo(() => (data.data ?? []).filter(isComposer).filter((a) => a.name.toLowerCase().includes(filter.trim().toLowerCase())), [data.data, filter])
  if (data.loading && !data.data) return <Loading />
  if (data.error) return <ErrorBox message={data.error} onRetry={data.retry} />
  return (
    <div className="page">
      <ScreenHeader title="Music directors" />
      <div style={{ padding: '0 16px 8px' }}>
        <div className="search-box">
          <Icon name="filter_list" />
          <input value={filter} placeholder="Filter" onChange={(e) => setFilter(e.target.value)} />
        </div>
      </div>
      {composers.map((a) => (
        <PersonRow key={a.id} artist={a} onClick={() => nav.openArtist(a.id)} />
      ))}
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
  if (data.loading && !data.data) return <Loading />
  if (data.error) return <ErrorBox message={data.error} onRetry={data.retry} />
  const { artist, songs } = data.data!
  const albums = artist.album ?? []
  return (
    <div className="page">
      <ScreenHeader title={artist.name} onBack={nav.back} />
      {songs.length > 0 ? (
        <>
          <PlayShuffleRow onPlay={() => player.play(songs, 0, false, `singer:${artist.id}`)} onShuffle={() => player.play(songs, 0, true, `singer:${artist.id}`)}>
            <div className="body-medium muted">{songs.length} songs</div>
          </PlayShuffleRow>
          {songs.map((s, i) => (
            <SongRow key={s.id} song={s} showCover onOpenAlbum={nav.openAlbum} onClick={() => player.play(songs, i, false, `singer:${artist.id}`)} />
          ))}
        </>
      ) : (
        <>
          <div className="body-medium muted" style={{ padding: '0 16px 12px' }}>{albums.length} albums</div>
          <div className="grid">
            {albums.map((a) => (
              <AlbumCard key={a.id} album={a} onClick={() => nav.openAlbum(a.id)} />
            ))}
          </div>
        </>
      )}
    </div>
  )
}
