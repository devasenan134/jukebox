import { useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react'
import {
  BUILTIN_PROFILES, CURVE_FREQS, EQ_BANDS, EQ_MAX_DB, allProfiles, chooseProfile, deleteProfile, ownProfiles, preampDb, renameProfile,
  responseDb, sameGains, saveProfileAs, setBandGain, setEqEnabled, setProfileSync, updateProfile, useEqualizer,
} from '../player/equalizer'
import { useSession } from '../state/session'
import { Dialog, Icon, NameDialog, Switch, toast } from '../ui/kit'

/**
 * Settings › Sound (SoundCard.kt): the equalizer. A profile list (built-in and your own), and, folded away under
 * "Adjust bands", the curve with a handle per band to drag up and down (or focus and use the arrow keys).
 */
export function SoundSettings() {
  const s = useEqualizer()
  const signedIn = useSession((x) => x.social != null)
  const [naming, setNaming] = useState<'new' | 'rename' | null>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)
  // The bands, saving and sync fold away, so Settings stays short: the profile list is enough most of the time.
  const [open, setOpen] = useState(false)
  const profiles = allProfiles(s)
  const chosen = profiles.find((p) => p.id === s.profileId)
  const edited = chosen != null && !sameGains(chosen.gains, s.gains)
  /** The chosen profile, if it's one of yours (those can be saved over, renamed and deleted). */
  const own = chosen?.id.startsWith('custom:') ? chosen : null
  const preamp = preampDb(s.gains)

  return (
    <>
      <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 12 }}>
        <div className="body-medium muted" style={{ flex: 1 }}>
          An equalizer for your headphones or IEMs: choose a profile, or adjust the bands and save your own.
        </div>
        <Switch checked={s.enabled} onChange={setEqEnabled} />
      </div>

      <div className="field" style={{ position: 'relative', marginBottom: 8 }}>
        <select aria-label="Profile" value={chosen ? chosen.id : ''} onChange={(e) => chooseProfile(e.target.value)}>
          {!chosen && <option value="" disabled>Your changes (not saved)</option>}
          {[
            { title: 'Yours', list: ownProfiles(s) },
            { title: 'Tuning', list: BUILTIN_PROFILES.filter((p) => p.group === 'tuning') },
            { title: 'Genres', list: BUILTIN_PROFILES.filter((p) => p.group === 'genre') },
          ].filter((g) => g.list.length > 0).map((g) => (
            <optgroup key={g.title} label={g.title}>
              {g.list.map((p) => (
                <option key={p.id} value={p.id}>{p.name}{p.id === s.profileId && edited ? ' (changed)' : ''}</option>
              ))}
            </optgroup>
          ))}
        </select>
        <Icon name="expand_more" className="select-chevron" />
      </div>

      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <div className="body-small muted" style={{ flex: 1 }}>
          {!s.enabled ? 'Off: music plays as it is.' : preamp < 0 ? `Preamp ${preamp.toFixed(1)} dB, so boosts don't distort` : 'On'}
        </div>
        <button className="btn text small" aria-expanded={open} onClick={() => setOpen(!open)}>
          {open ? 'Hide bands' : 'Adjust bands'}
          <Icon name={open ? 'expand_less' : 'expand_more'} size={20} />
        </button>
      </div>

      {open && (
        <div style={{ marginTop: 8 }}>
          <div style={{ opacity: s.enabled ? 1 : 0.5, transition: 'opacity 0.2s' }}>
            <EqGraph gains={s.gains} />
          </div>

          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 12 }}>
            {own && edited && <button className="btn tonal small" onClick={() => { updateProfile(); toast(`Saved ${own.name}`) }}>Save</button>}
            <button className="btn tonal small" onClick={() => setNaming('new')}>Save as new profile</button>
            {own && <button className="btn text small" onClick={() => setNaming('rename')}>Rename</button>}
            {own && <button className="btn text small" style={{ color: 'var(--error)' }} onClick={() => setConfirmDelete(true)}>Delete</button>}
            <button className="btn text small" onClick={() => chooseProfile(BUILTIN_PROFILES[0].id)}>Reset to flat</button>
          </div>

          {signedIn && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginTop: 16 }}>
              <div style={{ flex: 1 }}>
                <div className="body-large">Sync my profiles</div>
                <div className="body-small muted">
                  {s.sync ? 'Profiles you save are on your phone, the Mac app and the website too. The equalizer\'s on/off and the curve playing stay with each device.'
                    : 'Your profiles are kept on this device only.'}
                </div>
              </div>
              <Switch checked={s.sync} onChange={setProfileSync} />
            </div>
          )}
        </div>
      )}

      {naming && (
        <NameDialog
          title={naming === 'new' ? 'Save profile' : 'Rename profile'}
          confirm="Save"
          label="Name, e.g. the IEMs it's for"
          initial={naming === 'rename' ? own?.name : ''}
          max={30}
          onClose={() => setNaming(null)}
          onConfirm={(name) => {
            if (naming === 'new') saveProfileAs(name)
            else if (own) renameProfile(own.id, name)
            setNaming(null)
            toast(`Saved ${name}`)
          }}
        />
      )}
      {confirmDelete && own && (
        <Dialog
          title={`Delete ${own.name}?`}
          onClose={() => setConfirmDelete(false)}
          actions={
            <>
              <button className="btn text" onClick={() => setConfirmDelete(false)}>Cancel</button>
              <button className="btn" onClick={() => { deleteProfile(own.id); setConfirmDelete(false) }}>Delete</button>
            </>
          }
        >
          The sound stays as it is until you choose another profile.
        </Dialog>
      )}
    </>
  )
}

const HEIGHT = 200
const PAD = { left: 34, right: 12, top: 12, bottom: 24 }
const MIN_F = 20
const MAX_F = 20_000

const freqLabel = (f: number) => (f >= 1000 ? `${f / 1000}k` : String(f))

/** The curve: the sound the bands make together, with a handle on each band. */
function EqGraph({ gains }: { gains: number[] }) {
  const box = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(600)
  const [active, setActive] = useState<number | null>(null)
  const dragging = useRef<number | null>(null)

  useEffect(() => {
    const el = box.current
    if (!el) return
    const ro = new ResizeObserver(([e]) => setWidth(Math.max(240, e.contentRect.width)))
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  const plotW = width - PAD.left - PAD.right
  const plotH = HEIGHT - PAD.top - PAD.bottom
  const x = (f: number) => PAD.left + (Math.log(f / MIN_F) / Math.log(MAX_F / MIN_F)) * plotW
  const y = (db: number) => PAD.top + ((EQ_MAX_DB - db) / (2 * EQ_MAX_DB)) * plotH
  const dbAt = (py: number) => EQ_MAX_DB - ((py - PAD.top) / plotH) * 2 * EQ_MAX_DB

  const curve = CURVE_FREQS.map((f, i) => `${i ? 'L' : 'M'}${x(f).toFixed(1)},${y(Math.max(-EQ_MAX_DB, Math.min(EQ_MAX_DB, responseDb(gains, f)))).toFixed(1)}`).join('')
  const fill = `${curve}L${x(MAX_F)},${y(0)}L${x(MIN_F)},${y(0)}Z`

  const local = (e: PointerEvent) => {
    const r = e.currentTarget.getBoundingClientRect()
    return { px: e.clientX - r.left, py: e.clientY - r.top }
  }
  const nearest = (px: number) => EQ_BANDS.reduce((best, f, i) => (Math.abs(x(f) - px) < Math.abs(x(EQ_BANDS[best]) - px) ? i : best), 0)

  const down = (e: PointerEvent<SVGSVGElement>) => {
    const { px, py } = local(e)
    const band = nearest(px)
    dragging.current = band
    setActive(band)
    e.currentTarget.setPointerCapture(e.pointerId)
    setBandGain(band, dbAt(py))
  }
  const move = (e: PointerEvent<SVGSVGElement>) => {
    if (dragging.current == null) return
    setBandGain(dragging.current, dbAt(local(e).py))
  }
  const up = () => (dragging.current = null)

  const key = (band: number) => (e: KeyboardEvent) => {
    const step = { ArrowUp: 0.5, ArrowRight: 0.5, ArrowDown: -0.5, ArrowLeft: -0.5, PageUp: 3, PageDown: -3 }[e.key]
    if (step != null) setBandGain(band, gains[band] + step)
    else if (e.key === 'Home' || e.key === '0') setBandGain(band, 0)
    else return
    e.preventDefault()
  }

  return (
    <div ref={box} style={{ width: '100%' }}>
      <div className="body-small" style={{ height: 18, color: 'var(--primary)', fontWeight: 600 }}>
        {active != null && `${freqLabel(EQ_BANDS[active])}Hz  ${gains[active] > 0 ? '+' : ''}${gains[active].toFixed(1)} dB`}
      </div>
      <svg
        width={width}
        height={HEIGHT}
        style={{ display: 'block', touchAction: 'none', cursor: 'ns-resize', userSelect: 'none' }}
        onPointerDown={down}
        onPointerMove={move}
        onPointerUp={up}
        onPointerCancel={up}
        onDoubleClick={(e) => setBandGain(nearest(e.clientX - e.currentTarget.getBoundingClientRect().left), 0)}
      >
        {[-12, -6, 0, 6, 12].map((db) => (
          <g key={db}>
            <line x1={PAD.left} x2={width - PAD.right} y1={y(db)} y2={y(db)} stroke="var(--outline-variant)" strokeWidth={db === 0 ? 1.5 : 1} />
            <text x={PAD.left - 6} y={y(db) + 4} textAnchor="end" fontSize={11} fill="var(--on-surface-variant)">{db > 0 ? `+${db}` : db}</text>
          </g>
        ))}
        {EQ_BANDS.map((f) => (
          <g key={f}>
            <line x1={x(f)} x2={x(f)} y1={PAD.top} y2={HEIGHT - PAD.bottom} stroke="var(--outline-variant)" strokeDasharray="2 4" />
            <text x={x(f)} y={HEIGHT - 6} textAnchor="middle" fontSize={11} fill="var(--on-surface-variant)">{freqLabel(f)}</text>
          </g>
        ))}
        <path d={fill} fill="var(--primary)" opacity={0.14} />
        <path d={curve} fill="none" stroke="var(--primary)" strokeWidth={2.5} strokeLinejoin="round" />
        {EQ_BANDS.map((f, i) => (
          <circle
            key={f}
            cx={x(f)}
            cy={y(gains[i])}
            r={active === i ? 9 : 7}
            fill={active === i ? 'var(--primary)' : 'var(--surface-container-highest)'}
            stroke="var(--primary)"
            strokeWidth={2.5}
            tabIndex={0}
            role="slider"
            aria-label={`${freqLabel(f)}Hz`}
            aria-valuemin={-EQ_MAX_DB}
            aria-valuemax={EQ_MAX_DB}
            aria-valuenow={gains[i]}
            aria-valuetext={`${gains[i]} dB`}
            onFocus={() => setActive(i)}
            onKeyDown={key(i)}
            style={{ outline: 'none', transition: 'r 0.12s' }}
          />
        ))}
      </svg>
    </div>
  )
}
