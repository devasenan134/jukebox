import { useParams } from 'react-router-dom'
import { subsonic } from '../api/subsonic'
import * as player from '../player/player'
import { Cover, formatTotalDuration, PlayShuffleRow, ScreenHeader, songCount, SongRow, useLoad } from '../ui/components'
import { ErrorBox, Loading } from '../ui/kit'
import { useNav } from '../ui/nav'

/** An album (a film's songs, or its background score): cover, music director, and the songs by disc. */
export function AlbumScreen() {
  const { id = '' } = useParams()
  const nav = useNav()
  const data = useLoad(() => subsonic.album(id), [id])
  if (data.loading && !data.data) return <Loading />
  if (data.error) return <ErrorBox message={data.error} onRetry={data.retry} />
  const album = data.data!
  const songs = album.song ?? []
  const discs = [...new Set(songs.map((s) => s.discNumber ?? 1))]
  const play = (index: number, shuffle = false) => player.play(songs, index, shuffle, `album:${album.id}`)

  return (
    <div className="page">
      <ScreenHeader title="" onBack={nav.back} />
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', padding: '0 24px', textAlign: 'center' }}>
        <Cover coverArt={album.coverArt} size={600} corner={12} style={{ width: 'min(260px, 70vw)', aspectRatio: '1', boxShadow: '0 12px 32px rgba(0,0,0,.25)' }} />
        <div className="headline-small" style={{ marginTop: 16, fontWeight: 700 }}>{album.name}</div>
        <div className="body-medium muted" style={{ marginTop: 4 }}>
          {album.artistId ? (
            <a className="primary-text" style={{ cursor: 'pointer' }} onClick={() => nav.openArtist(album.artistId!)}>{album.artist}</a>
          ) : album.artist}
          {album.year ? ` · ${album.year}` : ''}
        </div>
        <div className="body-small muted" style={{ marginTop: 2 }}>{songCount(album.songCount)} · {formatTotalDuration(album.duration)}</div>
      </div>
      <PlayShuffleRow onPlay={() => play(0)} onShuffle={() => play(0, true)} />
      {discs.map((disc) => (
        <div key={disc}>
          {discs.length > 1 && <div className="label-large muted" style={{ padding: '12px 16px 4px' }}>{`Disc ${disc}`}</div>}
          {songs.map((s, i) => ((s.discNumber ?? 1) === disc ? <SongRow key={s.id + i} song={s} onClick={() => play(i)} /> : null))}
        </div>
      ))}
    </div>
  )
}
