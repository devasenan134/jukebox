import { useEffect, useRef, useState } from 'react'
import { create } from 'zustand'
import * as player from './player'
import { usePlayer } from './player'
import { useJamRole } from './MiniPlayer'
import { jamOwnerName } from '../social/social'
import { load, save } from '../state/storage'
import { Cover, formatDuration, formatTotalDuration, PlayingBars, songCount } from '../ui/components'
import { Icon, IconButton, Sheet } from '../ui/kit'

/** Every row is this tall, so a dragged row's new place is just how far it moved divided by this. */
const ROW = 56

/** Wide screens: whether the queue panel is open next to the page (remembered in this browser). */
export const useQueuePanel = create<{ open: boolean; toggle: () => void; close: () => void }>((set, get) => ({
  open: load('queue.open', false),
  toggle: () => {
    save('queue.open', !get().open)
    set({ open: !get().open })
  },
  close: () => {
    save('queue.open', false)
    set({ open: false })
  },
}))

/** The queue panel on the right of wide screens. */
export function QueuePanel() {
  const close = useQueuePanel((s) => s.close)
  return (
    <aside className="queue-panel panel" aria-label="Queue">
      <div style={{ display: 'flex', alignItems: 'center', padding: '12px 8px 4px 16px' }}>
        <div className="title-medium" style={{ flex: 1, fontWeight: 700 }}>Queue</div>
        <IconButton icon="close" label="Close the queue" onClick={close} size={20} />
      </div>
      <QueueList />
    </aside>
  )
}

/** The queue as a sheet, from the full player (phones, and anyone who opens the full player). */
export function QueueSheet({ onClose }: { onClose: () => void }) {
  return (
    <Sheet onClose={onClose}>
      <div className="title-medium" style={{ padding: '0 16px 4px', fontWeight: 700 }}>Queue</div>
      <QueueList maxHeight="70dvh" />
    </Sheet>
  )
}

/**
 * The play queue in the order it will play (ui/player/QueueSheet.kt): click a song to jump to it, drag the
 * handle to move it, ✕ to take it out. Songs already played are dimmed. In someone else's jam the queue is
 * theirs, so it's view-only; in a jam you host, your changes reach everyone.
 */
function QueueList({ maxHeight }: { maxHeight?: string }) {
  const items = usePlayer((s) => s.items)
  const order = usePlayer((s) => s.order)
  const current = usePlayer((s) => s.current)
  const isPlaying = usePlayer((s) => s.isPlaying)
  const { listening } = useJamRole()
  const entries = order.map((i) => items[i]).filter(Boolean)
  const currentPos = order.indexOf(current)
  const box = useRef<HTMLDivElement>(null)

  // Dragging: which row, and how far it has moved (pointer movement plus any scrolling since it started).
  const [drag, setDrag] = useState<{ from: number; dy: number } | null>(null)
  const start = useRef({ y: 0, scroll: 0, pointerY: 0 })
  const target = drag ? Math.min(entries.length - 1, Math.max(0, drag.from + Math.round(drag.dy / ROW))) : -1

  // Open at the song that's playing.
  useEffect(() => {
    box.current?.querySelector('[data-current]')?.scrollIntoView({ block: 'center' })
  }, [])

  // Near the top or bottom edge while dragging, keep scrolling, so a song can go anywhere in a long queue.
  useEffect(() => {
    if (!drag) return
    let frame = 0
    const tick = () => {
      const el = box.current
      if (el) {
        const r = el.getBoundingClientRect()
        const y = start.current.pointerY
        const step = y < r.top + 40 ? -10 : y > r.bottom - 40 ? 10 : 0
        if (step) {
          const before = el.scrollTop
          el.scrollTop += step
          if (el.scrollTop !== before) setDrag((d) => d && { ...d, dy: y - start.current.y + el.scrollTop - start.current.scroll })
        }
      }
      frame = requestAnimationFrame(tick)
    }
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [drag != null]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!entries.length) {
    return <div className="body-medium muted" style={{ padding: '24px 16px' }}>Nothing in the queue. Play something and it shows up here.</div>
  }

  const left = entries.slice(currentPos + 1).reduce((t, e) => t + e.song.duration, 0)
  const offset = (pos: number) => {
    if (!drag) return 0
    if (pos === drag.from) return drag.dy
    if (drag.from < target && pos > drag.from && pos <= target) return -ROW
    if (target < drag.from && pos >= target && pos < drag.from) return ROW
    return 0
  }

  return (
    <>
      <div className="body-small muted" style={{ padding: '0 16px 8px' }}>
        {songCount(entries.length)}
        {left > 0 && ` · ${formatTotalDuration(left)} left`}
        {listening && <div style={{ marginTop: 4 }}>{jamOwnerName() ?? 'The host'} runs this jam, so only they can change the queue.</div>}
      </div>
      <div ref={box} className="queue-list" style={{ maxHeight, overflowY: 'auto', flex: 1, minHeight: 0, padding: '0 8px 8px', position: 'relative' }}>
        {entries.map((e, pos) => {
          const isCurrent = pos === currentPos
          const dragging = drag?.from === pos
          return (
            <div
              key={e.uid}
              data-current={isCurrent ? '' : undefined}
              className={`song-row queue-row${isCurrent ? ' current' : ''}`}
              onClick={() => !drag && !isCurrent && player.jumpTo(pos)}
              style={{
                height: ROW, padding: '0 4px 0 8px', opacity: pos < currentPos && !dragging ? 0.5 : 1,
                transform: offset(pos) ? `translateY(${offset(pos)}px)` : undefined,
                transition: dragging || !drag ? 'none' : 'transform 0.15s',
                zIndex: dragging ? 2 : undefined, background: dragging ? 'var(--surface-container-high)' : undefined,
                boxShadow: dragging ? '0 6px 20px rgba(0,0,0,0.5)' : undefined,
              }}
            >
              {!listening && (
                <span
                  className="drag-handle"
                  aria-label="Drag to move"
                  title="Drag to move"
                  onClick={(ev) => ev.stopPropagation()}
                  onPointerDown={(ev) => {
                    ev.preventDefault()
                    ev.currentTarget.setPointerCapture(ev.pointerId)
                    start.current = { y: ev.clientY, scroll: box.current?.scrollTop ?? 0, pointerY: ev.clientY }
                    setDrag({ from: pos, dy: 0 })
                  }}
                  onPointerMove={(ev) => {
                    if (!drag) return
                    start.current.pointerY = ev.clientY
                    setDrag({ ...drag, dy: ev.clientY - start.current.y + (box.current?.scrollTop ?? 0) - start.current.scroll })
                  }}
                  onPointerUp={() => {
                    if (drag && target !== drag.from) player.moveInQueue(drag.from, target)
                    setDrag(null)
                  }}
                  onPointerCancel={() => setDrag(null)}
                >
                  <Icon name="drag_indicator" size={20} />
                </span>
              )}
              <div style={{ position: 'relative', width: 40, height: 40, flexShrink: 0 }}>
                <Cover coverArt={e.song.coverArt} size={150} corner={4} style={{ width: '100%', height: '100%' }} />
                {isCurrent && (
                  <div style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.55)', borderRadius: 4, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                    <PlayingBars playing={isPlaying} size={14} />
                  </div>
                )}
              </div>
              <div className="text">
                <div className="title body-medium ellipsis">{e.song.title}</div>
                <div className="body-small muted ellipsis">{e.song.artist}</div>
              </div>
              <span className="dur body-small" style={{ padding: '0 4px' }}>{formatDuration(e.song.duration)}</span>
              {!listening && !isCurrent ? (
                <IconButton icon="close" label="Take out of the queue" size={18} className="row-remove" onClick={(ev) => { ev.stopPropagation(); player.removeFromQueue(e.uid) }} />
              ) : (
                <span style={{ width: 40, flexShrink: 0 }} />
              )}
            </div>
          )
        })}
      </div>
    </>
  )
}
