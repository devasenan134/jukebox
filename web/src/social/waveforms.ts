import { catalog } from '../api/catalog'

/**
 * The loudness shape of a song (its waveform), for picking a part of it to share: BARS numbers from
 * MIN_BAR to 1. Worked out once by decoding the song in the browser and kept for the session.
 * Same shaping as the Android app's data/Waveforms.kt.
 */
export const BARS = 80
export const MIN_BAR = 0.1

const cache = new Map<string, Promise<number[]>>()

export function waveform(songId: string): Promise<number[]> {
  let p = cache.get(songId)
  if (!p) {
    p = decode(songId)
    p.catch(() => cache.delete(songId))
    cache.set(songId, p)
  }
  return p
}

async function decode(songId: string): Promise<number[]> {
  const res = await fetch(catalog.streamUrl(songId, 'mobile'))
  if (!res.ok) throw new Error(`Couldn't load the song (${res.status})`)
  const data = await res.arrayBuffer()
  const ctx = new OfflineAudioContext(1, 1, 22050)
  const audio = await ctx.decodeAudioData(data)
  const samples = audio.getChannelData(0)
  const squares = new Float64Array(BARS)
  const counts = new Uint32Array(BARS)
  // Every 8th sample is plenty for a picture of loudness.
  for (let i = 0; i < samples.length; i += 8) {
    const bar = Math.min(BARS - 1, Math.floor((i * BARS) / samples.length))
    squares[bar] += samples[i] * samples[i]
    counts[bar]++
  }
  return shape([...squares].map((s, i) => (counts[i] ? Math.sqrt(s / counts[i]) : 0)))
}

/**
 * Film songs are mixed loud almost all the way through, so plain loudness looks like a flat block.
 * Stretch the range from the quiet parts to the loudest so the shape stands out.
 */
function shape(rms: number[]): number[] {
  const heard = rms.filter((x) => x > 0).sort((a, b) => a - b)
  if (!heard.length) return rms.map(() => MIN_BAR)
  const floor = heard[Math.floor(heard.length / 20)] * 0.8
  const top = Math.max(heard[heard.length - 1], floor + 1e-9)
  return rms.map((x) => MIN_BAR + (1 - MIN_BAR) * Math.min(1, Math.max(0, (x - floor) / (top - floor))))
}
