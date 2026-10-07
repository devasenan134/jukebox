import { useState } from 'react'
import { useDevices } from '../state/devices'
import { usePlayer } from './player'
import { Dialog, Icon, IconButton } from '../ui/kit'
import { PlayingBars } from '../ui/components'

export function DevicePickerButton() {
  const [open, setOpen] = useState(false)
  const devices = useDevices((s) => s.devices)
  const currentId = useDevices((s) => s.currentDeviceId)
  const activeId = useDevices((s) => s.activeDeviceId)

  // Active device that is not this device
  const remoteActive = devices.find((d) => d.id === activeId && d.id !== currentId)
  const isRemotePlaying = remoteActive?.playing

  const buttonIcon = isRemotePlaying
    ? remoteActive?.type === 'android' ? 'smartphone' : 'devices'
    : 'devices'

  const activeColor = isRemotePlaying ? 'var(--primary)' : undefined

  return (
    <>
      <IconButton
        icon={buttonIcon}
        label={isRemotePlaying ? `Listening on ${remoteActive.name}` : 'Connect to a device'}
        onClick={() => setOpen(true)}
        color={activeColor}
        size={20}
      />
      {open && <DevicePickerDialog onClose={() => setOpen(false)} />}
    </>
  )
}

export function DevicePickerDialog({ onClose }: { onClose: () => void }) {
  const devices = useDevices((s) => s.devices)
  const currentId = useDevices((s) => s.currentDeviceId)
  const activeId = useDevices((s) => s.activeDeviceId)
  const transferPlayback = useDevices((s) => s.transferPlayback)
  const sendRemoteCommand = useDevices((s) => s.sendRemoteCommand)
  const isLocalPlaying = usePlayer((s) => s.isPlaying)

  const getDeviceIcon = (type: string) => {
    switch (type) {
      case 'android': return 'smartphone'
      case 'desktop': return 'desktop_windows'
      case 'web': return 'laptop'
      default: return 'devices'
    }
  }

  return (
    <Dialog title="Connect to a device" onClose={onClose}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8, minWidth: 320, maxWidth: 440 }}>
        {devices.length === 0 ? (
          <div style={{ padding: '16px 0', textAlign: 'center', color: 'var(--on-surface-variant)' }}>
            <Icon name="devices" size={32} style={{ marginBottom: 8, opacity: 0.6 }} />
            <div>No other devices connected right now.</div>
            <div className="body-small" style={{ marginTop: 4 }}>
              Open Jukebox on your phone, tablet, or another browser to listen and control together.
            </div>
          </div>
        ) : (
          devices.map((device) => {
            const isCurrent = device.id === currentId
            const isActive = device.id === activeId || (isCurrent && !activeId && isLocalPlaying)
            const isPlaying = isCurrent ? isLocalPlaying : device.playing

            return (
              <div
                key={device.id}
                onClick={() => {
                  if (!isCurrent) {
                    transferPlayback(device.id)
                  }
                }}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  padding: '12px 14px',
                  borderRadius: 12,
                  background: isActive ? 'rgba(var(--primary-rgb, 120, 200, 120), 0.12)' : 'rgba(255, 255, 255, 0.04)',
                  border: isActive ? '1px solid var(--primary)' : '1px solid transparent',
                  cursor: isCurrent ? 'default' : 'pointer',
                  transition: 'background 0.15s, border-color 0.15s',
                }}
              >
                <div style={{ marginRight: 14, color: isActive ? 'var(--primary)' : 'inherit', display: 'flex', alignItems: 'center' }}>
                  <Icon name={getDeviceIcon(device.type)} size={26} />
                </div>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontWeight: 600, display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span className="ellipsis">{device.name}</span>
                    {isCurrent && (
                      <span className="body-small" style={{ opacity: 0.6, fontWeight: 400 }}>
                        (This device)
                      </span>
                    )}
                  </div>
                  <div className="body-small" style={{ color: isActive ? 'var(--primary)' : 'var(--on-surface-variant)', marginTop: 2 }}>
                    {isPlaying ? (
                      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                        <PlayingBars playing={isPlaying} />
                        <span className="ellipsis">{device.song?.title || 'Playing'}</span>
                      </span>
                    ) : isActive ? (
                      'Selected'
                    ) : (
                      'Connected'
                    )}
                  </div>
                </div>

                {!isCurrent && (
                  <div style={{ display: 'flex', alignItems: 'center', gap: 4 }} onClick={(e) => e.stopPropagation()}>
                    {isActive && (
                      <>
                        <IconButton
                          icon={device.playing ? 'pause' : 'play_arrow'}
                          label={device.playing ? 'Pause' : 'Play'}
                          size={20}
                          onClick={() => sendRemoteCommand(device.id, device.playing ? 'pause' : 'play')}
                        />
                        <IconButton
                          icon="skip_next"
                          label="Next song"
                          size={20}
                          onClick={() => sendRemoteCommand(device.id, 'next')}
                        />
                      </>
                    )}
                    <button
                      className="btn tonal"
                      style={{ padding: '6px 12px', fontSize: 13, borderRadius: 20 }}
                      onClick={() => transferPlayback(device.id)}
                    >
                      {isActive ? 'Active' : 'Play here'}
                    </button>
                  </div>
                )}
              </div>
            )
          })
        )}
      </div>
    </Dialog>
  )
}
