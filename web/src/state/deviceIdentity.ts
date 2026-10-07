const DEVICE_ID_KEY = 'jukebox.deviceId'
const DEVICE_NAME_KEY = 'jukebox.deviceName'

export function getDeviceId(): string {
  try {
    let id = localStorage.getItem(DEVICE_ID_KEY)
    if (!id) {
      id = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
        ? crypto.randomUUID()
        : `web-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
      localStorage.setItem(DEVICE_ID_KEY, id)
    }
    return id
  } catch {
    return `web-${Date.now()}`
  }
}

export function getDeviceName(): string {
  try {
    const custom = localStorage.getItem(DEVICE_NAME_KEY)
    if (custom) return custom
  } catch {}
  if (typeof window !== 'undefined' && (window as unknown as { __TAURI__?: unknown }).__TAURI__) {
    return 'Mac App'
  }
  const ua = typeof navigator !== 'undefined' ? navigator.userAgent : ''
  if (/Macintosh|Mac OS X/.test(ua)) return 'Web (Mac)'
  if (/Windows/.test(ua)) return 'Web (Windows)'
  if (/Linux/.test(ua)) return 'Web (Linux)'
  if (/Android/.test(ua)) return 'Web (Android)'
  if (/iPhone|iPad/.test(ua)) return 'Web (iOS)'
  return 'Web Browser'
}

export function setDeviceName(name: string) {
  try {
    localStorage.setItem(DEVICE_NAME_KEY, name.trim())
  } catch {}
}

export function getClientType(): string {
  if (typeof window !== 'undefined' && (window as unknown as { __TAURI__?: unknown }).__TAURI__) {
    return 'desktop'
  }
  return 'web'
}
