import { useEffect, useState, type CSSProperties, type ReactNode } from 'react'
import { subsonic } from '../api/subsonic'
import { Cover } from './components'
import { NEUTRAL, useCoverColor } from './coverColor'
import { IconButton } from './kit'
import { useNav } from './nav'

/**
 * A page for a collection of songs (album, playlist, mix, Liked songs, an artist): the header takes its
 * colour from the picture and fades into the page; the play bar and songs follow as children.
 */
export function Collection({ coverArt, art, round, kind, title, meta, tint, children }: {
  coverArt?: string | null
  /** A picture of its own (a mix's cover, the Liked songs heart) instead of coverArt. */
  art?: ReactNode
  round?: boolean
  /** "Album", "Playlist", "Mix"... shown above the name on wide screens. */
  kind: string
  title: string
  meta: ReactNode
  /** A colour to use instead of the picture's. */
  tint?: string
  children: ReactNode
}) {
  const nav = useNav()
  const fromCover = useCoverColor(tint ? null : coverArt, NEUTRAL)
  const color = tint ?? fromCover
  return (
    <div className="page" style={{ '--tint': color } as CSSProperties}>
      <div className="hero-top" style={{ position: 'absolute', top: 0, left: 0, zIndex: 6, padding: 8 }}>
        <IconButton icon="arrow_back" label="Back" onClick={nav.back} style={{ background: 'rgba(0,0,0,0.45)', color: '#fff' }} />
      </div>
      <div className={`hero${round ? ' round-art' : ''}`}>
        {art ?? <Cover coverArt={coverArt} size={600} className="art" round={round} />}
        <div className="text">
          <div className="kind">{kind}</div>
          <h1 data-testid="page-title">{title}</h1>
          <div className="meta">{meta}</div>
        </div>
      </div>
      {children}
    </div>
  )
}

/** Column names over a list of songs on wide screens. */
export function TrackHead({ album = true }: { album?: boolean }) {
  return (
    <div className="track-head">
      <span className="num">#</span>
      <span className="t">Title</span>
      {album && <span className="album-col">Album</span>}
      <span className="dur" title="Length">
        <span className="icon" style={{ fontSize: 18 }}>schedule</span>
      </span>
    </div>
  )
}

/** Soft shapes in place of a page that's still loading: a header, then rows. */
export function PageSkeleton({ hero = true, rows = 8 }: { hero?: boolean; rows?: number }) {
  return (
    <div className="page" aria-busy="true" aria-label="Loading">
      {hero && (
        <div className="hero" style={{ ['--tint' as string]: '#2a2a2a' }}>
          <div className="art skel" />
          <div className="text">
            <div className="skel" style={{ width: 70, height: 12, marginBottom: 12 }} />
            <div className="skel" style={{ width: '60%', height: 44, marginBottom: 12 }} />
            <div className="skel" style={{ width: '40%', height: 14 }} />
          </div>
        </div>
      )}
      <div style={{ padding: '24px 16px' }}>
        {Array.from({ length: rows }, (_, i) => (
          <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '8px 0' }}>
            <div className="skel" style={{ width: 40, height: 40, flexShrink: 0 }} />
            <div style={{ flex: 1 }}>
              <div className="skel" style={{ width: `${55 - (i % 3) * 10}%`, height: 13, marginBottom: 8 }} />
              <div className="skel" style={{ width: `${30 + (i % 2) * 8}%`, height: 11 }} />
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

/** Rows of cards still loading (Home, lists of albums). */
export function CardsSkeleton({ rows = 2, title = true }: { rows?: number; title?: boolean }) {
  return (
    <div aria-busy="true" aria-label="Loading">
      {Array.from({ length: rows }, (_, r) => (
        <div key={r}>
          {title && <div className="skel" style={{ width: 180, height: 22, margin: '28px 16px 14px' }} />}
          <div className="row-scroll" style={{ overflow: 'hidden' }}>
            {Array.from({ length: 7 }, (_, i) => (
              <div key={i} className="tile" style={{ padding: 10 }}>
                <div className="skel" style={{ width: '100%', aspectRatio: '1' }} />
                <div className="skel" style={{ width: '75%', height: 13, marginTop: 12 }} />
                <div className="skel" style={{ width: '50%', height: 11, marginTop: 8 }} />
              </div>
            ))}
          </div>
        </div>
      ))}
    </div>
  )
}

/** "1 album • 12 songs • 48 min": the parts of a header's line, with dots between. */
export function Meta({ parts }: { parts: (ReactNode | null | undefined | false)[] }) {
  const shown = parts.filter((p) => p != null && p !== false && p !== '')
  return (
    <>
      {shown.map((p, i) => (
        <span key={i} className={i > 0 ? 'dot' : undefined}>{p}</span>
      ))}
    </>
  )
}

export const heroArtStyle: CSSProperties = { width: 'min(240px, 62vw)' }

const AVATAR_HUES = [12, 28, 45, 140, 168, 198, 220, 262, 290, 330]

/** A person's picture: their initials on a colour picked from their name (there are no photos of people yet). */
/** A person's photo (or their newest album's cover) in a circle, over their initials, which show until it loads or if there's none. */
export function PersonAvatar({ name, size, fill, coverArt }: { name: string; size?: number; fill?: boolean; coverArt?: string | null }) {
  const [failed, setFailed] = useState(false)
  useEffect(() => setFailed(false), [coverArt])
  const src = coverArt && !failed ? subsonic.coverUrl(coverArt, (size ?? 300) > 160 || fill ? 600 : 150) : undefined
  let h = 0
  for (const c of name) h = (h * 31 + c.charCodeAt(0)) >>> 0
  const hue = AVATAR_HUES[h % AVATAR_HUES.length]
  // "A.R. Rahman" → AR, "Harris Jayaraj" → HJ, "Ilaiyaraaja" → I.
  const initials = name
    .replace(/[^\p{L}\s.]/gu, '')
    .split(/[\s.]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0].toUpperCase())
    .join('') || name.slice(0, 1).toUpperCase()
  const box: CSSProperties = fill ? { width: '100%', aspectRatio: '1' } : { width: size, height: size }
  return (
    <div
      className="art"
      style={{
        ...box, borderRadius: '50%', flexShrink: 0, display: 'flex', alignItems: 'center', justifyContent: 'center',
        background: `linear-gradient(135deg, hsl(${hue} 55% 46%), hsl(${(hue + 40) % 360} 50% 24%))`,
        color: 'rgba(255,255,255,0.92)', fontFamily: 'var(--display)', fontWeight: 800, letterSpacing: '-0.02em',
        fontSize: fill ? 'clamp(28px, 30cqi, 72px)' : (size ?? 48) * 0.38, containerType: 'inline-size', boxShadow: '0 8px 24px rgba(0,0,0,0.5)',
        position: 'relative', overflow: 'hidden',
      }}
    >
      {initials}
      {src && (
        <img
          src={src}
          alt=""
          loading="lazy"
          draggable={false}
          onError={() => setFailed(true)}
          style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover' }}
        />
      )}
    </div>
  )
}
