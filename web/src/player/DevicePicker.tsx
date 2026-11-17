import { create } from 'zustand'
import { useDevices } from '../state/devices'
import { usePlayer } from './player'
import { Dialog, IconButton } from '../ui/kit'
import { PlayingBars } from '../ui/components'

export const useDevicePicker = create<{ open: boolean; setOpen: (v: boolean) => void }>((set) => ({
  open: false,
  setOpen: (open) => set({ open }),
}))

function PhoneIcon({ size = 24 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor">
      <path d="M17 1.01L7 1c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V3c0-1.1-.9-1.99-2-1.99zM17 19H7V5h10v14z" />
    </svg>
  )
}

function LaptopIcon({ size = 24 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor">
      <path d="M20 18c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2H4c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2H0v2h24v-2h-4zM4 6h16v10H4V6z" />
    </svg>
  )
}

function DesktopIcon({ size = 24 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor">
      <path d="M21 2H3c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h7v2H8v2h8v-2h-2v-2h7c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm0 14H3V4h18v12z" />
    </svg>
  )
}

function DevicesIcon({ size = 24 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor">
      <path d="M4 6h18V4H4c-1.1 0-2 .9-2 2v11H0v3h14v-3H4V6zm19 2h-6c-.55 0-1 .45-1 1v10c0 .55.45 1 1 1h6c.55 0 1-.45 1-1V9c0-.55-.45-1-1-1zm-1 10h-4v-7h4v7z" />
    </svg>
  )
}

export function DeviceIcon({ type, size = 24 }: { type: string; size?: number }) {
  switch (type) {
    case 'android':
      return <PhoneIcon size={size} />
    case 'web':
      return <LaptopIcon size={size} />
    case 'desktop':
      return <DesktopIcon size={size} />
    default:
      return <DevicesIcon size={size} />
  }
}

export function DevicePickerButton() {
  const { open, setOpen } = useDevicePicker()
  const devices = useDevices((s) => s.devices)
  const currentId = useDevices((s) => s.currentDeviceId)
  const activeId = useDevices((s) => s.activeDeviceId)
  const isLocalPlaying = usePlayer((s) => s.isPlaying)

  // Remote active or playing device that is not this device
  const remotePlaying = devices.find((d) => d.playing && d.id !== currentId)
  const remoteActive = remotePlaying || devices.find((d) => d.id === activeId && d.id !== currentId)
  const isRemotePlaying = !isLocalPlaying && !!remoteActive?.playing

  const activeColor = isRemotePlaying ? 'var(--primary)' : undefined
  const label = isRemotePlaying ? `Listening on ${remoteActive?.name || 'device'}` : 'Connect to a device'

  return (
    <>
      <button
        type="button"
        className="icon-btn"
        aria-label={label}
        title={label}
        style={{ color: activeColor, display: 'inline-flex', alignItems: 'center', justifyContent: 'center' }}
        onClick={(e) => {
          e.stopPropagation()
          setOpen(true)
        }}
      >
        {isRemotePlaying && remoteActive ? (
          <DeviceIcon type={remoteActive.type} size={20} />
        ) : (
          <DevicesIcon size={20} />
        )}
      </button>
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

  const playingDevice = devices.find((d) => (d.id === currentId ? isLocalPlaying : d.playing))
  const effectiveActiveId = playingDevice ? playingDevice.id : (activeId ?? (isLocalPlaying ? currentId : null))

  return (
    <Dialog title="Connect to a device" onClose={onClose}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8, minWidth: 320, maxWidth: 440 }}>
        {devices.length === 0 ? (
          <div style={{ padding: '16px 0', textAlign: 'center', color: 'var(--on-surface-variant)' }}>
            <div style={{ marginBottom: 8, opacity: 0.6, display: 'flex', justifyContent: 'center' }}>
              <DevicesIcon size={32} />
            </div>
            <div>No other devices connected right now.</div>
            <div className="body-small" style={{ marginTop: 4 }}>
              Open Jukebox on your phone, tablet, or another browser to listen and control together.
            </div>
          </div>
        ) : (
          devices.map((device) => {
            const isCurrent = device.id === currentId
            const isActive = device.id === effectiveActiveId
            const isPlaying = isCurrent ? isLocalPlaying : device.playing

            return (
              <div
                key={device.id}
                onClick={() => {
                  if (!isActive) {
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
                  cursor: isActive ? 'default' : 'pointer',
                  transition: 'background 0.15s, border-color 0.15s',
                }}
              >
                <div style={{ marginRight: 14, color: isActive ? 'var(--primary)' : 'inherit', display: 'flex', alignItems: 'center' }}>
                  <DeviceIcon type={device.type} size={24} />
                </div>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontWeight: 600, display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {device.name}
                    </span>
                    {isCurrent && (
                      <span className="body-small" style={{ opacity: 0.6, fontWeight: 400, flexShrink: 0 }}>
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

                <div style={{ display: 'flex', alignItems: 'center', gap: 4 }} onClick={(e) => e.stopPropagation()}>
                  {!isCurrent && isActive && (
                    <>
                      <IconButton
                        icon={device.playing ? 'pause' : 'play_arrow'}
                        label={device.playing ? 'Pause' : 'Play'}
                        size={20}
                        onClick={() => {
                          if (device.song) {
                            sendRemoteCommand(device.id, device.playing ? 'pause' : 'play')
                          } else {
                            transferPlayback(device.id)
                          }
                        }}
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
                    onClick={() => {
                      if (!isActive) {
                        transferPlayback(device.id)
                      }
                    }}
                  >
                    {isActive ? (isCurrent ? 'Listening here' : 'Active') : (isCurrent ? 'Play here' : 'Play on device')}
                  </button>
                </div>
              </div>
            )
          })
        )}
      </div>
    </Dialog>
  )
}
