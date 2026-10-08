import { subsonic } from '../api/subsonic'
import { catalog } from '../api/catalog'
import { credited, type SongDetails } from '../api/types'
import { formatDuration, useLoad } from '../ui/components'

/** "About this song", below the full player: who made it, where it's from, versions, audio metadata. */
export function SongDetailsSection({ songId, onOpenAlbum }: { songId: string; onOpenAlbum: (id: string) => void }) {
  const details = useLoad(['song-details', songId], () => subsonic.songDetails(songId))
  const native = useLoad(['song-v2', songId], () => catalog.song(songId).catch(() => null))

  const song = details.data
  const v2 = native.data

  return (
    <section className="song-details">
      <div className="title-large" style={{ fontWeight: 800, marginBottom: 12 }}>About this song</div>
      {!song ? (
        <div className="body-medium muted">{details.error ? "Couldn't load the details" : 'Loading…'}</div>
      ) : (
        <>
          <div className="details-card">
            <Detail label="Artists" value={(song.artists?.map((a) => a.name) ?? []).join(', ') || song.artist} />
            <Detail label="Composer" value={composers(song).join(', ')} />
            <Detail label="Lyricist" value={credited(song, 'lyricist').join(', ')} />
            <Detail label="Movie" value={song.album} onClick={song.albumId ? () => onOpenAlbum(song.albumId!) : undefined} />
            <Detail label="Year" value={song.year?.toString()} />
            <Detail label="Genre" value={(song.genres?.map((g) => g.name) ?? []).join(', ') || song.genre} />
            {v2?.version && v2.version !== 'original' && (
              <Detail label="Version" value={v2.version.toUpperCase()} />
            )}
          </div>

          {v2?.versions && v2.versions.length > 0 && (
            <div className="details-card">
              <div className="label-large" style={{ fontWeight: 700, marginBottom: 8, color: 'var(--primary)' }}>
                Other versions
              </div>
              {v2.versions.map((ver) => (
                <Detail
                  key={ver.id}
                  label={ver.version.charAt(0).toUpperCase() + ver.version.slice(1)}
                  value={ver.title}
                />
              ))}
            </div>
          )}

          <div className="details-card">
            <Detail
              label="Track"
              value={[song.discNumber && song.discNumber > 1 ? `Disc ${song.discNumber}` : null, song.track ? `Track ${song.track}` : null].filter(Boolean).join(', ')}
            />
            <Detail label="Length" value={formatDuration(song.duration)} />
            <Detail label="Quality" value={quality(song)} />
            <Detail label="File size" value={song.size ? fileSize(song.size) : undefined} />
            <Detail label="BPM" value={song.bpm && song.bpm > 0 ? String(song.bpm) : undefined} />
            <Detail
              label="Plays"
              value={song.playCount ? `${song.playCount === 1 ? 'Once' : `${song.playCount.toLocaleString('en')} times`}${song.played ? `, last on ${date(song.played)}` : ''}` : 'Not played yet'}
            />
            <Detail label="Added" value={song.created ? date(song.created) : undefined} />
            <Detail label="Comment" value={song.comment} />
          </div>
        </>
      )}
    </section>
  )
}

/** One labelled line. Empty values are skipped, so songs with fewer tags just show less. */
function Detail({ label, value, onClick }: { label: string; value?: string; onClick?: () => void }) {
  if (!value?.trim()) return null
  return (
    <div className="detail">
      <span className="muted">{label}</span>
      {onClick ? <a onClick={onClick} style={{ cursor: 'pointer', fontWeight: 600, color: 'var(--primary)' }}>{value}</a> : <span>{value}</span>}
    </div>
  )
}

/** In film music the album artist (music director) is the composer; songs without a composer tag use it. */
function composers(song: SongDetails): string[] {
  const tagged = credited(song, 'composer')
  if (tagged.length) return tagged
  if (song.displayComposer) return [song.displayComposer]
  const albumArtists = song.albumArtists?.map((a) => a.name) ?? []
  return albumArtists.length ? albumArtists : song.displayAlbumArtist ? [song.displayAlbumArtist] : []
}

/** "FLAC · 1,411 kbps · 44.1 kHz · 16-bit · Stereo" */
function quality(song: SongDetails): string {
  const channels = song.channelCount === 1 ? 'Mono' : song.channelCount === 2 ? 'Stereo' : song.channelCount ? `${song.channelCount} channels` : null
  return [
    song.suffix?.toUpperCase(),
    song.bitRate && song.bitRate > 0 ? `${song.bitRate.toLocaleString('en')} kbps` : null,
    song.samplingRate && song.samplingRate > 0 ? `${(song.samplingRate / 1000).toFixed(1).replace(/\.0$/, '')} kHz` : null,
    song.bitDepth && song.bitDepth > 0 ? `${song.bitDepth}-bit` : null,
    channels,
  ].filter(Boolean).join(' · ')
}

function fileSize(bytes: number): string {
  if (bytes >= 1e9) return `${(bytes / 1e9).toFixed(1)} GB`
  if (bytes >= 1e6) return `${(bytes / 1e6).toFixed(1)} MB`
  return `${Math.round(bytes / 1000)} KB`
}

/** "2026-09-12T18:04:00Z" -> "12 Sep 2026" */
function date(iso: string): string {
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso.slice(0, 10) : d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })
}
