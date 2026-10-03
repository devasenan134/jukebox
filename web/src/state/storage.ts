// Small wrappers around localStorage. It can be missing or throw (private windows, blocked
// storage), so every read and write is guarded and the app keeps working without it.

const PREFIX = 'isaipetti.'

export function load<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(PREFIX + key)
    return raw == null ? fallback : (JSON.parse(raw) as T)
  } catch {
    return fallback
  }
}

export function save(key: string, value: unknown) {
  try {
    localStorage.setItem(PREFIX + key, JSON.stringify(value))
  } catch {
    // Storage full or blocked: this device just won't remember it.
  }
}

export function remove(key: string) {
  try {
    localStorage.removeItem(PREFIX + key)
  } catch {
    // ignore
  }
}

/** Everything this app saved, on logout (the next account has its own history). */
export function clearAll(keep: string[] = []) {
  try {
    Object.keys(localStorage)
      .filter((k) => k.startsWith(PREFIX) && !keep.some((x) => k === PREFIX + x))
      .forEach((k) => localStorage.removeItem(k))
  } catch {
    // ignore
  }
}
