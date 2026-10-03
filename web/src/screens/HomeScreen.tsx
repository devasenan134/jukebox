import { useEffect } from 'react'
import type { Album, Mix } from '../api/types'
import { refToSong } from '../api/types'
import { subsonic } from '../api/subsonic'
import * as player from '../player/player'
import { activity, useActivity, useRecentSongs, type ActivityItem } from '../state/history'
import { useMixes } from '../state/library'
import { AlbumCard, Cover, SectionTitle, SongRow, useLoad } from '../ui/components'
import { ErrorBox, IconButton, Loading } from '../ui/kit'
import { LikedTile, MixCover, MixSections } from '../ui/mixes'
import { useNav, type Nav } from '../ui/nav'
import { queryClient } from '../state/queries'

// Home (ui/home/HomeScreen.kt): Made for you, the songs you played lately, "Jump back in", the other
// mixes, then rows of albums: most played, recently added and random picks.

const RECENT_SONGS = 6

export function HomeScreen() {
  const nav = useNav()
  const mixes = useMixes((s) => s.home)
  const followed = useMixes((s) => s.followed)
  const items = useActivity((s) => s.items)
  const recentSongs = useRecentSongs((s) => s.songs)
  useEffect(() => useMixes.getState().refresh(), [])
  const lists = useLoad(['home', 'lists'], async () => {
    const [recent, frequent, newest] = await Promise.all([
      subsonic.albumList('recent', 20),
      subsonic.albumList('frequent', 20),
      subsonic.albumList('newest', 20),
    ])
    return { recent, frequent, newest }
  })
  // Random picks stay the same until you ask for new ones (Refresh), not every time you come back.
  const random = useLoad(['home', 'random'], () => subsonic.albumList('random', 20), Infinity)
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['home'] })
    useMixes.getState().refresh(true)
  }

  if (lists.loading && !lists.data) return <Loading />
  if (lists.error) return <ErrorBox message={lists.error} onRetry={lists.retry} />
  const d = { ...lists.data!, random: random.data ?? [] }
  const sections = mixes?.sections ?? []
  const knownMixes = new Map([...sections.flatMap((s) => s.mixes), ...followed].map((m) => [m.id, m]))
  const songs = recentSongs.slice(0, RECENT_SONGS).map(refToSong)
  const collections = items.filter((i) => i.kind !== 'Song')

  return (
    <div className="page">
      <div style={{ padding: '16px 8px 0 16px', display: 'flex', alignItems: 'center', gap: 8 }}>
        <div className="headline-medium" style={{ fontWeight: 700, color: 'var(--primary)', fontFamily: 'var(--display)', flex: 1 }}>Jukebox</div>
        <IconButton icon="refresh" label="Refresh" onClick={refresh} />
      </div>
      <div style={{ display: 'flex', gap: 8, padding: '8px 16px 0' }}>
        <button className="chip" onClick={nav.openAlbums}>Albums</button>
        <button className="chip" onClick={nav.openArtists}>Music directors</button>
      </div>
      <MixSections sections={sections.filter((s) => s.id === 'made-for-you')} nav={nav} />
      {songs.length > 0 && (
        <>
          <SectionTitle>Recently played</SectionTitle>
          {songs.map((s) => (
            <SongRow key={s.id} song={s} showCover onOpenAlbum={nav.openAlbum} onClick={() => { activity.song(s); player.play([s]) }} />
          ))}
        </>
      )}
      {collections.length > 0 ? <JumpBackIn items={collections} mixes={knownMixes} nav={nav} /> : songs.length === 0 && <AlbumRow title="Jump back in" albums={d.recent} nav={nav} />}
      <MixSections sections={sections.filter((s) => s.id !== 'made-for-you')} nav={nav} />
      <AlbumRow title="Most played" albums={d.frequent} nav={nav} />
      <AlbumRow title="Recently added" albums={d.newest} nav={nav} />
      <AlbumRow title="Random picks" albums={d.random} nav={nav} />
    </div>
  )
}

function AlbumRow({ title, albums, nav }: { title: string; albums: Album[]; nav: Nav }) {
  if (!albums.length) return null
  return (
    <>
      <SectionTitle>{title}</SectionTitle>
      <div className="row-scroll">
        {albums.map((a) => (
          <AlbumCard key={a.id} album={a} onClick={() => nav.openAlbum(a.id)} className="tile" />
        ))}
      </div>
    </>
  )
}

/** Albums, playlists, mixes and Liked songs as squares; composers and singers as circles. */
function JumpBackIn({ items, mixes, nav }: { items: ActivityItem[]; mixes: Map<string, Mix>; nav: Nav }) {
  const open = (i: ActivityItem) => {
    switch (i.kind) {
      case 'Movie': return nav.openAlbum(i.id)
      case 'Playlist': return nav.openPlaylist(i.id)
      case 'Composer': return nav.openArtist(i.id)
      case 'Artist': return nav.openSinger({ id: i.id, name: i.title, coverArt: i.coverArt, roles: ['artist'] })
      case 'Liked': return nav.openLikedSongs()
      case 'Mix': return nav.openMix(i.id)
    }
  }
  return (
    <>
      <SectionTitle>Jump back in</SectionTitle>
      <div className="row-scroll">
        {items.map((i) => {
          const round = i.kind === 'Composer' || i.kind === 'Artist'
          const mix = i.kind === 'Mix' ? mixes.get(i.id) : undefined
          return (
            <div key={`${i.kind}-${i.id}`} className="card tile" onClick={() => open(i)} data-testid="jump-back-in">
              {i.kind === 'Liked' ? <LikedTile size={192} fill /> : mix ? <MixCover mix={mix} size={192} fill /> : (
                <Cover coverArt={i.coverArt} round={round} style={{ width: '100%', aspectRatio: '1' }} />
              )}
              <div className="name title-small ellipsis" style={{ textAlign: round ? 'center' : undefined }}>{i.title}</div>
              <div className="body-small muted ellipsis" style={{ textAlign: round ? 'center' : undefined }}>{i.subtitle}</div>
            </div>
          )
        })}
      </div>
    </>
  )
}
