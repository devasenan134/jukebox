import { create } from 'zustand'
import { load, save } from '../state/storage'

// The equalizer: ten bands, an octave apart, shaping what the player plays (Settings › Sound). The same bands,
// filters and built-in profiles as the Android app (data/SoundSettings.kt, playback/EqualizerProcessor.kt), so a
// profile sounds the same on both. Saved in this browser (and in the Mac app, on the Mac).
//
// The music goes <audio> → preamp → 10 peaking filters → speakers, through Web Audio. A media element can't be
// taken back out of Web Audio once it's in, so nothing is connected until the equalizer is first switched on;
// switched off, the filters are set flat (a flat peaking filter passes the sound through untouched).

/** Centre frequencies of the bands (Hz). */
export const EQ_BANDS = [32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000]
/** Each band's width: about an octave. */
export const EQ_Q = 1.41
/** How far a band goes up or down (dB). */
export const EQ_MAX_DB = 12

export interface EqProfile {
  id: string
  name: string
  /** One gain per band, in dB. */
  gains: number[]
  /** Built-in ones: where Settings lists them. */
  group?: 'tuning' | 'genre'
  /** Your own: when it last changed (the newest change wins when your devices sync). */
  updatedAt?: number
  /** Your own, deleted: kept so the deletion reaches your other devices. */
  deleted?: boolean
}

const flat = () => EQ_BANDS.map(() => 0)

/**
 * Built-in profiles: tuning for headphones and IEMs, and genres (like the presets music players have). Ids start
 * with "builtin:"; they can't be changed or deleted, only saved as your own. Flat comes first (Reset uses it).
 */
export const BUILTIN_PROFILES: EqProfile[] = [
  { id: 'builtin:flat', name: 'Flat', group: 'tuning', gains: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0] },
  { id: 'builtin:bass', name: 'Bass Booster', group: 'tuning', gains: [5.5, 4.5, 3.5, 2.5, 1, 0, 0, 0, 0, 0] },
  { id: 'builtin:subbass', name: 'Sub-bass', group: 'tuning', gains: [6, 4, 1.5, 0, 0, 0, 0, 0, 0, 0] },
  { id: 'builtin:bassreducer', name: 'Bass Reducer', group: 'tuning', gains: [-5.5, -4.5, -3.5, -2.5, -1, 0, 0, 0, 0, 0] },
  { id: 'builtin:bright', name: 'Treble Booster', group: 'tuning', gains: [0, 0, 0, 0, 0, 1, 2.5, 3.5, 4.5, 5.5] },
  { id: 'builtin:treblereducer', name: 'Treble Reducer', group: 'tuning', gains: [0, 0, 0, 0, 0, -1, -2.5, -3.5, -4.5, -5.5] },
  { id: 'builtin:tametreble', name: 'Tame Treble', group: 'tuning', gains: [0, 0, 0, 0, 0, 0, 0, -2, -4, -2] },
  { id: 'builtin:vshape', name: 'V-Shape', group: 'tuning', gains: [5, 4, 2.5, 0, -2, -2, 0, 2.5, 4, 4.5] },
  { id: 'builtin:warm', name: 'Warm', group: 'tuning', gains: [3, 3, 2, 1, 0, 0, -1, -1.5, -2, -2.5] },
  { id: 'builtin:vocal', name: 'Vocal Booster', group: 'tuning', gains: [-1.5, -3, -3, 1.5, 3.5, 3.5, 3, 1.5, 0, -1.5] },
  { id: 'builtin:loudness', name: 'Loudness', group: 'tuning', gains: [6, 4, 0, 0, -2, 0, -1, -5, 5, 1] },
  { id: 'builtin:deep', name: 'Deep', group: 'tuning', gains: [5, 3.5, 1.5, 1, 3, 2.5, 1.5, -2, -3.5, -4.5] },
  { id: 'builtin:smallspeakers', name: 'Small Speakers', group: 'tuning', gains: [5.5, 4.5, 3.5, 2.5, 1, 0, -1, -2.5, -3.5, -4.5] },
  { id: 'builtin:spoken', name: 'Spoken Word', group: 'tuning', gains: [-3.5, -0.5, 0, 0.5, 3.5, 4.5, 5, 4.5, 2.5, 0] },
  { id: 'builtin:acoustic', name: 'Acoustic', group: 'genre', gains: [5, 5, 4, 1, 2, 1.5, 3.5, 4, 3.5, 2] },
  { id: 'builtin:classical', name: 'Classical', group: 'genre', gains: [4.5, 3.5, 3, 2.5, -1.5, -1.5, 0, 2, 3, 3.5] },
  { id: 'builtin:dance', name: 'Dance', group: 'genre', gains: [3.5, 6.5, 5, 0, 2, 3.5, 5, 4.5, 3.5, 0] },
  { id: 'builtin:electronic', name: 'Electronic', group: 'genre', gains: [4, 4, 1, 0, -2, 2, 1, 1, 4, 5] },
  { id: 'builtin:hiphop', name: 'Hip-Hop', group: 'genre', gains: [5, 4, 1.5, 3, -1, -1, 1.5, -0.5, 2, 3] },
  { id: 'builtin:jazz', name: 'Jazz', group: 'genre', gains: [4, 3, 1.5, 2, -1.5, -1.5, 0, 1.5, 3, 3.5] },
  { id: 'builtin:latin', name: 'Latin', group: 'genre', gains: [4.5, 3, 0, 0, -1.5, -1.5, -1.5, 0, 3, 4.5] },
  { id: 'builtin:lounge', name: 'Lounge', group: 'genre', gains: [-3, -1.5, -0.5, 1.5, 4, 2.5, 0, -1.5, 2, 1] },
  { id: 'builtin:piano', name: 'Piano', group: 'genre', gains: [3, 2, 0, 2.5, 3, 1.5, 3.5, 4.5, 3, 3.5] },
  { id: 'builtin:pop', name: 'Pop', group: 'genre', gains: [-1.5, -1, 0, 2, 4, 4, 2, 0, -1, -1.5] },
  { id: 'builtin:rnb', name: 'R&B', group: 'genre', gains: [2.5, 7, 5.5, 1.5, -2, -1.5, 2.5, 2.5, 3, 3.5] },
  { id: 'builtin:rock', name: 'Rock', group: 'genre', gains: [5, 4, 3, 1.5, -0.5, -1, 0.5, 2.5, 3.5, 4.5] },
]

// ---- The filters' sound, worked out the same way the Android app does it ----

/** The response in dB at frequency f (Hz) of the bands with these gains, at sample rate fs. */
export function responseDb(gains: number[], f: number, fs = 48_000): number {
  const w = (2 * Math.PI * f) / fs
  let db = 0
  gains.forEach((g, i) => {
    if (g === 0 || EQ_BANDS[i] >= fs * 0.45) return
    const [b0, b1, b2, a0, a1, a2] = peaking(EQ_BANDS[i], g, fs)
    // |H(e^jw)|² for a biquad.
    const c1 = Math.cos(w)
    const c2 = Math.cos(2 * w)
    const num = b0 * b0 + b1 * b1 + b2 * b2 + 2 * (b0 * b1 + b1 * b2) * c1 + 2 * b0 * b2 * c2
    const den = a0 * a0 + a1 * a1 + a2 * a2 + 2 * (a0 * a1 + a1 * a2) * c1 + 2 * a0 * a2 * c2
    db += 10 * Math.log10(num / den)
  })
  return db
}

/** A peaking filter's coefficients (b0, b1, b2, a0, a1, a2), from the Audio EQ Cookbook (as Web Audio uses). */
function peaking(f0: number, gainDb: number, fs: number): number[] {
  const a = Math.pow(10, gainDb / 40)
  const w0 = (2 * Math.PI * f0) / fs
  const alpha = Math.sin(w0) / (2 * EQ_Q)
  const cos = Math.cos(w0)
  return [1 + alpha * a, -2 * cos, 1 - alpha * a, 1 + alpha / a, -2 * cos, 1 - alpha / a]
}

/** Frequencies the curve is drawn and checked at: 20 Hz to 20 kHz, evenly spaced on a log scale. */
export const CURVE_FREQS = Array.from({ length: 121 }, (_, i) => 20 * Math.pow(1000, i / 120))

/**
 * The preamp (dB, zero or less): turns everything down by the curve's highest boost, so boosted bands don't
 * clip. Without it, +6 dB of bass on a loud song distorts.
 */
export function preampDb(gains: number[]): number {
  const peak = Math.max(0, ...CURVE_FREQS.map((f) => responseDb(gains, f)))
  return -Math.round(peak * 10) / 10 || 0
}

// ---- Settings, kept in this browser ----

interface EqState {
  enabled: boolean
  /** What's playing now (may be a profile you've changed and not saved). */
  gains: number[]
  /** The profile last chosen or saved, or null. */
  profileId: string | null
  /** Your own profiles (deleted ones too, marked). */
  custom: EqProfile[]
  /** Keep your own profiles the same on all your devices (through your account). */
  sync: boolean
}

const KEY = 'player.equalizer'

function restored(): EqState {
  const s = load<Partial<EqState> | null>(KEY, null)
  const ok = (g: unknown): g is number[] => Array.isArray(g) && g.length === EQ_BANDS.length && g.every((x) => typeof x === 'number')
  return {
    enabled: s?.enabled === true,
    gains: ok(s?.gains) ? s.gains : flat(),
    profileId: typeof s?.profileId === 'string' ? s.profileId : 'builtin:flat',
    custom: Array.isArray(s?.custom) ? s.custom.filter((p) => p && typeof p.id === 'string' && typeof p.name === 'string' && ok(p.gains)) : [],
    sync: s?.sync !== false,
  }
}

export const useEqualizer = create<EqState>(restored)

export const setEq = (s: Partial<EqState>) => {
  useEqualizer.setState(s)
  const { enabled, gains, profileId, custom, sync } = useEqualizer.getState()
  save(KEY, { enabled, gains, profileId, custom, sync })
}

/** Your own profiles, without the deleted ones. */
export const ownProfiles = (s: EqState = useEqualizer.getState()) => s.custom.filter((p) => !p.deleted)

export const allProfiles = (s: EqState = useEqualizer.getState()) => [...BUILTIN_PROFILES, ...ownProfiles(s)]

export const sameGains = (a: number[], b: number[]) => a.every((g, i) => Math.abs(g - b[i]) < 0.05)

export const clampGain = (g: number) => Math.max(-EQ_MAX_DB, Math.min(EQ_MAX_DB, Math.round(g * 2) / 2))

export function setEqEnabled(enabled: boolean) {
  setEq({ enabled })
}

/** Changes one band (dB); the equalizer turns on if it was off. */
export function setBandGain(band: number, gain: number) {
  const gains = [...useEqualizer.getState().gains]
  gains[band] = clampGain(gain)
  setEq({ gains, enabled: true })
}

/** Plays with a profile's curve (and turns the equalizer on). */
export function chooseProfile(id: string) {
  const p = allProfiles().find((x) => x.id === id)
  if (p) setEq({ gains: [...p.gains], profileId: id, enabled: true })
}

/** Saves the current curve as a new profile of yours, and chooses it. */
export function saveProfileAs(name: string) {
  const s = useEqualizer.getState()
  const p: EqProfile = { id: `custom:${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`, name: name.trim(), gains: [...s.gains], updatedAt: Date.now() }
  setEq({ custom: [...s.custom, p], profileId: p.id })
}

/** Saves the current curve over the chosen profile (only your own). */
export function updateProfile() {
  const s = useEqualizer.getState()
  if (!s.profileId?.startsWith('custom:')) return
  setEq({ custom: s.custom.map((p) => (p.id === s.profileId ? { ...p, gains: [...s.gains], updatedAt: Date.now() } : p)) })
}

export function renameProfile(id: string, name: string) {
  setEq({ custom: useEqualizer.getState().custom.map((p) => (p.id === id ? { ...p, name: name.trim(), updatedAt: Date.now() } : p)) })
}

/** Deletes one of your profiles; the curve that's playing stays. */
export function deleteProfile(id: string) {
  const s = useEqualizer.getState()
  setEq({
    custom: s.custom.map((p) => (p.id === id ? { ...p, deleted: true, updatedAt: Date.now() } : p)),
    profileId: s.profileId === id ? null : s.profileId,
  })
}

export function setProfileSync(sync: boolean) {
  setEq({ sync })
}

// ---- The Web Audio side ----

let elements: HTMLMediaElement[] = []
let graph: { ctx: AudioContext; preamp: GainNode; filters: BiquadFilterNode[] } | null = null
/** Made, but waiting to be allowed to run before the elements are connected. */
let starting: AudioContext | null = null

/** The player's audio elements (player.ts): the equalizer plays them once it's been switched on. */
export function equalizeElements(els: HTMLMediaElement[]) {
  elements = els
  for (const el of els) el.addEventListener('play', () => void wake())
  apply()
}

/**
 * Builds the graph the first time it's needed, then connects the audio elements once the AudioContext runs.
 * A browser only lets a page start an AudioContext after a click or key press. Until then the elements play
 * straight out without the equalizer, rather than going silent into a context that isn't running.
 */
function build() {
  if (graph || !elements.length) return
  if (starting) {
    void starting.resume().catch(() => {})
    return
  }
  const Ctx = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
  if (!Ctx) return
  const ctx = new Ctx({ latencyHint: 'playback' })
  starting = ctx
  const preamp = ctx.createGain()
  const filters = EQ_BANDS.map((f) => {
    const b = ctx.createBiquadFilter()
    b.type = 'peaking'
    b.frequency.value = Math.min(f, ctx.sampleRate * 0.45)
    b.Q.value = EQ_Q
    b.gain.value = 0
    return b
  })
  const chain: AudioNode[] = [preamp, ...filters, ctx.destination]
  chain.slice(1).forEach((node, i) => chain[i].connect(node))
  const connect = () => {
    if (graph || ctx.state !== 'running') return
    for (const el of elements) ctx.createMediaElementSource(el).connect(preamp)
    graph = { ctx, preamp, filters }
    starting = null
    apply()
  }
  ctx.addEventListener('statechange', connect)
  connect()
  if (ctx.state !== 'running') void ctx.resume().catch(() => {})
}

/** Called on clicks and key presses, and when music starts: starts (or restarts) the AudioContext if it's needed. */
function wake() {
  if (!useEqualizer.getState().enabled && !graph) return
  if (!graph) return build()
  if (graph.ctx.state !== 'running') void graph.ctx.resume().catch(() => {})
}
addEventListener('pointerdown', wake, true)
addEventListener('keydown', wake, true)

/** Sets the filters to the current settings (smoothly, so dragging a band doesn't click). */
function apply() {
  const s = useEqualizer.getState()
  if (!graph) {
    if (s.enabled) build()
    return
  }
  const gains = s.enabled ? s.gains : flat()
  const t = graph.ctx.currentTime
  graph.filters.forEach((f, i) => f.gain.setTargetAtTime(gains[i], t, 0.02))
  graph.preamp.gain.setTargetAtTime(Math.pow(10, preampDb(gains) / 20), t, 0.02)
}

useEqualizer.subscribe((s, before) => {
  if (s.enabled !== before.enabled || s.gains !== before.gains) apply()
})
