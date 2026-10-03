import { useState } from 'react'
import type { Album } from '../api/types'
import { subsonic } from '../api/subsonic'
import { AlbumCard, SectionTitle, useLoad } from '../ui/components'
import { ErrorBox, Loading } from '../ui/kit'
import { useNav } from '../ui/nav'

// Home: new albums, a random handful to discover, and a way into the whole library.
// (Mixes, Recently played and Most played come back when Jukebox keeps plays, milestone 2.)

export function HomeScreen() {
  const nav = useNav()
  const [seed, setSeed] = useState(0)
  const data = useLoad(async () => {
    const [newest, random, byName] = await Promise.all([
      subsonic.albumList('newest', 20),
      subsonic.albumList('random', 20),
      subsonic.albumList('alphabeticalByName', 20),
    ])
    return { newest, random, byName }
  }, [seed])

  if (data.loading && !data.data) return <Loading />
  if (data.error) return <ErrorBox message={data.error} onRetry={data.retry} />
  const d = data.data!
  return (
    <div className="page">
      <div style={{ padding: '20px 16px 0', display: 'flex', alignItems: 'baseline', gap: 10 }}>
        <div className="headline-medium" style={{ fontWeight: 700 }}>Jukebox</div>
      </div>
      <Row title="New in the library" albums={d.newest} onOpen={nav.openAlbum} />
      <Row title="Something different" albums={d.random} onOpen={nav.openAlbum} action={{ label: 'Shuffle', onClick: () => setSeed((s) => s + 1) }} />
      <Row title="A to Z" albums={d.byName} onOpen={nav.openAlbum} action={{ label: 'See all', onClick: nav.openAlbums }} />
    </div>
  )
}

function Row({ title, albums, onOpen, action }: { title: string; albums: Album[]; onOpen: (id: string) => void; action?: { label: string; onClick: () => void } }) {
  if (!albums.length) return null
  return (
    <>
      <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', paddingRight: 8 }}>
        <SectionTitle>{title}</SectionTitle>
        {action && <button className="btn text" onClick={action.onClick}>{action.label}</button>}
      </div>
      <div className="row-scroll">
        {albums.map((a) => (
          <AlbumCard key={a.id} album={a} onClick={() => onOpen(a.id)} className="tile" />
        ))}
      </div>
    </>
  )
}
