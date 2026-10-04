import { useEffect, useState } from 'react'
import { subsonic } from '../api/subsonic'

// A cover's colour, for page headers, the mini player and the full player: picked in the browser from a
// small copy of the picture (as the app's ui/player/CoverColor.kt does), then darkened so white text reads on it.

const cache = new Map<string, string>()
export const NEUTRAL = 'hsl(0 0% 32%)'

function pick(data: Uint8ClampedArray): string {
  // Favour colourful, mid-light pixels: weigh each by its saturation, skip near-black and near-white.
  let r = 0, g = 0, b = 0, weight = 0
  let ar = 0, ag = 0, ab = 0, n = 0
  for (let i = 0; i < data.length; i += 4) {
    const pr = data[i], pg = data[i + 1], pb = data[i + 2]
    ar += pr; ag += pg; ab += pb; n++
    const max = Math.max(pr, pg, pb), min = Math.min(pr, pg, pb)
    const l = (max + min) / 510
    if (l < 0.12 || l > 0.9) continue
    const s = max === min ? 0 : (max - min) / (255 - Math.abs(max + min - 255))
    const w = s * s
    r += pr * w; g += pg * w; b += pb * w; weight += w
  }
  const [cr, cg, cb] = weight > 4 ? [r / weight, g / weight, b / weight] : [ar / n, ag / n, ab / n]
  // To HSL, then a calm, dark version of it.
  const max = Math.max(cr, cg, cb) / 255, min = Math.min(cr, cg, cb) / 255
  const l = (max + min) / 2
  const d = max - min
  let h = 0
  const s = d === 0 ? 0 : d / (1 - Math.abs(2 * l - 1))
  if (d !== 0) {
    const [R, G, B] = [cr / 255, cg / 255, cb / 255]
    h = max === R ? ((G - B) / d) % 6 : max === G ? (B - R) / d + 2 : (R - G) / d + 4
    h = (h * 60 + 360) % 360
  }
  return `hsl(${Math.round(h)} ${Math.round(Math.min(s, 0.62) * 100)}% ${s < 0.08 ? 30 : 34}%)`
}

/** The colour of a cover (dark enough for white text on it), or [fallback] until it's known. */
export function useCoverColor(coverArt: string | null | undefined, fallback = NEUTRAL): string {
  const [color, setColor] = useState(() => (coverArt && cache.get(coverArt)) || fallback)
  useEffect(() => {
    if (!coverArt) return setColor(fallback)
    const known = cache.get(coverArt)
    if (known) return setColor(known)
    const src = subsonic.coverUrl(coverArt, 150)
    if (!src) return
    let alive = true
    const img = new Image()
    img.onload = () => {
      try {
        const c = document.createElement('canvas')
        c.width = c.height = 24
        const ctx = c.getContext('2d', { willReadFrequently: true })!
        ctx.drawImage(img, 0, 0, 24, 24)
        const found = pick(ctx.getImageData(0, 0, 24, 24).data)
        cache.set(coverArt, found)
        if (alive) setColor(found)
      } catch {
        // A picture the browser won't let us read: keep the neutral colour.
      }
    }
    img.src = src
    return () => {
      alive = false
    }
  }, [coverArt, fallback])
  return color
}
