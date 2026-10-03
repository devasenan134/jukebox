import type { Mix, MixSection } from '../api/types'
import { session } from '../state/session'
import { Cover, SectionTitle } from './components'
import { Icon } from './kit'
import type { Nav } from './nav'

// Mixes by Jukebox, drawn in the browser (ui/mixes/MixComponents.kt).

const DEFAULT_TINT = '#7A3E9D'

function hexToHsl(hex: string): [number, number, number] {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim())
  const n = parseInt(m ? m[1] : DEFAULT_TINT.slice(1), 16)
  const r = ((n >> 16) & 255) / 255
  const g = ((n >> 8) & 255) / 255
  const b = (n & 255) / 255
  const max = Math.max(r, g, b)
  const min = Math.min(r, g, b)
  const l = (max + min) / 2
  if (max === min) return [0, 0, l]
  const d = max - min
  const s = l > 0.5 ? d / (2 - max - min) : d / (max + min)
  let h = max === r ? (g - b) / d + (g < b ? 6 : 0) : max === g ? (b - r) / d + 2 : (r - g) / d + 4
  h *= 60
  return [h, s, l]
}
const hsl = (h: number, s: number, l: number) => `hsl(${h} ${s * 100}% ${l * 100}%)`

export const mixTint = (m: Mix) => (/^#[0-9a-f]{6}$/i.test(m.color) ? m.color : DEFAULT_TINT)
/** A soft pastel of the colour: the same hue, pale and gentle. */
const pastelOf = (c: string) => {
  const [h] = hexToHsl(c)
  return hsl(h, 0.55, 0.84)
}
/** For gradients: a brighter, warmer neighbour of the colour and a deeper, cooler one. */
const glowOf = (c: string) => {
  const [h, s] = hexToHsl(c)
  return hsl((h + 330) % 360, Math.max(s, 0.6), 0.58)
}
const partnerOf = (c: string) => {
  const [h, s] = hexToHsl(c)
  return hsl((h + 40) % 360, Math.max(s, 0.5), 0.28)
}

/**
 * A mix's artwork. Three looks:
 * - made for you (Daily Mixes, Discover Weekly...): a colour gradient, like cover art;
 * - "This Is" and stations: the composer's, singer's or song's picture on a soft pastel;
 * - everything else (moods, decades, charts): the covers of its first movies.
 * All carry the "Jukebox" mark and the mix's name.
 */
export function MixCover({ mix, size = 150, fill }: { mix: Mix; size?: number; fill?: boolean }) {
  const big = size > 120
  const tiny = size < 80
  const tint = mixTint(mix)
  const pastel = mix.round || mix.endless
  const gradient = !pastel && mix.personal
  const text = pastel ? '#1F1B24' : '#fff'
  const background = pastel ? pastelOf(tint) : gradient ? `linear-gradient(135deg, ${glowOf(tint)}, ${tint}, ${partnerOf(tint)})` : tint
  const box: React.CSSProperties = fill
    ? { width: '100%', aspectRatio: '1', borderRadius: size / 18 }
    : { width: size, height: size, borderRadius: size / 18 }
  return (
    <div style={{ ...box, position: 'relative', overflow: 'hidden', background, flexShrink: 0, containerType: 'inline-size' }}>
      {pastel ? (
        <Cover
          coverArt={mix.covers[0]}
          size={300}
          round={mix.round}
          corner={size / 16}
          style={{
            position: 'absolute', left: '50%', top: '50%', transform: `translate(-50%, ${tiny ? '-50%' : 'calc(-50% - 8%)'})`,
            width: `${tiny ? 72 : 56}%`, height: `${tiny ? 72 : 56}%`,
          }}
        />
      ) : gradient ? null : mix.covers.length >= 4 ? (
        <div style={{ position: 'absolute', inset: 0, display: 'grid', gridTemplateColumns: '1fr 1fr', gridTemplateRows: '1fr 1fr' }}>
          {mix.covers.slice(0, 4).map((c, i) => (
            <Cover key={i} coverArt={c} size={200} corner={0} style={{ width: '100%', height: '100%' }} />
          ))}
        </div>
      ) : mix.covers.length ? (
        <Cover coverArt={mix.covers[0]} size={400} corner={0} style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }} />
      ) : null}
      {/* Covers get darker towards the bottom so the name stays readable on any picture. */}
      {!pastel && !gradient && <div style={{ position: 'absolute', inset: 0, background: `linear-gradient(transparent 35%, ${tint}f2)` }} />}
      {!tiny && (
        <>
          <div style={{ position: 'absolute', top: '5.5%', left: '5.5%', display: 'flex', alignItems: 'center', gap: 3, color: text }}>
            <Icon name={mix.endless ? 'radio' : 'music_note'} filled size={big ? 14 : 10} />
            <span style={{ fontWeight: 700, fontSize: big ? 12 : 9 }}>Jukebox</span>
          </div>
          <div
            className="clamp2"
            style={{
              position: 'absolute', left: '7%', right: '7%', bottom: '6%', color: text, fontWeight: 800,
              fontSize: big ? (gradient ? 26 : 20) : 14, lineHeight: big ? (gradient ? '28px' : '22px') : '15px',
            }}
          >
            {mix.title}
          </div>
        </>
      )}
    </div>
  )
}

/** A tile on Home: the artwork, then the mix's one-line description. */
export function MixCard({ mix, onClick }: { mix: Mix; onClick: () => void }) {
  return (
    <div className="card tile" onClick={onClick}>
      <MixCover mix={mix} size={192} fill />
      <div className="body-small muted clamp2" style={{ marginTop: 6 }}>{mix.subtitle}</div>
    </div>
  )
}

/** Your name for "Made for …": your name on the friends server, or else your username. */
export function madeForName(): string | undefined {
  const s = session()
  return s.social?.user.displayName?.trim() || s.credentials?.username
}

/** Home's rows of mixes ("Made for you", "Moods and vibes", ...). */
export function MixSections({ sections, nav }: { sections: MixSection[]; nav: Nav }) {
  const name = madeForName()
  return (
    <>
      {sections
        .filter((s) => s.mixes.length)
        .map((section) => (
          <div key={section.id}>
            {/* "Made for Devs", like Spotify's "Made For <name>". */}
            <SectionTitle>{section.id === 'made-for-you' && name ? `Made for ${name}` : section.title}</SectionTitle>
            <div className="row-scroll">
              {section.mixes.map((m) => (
                <MixCard key={m.id} mix={m} onClick={() => nav.openMix(m.id)} />
              ))}
            </div>
          </div>
        ))}
    </>
  )
}

/** "Updated today", "Updated yesterday", "Updated 12 Sep". */
export function updatedText(millis: number): string {
  if (millis <= 0) return ''
  const day = new Date(millis)
  const today = new Date()
  const same = (a: Date, b: Date) => a.toDateString() === b.toDateString()
  const yesterday = new Date(today)
  yesterday.setDate(today.getDate() - 1)
  return 'Updated ' + (same(day, today) ? 'today' : same(day, yesterday) ? 'yesterday' : day.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' }))
}

/** The Liked songs tile: a heart on the accent colour. */
export function LikedTile({ size, fill }: { size: number; fill?: boolean }) {
  return (
    <div
      style={{
        ...(fill ? { width: '100%', aspectRatio: '1' } : { width: size, height: size }),
        borderRadius: size / 10, background: 'var(--primary)', color: 'var(--on-primary)',
        display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0,
      }}
    >
      <Icon name="favorite" filled size={size * 0.45} />
    </div>
  )
}
