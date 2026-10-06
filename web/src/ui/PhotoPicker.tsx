import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Dialog, IconButton, Menu, toast } from './kit'

/**
 * Choosing a picture for a profile, a group or a playlist cover (PhotoPicker.kt + PhotoCropper.kt): pick a file,
 * then frame it (drag to move, scroll or the slider to zoom, Rotate), and get a square JPEG. Profile and group
 * pictures show a round guide, since that's how they appear. With onRemove, the menu also offers Remove.
 *
 *   const picker = usePhotoPicker({ title: 'Group photo', round: true, onPicked, onRemove })
 *   <button onClick={picker.open}>…</button>{picker.element}
 */
export function usePhotoPicker({ title, round, size = 512, onPicked, onRemove }: {
  title: string
  round?: boolean
  size?: number
  onPicked: (jpeg: Blob) => void | Promise<void>
  onRemove?: () => void | Promise<void>
}) {
  const file = useRef<HTMLInputElement>(null)
  const [picked, setPicked] = useState<ImageBitmap | null>(null)
  const [menu, setMenu] = useState<DOMRect | null>(null)
  const choose = () => file.current?.click()
  const element: ReactNode = (
    <>
      <input
        ref={file} type="file" accept="image/*" hidden
        onChange={(e) => {
          const f = e.target.files?.[0]
          e.target.value = ''
          if (f) createImageBitmap(f).then(setPicked).catch(() => toast("Couldn't read that picture"))
        }}
      />
      {menu && (
        <Menu
          anchor={menu}
          onClose={() => setMenu(null)}
          items={[
            { label: 'Choose a picture', onClick: choose },
            { label: 'Remove', onClick: () => void onRemove?.() },
          ]}
        />
      )}
      {picked && (
        <Cropper
          bitmap={picked} title={title} round={round} size={size}
          onClose={() => setPicked(null)}
          onDone={async (jpeg) => {
            setPicked(null)
            await onPicked(jpeg)
          }}
        />
      )}
    </>
  )
  return {
    /** Opens the file chooser, or with onRemove a menu at the clicked element (Choose / Remove). */
    open: (e?: { currentTarget: EventTarget }) => {
      if (onRemove && e) setMenu((e.currentTarget as HTMLElement).getBoundingClientRect())
      else choose()
    },
    element,
  }
}

const VIEW = 300

/** The framing step: the picture under a square (or round) window; what's inside the window is kept. */
function Cropper({ bitmap, title, round, size, onClose, onDone }: {
  bitmap: ImageBitmap
  title: string
  round?: boolean
  size: number
  onClose: () => void
  onDone: (jpeg: Blob) => void
}) {
  const canvas = useRef<HTMLCanvasElement>(null)
  const [rotation, setRotation] = useState(0)
  const turned = rotation % 180 !== 0
  const w = turned ? bitmap.height : bitmap.width
  const h = turned ? bitmap.width : bitmap.height
  // At zoom 1 the picture just covers the window; the slider goes up to 4×.
  const cover = VIEW / Math.min(w, h)
  const [zoom, setZoom] = useState(1)
  const [offset, setOffset] = useState({ x: 0, y: 0 })
  const drag = useRef<{ x: number; y: number; ox: number; oy: number } | null>(null)

  /** Keeps the picture covering the window: it can't be moved so far that an edge shows. */
  const clamp = (o: { x: number; y: number }, z = zoom) => {
    const maxX = Math.max(0, (w * cover * z - VIEW) / 2)
    const maxY = Math.max(0, (h * cover * z - VIEW) / 2)
    return { x: Math.min(maxX, Math.max(-maxX, o.x)), y: Math.min(maxY, Math.max(-maxY, o.y)) }
  }

  /** Draws the picture as framed into a canvas of side px. */
  const draw = (target: HTMLCanvasElement, side: number) => {
    const g = target.getContext('2d')!
    const k = side / VIEW
    g.fillStyle = '#000'
    g.fillRect(0, 0, side, side)
    g.save()
    g.translate(side / 2 + offset.x * k, side / 2 + offset.y * k)
    g.rotate((rotation * Math.PI) / 180)
    const s = cover * zoom * k
    g.drawImage(bitmap, (-bitmap.width * s) / 2, (-bitmap.height * s) / 2, bitmap.width * s, bitmap.height * s)
    g.restore()
  }

  useEffect(() => {
    if (canvas.current) draw(canvas.current, VIEW * 2)
  })

  const save = () => {
    const out = document.createElement('canvas')
    const side = Math.min(size, Math.round(Math.min(w, h) / zoom))
    out.width = out.height = Math.max(64, side)
    draw(out, out.width)
    out.toBlob((b) => (b ? onDone(b) : toast("Couldn't make the picture")), 'image/jpeg', 0.88)
  }

  return (
    <Dialog
      title={title}
      onClose={onClose}
      actions={<><button className="btn text" onClick={onClose}>Cancel</button><button className="btn" onClick={save}>Save</button></>}
    >
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 12 }}>
        <div
          style={{ position: 'relative', width: VIEW, height: VIEW, touchAction: 'none', cursor: 'grab', userSelect: 'none' }}
          onPointerDown={(e) => {
            e.currentTarget.setPointerCapture(e.pointerId)
            drag.current = { x: e.clientX, y: e.clientY, ox: offset.x, oy: offset.y }
          }}
          onPointerMove={(e) => {
            const d = drag.current
            if (d) setOffset(clamp({ x: d.ox + e.clientX - d.x, y: d.oy + e.clientY - d.y }))
          }}
          onPointerUp={() => (drag.current = null)}
          onWheel={(e) => {
            const z = Math.min(4, Math.max(1, zoom * (e.deltaY < 0 ? 1.08 : 1 / 1.08)))
            setZoom(z)
            setOffset((o) => clamp(o, z))
          }}
        >
          <canvas ref={canvas} width={VIEW * 2} height={VIEW * 2} style={{ width: VIEW, height: VIEW, display: 'block', borderRadius: 8 }} />
          {round && (
            <div style={{ position: 'absolute', inset: 0, borderRadius: '50%', boxShadow: '0 0 0 400px rgba(0,0,0,0.55)', pointerEvents: 'none', outline: '2px solid rgba(255,255,255,0.7)' }} />
          )}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, width: VIEW }}>
          <input
            type="range" className="slider" min={1} max={4} step={0.01} value={zoom} aria-label="Zoom" style={{ flex: 1, ['--fill' as string]: `${((zoom - 1) / 3) * 100}%` }}
            onChange={(e) => {
              const z = Number(e.target.value)
              setZoom(z)
              setOffset((o) => clamp(o, z))
            }}
          />
          <IconButton icon="rotate_right" label="Rotate" onClick={() => { setRotation((r) => (r + 90) % 360); setOffset({ x: 0, y: 0 }) }} />
        </div>
        <div className="body-small muted">Drag to move · scroll to zoom</div>
      </div>
    </Dialog>
  )
}
