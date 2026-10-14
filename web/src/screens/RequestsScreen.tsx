import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import type { CatalogHit, CatalogItem, MovieHit, MusicRequest } from '../api/types'
import { social } from '../api/social'
import { pause as pausePlayer } from '../player/player'
import { me } from '../social/social'
import { useSession } from '../state/session'
import { Cover, ScreenHeader, SectionTitle } from '../ui/components'
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
  // Admins: how many downloads wait for a check, on the "Everyone's" chip.
  const all = useQuery({ queryKey: ['requests', true], queryFn: () => social.allMusicRequests(), enabled: user != null && admin })
  const toCheck = (all.data ?? []).filter((r) => r.stage === 'review' && !r.review?.coveredBy)
  const [rejecting, setRejecting] = useState<MusicRequest | null>(null)
  const client = useQueryClient()
  const [busy, setBusy] = useState<number | null>(null)
  const [declining, setDeclining] = useState<MusicRequest | null>(null)
  const [note, setNote] = useState('')
  const [show, setShow] = useState<Show>('all')
  /** Picking an album: to close (or relink) a request with, or as a download's destination. */
  const [matching, setMatching] = useState<{ r: MusicRequest; forDownload: boolean } | null>(null)

  const act = async (r: MusicRequest, action: () => Promise<MusicRequest | null>) => {
    setBusy(r.id)
    try {
      const updated = await action()
      client.setQueryData(['requests', everyone], (old: MusicRequest[] | undefined) =>
        updated ? old?.map((x) => (x.id === r.id ? updated : x)) : old?.filter((x) => x.id !== r.id),
      )
      // Approving or rejecting an album decides the songs that come with it too.
      if (updated?.stage) void client.invalidateQueries({ queryKey: ['requests', true] })
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
              {v && toCheck.length > 0 && <span className="badge" style={{ position: 'static', marginLeft: 6 }}>{toCheck.length}</span>}
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
          <div className="chips" style={{ padding: '0 8px 8px' }}>
            {SHOWS.map(([key, name]) => (
              <button key={key} className={`chip${show === key ? ' selected' : ''}`} onClick={() => setShow(key)}>
                {name} <span className="muted" style={{ marginLeft: 4 }}>{list.data.filter((r) => shows(key, r)).length}</span>
              </button>
            ))}
          </div>
          {everyone && (show === 'all' || show === 'open') && list.data.some((r) => r.stage === 'review' && r.review && !r.review.coveredBy) && (
            <>
              <SectionTitle>Ready to check</SectionTitle>
              <div className="body-small muted" style={{ padding: '0 8px 8px', marginTop: -6 }}>
                Downloaded and tagged. Listen, then approve to move it into the library, or reject to delete it.
              </div>
              {list.data.filter((r) => r.stage === 'review' && r.review && !r.review.coveredBy).map((r) => (
                <ReviewCard
                  key={r.id}
                  r={r}
                  comesWith={list.data.filter((x) => x.review?.coveredBy === r.id)}
                  busy={busy === r.id}
                  onApprove={() => void act(r, () => social.approveMusicRequest(r.id))}
                  onReject={() => { setNote(''); setRejecting(r) }}
                  onChangeDestination={() => setMatching({ r, forDownload: true })}
                />
              ))}
              <SectionTitle>{SHOWS.find(([k]) => k === show)![1]} requests</SectionTitle>
            </>
          )}
          {list.data.filter((r) => shows(show, r) && !(everyone && r.stage === 'review' && !r.review?.coveredBy)).map((r) => (
            <RequestRow
              key={r.id}
              r={r}
              showAskers={everyone}
              busy={busy === r.id}
              onOpen={r.status === 'done' && r.albumId ? () => nav.openAlbum(r.albumId!) : undefined}
              onCancel={!everyone && isOpen(r) && !r.stage ? () => void act(r, async () => { await social.cancelMusicRequest(r.id); return null }) : undefined}
              onDone={everyone && isOpen(r) && !r.stage ? () => void act(r, () => social.completeMusicRequest(r.id)) : undefined}
              onDecline={everyone && isOpen(r) && r.stage !== 'review' ? () => { setNote(''); setDeclining(r) } : undefined}
              onMatch={everyone && (isOpen(r) || r.status === 'done') && r.stage !== 'review' ? () => setMatching({ r, forDownload: false }) : undefined}
              onAgain={r.status === 'declined' ? () => void act(r, () => social.requestMusic(r.item.id)) : undefined}
            />
          ))}
          {list.data.filter((r) => shows(show, r)).length === 0 && (
            <div className="body-medium muted" style={{ padding: '8px 8px 16px' }}>Nothing here.</div>
          )}
        </div>
      )}
      {matching && (
        <AlbumPicker
          r={matching.r}
          forDownload={matching.forDownload}
          onClose={() => setMatching(null)}
          onPick={(album) => {
            const { r, forDownload } = matching
            setMatching(null)
            void act(r, () => (forDownload ? social.setReviewDestination(r.id, album?.id ?? null) : social.completeMusicRequest(r.id, album!.id)))
          }}
        />
      )}
      {rejecting && (
        <Dialog
          title="Reject this download?"
          onClose={() => setRejecting(null)}
          actions={
            <>
              <button className="btn text" onClick={() => setRejecting(null)}>Cancel</button>
              <button className="btn" onClick={() => { const r = rejecting; setRejecting(null); void act(r, () => social.rejectMusicRequest(r.id, note.trim() || null)) }}>Reject</button>
            </>
          }
        >
          <p style={{ marginTop: 0 }}>The download is deleted and the request waits again, so it can be matched again. Nobody is told.</p>
          <div className="field">
            <input value={note} maxLength={200} placeholder="What was wrong (optional)" onChange={(e) => setNote(e.target.value)} aria-label="What was wrong (optional)" />
          </div>
        </Dialog>
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

type Show = 'all' | 'open' | 'closed' | 'notfound'
const SHOWS: [Show, string][] = [['all', 'All'], ['open', 'Open'], ['closed', 'Closed'], ['notfound', 'Not found']]
const shows = (show: Show, r: MusicRequest) =>
  show === 'all' || (show === 'open' && r.status === 'open') || (show === 'closed' && r.status === 'done') || (show === 'notfound' && r.status === 'declined')

/**
 * Picks an album from the library: to close a request with (or point a closed one at the right album), or as where
 * a download goes ([forDownload]: "New album instead" is offered too).
 */
function AlbumPicker({ r, forDownload, onPick, onClose }: {
  r: MusicRequest
  forDownload: boolean
  onPick: (album: MovieHit | null) => void
  onClose: () => void
}) {
  const [q, setQ] = useState(r.review?.album ?? r.item.movie)
  const found = useQuery({ queryKey: ['album-pick', q.trim()], queryFn: () => social.search(q.trim()), enabled: q.trim().length >= 2, staleTime: 60_000 })
  const albums = found.data?.movies ?? []
  return (
    <Dialog
      title={forDownload ? 'Where does it go?' : r.status === 'done' ? 'Point it at another album' : 'Which album is it?'}
      onClose={onClose}
      actions={
        <>
          {forDownload && <button className="btn text" onClick={() => onPick(null)}>New album instead</button>}
          <button className="btn text" onClick={onClose}>Cancel</button>
        </>
      }
    >
      <p className="body-small muted" style={{ marginTop: 0 }}>
        {forDownload
          ? 'The download joins the album you pick, with its tags, when you approve it.'
          : `Closes ${label(r.item)} with the album you pick${r.status === 'done' ? '' : '; the people who asked are told'}.`}
      </p>
      <div className="field">
        <input value={q} autoFocus onChange={(e) => setQ(e.target.value)} placeholder="Search the library" aria-label="Search the library" />
      </div>
      <div style={{ maxHeight: 320, overflowY: 'auto', marginTop: 8 }}>
        {found.isFetching && albums.length === 0 && <Loading />}
        {!found.isFetching && q.trim().length >= 2 && albums.length === 0 && <div className="body-small muted">No albums found.</div>}
        {albums.map((a) => (
          <div key={a.id} className="list-row" onClick={() => onPick(a)} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '6px 4px', cursor: 'pointer' }}>
            <Cover coverArt={a.coverArt} size={150} corner={4} style={{ width: 44, height: 44 }} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="body-medium ellipsis">{a.name}</div>
              <div className="body-small muted ellipsis">{[a.year, a.composer, `${a.songCount} songs`].filter(Boolean).join(' · ')}</div>
            </div>
          </div>
        ))}
      </div>
    </Dialog>
  )
}

function RequestRow({ r, showAskers, busy, onOpen, onCancel, onDone, onDecline, onMatch, onAgain }: {
  r: MusicRequest
  showAskers: boolean
  busy: boolean
  onOpen?: () => void
  onCancel?: () => void
  onDone?: () => void
  onDecline?: () => void
  onMatch?: () => void
  onAgain?: () => void
}) {
  const item = r.item
  const movie = item.kind === 'movie'
  const [status, color] =
    r.status === 'done' ? ['In the library · click to open', 'var(--primary)']
      : r.status === 'declined' ? [`Couldn't get it${r.note ? `: ${r.note}` : ''}`, 'var(--error)']
        : r.stage === 'approved' ? [showAskers ? 'Approved · moving into the library' : 'Found · being added', 'var(--primary)']
          : r.stage === 'review' ? [r.review?.coveredBy ? `Comes with request ${r.review.coveredBy}` : 'Found · being added', 'var(--primary)']
            : r.stage === 'rejected' ? ['Rejected · removing the download', 'var(--on-surface-variant)']
              : [`Waiting · asked ${ago(r.requestedAt)}${showAskers && r.reviewNote ? ` · last download rejected: ${r.reviewNote}` : ''}`, 'var(--on-surface-variant)']
  return (
    <div className="list-row" onClick={onOpen} style={{ display: 'block', padding: '8px', cursor: onOpen ? 'pointer' : 'default' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
        <Artwork item={item} size={48} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
            <div className="body-large ellipsis">{movie ? item.movie : item.title}</div>
            <span className={`kind-tag${movie ? ' album' : ''}`}>{movie ? 'Album' : 'Song'}</span>
          </div>
          <div className="body-small muted ellipsis">
            {(movie ? [item.year, item.trackCount > 0 ? `${item.trackCount} songs` : null] : [item.movie, item.year, item.artist]).filter(Boolean).join(' · ')}
          </div>
          <div className="body-small" style={{ color }}>{status}</div>
          {showAskers && r.askedBy.length > 0 && <div className="body-small muted ellipsis">Asked by {r.askedBy.join(', ')}</div>}
        </div>
        {onCancel && <IconButton icon="close" label="Take back the request" disabled={busy} onClick={(e) => { e.stopPropagation(); onCancel() }} />}
      </div>
      {(onDone || onDecline || onMatch || onAgain) && (
        <div style={{ display: 'flex', justifyContent: 'flex-end', flexWrap: 'wrap', gap: 8, marginTop: 6 }} onClick={(e) => e.stopPropagation()}>
          {onAgain && <button className="btn tonal" style={{ flex: 'none' }} disabled={busy} onClick={onAgain}>Request again</button>}
          {onDecline && <button className="btn text" style={{ flex: 'none' }} disabled={busy} onClick={onDecline}>Can't find</button>}
          {onMatch && <button className={`btn ${r.status === 'done' ? 'text' : 'tonal'}`} style={{ flex: 'none' }} disabled={busy} onClick={onMatch}>{r.status === 'done' ? 'Change album' : 'Match album'}</button>}
          {onDone && <button className="btn" style={{ flex: 'none' }} disabled={busy} onClick={onDone}>Added</button>}
        </div>
      )}
    </div>
  )
}

/** One shared player for listening to downloads, so starting one track stops the other (and the main player). */
let reviewAudio: HTMLAudioElement | null = null

/** A fulfilled request's download: what it is, where it goes, each track to listen to, then approve or reject. */
function ReviewCard({ r, comesWith, busy, onApprove, onReject, onChangeDestination }: {
  r: MusicRequest
  comesWith: MusicRequest[]
  busy: boolean
  onApprove: () => void
  onReject: () => void
  onChangeDestination: () => void
}) {
  const review = r.review!
  const [playing, setPlaying] = useState<number | null>(null)
  const mine = useRef(false)
  useEffect(() => () => { if (mine.current) reviewAudio?.pause() }, [])
  const play = (n: number) => {
    if (!reviewAudio) reviewAudio = new Audio()
    const a = reviewAudio
    if (playing === n && !a.paused) {
      a.pause()
      setPlaying(null)
      return
    }
    pausePlayer()
    a.onended = a.onpause = () => setPlaying(null)
    a.onerror = () => { setPlaying(null); toast("Couldn't play it") }
    a.src = social.reviewStreamUrl(r.id, n)
    mine.current = true
    void a.play().then(() => {
      // The other card's handlers are replaced: this card now owns the player.
      a.onended = a.onpause = () => setPlaying(null)
      setPlaying(n)
    }).catch(() => setPlaying(null))
  }
  const where = review.destination === 'existing'
    ? `Joins ${review.destinationFolder ?? 'its album'} in the library`
    : `New album: ${review.destinationFolder ?? review.album}`
  return (
    <div className="review-card">
      <div style={{ display: 'flex', gap: 14 }}>
        {review.coverUrl ? (
          <img src={review.coverUrl} alt="" style={{ width: 72, height: 72, objectFit: 'cover', borderRadius: 6, flexShrink: 0, background: 'var(--surface-container-high)' }} />
        ) : (
          <div style={{ width: 72, height: 72, borderRadius: 6, background: 'var(--surface-container-high)', flexShrink: 0 }} />
        )}
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
            <div className="title-medium ellipsis">{review.album}{review.year ? ` (${review.year})` : ''}</div>
            <span className={`kind-tag${r.item.kind === 'movie' ? ' album' : ''}`}>{r.item.kind === 'movie' ? 'Album' : 'Song'}</span>
          </div>
          <div className="body-small muted ellipsis">{[review.composer && `Music ${review.composer}`, review.lyricists && `Lyrics ${review.lyricists}`].filter(Boolean).join(' · ')}</div>
          <div className="body-small muted ellipsis">Asked for {label(r.item)}{r.item.year ? ` · ${r.item.year}` : ''} by {r.askedBy.join(', ')}</div>
          <div className="body-small primary-text">
            {where}{' '}
            <button className="link-button" disabled={busy} onClick={onChangeDestination}>Change</button>
          </div>
          {review.tracks.some((t) => t.score) && review.scoreFolder && (
            <div className="body-small muted ellipsis">Background score → {review.scoreFolder}</div>
          )}
          {(review.skipped?.length ?? 0) > 0 && (
            <div className="body-small muted">Already in the library, not downloaded: {review.skipped!.join(', ')}</div>
          )}
        </div>
      </div>
      {review.notes.length > 0 && (
        <ul className="review-notes">
          {review.notes.map((n, i) => <li key={i}>{n}</li>)}
        </ul>
      )}
      <div style={{ marginTop: 8 }}>
        {review.tracks.map((t) => (
          <div key={t.n} className="review-track">
            <IconButton icon={playing === t.n ? 'pause' : 'play_arrow'} label={playing === t.n ? 'Pause' : `Play ${t.title}`} onClick={() => play(t.n)} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="body-medium ellipsis">{t.n}. {t.title}</div>
              <div className="body-small muted ellipsis">
                {[t.singers, t.durationMs ? clock(t.durationMs) : null, t.bitrate ? `${t.bitrate} kbps` : null].filter(Boolean).join(' · ')}
              </div>
            </div>
            {t.score && <span className="kind-tag">Score</span>}
            <span className={`kind-tag${t.lyrics === 'none' ? '' : ' album'}`}>{t.lyrics === 'synced' ? 'Synced lyrics' : t.lyrics === 'plain' ? 'Lyrics' : 'No lyrics'}</span>
          </div>
        ))}
      </div>
      {comesWith.length > 0 && (
        <div className="body-small muted" style={{ marginTop: 6 }}>
          Also closes {comesWith.map((c) => `“${c.item.title}” for ${c.askedBy.join(', ')}`).join('; ')}
        </div>
      )}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 10 }}>
        {review.source && (
          <a className="body-small" href={review.source} target="_blank" rel="noreferrer" style={{ marginRight: 'auto' }}>
            Matched page{review.score != null ? ` · score ${Math.round(review.score)}` : ''}
          </a>
        )}
        <button className="btn text" style={{ flex: 'none', marginLeft: review.source ? undefined : 'auto' }} disabled={busy} onClick={onReject}>Reject</button>
        <button className="btn" style={{ flex: 'none' }} disabled={busy} onClick={onApprove}>Approve</button>
      </div>
    </div>
  )
}

function clock(ms: number) {
  const s = Math.round(ms / 1000)
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

function ago(millis: number): string {
  const minutes = Math.floor((Date.now() - millis) / 60_000)
  if (minutes < 1) return 'just now'
  if (minutes < 60) return `${minutes} min ago`
  if (minutes < 24 * 60) return `${Math.floor(minutes / 60)} h ago`
  if (minutes < 2 * 24 * 60) return 'yesterday'
  return `${Math.floor(minutes / (24 * 60))} days ago`
}
