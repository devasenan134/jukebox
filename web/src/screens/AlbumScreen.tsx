import { useParams } from 'react-router-dom'
import { catalog, albumDetailToAlbum } from '../api/catalog'
import * as player from '../player/player'
import { activity } from '../state/history'
import { likes, useLikes } from '../state/likes'
import { keys } from '../state/queries'
import { Collection, Meta, PageSkeleton, TrackHead } from '../ui/collection'
import { formatTotalDuration, LikeButton, PlayShuffleRow, songCount, SongRow, useLoad } from '../ui/components'
import { ErrorBox, Icon, MoreMenu } from '../ui/kit'
import { startStation } from '../ui/components'
import { useNav } from '../ui/nav'

/** An album: cover, music director, director, cast, and tracks. */
export function AlbumScreen() {
  const { id = '' } = useParams()
  const nav = useNav()
  const data = useLoad(keys.album(id), () => catalog.album(id))
  const liked = useLikes((s) => s.albums.some((a) => a.id === id))

  if (data.loading && !data.data) return <PageSkeleton />
  if (data.error) return <ErrorBox message={data.error} onRetry={data.retry} />

  const detail = data.data!
  const album = albumDetailToAlbum(detail)
  const allSongs = album.song ?? []
  const discs = [...new Set(allSongs.map((s) => s.discNumber ?? 1))]

  const playAll = (startIndex: number, shuffle = false) => {
    activity.movie(album)
    player.play(allSongs, startIndex, shuffle, `album:${album.id}`)
  }

  const primaryComposer = detail.composers[0]

  return (
    <Collection
      coverArt={detail.coverArt}
      kind={detail.kind === 'score' ? 'Score' : detail.kind === 'film' ? 'Film' : 'Album'}
      title={detail.title}
      meta={
        <Meta
          parts={[
            detail.composers.length > 0 ? (
              <span>
                {detail.composers.map((c, i) => (
                  <span key={c.id}>
                    {i > 0 && ', '}
                    <b>
                      <a
                        href={`/artist/${c.id}`}
                        onClick={(e) => {
                          e.preventDefault()
                          nav.openArtist(c.id)
                        }}
                      >
                        {c.name}
                      </a>
                    </b>
                  </span>
                ))}
              </span>
            ) : null,
            detail.directors.length > 0 && <span>Dir. {detail.directors.join(', ')}</span>,
            detail.year,
            songCount(allSongs.length),
            formatTotalDuration(album.duration),
          ]}
        />
      }
    >
      <PlayShuffleRow
        onPlay={() => playAll(0)}
        onShuffle={() => playAll(0, true)}
        resume={{ source: `album:${album.id}`, songs: allSongs, onResume: () => activity.movie(album) }}
      >
        <LikeButton liked={liked} onToggle={() => likes().toggleAlbum(album)} big />
        <MoreMenu
          icon="more_horiz"
          items={[
            { label: 'Start album radio', onClick: () => void startStation('album', album.id) },
            {
              label: 'Go to music director',
              onClick: () => primaryComposer && nav.openArtist(primaryComposer.id),
              hidden: !primaryComposer,
            },
          ]}
        />
      </PlayShuffleRow>

      {detail.cast.length > 0 && (
        <div style={{ padding: '0 16px 16px' }}>
          <div className="label-medium muted" style={{ marginBottom: 6 }}>Starring</div>
          <div className="chips" style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
            {detail.cast.map((actor) => (
              <button
                key={actor.id || actor.name}
                className="chip"
                onClick={() => nav.openSearch()}
                title={`Find music featuring ${actor.name}`}
              >
                <Icon name="person" size={16} />
                <span>{actor.name}</span>
              </button>
            ))}
          </div>
        </div>
      )}

      <div className="tracks">
        <TrackHead album={false} />
        {discs.map((disc) => (
          <div key={disc}>
            {discs.length > 1 && (
              <div className="label-large muted" style={{ padding: '12px 16px 4px' }}>
                {`Disc ${disc}`}
              </div>
            )}
            {allSongs
              .filter((s) => (s.discNumber ?? 1) === disc)
              .map((song) => {
                const overallIndex = allSongs.findIndex((s) => s.id === song.id)
                return (
                  <SongRow
                    key={song.id}
                    song={song}
                    onClick={() => playAll(overallIndex >= 0 ? overallIndex : 0)}
                  />
                )
              })}
          </div>
        ))}
      </div>
    </Collection>
  )
}
