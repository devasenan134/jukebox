import { useParams } from 'react-router-dom'
import { subsonic } from '../api/subsonic'
import * as player from '../player/player'
import { activity } from '../state/history'
import { likes, useLikes } from '../state/likes'
import { keys } from '../state/queries'
import { Collection, Meta, PageSkeleton, TrackHead } from '../ui/collection'
import { formatTotalDuration, LikeButton, PlayShuffleRow, songCount, SongRow, useLoad } from '../ui/components'
import { ErrorBox, MoreMenu } from '../ui/kit'
import { startStation } from '../ui/components'
import { useNav } from '../ui/nav'

/** An album (a film's songs, or its background score): cover, music director, and the songs by disc. */
export function AlbumScreen() {
  const { id = '' } = useParams()
  const nav = useNav()
  const data = useLoad(keys.album(id), () => subsonic.album(id))
  const liked = useLikes((s) => s.albums.some((a) => a.id === id))
  if (data.loading && !data.data) return <PageSkeleton />
  if (data.error) return <ErrorBox message={data.error} onRetry={data.retry} />
  const album = data.data!
  const songs = album.song ?? []
  const discs = [...new Set(songs.map((s) => s.discNumber ?? 1))]
  const play = (index: number, shuffle = false) => {
    activity.movie(album)
    player.play(songs, index, shuffle, `album:${album.id}`)
  }

  return (
    <Collection
      coverArt={album.coverArt}
      kind="Album"
      title={album.name}
      meta={
        <Meta
          parts={[
            album.artistId ? (
              <b><a href={`/artist/${album.artistId}`} onClick={(e) => { e.preventDefault(); nav.openArtist(album.artistId!) }}>{album.artist}</a></b>
            ) : album.artist && <b>{album.artist}</b>,
            album.year,
            songCount(album.songCount),
            formatTotalDuration(album.duration),
          ]}
        />
      }
    >
      <PlayShuffleRow onPlay={() => play(0)} onShuffle={() => play(0, true)} resume={{ source: `album:${album.id}`, songs, onResume: () => activity.movie(album) }}>
        <LikeButton liked={liked} onToggle={() => likes().toggleAlbum(album)} big />
        <MoreMenu
          icon="more_horiz"
          items={[
            { label: 'Start album radio', onClick: () => void startStation('album', album.id) },
            { label: 'Go to music director', onClick: () => album.artistId && nav.openArtist(album.artistId), hidden: !album.artistId },
          ]}
        />
      </PlayShuffleRow>
      <div className="tracks">
        <TrackHead album={false} />
        {discs.map((disc) => (
          <div key={disc}>
            {discs.length > 1 && <div className="label-large muted" style={{ padding: '12px 16px 4px' }}>{`Disc ${disc}`}</div>}
            {songs.map((s, i) => ((s.discNumber ?? 1) === disc ? <SongRow key={s.id + i} song={s} onClick={() => play(i)} /> : null))}
          </div>
        ))}
      </div>
    </Collection>
  )
}
