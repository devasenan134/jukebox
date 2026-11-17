import { useEffect, useRef, useState, type ReactNode } from 'react'
import type { ChatMessage, Conversation, Pin, ReplyQuote } from '../api/socialTypes'
import type { SocialUser } from '../api/types'
import { social } from '../api/social'
import { Avatar, GroupAvatar } from './avatars'
import { me, useSocial } from './social'
import { chatTitle, dmPartner, shortTime } from '../screens/FriendsScreen'
import { Checkbox, Dialog, Icon, IconButton, Menu } from '../ui/kit'

// The chat's extra tools (ui/social/ChatTools.kt, ChatMedia.kt and parts of ChatScreen.kt): @mentions, pinned
// messages, forwarding, and searching a chat.

/** One line saying what a quoted message was: its text, or "🎵 song", "📷 Photo", "🎤 Voice message". */
export function quoteText(q: ReplyQuote): string {
  if (q.hidden) return 'An earlier message'
  if (q.deleted) return 'Message deleted'
  if (q.song) return `🎵 ${q.song.title}`
  if (q.imageKind) return q.imageKind === 'gif' ? 'GIF' : q.imageKind === 'sticker' ? 'Sticker' : '📷 Photo'
  if (q.voiceMs) return '🎤 Voice message'
  return q.body
}

/** A message as one line, for search results. */
const summary = (m: ChatMessage) =>
  m.deleted ? 'Message deleted' : m.song ? `🎵 ${m.song.title}${m.song.artist ? ` · ${m.song.artist}` : ''}${m.body ? ` · ${m.body}` : ''}`
    : m.image ? `📷 ${m.body || 'Photo'}` : m.voiceMs ? '🎤 Voice message' : m.body

// ---- @mentions ----

/**
 * Where an "@name" being typed starts: the "@" before the cursor, at the start or after a space, with at most
 * 30 characters (and no new line) since. -1 if there's none.
 */
export function mentionStart(text: string, cursor: number): number {
  for (let i = cursor - 1; i >= 0 && cursor - i <= 31; i--) {
    const c = text[i]
    if (c === '\n') return -1
    if (c === '@') return i === 0 || /\s/.test(text[i - 1]) ? i : -1
  }
  return -1
}

/** Members to @mention while typing "@…" in a group (up to 5). */
export function mentionSuggestions(conversation: Conversation, text: string, cursor: number): { at: number; members: SocialUser[] } {
  const at = conversation.kind === 'group' ? mentionStart(text, cursor) : -1
  if (at < 0) return { at, members: [] }
  const q = text.slice(at + 1, cursor).toLowerCase()
  const members = conversation.members
    .filter((m) => m.id !== me()?.id && (m.displayName.toLowerCase().includes(q) || m.username.toLowerCase().startsWith(q)))
    .slice(0, 5)
  return { at, members }
}

/** The list above the message box. */
export function MentionList({ members, onPick }: { members: SocialUser[]; onPick: (m: SocialUser) => void }) {
  return (
    <div className="mention-list" role="listbox" aria-label="Mention someone">
      {members.map((m) => (
        <div
          key={m.id} className="list-row" role="option" aria-selected={false} style={{ padding: '6px 12px' }}
          // mousedown, so the message box keeps its focus and cursor.
          onMouseDown={(e) => { e.preventDefault(); onPick(m) }}
        >
          <Avatar name={m.displayName} userKey={m.username} user={m} size={32} />
          <span className="text body-large ellipsis">{m.displayName}</span>
          <span className="body-small muted">@{m.username}</span>
        </div>
      ))}
    </div>
  )
}

/** A message's text with each "@Name" it mentions in colour, and a mention of you highlighted. */
export function WithMentions({ body, m, conversation }: { body: string; m: ChatMessage; conversation: Conversation }) {
  const names = (m.mentions ?? []).map((id) => conversation.members.find((x) => x.id === id)?.displayName).filter((n): n is string => !!n)
  if (!names.length) return <>{body}</>
  const myName = me()?.displayName
  const tags = [...new Set(names)].sort((a, b) => b.length - a.length).map((n) => '@' + n)
  const parts: ReactNode[] = []
  let plain = ''
  for (let i = 0; i < body.length; ) {
    const tag = tags.find((t) => body.startsWith(t, i))
    if (!tag) {
      plain += body[i++]
      continue
    }
    if (plain) parts.push(plain)
    plain = ''
    parts.push(<b key={i} className={`mention${tag === '@' + myName ? ' me' : ''}`}>{tag}</b>)
    i += tag.length
  }
  if (plain) parts.push(plain)
  return <>{parts}</>
}

// ---- Pins ----

/** Under the chat's name: the pinned messages. Click to go to one (each click the next); right-click for Unpin. */
export function PinnedBar({ pins, onJump, onUnpin }: { pins: Pin[]; onJump: (id: number) => void; onUnpin: (id: number) => void }) {
  const [index, setIndex] = useState(0)
  const [menu, setMenu] = useState<DOMRect | null>(null)
  if (!pins.length) return null
  const pin = pins[index % pins.length]
  const myId = me()?.id
  return (
    <div
      className="pinned-bar"
      onClick={() => { onJump(pin.message.id); setIndex((i) => (i + 1) % pins.length) }}
      onContextMenu={(e) => { e.preventDefault(); setMenu(new DOMRect(e.clientX, e.clientY, 0, 0)) }}
      title="Go to the pinned message (right-click to unpin)"
    >
      <Icon name="push_pin" size={18} style={{ color: 'var(--primary)' }} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div className="label-medium primary-text ellipsis">
          {pins.length > 1 ? `Pinned message ${(index % pins.length) + 1} of ${pins.length}` : 'Pinned message'} · {pin.message.sender.id === myId ? 'You' : pin.message.sender.displayName}
        </div>
        <div className="body-small ellipsis">{quoteText(pin.message)}</div>
      </div>
      <IconButton icon="close" label="Unpin" size={18} onClick={(e) => { e.stopPropagation(); onUnpin(pin.message.id) }} />
      {menu && (
        <span onClick={(e) => e.stopPropagation()} style={{ display: 'contents' }}>
          <Menu anchor={menu} onClose={() => setMenu(null)} items={[{ label: 'Go to message', onClick: () => onJump(pin.message.id) }, { label: 'Unpin', onClick: () => onUnpin(pin.message.id) }]} />
        </span>
      )}
    </div>
  )
}

/** For how long to pin a message. */
export function PinDialog({ onPin, onClose }: { onPin: (hours: number) => void; onClose: () => void }) {
  return (
    <Dialog title="Pin for" onClose={onClose} actions={<button className="btn text" onClick={onClose}>Cancel</button>}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 4, marginTop: -4 }}>
        {[[24, '24 hours'], [24 * 7, '7 days'], [24 * 30, '30 days']].map(([hours, label]) => (
          <button key={label} className="list-row" style={{ border: 0, background: 'none', color: 'var(--on-surface)', font: 'inherit', textAlign: 'left' }} onClick={() => { onClose(); onPin(hours as number) }}>
            {label}
          </button>
        ))}
        <div className="body-small muted" style={{ marginTop: 6 }}>A chat keeps 3 pins; a fourth replaces the oldest.</div>
      </div>
    </Dialog>
  )
}

// ---- Forwarding ----

/** Pick up to 10 chats to forward a message to. */
export function ForwardDialog({ onForward, onClose }: { onForward: (ids: number[]) => void; onClose: () => void }) {
  const chats = useSocial((s) => s.conversations).filter((c) => c.canMessage !== false)
  const [picked, setPicked] = useState<number[]>([])
  return (
    <Dialog
      title="Forward to…"
      onClose={onClose}
      actions={
        <>
          <button className="btn text" onClick={onClose}>Cancel</button>
          <button className="btn" disabled={!picked.length} onClick={() => onForward(picked)}>{picked.length > 1 ? `Send to ${picked.length}` : 'Send'}</button>
        </>
      }
    >
      {chats.length === 0 ? 'You have no chats to forward to.' : (
        <div style={{ maxHeight: 380, overflowY: 'auto' }}>
          {chats.map((c) => {
            const on = picked.includes(c.id)
            const toggle = () => setPicked(on ? picked.filter((x) => x !== c.id) : picked.length < 10 ? [...picked, c.id] : picked)
            const partner = dmPartner(c)
            return (
              <div key={c.id} className="list-row" onClick={toggle} style={{ padding: '6px 4px' }}>
                {partner ? <Avatar name={partner.displayName} userKey={partner.username} user={partner} size={36} /> : <GroupAvatar groupKey={String(c.id)} conversation={c} size={36} />}
                <div className="text body-large ellipsis">{chatTitle(c)}</div>
                <Checkbox checked={on} onChange={toggle} />
              </div>
            )
          })}
        </div>
      )}
    </Dialog>
  )
}

// ---- Search in a chat ----

/** A search box over the chat, with matching messages and shared songs, newest first. Clicking one goes to it. */
export function ChatSearch({ conversationId, onPick, onClose }: { conversationId: number; onPick: (id: number) => void; onClose: () => void }) {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<ChatMessage[] | null>(null)
  const [failed, setFailed] = useState(false)
  const box = useRef<HTMLInputElement>(null)
  useEffect(() => box.current?.focus(), [])
  // Ask the server a moment after typing stops.
  useEffect(() => {
    const q = query.trim()
    const t = setTimeout(() => {
      if (!q) return setResults(null)
      social.searchChat(conversationId, q)
        .then((r) => { setResults(r); setFailed(false) })
        .catch(() => setFailed(true))
    }, q ? 300 : 0)
    return () => clearTimeout(t)
  }, [query, conversationId])
  const q = query.trim()
  const myId = me()?.id
  return (
    <div className="chat-search">
      <div style={{ display: 'flex', alignItems: 'center', gap: 4, padding: '8px 8px 8px 16px' }}>
        <div className="search-box" style={{ flex: 1 }}>
          <Icon name="search" />
          <input ref={box} value={query} maxLength={100} placeholder="Search this chat" aria-label="Search this chat" onChange={(e) => setQuery(e.target.value)} onKeyDown={(e) => e.key === 'Escape' && onClose()} />
        </div>
        <IconButton icon="close" label="Close search" onClick={onClose} />
      </div>
      <div style={{ overflowY: 'auto', flex: 1, minHeight: 0 }}>
        {failed ? <Hint>Couldn't search right now</Hint>
          : results == null ? <Hint>Search messages and shared songs</Hint>
            : results.length === 0 ? <Hint>Nothing found for “{q}”</Hint>
              : results.map((m) => (
                <div key={m.id} className="list-row" style={{ display: 'block', padding: '10px 16px' }} onClick={() => onPick(m.id)}>
                  <div style={{ display: 'flex' }}>
                    <span className="label-medium primary-text" style={{ flex: 1 }}>{m.sender.id === myId ? 'You' : m.sender.displayName}</span>
                    <span className="body-small muted">{shortTime(m.createdAt)}</span>
                  </div>
                  <div className="body-medium clamp2">{highlight(summary(m), q)}</div>
                </div>
              ))}
      </div>
    </div>
  )
}

const Hint = ({ children }: { children: ReactNode }) => <div className="body-medium muted" style={{ padding: 16 }}>{children}</div>

/** text with every place query appears (ignoring case) in bold. */
function highlight(text: string, query: string): ReactNode[] {
  if (!query) return [text]
  const out: ReactNode[] = []
  const lower = text.toLowerCase()
  const q = query.toLowerCase()
  let at = 0
  for (let found = lower.indexOf(q); found >= 0; found = lower.indexOf(q, at)) {
    out.push(text.slice(at, found), <b key={found}>{text.slice(found, found + q.length)}</b>)
    at = found + q.length
  }
  out.push(text.slice(at))
  return out
}
