import { useEffect, useRef, useState } from 'react'
import type { ImportItem, ImportPreview, ImportRow, MixSong } from '../api/types'
import { catalog } from '../api/catalog'
import { social, SocialError } from '../api/social'
import { pause as pausePlayer } from '../player/player'
import { useMyPlaylists } from '../state/library'
import { playlistChanged } from '../state/queries'
import { Cover, formatDuration, ScreenHeader } from '../ui/components'
import { Checkbox, Dialog, Icon, IconButton, Menu, Spinner, toast } from '../ui/kit'
import { useNav } from '../ui/nav'

// Importing a playlist (ui/library/ImportScreen.kt in the app): paste a link to a Spotify, Apple Music or YouTube
// playlist, or choose an exported file (CSV, M3U, Apple Music XML) or paste a list. The server reads the songs and
// finds them in the library; here you check the matches, pick where it wasn't sure, request what's missing, and
// save it as a playlist of yours: without the missing songs, or with them greyed out in their place to request.

const SOURCES: Record<string, string> = { spotify: 'Spotify', apple: 'Apple Music', youtube: 'YouTube', file: 'your file' }

export function ImportScreen() {
  const nav = useNav()
  const [link, setLink] = useState('')
  const [text, setText] = useState('')
  const [fileName, setFileName] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [preview, setPreview] = useState<ImportPreview | null>(null)
  const file = useRef<HTMLInputElement>(null)

  const read = async (o: { url?: string; text?: string; name?: string }) => {
    setBusy(true)
    try {
      setPreview(await social.importPreview(o))
    } catch (e) {
      toast((e as Error).message || "Couldn't read that playlist", true)
    }
    setBusy(false)
  }
  const pickFile = async (f: File | undefined) => {
    if (!f) return
    if (f.size > 12 * 1024 * 1024) return toast('That file is too big (12 MB at most)')
    setFileName(f.name)
    await read({ text: await f.text(), name: f.name.replace(/\.[^.]+$/, '') })
  }

  if (preview) return <ImportReview preview={preview} onBack={() => setPreview(null)} onSaved={(id) => nav.openPlaylist(id)} />

  return (
    <div className="page">
      <ScreenHeader title="Import a playlist" onBack={nav.back} />
      <div style={{ maxWidth: 680, padding: '0 16px', display: 'flex', flexDirection: 'column', gap: 16 }}>
        <section className="import-card">
          <div className="title-medium">From a link</div>
          <div className="body-medium muted">A public playlist on Spotify, Apple Music, YouTube or YouTube Music (Share › Copy link).</div>
          <div style={{ display: 'flex', gap: 8 }}>
            <div className="search-box" style={{ flex: 1 }}>
              <Icon name="link" />
              <input
                value={link} placeholder="https://open.spotify.com/playlist/…" aria-label="Playlist link" inputMode="url"
                onChange={(e) => setLink(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && link.trim() && !busy && void read({ url: link.trim() })}
              />
            </div>
            <button className="btn" disabled={!link.trim() || busy} onClick={() => void read({ url: link.trim() })}>Find songs</button>
          </div>
        </section>

        <section className="import-card">
          <div className="title-medium">From a file</div>
          <div className="body-medium muted">
            A CSV from <a href="https://exportify.app" target="_blank" rel="noreferrer">Exportify</a> (all of a Spotify playlist), TuneMyMusic or Soundiiz, an M3U playlist, or an Apple Music export (Music app › File › Library › Export Playlist).
          </div>
          <div>
            <button className="btn tonal" disabled={busy} onClick={() => file.current?.click()}><Icon name="upload_file" size={18} />Choose a file</button>
            {fileName && <span className="body-small muted" style={{ marginLeft: 12 }}>{fileName}</span>}
          </div>
          <input ref={file} type="file" hidden accept=".csv,.tsv,.txt,.m3u,.m3u8,.xml" onChange={(e) => { void pickFile(e.target.files?.[0]); e.target.value = '' }} />
        </section>

        <section className="import-card">
          <div className="title-medium">Or paste a list</div>
          <div className="body-medium muted">One song a line, like "Kanave Kanave - Anirudh".</div>
          <div className="field">
            <textarea value={text} rows={5} placeholder={'Kanave Kanave - Anirudh\nMunbe Vaa - A.R. Rahman'} aria-label="Songs, one a line" onChange={(e) => setText(e.target.value)} />
          </div>
          <div><button className="btn tonal" disabled={!text.trim() || busy} onClick={() => void read({ text, name: 'Imported playlist' })}>Find songs</button></div>
        </section>
        {busy && <div style={{ display: 'flex', alignItems: 'center', gap: 12 }} className="body-medium muted"><Spinner size={20} />Reading the playlist and finding its songs…</div>}
      </div>
    </div>
  )
}

/** Checking the matches before saving. */
function ImportReview({ preview, onBack, onSaved }: { preview: ImportPreview; onBack: () => void; onSaved: (playlistId: string) => void }) {
  const [name, setName] = useState(preview.name)
  // Per row: the song chosen for it (the match, a choice, or none), and whether it goes in.
  const [chosen, setChosen] = useState<(MixSong | undefined)[]>(() => preview.rows.map((r) => r.match))
  const [included, setIncluded] = useState<boolean[]>(() => preview.rows.map((r) => !!r.match))
  // Per row: asking for it, or the request made.
  const [requested, setRequested] = useState<Record<number, 'busy' | number>>({})
  // Songs the library doesn't have: left out, or kept in their place (greyed out, to request).
  const [keepMissing, setKeepMissing] = useState(false)
  const [saving, setSaving] = useState(false)
  // Asking for every missing song in turn: how far it got.
  const [requestingAll, setRequestingAll] = useState<{ done: number; total: number } | null>(null)
  // Going through the matches it wasn't sure of, one at a time.
  const [reviewing, setReviewing] = useState(false)
  const found = preview.rows.filter((r) => r.match).length
  const unsure = preview.rows.flatMap((r, i) => ((r.match && !r.sure) || (!r.match && r.choices.length) ? [i] : []))
  const toRequest = preview.rows.flatMap((_, i) => (!chosen[i] && requested[i] === undefined ? [i] : []))
  const songs = chosen.filter((s, i) => s && included[i]) as MixSong[]
  const missing = preview.rows.filter((_, i) => !chosen[i]).length
  const items: ImportItem[] = preview.rows.flatMap((r, i): ImportItem[] => {
    const s = chosen[i]
    if (s) return included[i] ? [{ songId: s.id }] : []
    const asked = requested[i]
    return keepMissing ? [{ track: r.track, requestId: typeof asked === 'number' ? asked : undefined }] : []
  })

  const choose = (i: number, s: MixSong | undefined) => {
    setChosen(chosen.map((x, j) => (j === i ? s : x)))
    setIncluded(included.map((x, j) => (j === i ? !!s : x)))
  }
  const request = async (i: number) => {
    setRequested({ ...requested, [i]: 'busy' })
    try {
      const asked = await social.importRequest(preview.rows[i].track)
      setRequested((r) => ({ ...r, [i]: asked.id }))
    } catch (e) {
      setRequested((r) => { const n = { ...r }; delete n[i]; return n })
      toast((e as Error).message || "Couldn't request it", true)
    }
  }
  const requestAll = async () => {
    const list = toRequest
    setRequestingAll({ done: 0, total: list.length })
    let asked = 0
    let failed = 0
    for (const i of list) {
      setRequested((r) => ({ ...r, [i]: 'busy' }))
      try {
        const made = await social.importRequest(preview.rows[i].track)
        setRequested((r) => ({ ...r, [i]: made.id }))
        asked++
      } catch (e) {
        setRequested((r) => { const n = { ...r }; delete n[i]; return n })
        failed++
        // Too many requests waiting: the rest would fail the same way.
        if (e instanceof SocialError && e.code === 429) {
          toast(e.message, true)
          break
        }
      }
      setRequestingAll({ done: asked + failed, total: list.length })
    }
    setRequestingAll(null)
    if (asked) toast(`Requested ${asked} ${asked === 1 ? 'song' : 'songs'}${failed ? `. ${failed} couldn't be requested (not in the catalog, or already asked for)` : ''}`, failed > 0)
    else if (failed) toast("Couldn't request them. They may not be in the catalog", true)
  }
  const save = async () => {
    setSaving(true)
    try {
      const r = await social.importCreate(name.trim() || preview.name, items)
      playlistChanged()
      useMyPlaylists.getState().refresh()
      toast(`Saved "${name.trim() || preview.name}" with ${r.songCount} songs${r.missingCount ? `, and ${r.missingCount} to request` : ''}`)
      onSaved(r.playlistId)
    } catch (e) {
      toast((e as Error).message || "Couldn't save it", true)
      setSaving(false)
    }
  }

  return (
    <div className="page">
      <ScreenHeader title="Import a playlist" onBack={onBack} />
      <div style={{ padding: '0 16px 12px', maxWidth: 900 }}>
        <div className="field" style={{ marginBottom: 8 }}>
          <label>Playlist name</label>
          <input value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
        </div>
        <div className="body-medium">
          <b>{found} of {preview.rows.length}</b> songs from {SOURCES[preview.source] ?? preview.source} are in the library.
          {found < preview.rows.length && ' The others can be requested.'}
        </div>
        {preview.note && <div className="body-small muted" style={{ marginTop: 4 }}>{preview.note}</div>}
        {(unsure.length > 0 || toRequest.length > 0 || requestingAll) && (
          <div style={{ display: 'flex', gap: 8, marginTop: 12, flexWrap: 'wrap' }}>
            {unsure.length > 0 && (
              <button className="btn tonal" onClick={() => setReviewing(true)}><Icon name="hearing" size={18} />Check {unsure.length} unsure {unsure.length === 1 ? 'match' : 'matches'}</button>
            )}
            {requestingAll ? (
              <span className="body-medium muted" style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}><Spinner size={18} />Requesting {requestingAll.done + 1} of {requestingAll.total}…</span>
            ) : toRequest.length > 0 && (
              <button className="btn tonal" onClick={() => void requestAll()}><Icon name="library_add" size={18} />Request all missing ({toRequest.length})</button>
            )}
          </div>
        )}
        {missing > 0 && (
          <div style={{ marginTop: 12 }}>
            <div className="body-medium">{missing === 1 ? 'The song that isn\'t in the library:' : `The ${missing} songs that aren't in the library:`}</div>
            <div className="chips" style={{ display: 'flex', gap: 8, marginTop: 6, flexWrap: 'wrap' }}>
              <button className={`chip${keepMissing ? '' : ' selected'}`} onClick={() => setKeepMissing(false)}>Leave out</button>
              <button className={`chip${keepMissing ? ' selected' : ''}`} onClick={() => setKeepMissing(true)}>Keep, greyed out</button>
            </div>
            <div className="body-small muted" style={{ marginTop: 4 }}>
              {keepMissing
                ? 'They stay in their place in the playlist, greyed out, with Request. When one arrives in the library it plays like the rest.'
                : 'The playlist has only the songs in the library.'}
            </div>
          </div>
        )}
        <div style={{ display: 'flex', gap: 8, marginTop: 12, flexWrap: 'wrap' }}>
          <button className="btn" disabled={!items.length || saving} onClick={() => void save()}>
            {saving ? 'Saving…' : `Save playlist (${songs.length} songs${items.length > songs.length ? ` + ${items.length - songs.length} to request` : ''})`}
          </button>
          <button className="btn text" onClick={onBack}>Start over</button>
        </div>
      </div>
      {reviewing && (
        <UnsureReview
          rows={preview.rows}
          indices={unsure}
          chosen={chosen}
          source={SOURCES[preview.source] ?? preview.source}
          onDecide={choose}
          onClose={() => setReviewing(false)}
        />
      )}
      <div style={{ padding: '0 8px 24px' }}>
        {preview.rows.map((row, i) => (
          <ImportRowView
            key={i} row={row} chosen={chosen[i]} included={included[i]} request={requested[i]}
            onInclude={(v) => setIncluded(included.map((x, j) => (j === i ? v : x)))}
            onChoose={(s) => choose(i, s)}
            onRequest={() => void request(i)}
          />
        ))}
      </div>
    </div>
  )
}

function ImportRowView({ row, chosen, included, request, onInclude, onChoose, onRequest }: {
  row: ImportRow
  chosen?: MixSong
  included: boolean
  request?: 'busy' | number
  onInclude: (v: boolean) => void
  onChoose: (s: MixSong | undefined) => void
  onRequest: () => void
}) {
  const [menu, setMenu] = useState<DOMRect | null>(null)
  const t = row.track
  const unsure = !!chosen && !row.sure && chosen.id === row.match?.id
  return (
    <div className="import-row">
      <div className="from">
        <div className="body-medium ellipsis">{t.title}</div>
        <div className="body-small muted ellipsis">{[t.artists.join(', '), t.durationMs ? formatDuration(t.durationMs / 1000) : null].filter(Boolean).join(' · ')}</div>
      </div>
      <Icon name="arrow_forward" size={18} className="muted arrow" />
      <div className="to">
        {chosen ? (
          <>
            <Checkbox checked={included} onChange={onInclude} />
            <Cover coverArt={chosen.coverArt} size={150} corner={4} style={{ width: 40, height: 40, flexShrink: 0 }} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="body-medium ellipsis">{chosen.title}</div>
              <div className="body-small muted ellipsis">{[chosen.album, chosen.artist].filter(Boolean).join(' · ')}</div>
              {unsure && <div className="body-small primary-text">Is this the one?</div>}
            </div>
          </>
        ) : (
          <div style={{ flex: 1, minWidth: 0 }} className="body-small muted">
            {row.choices.length ? 'Not sure which song this is' : 'Not in the library'}
          </div>
        )}
        {row.choices.length > (chosen ? 1 : 0) && (
          <button className="btn text small" onClick={(e) => setMenu((e.currentTarget as HTMLElement).getBoundingClientRect())}>{chosen ? 'Change' : 'Pick'}</button>
        )}
        {!chosen && (
          typeof request === 'number' ? <span className="body-small primary-text" style={{ whiteSpace: 'nowrap' }}><Icon name="check" size={16} /> Requested</span>
            : <button className="btn tonal small" disabled={request === 'busy'} onClick={onRequest}>Request</button>
        )}
      </div>
      {menu && (
        <Menu
          anchor={menu}
          onClose={() => setMenu(null)}
          items={[
            ...row.choices.map((c) => ({ label: `${c.title} · ${c.album ?? ''}${c.artist ? ` · ${c.artist}` : ''}`.slice(0, 80), onClick: () => onChoose(c) })),
            { label: 'None of these', onClick: () => onChoose(undefined) },
          ]}
        />
      )}
    </div>
  )
}

/** The preview player, apart from the music player so the queue is left alone. */
let previewAudio: HTMLAudioElement | null = null

/**
 * The matches it wasn't sure of, one at a time: the song as the other service has it next to the library's
 * best guess, which can be played for a moment. Same song takes it; Not this one shows the next guess (and
 * after the last, leaves the song out); Skip keeps it as it is.
 */
function UnsureReview({ rows, indices, chosen, source, onDecide, onClose }: {
  rows: ImportRow[]
  indices: number[]
  chosen: (MixSong | undefined)[]
  source: string
  onDecide: (i: number, s: MixSong | undefined) => void
  onClose: () => void
}) {
  const [at, setAt] = useState(0)
  const [guess, setGuess] = useState(0)
  const [playing, setPlaying] = useState(false)
  useEffect(() => () => previewAudio?.pause(), [])
  const i = indices[at]
  const row = rows[i]
  // The one chosen now first, then the others it could be.
  const guesses = row ? [chosen[i] ?? row.match, ...row.choices].filter((s, n, all): s is MixSong => !!s && all.findIndex((x) => x?.id === s.id) === n) : []
  const song = guesses[guess]

  const stop = () => {
    previewAudio?.pause()
    setPlaying(false)
  }
  const next = () => {
    stop()
    setGuess(0)
    if (at + 1 >= indices.length) {
      toast('All checked')
      onClose()
    } else setAt(at + 1)
  }
  const play = () => {
    if (!song) return
    if (!previewAudio) previewAudio = new Audio()
    const a = previewAudio
    if (playing) return stop()
    pausePlayer()
    a.src = catalog.streamUrl(song.id)
    // A bit into the song, where it's more itself than its first notes.
    a.onloadedmetadata = () => { if (a.duration > 60) a.currentTime = Math.min(a.duration * 0.3, 60) }
    a.onended = () => setPlaying(false)
    a.onerror = () => { setPlaying(false); toast("Couldn't play it") }
    void a.play().then(() => setPlaying(true)).catch(() => setPlaying(false))
  }
  if (!row) return null
  const t = row.track
  const search = `https://www.youtube.com/results?search_query=${encodeURIComponent([t.title, ...t.artists.slice(0, 2)].join(' '))}`

  return (
    <Dialog
      title={`Is it the same song? (${at + 1} of ${indices.length})`}
      onClose={() => { stop(); onClose() }}
      actions={
        <>
          <button className="btn text" onClick={next}>Skip</button>
          <button className="btn tonal" onClick={() => {
            stop()
            if (guess + 1 < guesses.length) setGuess(guess + 1)
            else { onDecide(i, undefined); next() }
          }}>{guess + 1 < guesses.length ? 'Not this one' : 'None of these'}</button>
          <button className="btn" disabled={!song} onClick={() => { onDecide(i, song); next() }}>Same song</button>
        </>
      }
    >
      <div style={{ color: 'var(--on-surface)', display: 'flex', flexDirection: 'column', gap: 14, minWidth: 'min(420px, 80vw)' }}>
        <div>
          <div className="body-small muted">On {source}</div>
          <div className="title-medium">{t.title}</div>
          <div className="body-small muted">{[t.artists.join(', '), t.album, t.durationMs ? formatDuration(t.durationMs / 1000) : null].filter(Boolean).join(' · ')}</div>
          <a className="body-small" href={search} target="_blank" rel="noreferrer">Hear it on YouTube</a>
        </div>
        {song && (
          <div>
            <div className="body-small muted">In the library{guesses.length > 1 ? ` (guess ${guess + 1} of ${guesses.length})` : ''}</div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginTop: 6 }}>
              <Cover coverArt={song.coverArt} size={150} corner={6} style={{ width: 56, height: 56, flexShrink: 0 }} />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div className="title-medium ellipsis">{song.title}</div>
                <div className="body-small muted ellipsis">{[song.album, song.artist, formatDuration(song.duration)].filter(Boolean).join(' · ')}</div>
              </div>
              <IconButton icon={playing ? 'pause' : 'play_arrow'} label={playing ? 'Pause' : 'Play a bit'} filled onClick={play} />
            </div>
          </div>
        )}
      </div>
    </Dialog>
  )
}
