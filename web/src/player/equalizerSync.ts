import { social } from '../api/social'
import type { SoundProfile } from '../api/socialTypes'
import { session } from '../state/session'
import { sameGains, setEq, useEqualizer, type EqProfile } from './equalizer'

// Your own equalizer profiles, the same on all your devices (SoundProfiles.kt on the server, SoundSync in the
// Android app). This device sends all of its profiles and gets all of yours back; for each, the newest change
// wins. It syncs when the friends connection opens, when a profile changes here, and when the server says one
// changed on another device. Whether the equalizer is on, and the curve playing, stay with each device.

/** Your profiles from both sides: each one's newest change (the other side's on a tie). */
export function mergeProfiles(mine: EqProfile[], theirs: EqProfile[]): EqProfile[] {
  const merged = new Map(mine.map((p) => [p.id, p]))
  for (const p of theirs) {
    const had = merged.get(p.id)
    if (!had || (p.updatedAt ?? 0) >= (had.updatedAt ?? 0)) merged.set(p.id, p)
  }
  return [...merged.values()]
}

const key = (ps: EqProfile[]) => JSON.stringify(ps.map((p) => [p.id, p.name, p.gains, p.updatedAt ?? 0, !!p.deleted]))

let running: Promise<void> | null = null
let again = false

/** Syncs now (or right after the sync that's running). */
export function syncSoundProfiles(): Promise<void> {
  if (running) {
    again = true
    return running
  }
  running = (async () => {
    do {
      again = false
      await syncOnce()
    } while (again)
  })().finally(() => (running = null))
  return running
}

async function syncOnce() {
  const s = useEqualizer.getState()
  if (!s.sync || !session().social) return
  const sent: SoundProfile[] = s.custom.map((p) => ({ id: p.id, name: p.name, gains: p.gains, updatedAt: p.updatedAt ?? 0, deleted: !!p.deleted }))
  const server = await social.syncSoundProfiles(sent).catch(() => null)
  if (!server) return
  // Profiles may have changed here while it was syncing: merge with what's here now.
  const now = useEqualizer.getState()
  const custom = mergeProfiles(now.custom, server.map((p) => ({ ...p })))
  if (key(custom) === key(now.custom)) return
  // The profile you chose was changed on another device, and you hadn't changed its curve here: play the new one.
  const before = now.custom.find((p) => p.id === now.profileId)
  const after = custom.find((p) => p.id === now.profileId)
  const follow = before && after && !after.deleted && sameGains(before.gains, now.gains) && !sameGains(after.gains, now.gains)
  fromServer = true
  setEq({
    custom,
    ...(follow ? { gains: [...after.gains] } : {}),
    ...(after?.deleted ? { profileId: null } : {}),
  })
  fromServer = false
}

/** Set while taking in the server's profiles, so that isn't sent straight back. */
let fromServer = false

// A profile changed here (saved, renamed, deleted), or sync was switched on: sync.
useEqualizer.subscribe((s, before) => {
  if (fromServer) return
  if ((s.sync && !before.sync) || (s.sync && key(s.custom) !== key(before.custom))) void syncSoundProfiles()
})
