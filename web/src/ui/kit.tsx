import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { create } from 'zustand'

// Small building blocks shared by every screen: icons, menus, bottom sheets, dialogs, toasts.
// They follow Material 3, like the Android app's Compose components.

/** A Material Symbols icon, by name ("home", "favorite", ...). */
export function Icon({ name, filled, size, className, style }: { name: string; filled?: boolean; size?: number; className?: string; style?: React.CSSProperties }) {
  return (
    <span className={`icon${filled ? ' filled' : ''}${className ? ' ' + className : ''}`} style={{ ...(size ? { fontSize: size } : {}), ...style }} aria-hidden>
      {name}
    </span>
  )
}

export function IconButton({ icon, label, onClick, filled, disabled, size, className, style, color }: {
  icon: string
  label: string
  onClick?: (e: React.MouseEvent) => void
  filled?: boolean
  disabled?: boolean
  size?: number
  className?: string
  style?: React.CSSProperties
  color?: string
}) {
  return (
    <button
      type="button"
      className={`icon-btn${className ? ' ' + className : ''}`}
      aria-label={label}
      title={label}
      disabled={disabled}
      style={{ ...style, ...(color ? { color } : {}) }}
      onClick={(e) => {
        e.stopPropagation()
        onClick?.(e)
      }}
    >
      <Icon name={icon} filled={filled} size={size} />
    </button>
  )
}

export function Spinner({ size = 32 }: { size?: number }) {
  return <div className="spinner" style={{ width: size, height: size }} />
}

export function Loading() {
  return (
    <div className="center-box">
      <Spinner />
    </div>
  )
}

export function ErrorBox({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="center-box">
      <div className="muted">{message}</div>
      {onRetry && (
        <button className="btn tonal" onClick={onRetry}>
          Try again
        </button>
      )}
    </div>
  )
}

// ---- Toasts ----

interface ToastState {
  toasts: { id: number; text: string }[]
}
const useToasts = create<ToastState>(() => ({ toasts: [] }))
let toastId = 1

/** A short message at the bottom of the screen (Android's Toast). */
export function toast(text: string, long = false) {
  const id = toastId++
  useToasts.setState((s) => ({ toasts: [...s.toasts.slice(-2), { id, text }] }))
  setTimeout(() => useToasts.setState((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })), long ? 3500 : 2000)
}

export function Toasts() {
  const toasts = useToasts((s) => s.toasts)
  return createPortal(
    <div className="toasts">
      {toasts.map((t) => (
        <div key={t.id} className="toast">
          {t.text}
        </div>
      ))}
    </div>,
    document.body,
  )
}

// ---- Menus ----

export interface MenuItem {
  label: string
  icon?: string
  danger?: boolean
  onClick: () => void
  hidden?: boolean
}

/** A dropdown menu opened from a button (the ⋮ menus). */
export function Menu({ items, anchor, onClose, header }: { items: MenuItem[]; anchor: DOMRect; onClose: () => void; header?: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null)
  const [pos, setPos] = useState<{ left: number; top: number }>({ left: anchor.right, top: anchor.bottom })
  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    const w = el.offsetWidth
    const h = el.offsetHeight
    let left = anchor.right - w
    if (left < 8) left = Math.min(anchor.left, innerWidth - w - 8)
    let top = anchor.bottom
    if (top + h > innerHeight - 8) top = Math.max(8, anchor.top - h)
    setPos({ left: Math.max(8, left), top })
  }, [anchor])
  useEffect(() => {
    const close = (e: Event) => {
      if (ref.current && !ref.current.contains(e.target as Node)) onClose()
    }
    const esc = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    setTimeout(() => {
      addEventListener('pointerdown', close)
      addEventListener('keydown', esc)
    })
    addEventListener('resize', onClose)
    return () => {
      removeEventListener('pointerdown', close)
      removeEventListener('keydown', esc)
      removeEventListener('resize', onClose)
    }
  }, [onClose])
  return createPortal(
    <div className="menu" ref={ref} style={pos} onClick={(e) => e.stopPropagation()}>
      {header}
      {items
        .filter((i) => !i.hidden)
        .map((i) => (
          <button
            key={i.label}
            onClick={() => {
              onClose()
              i.onClick()
            }}
            style={i.danger ? { color: 'var(--error)' } : undefined}
          >
            {i.icon && <Icon name={i.icon} size={20} />}
            {i.label}
          </button>
        ))}
    </div>,
    document.body,
  )
}

/** A ⋮ button that opens a menu. */
export function MoreMenu({ items, label = 'More', icon = 'more_horiz' }: { items: MenuItem[]; label?: string; icon?: string }) {
  const [anchor, setAnchor] = useState<DOMRect | null>(null)
  return (
    <>
      <IconButton icon={icon} label={label} onClick={(e) => setAnchor((e.currentTarget as HTMLElement).getBoundingClientRect())} />
      {anchor && <Menu items={items} anchor={anchor} onClose={() => setAnchor(null)} />}
    </>
  )
}

// ---- Sheets and dialogs ----

/** A bottom sheet (ModalBottomSheet): slides up, closes on tapping outside or Escape. */
export function Sheet({ onClose, children }: { onClose: () => void; children: ReactNode }) {
  useEscape(onClose)
  return createPortal(
    <div className="scrim" onClick={onClose}>
      <div className="sheet" onClick={(e) => e.stopPropagation()} role="dialog">
        <div className="handle" />
        {children}
      </div>
    </div>,
    document.body,
  )
}

/** An alert dialog with a title, text and buttons. */
export function Dialog({ title, children, actions, onClose }: { title?: string; children?: ReactNode; actions?: ReactNode; onClose: () => void }) {
  useEscape(onClose)
  return createPortal(
    <div className="scrim center" onClick={onClose}>
      <div className="dialog" onClick={(e) => e.stopPropagation()} role="dialog">
        {title && <div className="headline-small" style={{ marginBottom: 16 }}>{title}</div>}
        {children && <div className="body-medium muted">{children}</div>}
        {actions && <div className="actions">{actions}</div>}
      </div>
    </div>,
    document.body,
  )
}

/** Asks for a name (new playlist, rename, ...). */
export function NameDialog({ title, confirm, initial = '', label = 'Name', max = 100, onClose, onConfirm }: {
  title: string
  confirm: string
  initial?: string
  label?: string
  max?: number
  onClose: () => void
  onConfirm: (name: string) => void
}) {
  const [name, setName] = useState(initial)
  return (
    <Dialog
      title={title}
      onClose={onClose}
      actions={
        <>
          <button className="btn text" onClick={onClose}>
            Cancel
          </button>
          <button className="btn" disabled={!name.trim()} onClick={() => onConfirm(name.trim())}>
            {confirm}
          </button>
        </>
      }
    >
      <div className="field" style={{ color: 'var(--on-surface)' }}>
        <label>{label}</label>
        <input
          autoFocus
          value={name}
          onChange={(e) => setName(e.target.value.slice(0, max))}
          onKeyDown={(e) => e.key === 'Enter' && name.trim() && onConfirm(name.trim())}
        />
      </div>
    </Dialog>
  )
}

function useEscape(onClose: () => void) {
  useEffect(() => {
    const f = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    addEventListener('keydown', f)
    return () => removeEventListener('keydown', f)
  }, [onClose])
}

/** A checkbox styled like Material's. */
export function Checkbox({ checked, disabled, onChange }: { checked: boolean; disabled?: boolean; onChange?: (v: boolean) => void }) {
  return (
    <span
      role="checkbox"
      aria-checked={checked}
      onClick={(e) => {
        e.stopPropagation()
        if (!disabled) onChange?.(!checked)
      }}
      style={{ width: 40, height: 40, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', cursor: disabled ? 'default' : 'pointer', opacity: disabled ? 0.38 : 1 }}
    >
      <Icon name={checked ? 'check_box' : 'check_box_outline_blank'} filled={checked} style={{ color: checked ? 'var(--primary)' : 'var(--on-surface-variant)' }} />
    </span>
  )
}

/** An on/off switch. */
export function Switch({ checked, onChange, disabled }: { checked: boolean; onChange: (v: boolean) => void; disabled?: boolean }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      disabled={disabled}
      onClick={(e) => {
        e.stopPropagation()
        onChange(!checked)
      }}
      style={{
        width: 52, height: 32, borderRadius: 16, border: checked ? 0 : '2px solid var(--outline)', padding: 0, cursor: 'pointer',
        background: checked ? 'var(--primary)' : 'var(--surface-container-highest)', position: 'relative', flexShrink: 0,
        opacity: disabled ? 0.38 : 1,
      }}
    >
      <span
        style={{
          position: 'absolute', top: '50%', transform: 'translateY(-50%)', left: checked ? 24 : 6, width: checked ? 24 : 16, height: checked ? 24 : 16,
          borderRadius: '50%', background: checked ? 'var(--on-primary)' : 'var(--outline)', transition: 'all 0.15s',
        }}
      />
    </button>
  )
}
