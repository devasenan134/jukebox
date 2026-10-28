import { useId, type CSSProperties, type ReactNode } from 'react'
import type { Mix, MixArt } from '../api/types'
import { subsonic } from '../api/subsonic'

// Mix covers drawn in the browser (ui/mixes/MixArt.kt on Android): one design per kind of mix, chosen with
// its picture, colours and words by the server (MixArt in MixMaker.kt). Each design is laid out on a 280 x 280
// grid and scales with the tile (cqw units), so the same cover works as a thumbnail and as a page header.

/** A length on the 280-unit grid, as a share of the cover's width. */
const u = (n: number) => `${(n / 2.8).toFixed(3)}cqw`

const at = (left: number, top: number, width: number, height: number, more: CSSProperties = {}): CSSProperties => ({
  position: 'absolute', left: u(left), top: u(top), width: u(width), height: u(height), ...more,
})

const MONO: CSSProperties = { fontFamily: "'Space Mono', monospace", fontWeight: 700, textTransform: 'uppercase' }
const ANTON: CSSProperties = { fontFamily: 'Anton, sans-serif', fontWeight: 400, lineHeight: 0.95 }
const SERIF: CSSProperties = { fontFamily: 'Fraunces, serif', fontStyle: 'italic', fontWeight: 600, lineHeight: 1 }
const HAND: CSSProperties = { fontFamily: "'Permanent Marker', cursive", lineHeight: 1.05 }

/** Vinyl, as seen from above: fine grooves. */
const GROOVES = `repeating-radial-gradient(circle, #121212 0 ${u(1.5)}, #1f1f1f ${u(1.5)} ${u(3)})`

/** A font size that fits [text] in [width] units on at most [lines] lines ([charWidth]: a letter's width in ems). */
function fit(text: string, width: number, max: number, charWidth: number, lines = 1) {
  return Math.min(max, (width * lines) / Math.max(1, text.length * charWidth))
}

function rgb(hex: string): [number, number, number] {
  const n = parseInt(/^#?([0-9a-f]{6})$/i.exec(hex.trim())?.[1] ?? '777777', 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}

/** [hex] mixed with black (t < 0) or white (t > 0). */
export function shade(hex: string, t: number) {
  const target = t < 0 ? 0 : 255
  return '#' + rgb(hex).map((c) => Math.round(c + (target - c) * Math.abs(t)).toString(16).padStart(2, '0')).join('')
}

/**
 * A two-tone photo: dark parts in [dark], light parts in [light], a little more contrast. The same colour matrix
 * as the Android app's, so both draw the same picture.
 */
function duotoneMatrix(dark: string, light: string) {
  const s = rgb(dark).map((c) => c / 255)
  const h = rgb(light).map((c) => c / 255)
  const k = 1.2
  const b = -0.1
  return [0, 1, 2].map((i) => {
    const d = h[i] - s[i]
    return [d * k * 0.2126, d * k * 0.7152, d * k * 0.0722, 0, s[i] + d * b].map((v) => v.toFixed(4)).join(' ')
  }).join(' ') + ' 0 0 0 1 0'
}

function Img({ id, size = 400, style, filter }: { id?: string | null; size?: number; style: CSSProperties; filter?: string }) {
  const src = subsonic.coverUrl(id ?? undefined, size)
  if (!src) return <div style={{ ...style, background: 'rgba(255,255,255,0.08)' }} />
  return <img src={src} alt="" draggable={false} style={{ objectFit: 'cover', display: 'block', filter, ...style }} />
}

/** A photo in two tones (see [duotoneMatrix]); [position] picks which part shows (faces are near the top). */
function Duotone({ id, dark, light, style, position = '50% 25%' }: { id?: string | null; dark: string; light: string; style: CSSProperties; position?: string }) {
  const filterId = 'duo' + useId().replace(/:/g, '')
  return (
    <>
      <svg width="0" height="0" style={{ position: 'absolute' }} aria-hidden="true">
        <filter id={filterId} colorInterpolationFilters="sRGB"><feColorMatrix type="matrix" values={duotoneMatrix(dark, light)} /></filter>
      </svg>
      <Img id={id} size={500} filter={`url(#${filterId})`} style={{ objectPosition: position, ...style }} />
    </>
  )
}

/** A record seen from above with [label] in the middle; [size] and [left]/[top] in grid units. */
function Record({ left, top, size, label, hole = 8, labelSize, children }: {
  left: number; top: number; size: number; label: ReactNode; hole?: number; labelSize?: number; children?: ReactNode
}) {
  const l = labelSize ?? size * 0.32
  return (
    <div style={at(left, top, size, size, { borderRadius: '50%', background: `${GROOVES}, #121212` })}>
      <div style={at((size - l) / 2, (size - l) / 2, l, l, { borderRadius: '50%', overflow: 'hidden' })}>{label}</div>
      {hole > 0 && <div style={at((size - hole) / 2, (size - hole) / 2, hole, hole, { borderRadius: '50%', background: '#121212' })} />}
      {children}
    </div>
  )
}

/** The cover of [mix] drawn from its [art]: fills its box, which should be square. */
export function MixArtCover({ mix, art, radius = 0 }: { mix: Mix; art: MixArt; radius?: number | string }) {
  return (
    <div style={{ position: 'relative', width: '100%', aspectRatio: '1', overflow: 'hidden', containerType: 'inline-size', borderRadius: radius, background: art.ground, color: art.ink, isolation: 'isolate' }}>
      <Design mix={mix} art={art} />
    </div>
  )
}

function Design({ mix, art }: { mix: Mix; art: MixArt }) {
  const covers = mix.covers
  const first = art.picture ?? covers[0]
  const ink = art.ink
  const ground = art.ground
  switch (art.style) {
    // Daily Mix: a record sleeve with its vinyl sliding out; the mix's main artist in two tones.
    case 'sleeve':
      return (
        <>
          <Record left={116} top={22} size={236} label={<div style={{ width: '100%', height: '100%', background: ink }} />} labelSize={70} />
          <div style={at(0, 0, 212, 280, { boxShadow: `${u(8)} 0 ${u(20)} rgba(0,0,0,0.45)`, overflow: 'hidden' })}>
            <Duotone id={first} dark={ground} light={ink} position="55% 25%" style={{ width: '100%', height: '100%' }} />
          </div>
          <div style={at(16, 14, 240, 14, { ...MONO, fontSize: u(10), letterSpacing: u(2), color: shade(ink, 0.6) })}>Jukebox · daily</div>
          <div style={{ position: 'absolute', left: u(14), bottom: u(12), display: 'flex', flexDirection: 'column', color: shade(ink, 0.6) }}>
            <span style={{ ...MONO, fontSize: u(11), letterSpacing: u(3) }}>{art.kicker}</span>
            <span style={{ ...ANTON, fontSize: u(96), lineHeight: 0.9 }}>{art.headline}</span>
          </div>
        </>
      )

    // Discover Weekly: a mixtape, the title written on its label.
    case 'cassette':
      return (
        <>
          <div style={{ position: 'absolute', inset: 0, background: `repeating-linear-gradient(135deg, ${ground} 0 ${u(22)}, ${shade(ground, 0.12)} ${u(22)} ${u(44)})` }} />
          <div style={at(24, 58, 232, 150, { borderRadius: u(12), background: '#1b1b1d', transform: 'rotate(-7deg)', boxShadow: `0 ${u(14)} ${u(30)} rgba(60,10,0,0.45)` })}>
            <div style={at(12, 10, 208, 74, { borderRadius: u(6), background: '#f3ead7', overflow: 'hidden' })}>
              <div style={at(0, 0, 208, 12, { background: '#2f6bff' })} />
              <div style={at(10, 48, 188, 1, { background: '#c9bfa9' })} />
              <div style={at(10, 62, 188, 1, { background: '#c9bfa9' })} />
              <div style={at(10, 18, 170, 34, { ...HAND, fontSize: u(fit(art.headline, 168, 24, 0.5)), color: '#1d2a6b', transform: 'rotate(-2deg)', whiteSpace: 'nowrap' })}>{art.headline}</div>
              <div style={at(182, 15, 18, 18, { ...MONO, border: `${u(2)} solid #1d2a6b`, borderRadius: u(3), boxSizing: 'border-box', fontSize: u(12), color: '#1d2a6b', display: 'flex', alignItems: 'center', justifyContent: 'center' })}>A</div>
            </div>
            <div style={at(50, 92, 132, 34, { borderRadius: u(17), background: '#2b2b2e' })}>
              <Reel left={6} />
              <div style={at(36, 10, 60, 14, { background: '#5a3a28', borderRadius: u(3) })} />
              <Reel left={102} />
            </div>
            <div style={at(44, 134, 144, 16, { background: '#2b2b2e', clipPath: 'polygon(8% 0, 92% 0, 100% 100%, 0 100%)' })} />
          </div>
          <div style={at(16, 250, 250, 16, { ...MONO, fontSize: u(11), letterSpacing: u(2), color: ink })}>{art.kicker}</div>
        </>
      )

    // Time of day, seasons and calm moods: a sky, the cover as its sun or moon.
    case 'sky':
      return <Sky art={art} picture={first} />

    // Fresh for You, New Arrivals: covers pulled out of a crate, a sticker.
    case 'crate':
      return (
        <>
          <Img id={covers[1] ?? covers[0]} style={at(22, 40, 128, 128, { transform: 'rotate(-12deg)', boxShadow: `0 ${u(10)} ${u(22)} rgba(40,25,10,0.3)` })} />
          <Img id={covers[2] ?? covers[0]} style={at(130, 34, 128, 128, { transform: 'rotate(10deg)', boxShadow: `0 ${u(10)} ${u(22)} rgba(40,25,10,0.3)` })} />
          <Img id={covers[0]} style={at(76, 52, 128, 128, { boxShadow: `0 ${u(12)} ${u(26)} rgba(40,25,10,0.38)` })} />
          <div style={at(186, 132, 76, 76, { borderRadius: '50%', background: '#e63b2e', color: '#fff', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', transform: 'rotate(14deg)' })}>
            <span style={{ ...ANTON, fontSize: u(22) }}>FRESH</span>
            {art.stamp && <span style={{ ...MONO, fontSize: u(9), letterSpacing: u(1) }}>{art.stamp}</span>}
          </div>
          <div style={{ position: 'absolute', left: u(16), right: u(16), bottom: u(12), display: 'flex', flexDirection: 'column' }}>
            <span style={{ ...MONO, fontSize: u(10), letterSpacing: u(2), color: '#8a6d4a' }}>{art.kicker}</span>
            <span style={{ fontFamily: 'Figtree Variable, Figtree, sans-serif', fontWeight: 900, fontSize: u(fit(art.headline, 248, 34, 0.55)), lineHeight: 1, letterSpacing: u(-1), color: ink }}>{art.headline}</span>
          </div>
        </>
      )

    // On Repeat, Top 50, scores: a spinning record, the words round its rim.
    case 'spin': {
      const ring = 'ring' + mix.id.replace(/[^a-z0-9]/gi, '')
      const text = `${art.headline} • `.repeat(Math.max(2, Math.ceil(70 / (art.headline.length + 3))))
      return (
        <>
          <Record left={40} top={40} size={200} labelSize={80} label={<Img id={first} size={200} style={{ width: '100%', height: '100%' }} />}>
            <div style={at(20, 20, 160, 160, { borderRadius: '50%', background: 'conic-gradient(from 20deg, transparent 0 60deg, rgba(255,255,255,0.10) 75deg, transparent 90deg 240deg, rgba(255,255,255,0.08) 255deg, transparent 270deg)' })} />
          </Record>
          <svg viewBox="0 0 280 280" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }} aria-hidden="true">
            <defs><path id={ring} d="M140,140 m-118,0 a118,118 0 1,1 236,0 a118,118 0 1,1 -236,0" /></defs>
            <text fontFamily="Space Mono, monospace" fontWeight="700" fontSize="13" letterSpacing="3" fill={ink} textLength="735" lengthAdjust="spacing"><textPath href={`#${ring}`}>{text}</textPath></text>
          </svg>
        </>
      )
    }

    // Rewind: an old favourite as a faded polaroid.
    case 'polaroid':
      return (
        <>
          <div style={at(14, 12, 140, 20, { ...MONO, fontSize: u(15), letterSpacing: u(1), color: ink })}>{art.kicker}</div>
          <div style={{ position: 'absolute', right: u(14), top: u(13), ...MONO, fontSize: u(12), color: ink }}>SP 0:45:12</div>
          <div style={at(66, 48, 156, 192, { background: '#f7f3ea', transform: 'rotate(5deg)', boxShadow: `0 ${u(12)} ${u(26)} rgba(0,20,25,0.4)` })}>
            <Img id={first} filter="sepia(0.55) saturate(0.8) contrast(0.9) brightness(1.05)" style={at(12, 12, 132, 132)} />
            <div style={{ position: 'absolute', left: 0, right: 0, bottom: u(10), textAlign: 'center', ...HAND, fontSize: u(fit(art.headline, 140, 24, 0.5)), color: '#2b2b2b' }}>{art.headline}</div>
          </div>
          <div style={at(112, 38, 74, 22, { background: 'rgba(240,226,190,0.8)', transform: 'rotate(-4deg)' })} />
        </>
      )

    // Hidden Gems, Friends Mix: photos scattered on a table.
    case 'photos':
      return (
        <>
          <Polaroid id={covers[1] ?? covers[0]} left={18} top={26} turn={-10} />
          <Polaroid id={covers[2] ?? covers[0]} left={150} top={18} turn={9} />
          <Polaroid id={covers[0]} left={84} top={60} turn={-2} />
          <div style={{ position: 'absolute', left: u(16), right: u(16), bottom: u(12), display: 'flex', flexDirection: 'column' }}>
            <span style={{ ...MONO, fontSize: u(10), letterSpacing: u(2), color: shade(ink, -0.3) }}>{art.kicker}</span>
            <span style={{ ...HAND, fontSize: u(fit(art.headline, 248, 36, 0.52)), color: ink }}>{art.headline}</span>
          </div>
        </>
      )

    // Composer and singer mixes: an LP, its record sliding out the top; the artist in two tones.
    case 'lp':
      return (
        <>
          <Record left={30} top={-46} size={220} labelSize={64} hole={0} label={<div style={{ width: '100%', height: '100%', background: ink }} />} />
          <div style={at(0, 66, 280, 214, { overflow: 'hidden', boxShadow: `0 ${u(-8)} ${u(20)} rgba(0,0,0,0.45)` })}>
            <Duotone id={first} dark={ground} light={ink} position="50% 18%" style={{ width: '100%', height: '100%' }} />
          </div>
          <div style={{ position: 'absolute', left: u(16), right: u(16), bottom: u(12), display: 'flex', flexDirection: 'column', color: shade(ink, 0.6) }}>
            <span style={{ ...MONO, fontSize: u(11), letterSpacing: u(3) }}>{art.kicker}</span>
            <span style={{ ...ANTON, fontSize: u(fit(art.headline, 248, 50, 0.44, 2)) }}>{art.headline}</span>
          </div>
        </>
      )

    // Because you played: the song as a 7" single.
    case 'single':
      return (
        <>
          <div style={at(16, 16, 248, 14, { ...MONO, fontSize: u(10), letterSpacing: u(2), color: shade(ink, 0.25) })}>{art.kicker}</div>
          <div style={at(16, 32, 248, 70, { ...SERIF, fontSize: u(fit(art.headline, 248, 36, 0.48, 2)), color: ink })}>{art.headline}</div>
          <Record left={100} top={96} size={166} labelSize={80} hole={0} label={
            <div style={{ position: 'relative', width: '100%', height: '100%', background: '#c0392b' }}>
              <div style={at(20, 20, 40, 40, { borderRadius: '50%', background: ground })} />
            </div>
          } />
          <Img id={first} style={at(16, 104, 158, 158, { boxShadow: `${u(8)} ${u(10)} ${u(22)} rgba(0,0,0,0.3)` })} />
        </>
      )

    // 60s–80s decades: a reel-to-reel tape recorder, drawn simply.
    case 'reel':
      return (
        <>
          <svg viewBox="0 0 280 170" style={{ position: 'absolute', left: 0, top: 0, width: '100%' }} aria-hidden="true">
            <path d="M28 128 L140 152 L252 116" fill="none" stroke="#c9893d" strokeWidth="2" />
            <rect x="128" y="146" width="24" height="12" rx="3" fill="#f2e6c9" />
            <g transform="translate(78 82)"><circle r="62" fill="#c9893d" /><circle r="62" fill="none" stroke="#f2e6c9" strokeWidth="4" /><circle r="30" fill={ground} /><g fill={ground}><circle cx="0" cy="-42" r="12" /><circle cx="36.4" cy="21" r="12" /><circle cx="-36.4" cy="21" r="12" /></g><circle r="9" fill="#f2e6c9" /></g>
            <g transform="translate(206 76)"><circle r="54" fill="none" stroke="#f2e6c9" strokeWidth="4" /><circle r="24" fill="#c9893d" /><g fill={ground}><circle cx="0" cy="-34" r="11" /><circle cx="29.4" cy="17" r="11" /><circle cx="-29.4" cy="17" r="11" /></g><circle r="8" fill="#f2e6c9" /></g>
          </svg>
          <div style={{ position: 'absolute', left: u(16), bottom: u(10), display: 'flex', flexDirection: 'column' }}>
            <span style={{ ...MONO, fontSize: u(11), letterSpacing: u(3), color: '#f2e6c9' }}>{art.kicker}</span>
            <span style={{ ...ANTON, fontSize: u(78), color: ink }}>{art.headline}</span>
          </div>
        </>
      )

    // 90s–2000s decades: a CD with the cover printed on it.
    case 'cd':
      return (
        <>
          <div style={at(82, 14, 186, 186, { borderRadius: '50%', padding: u(5), boxSizing: 'border-box', background: 'conic-gradient(from 40deg, #d8dbe6, #f6cfe9, #c9ecff, #dcffe2, #fff3c2, #f6cfe9, #d8dbe6)', boxShadow: `0 ${u(12)} ${u(26)} rgba(0,0,0,0.45)` })}>
            <div style={{ position: 'relative', width: '100%', height: '100%', borderRadius: '50%', overflow: 'hidden' }}>
              <Img id={first} style={{ width: '100%', height: '100%' }} />
              <div style={at(58, 58, 60, 60, { borderRadius: '50%', background: 'rgba(235,238,250,0.75)' })} />
              <div style={at(76, 76, 24, 24, { borderRadius: '50%', background: ground })} />
            </div>
          </div>
          <div style={{ position: 'absolute', left: u(16), right: u(16), bottom: u(12), display: 'flex', flexDirection: 'column' }}>
            <span style={{ ...ANTON, fontSize: u(58), color: shade(ink, 0.7) }}>{art.headline}</span>
            <span style={{ ...MONO, fontSize: u(11), letterSpacing: u(2), color: ink }}>{art.kicker}</span>
          </div>
        </>
      )

    // A composer's mood or pairing: a die-cut sleeve, the record's label (the artist) showing through.
    case 'diecut':
      return (
        <>
          <div style={at(54, 18, 172, 172, { borderRadius: '50%', background: '#121212', boxShadow: `inset 0 ${u(6)} ${u(14)} rgba(0,0,0,0.6)` })}>
            <div style={at(12, 12, 148, 148, { borderRadius: '50%', overflow: 'hidden' })}>
              <Duotone id={first} dark={shade(ground, -0.65)} light={shade(ground, 0.7)} style={{ width: '100%', height: '100%' }} />
            </div>
          </div>
          <div style={{ position: 'absolute', left: u(16), right: u(16), bottom: u(12), display: 'flex', flexDirection: 'column', color: ink }}>
            <span style={{ ...MONO, fontSize: u(11), letterSpacing: u(3), opacity: 0.85 }}>{art.kicker}</span>
            <span style={{ ...SERIF, fontSize: u(fit(art.headline, 248, 34, 0.48)) }}>{art.headline}</span>
          </div>
        </>
      )

    // Deep Cuts: a record half out of its paper inner sleeve.
    case 'paper':
      return (
        <>
          <Record left={96} top={40} size={200} labelSize={76} label={<Img id={first} size={200} style={{ width: '100%', height: '100%' }} />} />
          <div style={at(16, 30, 176, 220, { background: '#c9a274', boxShadow: `${u(10)} 0 ${u(22)} rgba(0,0,0,0.5)` })}>
            <div style={at(62, 74, 52, 52, { borderRadius: '50%', background: ground })} />
            <div style={{ position: 'absolute', left: u(14), bottom: u(12), display: 'flex', flexDirection: 'column' }}>
              <span style={{ ...MONO, fontSize: u(9), letterSpacing: u(2), color: '#5b3a1c' }}>{art.kicker}</span>
              <span style={{ ...ANTON, fontSize: u(44), color: ink, whiteSpace: 'pre-line' }}>{art.headline.replace(' ', '\n')}</span>
            </div>
          </div>
        </>
      )

    // Energetic moods: a turntable from above, the cover on the platter.
    case 'turntable':
      return (
        <>
          <div style={at(14, 64, 200, 200, { borderRadius: '50%', background: '#9aa0a6', boxShadow: `0 ${u(6)} ${u(16)} rgba(0,0,0,0.6)` })}>
            <div style={at(6, 6, 188, 188, { borderRadius: '50%', background: '#d7263d' })} />
            <Record left={12} top={12} size={176} labelSize={64} hole={0} label={<Img id={first} size={200} style={{ width: '100%', height: '100%' }} />} />
          </div>
          <svg viewBox="0 0 280 280" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }} aria-hidden="true">
            <circle cx="244" cy="96" r="16" fill="#3a3a3c" /><circle cx="244" cy="96" r="7" fill="#c9ccd0" />
            <path d="M244 96 L238 196 L196 230" fill="none" stroke="#c9ccd0" strokeWidth="5" strokeLinecap="round" />
            <rect x="182" y="224" width="22" height="13" rx="2" transform="rotate(-38 193 230)" fill="#e9eaec" />
            <rect x="236" y="214" width="8" height="48" rx="3" fill="#2c2c2e" /><rect x="232" y="234" width="16" height="8" rx="2" fill="#d7263d" />
          </svg>
          <div style={{ position: 'absolute', left: u(16), top: u(12), right: u(16), display: 'flex', flexDirection: 'column' }}>
            <span style={{ ...ANTON, fontSize: u(fit(art.headline, 210, 36, 0.44)), color: ink }}>{art.headline}</span>
            <span style={{ ...MONO, fontSize: u(10), letterSpacing: u(2), color: '#ff8a96' }}>{art.kicker}</span>
          </div>
        </>
      )

    // Stations: an old radio's face, the artist in the speaker, a dial tuned to the station.
    case 'tuner':
      return <Tuner mix={mix} art={art} />

    default:
      return <Img id={covers[0]} style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }} />
  }
}

function Reel({ left }: { left: number }) {
  return (
    <svg viewBox="0 0 24 24" style={at(left, 5, 24, 24)} aria-hidden="true">
      <circle cx="12" cy="12" r="11" fill="#e9e3d6" /><circle cx="12" cy="12" r="4" fill="#2b2b2e" />
      <g fill="#2b2b2e"><rect x="11" y="1.5" width="2" height="4" /><rect x="11" y="18.5" width="2" height="4" /><rect x="1.5" y="11" width="4" height="2" /><rect x="18.5" y="11" width="4" height="2" /></g>
    </svg>
  )
}

function Polaroid({ id, left, top, turn }: { id?: string; left: number; top: number; turn: number }) {
  return (
    <div style={at(left, top, 112, 132, { background: '#f7f3ea', transform: `rotate(${turn}deg)`, boxShadow: `0 ${u(10)} ${u(20)} rgba(0,0,0,0.4)` })}>
      <Img id={id} size={200} style={at(8, 8, 96, 96)} />
    </div>
  )
}

/** The skies: their gradient, what's on the horizon, where the sun or moon sits, and the colours of the words. */
const SKIES: Record<string, { sky: string; horizon: 'sea' | 'hills' | 'clouds'; low: string; high: string; sun: [number, number, number]; text: string; kicker: string; stars?: boolean }> = {
  dawn: { sky: 'linear-gradient(180deg, #ffe08a 0%, #ffab5e 50%, #ff7a59 66%)', horizon: 'sea', low: '#c2185b', high: '#c2185b', sun: [65, 66, 150], text: '#fff4ec', kicker: '#7a2d00' },
  day: { sky: 'linear-gradient(180deg, #5fb4f0 0%, #a9dcff 62%, #fff1cf 100%)', horizon: 'hills', low: '#1f5c38', high: '#2f7d4f', sun: [168, 26, 92], text: '#ffffff', kicker: '#0d3550' },
  dusk: { sky: 'linear-gradient(180deg, #2b1b5a 0%, #a23e78 55%, #ff8a5b 85%, #ffc36b 100%)', horizon: 'hills', low: '#200a26', high: '#3a1640', sun: [92, 96, 110], text: '#fff4ec', kicker: '#ffd0c2' },
  night: { sky: 'linear-gradient(180deg, #070b22 0%, #1d1650 55%, #6a2c70 82%, #e2667f 100%)', horizon: 'hills', low: '#07051a', high: '#120a2a', sun: [150, 22, 104], text: '#fff3f6', kicker: '#ffc9d6', stars: true },
  rain: { sky: 'linear-gradient(180deg, #6d7d8c 0%, #3b4b5b 55%, #1d2935 100%)', horizon: 'clouds', low: '#7a8794', high: '#8a97a3', sun: [148, 24, 96], text: '#f4f8fc', kicker: '#cfe0f0' },
}

function Sky({ art, picture }: { art: MixArt; picture?: string }) {
  const s = SKIES[art.variant ?? 'dusk'] ?? SKIES.dusk
  const [sx, sy, size] = s.sun
  return (
    <>
      <div style={{ position: 'absolute', inset: 0, background: s.sky }} />
      {s.stars && [[30, 30, 2], [92, 18, 3], [60, 74, 2], [236, 112, 2], [20, 128, 2], [118, 54, 2]].map(([x, y, r], i) => (
        <div key={i} style={at(x, y, r, r, { borderRadius: '50%', background: '#fff' })} />
      ))}
      <div style={at(sx, sy, size, size, { borderRadius: '50%', overflow: 'hidden', boxShadow: s.stars ? `0 0 0 ${u(6)} rgba(255,255,255,0.06), 0 0 0 ${u(14)} rgba(255,255,255,0.03)` : undefined })}>
        <Img id={picture} filter={art.variant === 'rain' ? 'saturate(0.5) brightness(1.05)' : 'saturate(0.85)'} style={{ width: '100%', height: '100%' }} />
      </div>
      {s.horizon === 'sea' && (
        <div style={at(0, 176, 280, 104, { background: s.low })}>
          <div style={{ position: 'absolute', inset: 0, background: `repeating-linear-gradient(180deg, rgba(255,200,140,0.35) 0 ${u(2)}, transparent ${u(2)} ${u(9)})` }} />
        </div>
      )}
      {s.horizon === 'hills' && (
        <svg viewBox="0 0 280 90" style={{ position: 'absolute', left: 0, bottom: 0, width: '100%' }} aria-hidden="true">
          <path d="M0 52 C40 30 70 36 104 50 C140 64 170 34 210 38 C240 41 262 52 280 46 L280 90 L0 90 Z" fill={s.high} />
          <path d="M0 70 C50 56 90 62 130 72 C170 82 220 60 280 66 L280 90 L0 90 Z" fill={s.low} />
        </svg>
      )}
      {s.horizon === 'clouds' && (
        <>
          <svg viewBox="0 0 280 140" style={{ position: 'absolute', left: 0, top: 0, width: '100%' }} aria-hidden="true">
            <path d="M-10 92 C10 66 46 64 62 80 C70 56 110 50 124 74 C140 62 168 66 172 88 C190 82 214 90 214 108 L-10 108 Z" fill={s.high} />
            <path d="M110 120 C124 98 160 96 172 112 C182 92 222 88 236 110 C250 102 284 106 290 124 L110 124 Z" fill={s.low} />
          </svg>
          <div style={{ position: 'absolute', left: 0, right: 0, top: u(104), bottom: 0, background: `repeating-linear-gradient(105deg, transparent 0 ${u(13)}, rgba(220,232,245,0.28) ${u(13)} ${u(14)})` }} />
        </>
      )}
      {art.kicker && <div style={at(16, s.horizon === 'sea' ? 14 : 156, 248, 14, { ...MONO, fontSize: u(10), letterSpacing: u(2), color: s.kicker })}>{art.kicker}</div>}
      <div style={{ position: 'absolute', left: u(16), right: u(16), bottom: u(14), ...SERIF, fontSize: u(fit(art.headline, 248, 34, 0.46, 2)), color: s.text }}>{art.headline}</div>
    </>
  )
}

/** Stations: the dial runs 88–108; the needle sits at the station's frequency. */
function Tuner({ mix, art }: { mix: Mix; art: MixArt }) {
  const frequency = parseFloat(art.stamp ?? '98') || 98
  const needle = 6 + ((frequency - 88) / 20) * 234
  const dark = shade(art.ground, -0.35)
  const covers = mix.covers
  return (
    <>
      <div style={at(14, 12, 252, 14, { ...MONO, fontSize: u(10), letterSpacing: u(1.5), color: art.ink, display: 'flex', justifyContent: 'space-between' })}>
        <span>{art.kicker}</span><span>{art.stamp}</span>
      </div>
      <div style={at(58, 30, 164, 164, { borderRadius: '50%', background: `radial-gradient(circle, ${art.ink}59 ${u(1.2)}, transparent ${u(1.7)}) 0 0 / ${u(7)} ${u(7)}` })} />
      <div style={at(77, 49, 126, 126, { borderRadius: '50%', overflow: 'hidden', boxShadow: `0 0 0 ${u(4)} ${art.ground}, 0 0 0 ${u(6)} ${art.ink}` })}>
        {art.picture ? (
          <Duotone id={art.picture} dark={dark} light={art.ink} position="50% 20%" style={{ width: '100%', height: '100%' }} />
        ) : (
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', width: '100%', height: '100%' }}>
            {[0, 1, 2, 3].map((i) => <Img key={i} id={covers[i] ?? covers[0]} size={150} style={{ width: '100%', height: '100%' }} />)}
          </div>
        )}
      </div>
      <div style={at(8, 186, 264, 32, { textAlign: 'center', fontFamily: 'Figtree Variable, Figtree, sans-serif', fontWeight: 900, fontSize: u(fit(art.headline, 250, 27, 0.55)), letterSpacing: u(-0.5), color: '#fff', whiteSpace: 'nowrap' })}>{art.headline}</div>
      <div style={at(16, 228, 248, 38, { borderRadius: u(4), background: '#efe6cf', overflow: 'hidden' })}>
        <div style={at(6, 5, 236, 11, { background: `repeating-linear-gradient(90deg, #6b5a33 0 ${u(1)}, transparent ${u(1)} ${u(7)})` })} />
        <div style={at(6, 22, 236, 12, { ...MONO, fontSize: u(9), color: '#6b5a33', display: 'flex', justifyContent: 'space-between' })}>
          <span>88</span><span>92</span><span>96</span><span>100</span><span>104</span><span>108</span>
        </div>
        <div style={at(needle, 0, 2, 38, { background: '#e63b2e' })} />
      </div>
    </>
  )
}
