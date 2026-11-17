import { useState } from 'react'
import { create } from 'zustand'
import type { ChatMessage, Conversation } from '../api/socialTypes'
import { Avatar } from './avatars'
import { me } from './social'
import { load, save } from '../state/storage'
import { Dialog, IconButton, Sheet } from '../ui/kit'

// Reactions (ui/social/ChatReactions.kt): a row of quick emoji (yours to choose, kept in this browser), any
// other emoji from a grid or typed, and who reacted with what.

const COUNT = 6
const DEFAULT = ['👍', '❤️', '😂', '😮', '😢', '🙏']

/** Emoji to pick from (any other can be typed). */
const EMOJI = [
  '👍', '❤️', '😂', '😮', '😢', '🙏', '🔥', '🥰', '😍', '🤩', '😘', '😊', '😁', '🤣', '😅', '😆',
  '😉', '😎', '🤗', '🤔', '🙄', '😏', '😬', '😴', '🥺', '😭', '😱', '😡', '🤯', '🥳', '😇', '🤭',
  '🫡', '🫶', '👏', '🙌', '💪', '👌', '✌️', '🤞', '🤘', '👀', '👎', '💯', '✨', '🎉', '🎶', '🎵',
  '🎧', '🎤', '🎸', '🥁', '💃', '🕺', '💔', '💙', '💚', '💛', '🧡', '💜', '🖤', '🤍', '☕', '🍿',
  '🌙', '☀️', '🌹', '💐', '🙈', '💀', '🤡', '👻', '🤖', '😤', '😳', '🥲',
]

/** Something that looks like an emoji (no letters, digits or spaces), for the "type any emoji" box. */
const looksLikeEmoji = (t: string) => t.length > 0 && t.length <= 16 && !/[\p{L}\p{N}\s]/u.test(t)

export const useQuickReactions = create<{ emoji: string[]; set: (e: string[]) => void }>((set) => {
  const saved = load<string[] | null>('chat.quickReactions', null)
  return {
    emoji: saved?.length === COUNT ? saved : DEFAULT,
    set: (emoji) => {
      save('chat.quickReactions', emoji)
      set({ emoji })
    },
  }
})

/** The emoji you reacted to m with, if any. */
export const myReaction = (m: ChatMessage) => m.reactions?.find((r) => r.userIds.includes(me()?.id ?? -1))?.emoji

/** At the top of a message's menu: the quick emoji, ＋ for any other, ✎ to change the row. */
export function ReactionRow({ m, onReact, onMore, onEdit }: { m: ChatMessage; onReact: (e: string) => void; onMore: () => void; onEdit: () => void }) {
  const quick = useQuickReactions((s) => s.emoji)
  const mine = myReaction(m)
  return (
    <div className="reaction-row">
      {quick.map((e) => (
        <button key={e} className={`emoji${mine === e ? ' mine' : ''}`} aria-label={`React ${e}`} onClick={() => onReact(e)}>{e}</button>
      ))}
      <IconButton icon="add" label="More emoji" size={20} onClick={onMore} />
      <IconButton icon="edit" label="Change these emoji" size={18} onClick={onEdit} />
    </div>
  )
}

function EmojiGrid({ onPick }: { onPick: (e: string) => void }) {
  return (
    <div className="emoji-grid">
      {EMOJI.map((e) => <button key={e} className="emoji" onClick={() => onPick(e)}>{e}</button>)}
    </div>
  )
}

/** Pick any emoji to react with: from the grid, or typed. */
export function EmojiPicker({ onPick, onClose }: { onPick: (e: string) => void; onClose: () => void }) {
  const [typed, setTyped] = useState('')
  return (
    <Dialog
      title="React with…"
      onClose={onClose}
      actions={<><button className="btn text" onClick={onClose}>Cancel</button><button className="btn" disabled={!looksLikeEmoji(typed)} onClick={() => onPick(typed)}>React</button></>}
    >
      <EmojiGrid onPick={onPick} />
      <div className="field" style={{ marginTop: 8 }}>
        <input value={typed} placeholder="Or type any emoji" aria-label="Any emoji" onChange={(e) => setTyped(e.target.value.trim().slice(0, 16))} />
      </div>
    </Dialog>
  )
}

/** Change the quick emoji: click a place in the row, then the emoji to put there. */
export function EditQuickReactions({ onClose }: { onClose: () => void }) {
  const store = useQuickReactions()
  const [row, setRow] = useState(store.emoji)
  const [at, setAt] = useState(0)
  const put = (e: string) => {
    setRow(row.map((x, i) => (i === at ? e : x)))
    setAt((at + 1) % COUNT)
  }
  return (
    <Dialog
      title="Your quick reactions"
      onClose={onClose}
      actions={
        <>
          <button className="btn text" onClick={() => { setRow(DEFAULT); setAt(0) }}>Reset</button>
          <button className="btn text" onClick={onClose}>Cancel</button>
          <button className="btn" onClick={() => { store.set(row); onClose() }}>Save</button>
        </>
      }
    >
      <div className="reaction-row" style={{ justifyContent: 'center', marginBottom: 8 }}>
        {row.map((e, i) => (
          <button key={i} className={`emoji${i === at ? ' mine' : ''}`} aria-label={`Place ${i + 1}`} onClick={() => setAt(i)}>{e}</button>
        ))}
      </div>
      <div className="body-small" style={{ marginBottom: 8 }}>Click a place, then the emoji to put there.</div>
      <EmojiGrid onPick={put} />
    </Dialog>
  )
}

/** Who reacted: everyone, with a tab per emoji ("All 3 · 👍 2 · ❤️ 1"). Click your own to take it back. */
export function WhoReacted({ m, conversation, onRemoveMine, onClose }: { m: ChatMessage; conversation: Conversation; onRemoveMine: () => void; onClose: () => void }) {
  const [tab, setTab] = useState<string | null>(null)
  const reactions = m.reactions ?? []
  const all = reactions.flatMap((r) => r.userIds.map((uid) => ({ uid, emoji: r.emoji })))
  const shown = tab ? all.filter((x) => x.emoji === tab) : all
  const myId = me()?.id
  return (
    <Sheet onClose={onClose}>
      <div className="chips" style={{ padding: '0 16px 8px' }}>
        <button className={`chip${tab == null ? ' selected' : ''}`} onClick={() => setTab(null)}>All {all.length}</button>
        {reactions.map((r) => (
          <button key={r.emoji} className={`chip${tab === r.emoji ? ' selected' : ''}`} onClick={() => setTab(r.emoji)}>{r.emoji} {r.userIds.length}</button>
        ))}
      </div>
      {shown.map(({ uid, emoji }) => {
        const u = conversation.members.find((x) => x.id === uid)
        const isMe = uid === myId
        return (
          <div key={`${uid}-${emoji}`} className="list-row" style={{ padding: '6px 16px', cursor: isMe ? 'pointer' : 'default' }} onClick={() => { if (isMe) { onRemoveMine(); onClose() } }}>
            {u ? <Avatar name={u.displayName} userKey={u.username} user={u} size={36} /> : <span style={{ width: 36 }} />}
            <div className="text">
              <div className="body-large ellipsis">{isMe ? 'You' : (u?.displayName ?? 'Someone who left')}</div>
              {isMe && <div className="body-small muted">Click to remove</div>}
            </div>
            <span style={{ fontSize: 22 }}>{emoji}</span>
          </div>
        )
      })}
    </Sheet>
  )
}
