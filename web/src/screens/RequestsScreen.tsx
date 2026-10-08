import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import type { CatalogHit, CatalogItem, MusicRequest } from '../api/types'
import { social } from '../api/social'
import { me } from '../social/social'
import { useSession } from '../state/session'
import { ScreenHeader, SectionTitle } from '../ui/components'
import { Dialog, ErrorBox, Icon, IconButton, Loading, toast } from '../ui/kit'
import { useNav } from '../ui/nav'

// Music that isn't in the library (ui/search/Requests.kt): search shows it from the iTunes catalog with a
// Request button, and the requests page lists yours (and, for admins, everyone's to answer).

/** A catalog cover (from Apple's servers, not Jukebox). */
function Artwork({ item, size }: { item: CatalogItem; size: number | string }) {
  return item.artworkUrl ? (
    <img src={item.artworkUrl} alt="" loading="lazy" style={{ width: size, height: size, aspectRatio: '1', objectFit: 'cover', borderRadius: 6, display: 'block', background: 'var(--surface-container-high)', flexShrink: 0 }} />
  ) : (
    <div style={{ width: size, height: size, aspectRatio: '1', borderRadius: 6, background: 'var(--surface-container-high)', flexShrink: 0 }} />
  )
}

const label = (item: CatalogItem) => (item.kind === 'movie' ? item.movie : `${item.title} (${item.movie})`)
const isOpen = (r?: MusicRequest) => r?.status === 'open'

/** "Request", or "Requested ✓" once you asked (click it to take the request back). */
function RequestButton({ hit, onRequest, onCancel, wide }: { hit: CatalogHit; onRequest: () => void; onCancel: () => void; wide?: boolean }) {
  const mine = isOpen(hit.request) && hit.request!.mine
  return mine ? (
    <button className="btn outlined small" onClick={onCancel} style={wide ? { width: '100%' } : undefined}>
      <Icon name="check" size={16} />Requested
    </button>
  ) : (
    <button className="btn tonal small" onClick={onRequest} style={wide ? { width: '100%' } : undefined}>Request</button>
  )
}

/**
 * After the library's results: movies and songs from the catalog that aren't in the library, each with
 * a Request button. Loads after the library search, and only for searches of 3+ letters.
 */
export function CatalogResults({ query }: { query: string }) {
  const client = useQueryClient()
  const key = ['catalog', query]
  const signedIn = useSession((s) => s.social != null)
  const catalog = useQuery({ queryKey: key, queryFn: () => social.catalog(query), enabled: signedIn && query.length >= 3, staleTime: 30 * 60_000 })
  const [cancelling, setCancelling] = useState<CatalogHit | null>(null)
  const movies = catalog.data?.movies ?? []
  const songs = catalog.data?.songs ?? []
  if (!movies.length && !songs.length) return null

  /** Puts an item's new request (or none) into the cached results. */
  const changed = (itemId: string, request: MusicRequest | undefined) =>
    client.setQueryData(key, (old: typeof catalog.data) =>
      old && {
        movies: old.movies.map((h) => (h.item.id === itemId ? { ...h, request } : h)),
        songs: old.songs.map((h) => (h.item.id === itemId ? { ...h, request } : h)),
      },
    )
  const request = (hit: CatalogHit) =>
    social.requestMusic(hit.item.id)
      .then((r) => {
        changed(hit.item.id, r)
        toast("Requested. You'll see it in Your requests when it's in the library.", true)
      })
      .catch((e) => toast((e as Error).message || "Couldn't request it"))
  const cancel = async (hit: CatalogHit) => {
    setCancelling(null)
    const r = hit.request
    if (!r) return
    try {
      await social.cancelMusicRequest(r.id)
      // Others who asked keep it open.
      const others = r.askedBy.filter((n) => n !== me()?.displayName)
      changed(hit.item.id, others.length ? { ...r, mine: false, askedBy: others } : undefined)
    } catch (e) {
      toast((e as Error).message || "Couldn't take it back")
    }
  }

  return (
    <>
      <SectionTitle>Not in the library</SectionTitle>
      <div className="body-small muted" style={{ padding: '0 16px 8px', marginTop: -6 }}>
        Request one and it gets added, usually within a day.
      </div>
      {movies.length > 0 && (
        <div className="row-scroll">
          {movies.map((h) => (
            <div key={h.item.id} className="tile" style={{ display: 'flex', flexDirection: 'column', gap: 6, padding: 8 }}>
              <Artwork item={h.item} size="100%" />
              <div className="title-small ellipsis">{h.item.movie}</div>
              <div className="body-small muted ellipsis">{[h.item.year, h.item.artist].filter(Boolean).join(' · ')}</div>
              <RequestButton hit={h} wide onRequest={() => void request(h)} onCancel={() => setCancelling(h)} />
            </div>
          ))}
        </div>
      )}
      <div style={{ padding: '0 8px' }}>
        {songs.map((h) => (
          <div key={h.item.id} className="list-row" style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '6px 8px' }}>
            <Artwork item={h.item} size={48} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="body-large ellipsis">{h.item.title}</div>
              <div className="body-small muted ellipsis">{[h.item.movie, h.item.year, h.item.artist].filter(Boolean).join(' · ')}</div>
              {isOpen(h.request) && !h.request!.mine && h.request!.askedBy.length > 0 && (
                <div className="body-small primary-text ellipsis">Asked for by {h.request!.askedBy.join(', ')}</div>
              )}
            </div>
            <RequestButton hit={h} onRequest={() => void request(h)} onCancel={() => setCancelling(h)} />
          </div>
        ))}
      </div>
      {cancelling && (
        <Dialog
          title="Take back your request?"
          onClose={() => setCancelling(null)}
          actions={<><button className="btn text" onClick={() => setCancelling(null)}>Keep it</button><button className="btn" onClick={() => void cancel(cancelling)}>Take back</button></>}
        >
          {label(cancelling.item)}
        </Dialog>
      )}
    </>
  )
}

/**
 * Your requests for music, and for admins everyone's, to answer: "Added" once the music is in the library
 * (everyone who asked is told), or "Can't find" with an optional reason.
 */
export function RequestsScreen() {
  const nav = useNav()
  // Opened straight from its address, the page can be quicker than signing in: wait for that.
  const user = useSession((s) => s.social?.user.id)
  const admin = useQuery({ queryKey: ['admin-access', user], queryFn: () => social.adminAccess(), enabled: user != null, staleTime: Infinity }).data?.isAdmin ?? false
  const [everyone, setEveryone] = useState(false)
  const list = useQuery({ queryKey: ['requests', everyone], queryFn: () => (everyone ? social.allMusicRequests() : social.musicRequests()), enabled: user != null })
  const client = useQueryClient()
  const [busy, setBusy] = useState<number | null>(null)
  const [declining, setDeclining] = useState<MusicRequest | null>(null)
  const [note, setNote] = useState('')

  const act = async (r: MusicRequest, action: () => Promise<MusicRequest | null>) => {
    setBusy(r.id)
    try {
      const updated = await action()
      client.setQueryData(['requests', everyone], (old: MusicRequest[] | undefined) =>
        updated ? old?.map((x) => (x.id === r.id ? updated : x)) : old?.filter((x) => x.id !== r.id),
      )
    } catch (e) {
      toast((e as Error).message || "That didn't work", true)
    }
    setBusy(null)
  }

  return (
    <div className="page">
      <ScreenHeader title="Requests" onBack={nav.back} actions={<IconButton icon="refresh" label="Refresh" onClick={() => void list.refetch()} />} />
      {admin && (
        <div className="chips" style={{ padding: '0 16px 8px' }}>
          {[false, true].map((v) => (
            <button key={String(v)} className={`chip${everyone === v ? ' selected' : ''}`} onClick={() => setEveryone(v)}>
              {v ? "Everyone's" : 'Mine'}
            </button>
          ))}
        </div>
      )}
      {list.error && !list.data ? (
        <ErrorBox message={list.error.message || "Couldn't load requests"} onRetry={() => void list.refetch()} />
      ) : !list.data ? (
        <Loading />
      ) : list.data.length === 0 ? (
        <div className="body-medium muted" style={{ padding: 16 }}>
          {everyone ? 'No requests right now.' : "Nothing requested yet. Search for a song or album; ones that aren't in the library have a Request button."}
        </div>
      ) : (
        <div style={{ padding: '0 8px' }}>
          {list.data.map((r) => (
            <RequestRow
              key={r.id}
              r={r}
              showAskers={everyone}
              busy={busy === r.id}
              onOpen={r.status === 'done' && r.albumId ? () => nav.openAlbum(r.albumId!) : undefined}
              onCancel={!everyone && isOpen(r) ? () => void act(r, async () => { await social.cancelMusicRequest(r.id); return null }) : undefined}
              onDone={everyone && isOpen(r) ? () => void act(r, () => social.completeMusicRequest(r.id)) : undefined}
              onDecline={everyone && isOpen(r) ? () => { setNote(''); setDeclining(r) } : undefined}
            />
          ))}
        </div>
      )}
      {declining && (
        <Dialog
          title="Can't find it?"
          onClose={() => setDeclining(null)}
          actions={
            <>
              <button className="btn text" onClick={() => setDeclining(null)}>Cancel</button>
              <button className="btn" onClick={() => { const r = declining; setDeclining(null); void act(r, () => social.declineMusicRequest(r.id, note.trim() || null)) }}>Tell them</button>
            </>
          }
        >
          <p style={{ marginTop: 0 }}>{declining.askedBy.join(', ')} will be told that {label(declining.item)} couldn't be found.</p>
          <div className="field">
            <input value={note} maxLength={200} placeholder="Why (optional)" onChange={(e) => setNote(e.target.value)} aria-label="Why (optional)" />
          </div>
        </Dialog>
      )}
    </div>
  )
}

function RequestRow({ r, showAskers, busy, onOpen, onCancel, onDone, onDecline }: {
  r: MusicRequest
  showAskers: boolean
  busy: boolean
  onOpen?: () => void
  onCancel?: () => void
  onDone?: () => void
  onDecline?: () => void
}) {
  const item = r.item
  const movie = item.kind === 'movie'
  const [status, color] =
    r.status === 'done' ? ['In the library · click to open', 'var(--primary)']
      : r.status === 'declined' ? [`Couldn't get it${r.note ? `: ${r.note}` : ''}`, 'var(--error)']
        : [`Waiting · asked ${ago(r.requestedAt)}`, 'var(--on-surface-variant)']
  return (
    <div className="list-row" onClick={onOpen} style={{ display: 'block', padding: '8px', cursor: onOpen ? 'pointer' : 'default' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
        <Artwork item={item} size={48} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="body-large ellipsis">{movie ? item.movie : item.title}</div>
          <div className="body-small muted ellipsis">
            {(movie ? ['Whole movie', item.year, item.trackCount > 0 ? `${item.trackCount} songs` : null] : [item.movie, item.year, item.artist]).filter(Boolean).join(' · ')}
          </div>
          <div className="body-small" style={{ color }}>{status}</div>
          {showAskers && r.askedBy.length > 0 && <div className="body-small muted ellipsis">Asked by {r.askedBy.join(', ')}</div>}
        </div>
        {onCancel && <IconButton icon="close" label="Take back the request" disabled={busy} onClick={(e) => { e.stopPropagation(); onCancel() }} />}
      </div>
      {(onDone || onDecline) && (
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 6 }}>
          {onDecline && <button className="btn text" style={{ flex: 'none' }} disabled={busy} onClick={onDecline}>Can't find</button>}
          {onDone && <button className="btn" style={{ flex: 'none' }} disabled={busy} onClick={onDone}>Added</button>}
        </div>
      )}
    </div>
  )
}

function ago(millis: number): string {
  const minutes = Math.floor((Date.now() - millis) / 60_000)
  if (minutes < 1) return 'just now'
  if (minutes < 60) return `${minutes} min ago`
  if (minutes < 24 * 60) return `${Math.floor(minutes / 60)} h ago`
  if (minutes < 2 * 24 * 60) return 'yesterday'
  return `${Math.floor(minutes / (24 * 60))} days ago`
}
