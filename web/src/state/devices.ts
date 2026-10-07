import { create } from 'zustand'
import type { DeviceInfo, DevicePlaybackState } from '../api/socialTypes'
import { songToRef } from '../api/types'
import { currentItem, usePlayer, onPlayback } from '../player/player'
import * as player from '../player/player'
import { onEvent, sendEvent } from '../social/social'

export { getDeviceId, getDeviceName, getClientType, setDeviceName } from './deviceIdentity'
import { getDeviceId } from './deviceIdentity'

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

let deviceSyncStarted = false

/** Connects device events and playback sync to the realtime gateway at runtime. */
export function startDeviceSync(): () => void {
  if (deviceSyncStarted) return () => {}
  deviceSyncStarted = true

  const unsubEvent = onEvent((event) => {
    if (event.type === 'devices') {
      useDevices.getState().setDevices(event.activeDeviceId, event.devices)
    } else if (event.type === 'remoteCommand') {
      const currentId = useDevices.getState().currentDeviceId
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
      if (event.state.song) {
        if (!usePlayer.getState().isPlaying) {
          player.togglePlay()
        }
        if (event.state.positionMs) {
          setTimeout(() => player.seekTo(event.state.positionMs!), 200)
        }
      }
    }
  })

  const unsubPlayback = onPlayback(() => {
    syncCurrentPlayback()
  })

  // Initial snapshot
  syncCurrentPlayback()

  return () => {
    unsubEvent()
    unsubPlayback()
    deviceSyncStarted = false
  }
}
