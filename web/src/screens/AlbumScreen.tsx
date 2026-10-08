import { useParams } from 'react-router-dom'
import { catalog, albumDetailToAlbum, trackToSong } from '../api/catalog'
import type { Release, Track } from '../api/catalog'
import * as player from '../player/player'
import { activity } from '../state/history'
import { likes, useLikes } from '../state/likes'
import { keys } from '../state/queries'
import { Collection, Meta, PageSkeleton, TrackHead } from '../ui/collection'
import { formatTotalDuration, LikeButton, PlayShuffleRow, songCount, SongRow, useLoad } from '../ui/components'
import { ErrorBox, Icon, IconButton, MoreMenu } from '../ui/kit'
import { startStation } from '../ui/components'
import { useNav } from '../ui/nav'

function releaseKindLabel(kind: string): string {
  switch (kind) {
    case 'soundtrack': return 'Soundtrack'
    case 'score': return 'Background Score'
    case 'single': return 'Single'
    case 'rerelease': return 'Re-release'
    default: return 'Release'
  }
}

/** An album/film: cover, music director, director, cast, and releases (soundtrack, background score, singles). */
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
  const hasMultipleReleases = detail.releases.length > 1

  const playAll = (startIndex: number, shuffle = false) => {
    activity.movie(album)
    player.play(allSongs, startIndex, shuffle, `album:${album.id}`)
  }

  const playRelease = (rel: Release, shuffle = false) => {
    const relSongs = rel.tracks.map((t) => trackToSong(t, rel, detail))
    activity.movie(album)
    player.play(relSongs, 0, shuffle, `album:${album.id}:${rel.id}`)
  }

  const primaryComposer = detail.composers[0]

  return (
    <Collection
      coverArt={detail.coverArt}
      kind={detail.kind === 'film' ? 'Film' : 'Album'}
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
            hasMultipleReleases ? `${detail.releases.length} releases` : null,
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
        {hasMultipleReleases ? (
          detail.releases.map((rel) => {
            const discs = [...new Set(rel.tracks.map((t) => t.disc))]
            return (
              <div key={rel.id} style={{ marginBottom: 28 }}>
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    padding: '8px 16px',
                    borderBottom: '1px solid var(--outline-variant)',
                  }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                    <span className="title-medium" style={{ fontWeight: 700 }}>{rel.title}</span>
                    <span className="chip" style={{ fontSize: 11, padding: '2px 8px', height: 22 }}>
                      {releaseKindLabel(rel.kind)}
                    </span>
                    {rel.year && rel.year !== detail.year && (
                      <span className="body-small muted">({rel.year})</span>
                    )}
                  </div>
                  <IconButton
                    icon="play_arrow"
                    label={`Play ${rel.title}`}
                    onClick={() => playRelease(rel)}
                  />
                </div>
                <TrackHead album={false} />
                {discs.map((disc) => (
                  <div key={disc}>
                    {discs.length > 1 && (
                      <div className="label-large muted" style={{ padding: '12px 16px 4px' }}>
                        {`Disc ${disc}`}
                      </div>
                    )}
                    {rel.tracks
                      .filter((t) => t.disc === disc)
                      .map((t) => {
                        const song = trackToSong(t, rel, detail)
                        const overallIndex = allSongs.findIndex((s) => s.id === song.id)
                        return (
                          <SongRow
                            key={t.id}
                            song={song}
                            onClick={() => playAll(overallIndex >= 0 ? overallIndex : 0)}
                          />
                        )
                      })}
                  </div>
                ))}
              </div>
            )
          })
        ) : (
          <div>
            <TrackHead album={false} />
            {(() => {
              const rel = detail.releases[0]
              if (!rel) return null
              const discs = [...new Set(rel.tracks.map((t: Track) => t.disc))]
              return discs.map((disc) => (
                <div key={disc}>
                  {discs.length > 1 && (
                    <div className="label-large muted" style={{ padding: '12px 16px 4px' }}>
                      {`Disc ${disc}`}
                    </div>
                  )}
                  {rel.tracks
                    .filter((t: Track) => t.disc === disc)
                    .map((t: Track, i: number) => {
                      const song = trackToSong(t, rel, detail)
                      return (
                        <SongRow
                          key={t.id}
                          song={song}
                          onClick={() => playAll(i)}
                        />
                      )
                    })}
                </div>
              ))
            })()}
          </div>
        )}
      </div>
    </Collection>
  )
}
