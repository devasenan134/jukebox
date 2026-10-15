import { useRef, useState } from 'react'
import type { ImportItem, ImportPreview, ImportRow, MixSong } from '../api/types'
import { social } from '../api/social'
import { useMyPlaylists } from '../state/library'
import { playlistChanged } from '../state/queries'
import { Cover, formatDuration, ScreenHeader } from '../ui/components'
import { Checkbox, Icon, Menu, Spinner, toast } from '../ui/kit'
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
  const found = preview.rows.filter((r) => r.match).length
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
