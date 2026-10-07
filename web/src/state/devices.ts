import { create } from 'zustand'
import type { DeviceInfo, DevicePlaybackState } from '../api/socialTypes'
import { songToRef } from '../api/types'
import { currentItem, usePlayer } from '../player/player'
import * as player from '../player/player'
import { onEvent, sendEvent } from '../social/social'

const DEVICE_ID_KEY = 'jukebox.deviceId'
const DEVICE_NAME_KEY = 'jukebox.deviceName'

export function getDeviceId(): string {
  let id = localStorage.getItem(DEVICE_ID_KEY)
  if (!id) {
    id = typeof crypto !== 'undefined' && crypto.randomUUID ? crypto.randomUUID() : `web-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
    localStorage.setItem(DEVICE_ID_KEY, id)
  }
  return id
}

export function getDeviceName(): string {
  const custom = localStorage.getItem(DEVICE_NAME_KEY)
  if (custom) return custom
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
  localStorage.setItem(DEVICE_NAME_KEY, name.trim())
}

export function getClientType(): string {
  if (typeof window !== 'undefined' && (window as unknown as { __TAURI__?: unknown }).__TAURI__) {
    return 'desktop'
  }
  return 'web'
}

interface DevicesState {
  currentDeviceId: string
  activeDeviceId: string | null
  devices: DeviceInfo[]
  setDevices: (activeDeviceId: string | null, devices: DeviceInfo[]) => void
  sendRemoteCommand: (targetDeviceId: string, action: string, params?: { positionMs?: number; volume?: number }) => void
  transferPlayback: (toDeviceId: string) => void
  setActiveDevice: (deviceId: string) => void
}

export const useDevices = create<DevicesState>((set) => ({
  currentDeviceId: getDeviceId(),
  activeDeviceId: null,
  devices: [],

  setDevices: (activeDeviceId, devices) => {
    set({ activeDeviceId, devices })
  },

  sendRemoteCommand: (targetDeviceId, action, params) => {
    sendEvent({
      type: 'remoteCommand',
      targetDeviceId,
      action,
      positionMs: params?.positionMs,
      volume: params?.volume,
    })
  },

  transferPlayback: (toDeviceId) => {
    sendEvent({
      type: 'transferPlayback',
      toDeviceId,
    })
  },

  setActiveDevice: (deviceId) => {
    sendEvent({
      type: 'setActiveDevice',
      deviceId,
    })
  },
}))

/** Sends this device's current playback snapshot to the gateway. */
export function syncCurrentPlayback(positionMs?: number) {
  const p = usePlayer.getState()
  const item = currentItem(p)
  const playback: DevicePlaybackState = {
    song: item?.song ? songToRef(item.song) : null,
    queue: p.items.map((it) => songToRef(it.song)),
    index: p.current >= 0 ? p.current : 0,
    positionMs: positionMs ?? 0,
    playing: p.isPlaying,
    volume: p.volume,
    updatedAt: Date.now(),
  }
  sendEvent({ type: 'devicePlayback', playback })
}

// Handle live events from the realtime gateway
onEvent((event) => {
  if (event.type === 'devices') {
    useDevices.getState().setDevices(event.activeDeviceId, event.devices)
  } else if (event.type === 'remoteCommand') {
    const currentId = useDevices.getState().currentDeviceId
    // If byDeviceId is set and it's our own command, ignore
    if (event.byDeviceId === currentId) return

    switch (event.action) {
      case 'play':
        if (!usePlayer.getState().isPlaying) player.togglePlay()
        break
      case 'pause':
        player.pause()
        break
      case 'next':
        player.next()
        break
      case 'previous':
        player.previous()
        break
      case 'seek':
        if (event.positionMs != null) player.seekTo(event.positionMs)
        break
      case 'volume':
        if (event.volume != null) player.setVolume(event.volume)
        break
    }
  } else if (event.type === 'transferPlayback') {
    // If we received a transfer, start playback with the transferred state
    if (event.state.song) {
      // If position is provided, seek to it after a brief tick
      if (!usePlayer.getState().isPlaying) {
        player.togglePlay()
      }
      if (event.state.positionMs) {
        setTimeout(() => player.seekTo(event.state.positionMs!), 200)
      }
    }
  }
})
