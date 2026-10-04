import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import type { ChatMessage, Conversation } from '../api/socialTypes'
import type { SongRef } from '../api/types'
import { isClip, refToSong } from '../api/types'
import { social } from '../api/social'
import * as player from '../player/player'
import { Avatar, GroupAvatar, useFriendsServerPicture } from '../social/avatars'
import { useListen } from '../social/listen'
import { markRead, me, onMessage, onRemoved, refresh, sendTyping, setOpenConversation, useSocial } from '../social/social'
import { Cover, formatDuration } from '../ui/components'
import { Dialog, Icon, IconButton, Menu, toast, type MenuItem } from '../ui/kit'
import { useNav } from '../ui/nav'
import { chatTitle, dmPartner, shortTime } from './FriendsScreen'

const REACTIONS = ['❤️', '😂', '🔥', '😮', '😢', '👍']
const PAGE = 50

/** A chat (ui/social/ChatScreen.kt): messages with songs, pictures and voice notes, replies, reactions, and listening together. */
export function ChatScreen() {
  const id = Number(useParams().id)
  const nav = useNav()
  const conversation = useSocial((s) => s.conversations.find((c) => c.id === id))
  const typing = useSocial((s) => s.typing[id])
  const friends = useSocial((s) => s.friends)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [loaded, setLoaded] = useState(false)
  const [more, setMore] = useState(true)
  const [replyTo, setReplyTo] = useState<ChatMessage | null>(null)
  const [editing, setEditing] = useState<ChatMessage | null>(null)
  const list = useRef<HTMLDivElement>(null)
  const stick = useRef(true)

  const merge = (incoming: ChatMessage[]) =>
    setMessages((old) => {
      const byId = new Map(old.map((m) => [m.id, m]))
      for (const m of incoming) byId.set(m.id, m)
      return [...byId.values()].sort((a, b) => a.id - b.id)
    })

  useEffect(() => {
    setOpenConversation(id)
    setMessages([])
    setLoaded(false)
    social.messages(id).then((page) => {
      merge(page)
      setMore(page.length >= PAGE)
      setLoaded(true)
    }).catch((e) => toast((e as Error).message || "Couldn't load the chat"))
    if (!useSocial.getState().conversations.length) refresh()
    const offMessage = onMessage((m) => m.conversationId === id && merge([m]))
    const offRemoved = onRemoved((c) => c === id && nav.back())
    return () => {
      setOpenConversation(null)
      offMessage()
      offRemoved()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  // Read up to the newest message.
  const last = messages[messages.length - 1]
  useEffect(() => {
    if (last) markRead(id, last.id)
  }, [id, last])

  // Keep the newest message in view unless you scrolled up to read.
  useLayoutEffect(() => {
    const el = list.current
    if (el && stick.current) el.scrollTop = el.scrollHeight
  }, [messages, typing])

  const older = async () => {
    const first = messages[0]
    if (!first) return
    const el = list.current
    const before = el ? el.scrollHeight - el.scrollTop : 0
    const page = await social.messages(id, first.id).catch(() => [] as ChatMessage[])
    setMore(page.length >= PAGE)
    stick.current = false
    merge(page)
    requestAnimationFrame(() => el && (el.scrollTop = el.scrollHeight - before))
  }

  if (!conversation) {
    return (
      <div className="page">
        <div className="screen-header with-back"><IconButton icon="arrow_back" label="Back" onClick={nav.back} /></div>
        <div className="center-box muted">{loaded ? 'This chat is gone' : 'Loading…'}</div>
      </div>
    )
  }
  const partner = dmPartner(conversation)
  const online = partner ? friends.some((f) => f.user.id === partner.id && f.online) : false
  const typers = Object.entries(typing ?? {})
    .filter(([uid, at]) => Number(uid) !== me()?.id && Date.now() - at < 6000)
    .map(([uid]) => conversation.members.find((m) => m.id === Number(uid))?.displayName)
    .filter(Boolean)
  const subtitle = typers.length ? `${typers.join(', ')} ${typers.length > 1 ? 'are' : 'is'} typing…`
    : partner ? (online ? 'Online' : `@${partner.username}`)
      : `${conversation.members.length} members`

  return (
    <div className="chat" style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <div className="screen-header with-back" style={{ gap: 10, flexShrink: 0 }}>
        <IconButton icon="arrow_back" label="Back" onClick={nav.back} />
        {partner ? <Avatar name={partner.displayName} userKey={partner.username} user={partner} online={online} size={40} /> : <GroupAvatar groupKey={String(conversation.id)} conversation={conversation} size={40} />}
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="title-medium ellipsis" data-testid="chat-title">{chatTitle(conversation)}</div>
          <div className={`body-small ellipsis ${typers.length ? 'primary-text' : 'muted'}`}>{subtitle}</div>
        </div>
        <JamButton conversation={conversation} />
      </div>
      <div
        ref={list}
        onScroll={(e) => {
          const el = e.currentTarget
          stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80
        }}
        style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '8px 12px 12px' }}
      >
        {loaded && more && messages.length > 0 && (
          <div style={{ textAlign: 'center', padding: 8 }}><button className="btn text" onClick={() => void older()}>Earlier messages</button></div>
        )}
        {loaded && messages.length === 0 && <div className="center-box muted">Say hello, or share a song from any song's menu.</div>}
        {messages.map((m, i) => (
          <Bubble
            key={m.id}
            m={m}
            conversation={conversation}
            first={i === 0 || messages[i - 1].sender.id !== m.sender.id || messages[i - 1].system || m.createdAt - messages[i - 1].createdAt > 10 * 60_000}
            onReply={() => setReplyTo(m)}
            onEdit={() => setEditing(m)}
            onChanged={(x) => merge([x])}
          />
        ))}
      </div>
      {conversation.canMessage === false ? (
        <div className="body-medium muted" style={{ padding: 16, textAlign: 'center' }}>You can't message this chat anymore.</div>
      ) : (
        <Composer
          conversationId={id}
          replyTo={replyTo}
          onCancelReply={() => setReplyTo(null)}
          onSent={(m) => {
            stick.current = true
            setReplyTo(null)
            merge([m])
          }}
        />
      )}
      {editing && <EditMessage m={editing} onClose={() => setEditing(null)} onSaved={(m) => merge([m])} />}
    </div>
  )
}

/** Listen together: start a jam with this chat, join one that's on, or leave. */
function JamButton({ conversation }: { conversation: Conversation }) {
  const joined = useListen((s) => s.joined)
  const sessions = useListen((s) => s.sessions)
  const listeners = sessions[conversation.id] ?? conversation.listeners ?? []
  const inThis = joined === conversation.id
  if (inThis) {
    return <button className="btn tonal" onClick={() => useListen.getState().leave()}><Icon name="headphones" size={18} />Leave jam</button>
  }
  if (listeners.length > 0) {
    return <button className="btn" onClick={() => useListen.getState().join(conversation.id)}><Icon name="headphones" size={18} />Join jam</button>
  }
  return (
    <IconButton
      icon="headphones"
      label="Listen together"
      onClick={() => {
        if (joined != null) return toast('Leave the jam you are in first')
        useListen.getState().start(conversation.id)
        toast('Jam started: what you play, everyone in this chat can hear')
      }}
    />
  )
}

function Bubble({ m, conversation, first, onReply, onEdit, onChanged }: {
  m: ChatMessage
  conversation: Conversation
  first: boolean
  onReply: () => void
  onEdit: () => void
  onChanged: (m: ChatMessage) => void
}) {
  const mine = m.sender.id === me()?.id
  const [menu, setMenu] = useState<DOMRect | null>(null)
  const isGroup = conversation.kind === 'group'
  if (m.system) return <div className="body-small muted" style={{ textAlign: 'center', padding: '10px 0' }}>{m.body}</div>

  const react = (emoji: string) => {
    const mineNow = m.reactions?.find((r) => r.userIds.includes(me()?.id ?? -1))?.emoji
    social.react(m.conversationId, m.id, mineNow === emoji ? null : emoji).then(onChanged).catch(() => toast("Couldn't react"))
  }
  const items: MenuItem[] = [
    { label: 'Reply', onClick: onReply, hidden: m.deleted },
    { label: 'Play song', onClick: () => m.song && playShared(m.song), hidden: !m.song || m.deleted },
    { label: 'Copy text', onClick: () => void navigator.clipboard?.writeText(m.body), hidden: !m.body || m.deleted },
    { label: 'Edit', onClick: onEdit, hidden: !mine || m.deleted || !!m.song || !!m.image || !!m.voiceMs },
    { label: 'Delete', onClick: () => social.deleteMessage(m.conversationId, m.id).then(onChanged).catch(() => toast("Couldn't delete it")), hidden: !mine || m.deleted },
  ]
  const bg = mine ? 'var(--primary-container)' : 'var(--surface-container-high)'
  return (
    <div className="bubble-row" style={{ display: 'flex', flexDirection: mine ? 'row-reverse' : 'row', alignItems: 'flex-end', gap: 8, marginTop: first ? 12 : 3 }}>
      {!mine && isGroup && <div style={{ width: 28, flexShrink: 0 }}>{first && <Avatar name={m.sender.displayName} userKey={m.sender.username} user={m.sender} size={28} />}</div>}
      <div style={{ maxWidth: 'min(520px, 78%)', display: 'flex', flexDirection: 'column', alignItems: mine ? 'flex-end' : 'flex-start' }}>
        {first && !mine && isGroup && <div className="body-small muted" style={{ margin: '0 10px 2px' }}>{m.sender.displayName}</div>}
        <div
          onContextMenu={(e) => { e.preventDefault(); setMenu(new DOMRect(e.clientX, e.clientY, 0, 0)) }}
          onDoubleClick={() => !m.deleted && react('❤️')}
          style={{ background: bg, borderRadius: 18, padding: m.image ? 4 : '8px 12px', position: 'relative', overflowWrap: 'anywhere', whiteSpace: 'pre-wrap' }}
        >
          {m.forwarded && <div className="body-small muted" style={{ marginBottom: 2 }}>↪ Forwarded</div>}
          {m.replyTo && (
            <div className="body-small" style={{ borderLeft: '3px solid var(--primary)', padding: '2px 8px', marginBottom: 6, opacity: 0.8 }}>
              <b>{m.replyTo.sender.displayName}</b>
              <div className="ellipsis">{m.replyTo.hidden ? 'An earlier message' : m.replyTo.deleted ? 'Message deleted' : m.replyTo.song ? `🎵 ${m.replyTo.song.title}` : m.replyTo.imageKind ? '📷 Picture' : m.replyTo.voiceMs ? '🎤 Voice message' : m.replyTo.body}</div>
            </div>
          )}
          {m.deleted ? (
            <i className="muted">Message deleted</i>
          ) : (
            <>
              {m.song && <SongCard song={m.song} />}
              {m.image && <ChatPicture m={m} />}
              {m.voiceMs != null && m.voiceMs > 0 && <Voice m={m} />}
              {m.request && <RequestLine m={m} conversation={conversation} onChanged={onChanged} />}
              {m.body && <div style={{ padding: m.image ? '6px 8px 4px' : 0, marginTop: m.song ? 6 : 0 }}>{m.body}</div>}
            </>
          )}
        </div>
        {m.reactions && m.reactions.length > 0 && (
          <div style={{ display: 'flex', gap: 4, marginTop: -6, zIndex: 1 }}>
            {m.reactions.map((r) => (
              <button key={r.emoji} className="chip" onClick={() => react(r.emoji)} style={{ height: 24, padding: '0 8px', fontSize: 13, background: r.userIds.includes(me()?.id ?? -1) ? 'var(--primary-container)' : 'var(--surface-container-highest)' }}>
                {r.emoji}{r.userIds.length > 1 ? ` ${r.userIds.length}` : ''}
              </button>
            ))}
          </div>
        )}
        <div className="body-small muted" style={{ fontSize: 11, margin: '2px 8px 0' }}>
          {shortTime(m.createdAt)}{m.editedAt ? ' · edited' : ''}
        </div>
      </div>
      <div className="bubble-tools" style={{ display: 'flex', alignItems: 'center', alignSelf: 'center' }}>
        {!m.deleted && (
          <>
            <IconButton icon="add_reaction" label="React" size={18} onClick={(e) => setMenu((e.currentTarget as HTMLElement).getBoundingClientRect())} />
            <IconButton icon="reply" label="Reply" size={18} onClick={onReply} />
          </>
        )}
      </div>
      {menu && (
        <Menu
          anchor={menu}
          onClose={() => setMenu(null)}
          items={[
            ...(!m.deleted ? REACTIONS.map((e) => ({ label: `${e}  React`, onClick: () => react(e) })) : []),
            ...items,
          ]}
        />
      )}
    </div>
  )
}

function playShared(song: SongRef) {
  if (isClip(song)) player.playClip(song)
  else player.play([refToSong(song)], 0, false, 'chat')
}

function SongCard({ song }: { song: SongRef }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 220, cursor: 'pointer' }} onClick={() => playShared(song)}>
      <Cover coverArt={song.coverArt} size={150} corner={6} style={{ width: 52, height: 52, flexShrink: 0 }} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div className="title-small ellipsis">{song.title}</div>
        <div className="body-small muted ellipsis">
          {song.artist}
          {isClip(song) && ` · clip ${formatDuration(song.clipStartMs! / 1000)}–${formatDuration(song.clipEndMs! / 1000)}`}
        </div>
      </div>
      <span className="fab-play" style={{ width: 36, height: 36, boxShadow: 'none' }}><Icon name="play_arrow" filled size={22} /></span>
    </div>
  )
}

function ChatPicture({ m }: { m: ChatMessage }) {
  const url = useFriendsServerPicture(social.imagePath(m))
  const [big, setBig] = useState(false)
  const w = Math.min(320, m.image!.width || 320)
  const h = m.image!.width ? Math.round((w * m.image!.height) / m.image!.width) : 240
  return (
    <>
      {url ? (
        <img src={url} alt="" onClick={() => setBig(true)} style={{ display: 'block', width: w, maxWidth: '100%', height: 'auto', aspectRatio: `${w} / ${h}`, borderRadius: 14, cursor: 'zoom-in' }} />
      ) : (
        <div className="skel" style={{ width: w, maxWidth: '100%', aspectRatio: `${w} / ${h}`, borderRadius: 14 }} />
      )}
      {big && url && (
        <div className="scrim center" onClick={() => setBig(false)}>
          <img src={url} alt="" style={{ maxWidth: '92vw', maxHeight: '88vh', borderRadius: 8 }} />
        </div>
      )}
    </>
  )
}

function Voice({ m }: { m: ChatMessage }) {
  const url = useFriendsServerPicture(social.voicePath(m))
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 220 }}>
      <Icon name="mic" />
      {url ? <audio src={url} controls preload="none" style={{ height: 32, maxWidth: 260 }} /> : <span className="muted">Voice message · {formatDuration((m.voiceMs ?? 0) / 1000)}</span>}
    </div>
  )
}

/** A song request in a jam: the owner can play it or say no. */
function RequestLine({ m, conversation, onChanged }: { m: ChatMessage; conversation: Conversation; onChanged: (m: ChatMessage) => void }) {
  const owner = useListen((s) => s.owners[conversation.id])
  const iOwn = owner != null && owner === me()?.id
  const answer = (accept: boolean) => social.answerRequest(conversation.id, m.id, accept).then(onChanged).catch((e) => toast((e as Error).message || "Couldn't answer"))
  const label = { pending: m.requestMode === 'now' ? 'Asks to play this now' : 'Asks to play this next', accepted: 'Played', declined: 'Not played', expired: 'Expired' }[m.request ?? ''] ?? ''
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 6 }}>
      <span className="body-small muted" style={{ flex: 1 }}>{label}</span>
      {iOwn && m.request === 'pending' && (
        <>
          <button className="btn text" onClick={() => void answer(false)}>No</button>
          <button className="btn" style={{ height: 32, padding: '0 14px' }} onClick={() => void answer(true)}>Play</button>
        </>
      )}
    </div>
  )
}

function Composer({ conversationId, replyTo, onCancelReply, onSent }: {
  conversationId: number
  replyTo: ChatMessage | null
  onCancelReply: () => void
  onSent: (m: ChatMessage) => void
}) {
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const box = useRef<HTMLTextAreaElement>(null)
  const file = useRef<HTMLInputElement>(null)
  useEffect(() => { if (replyTo) box.current?.focus() }, [replyTo])
  const send = async () => {
    const body = text.trim()
    if (!body || busy) return
    setBusy(true)
    try {
      onSent(await social.sendMessage(conversationId, body, null, replyTo?.id ?? null))
      setText('')
    } catch (e) {
      toast((e as Error).message || "Couldn't send it")
    }
    setBusy(false)
    box.current?.focus()
  }
  const sendPicture = async (f: File | undefined) => {
    if (!f) return
    try {
      const { blob, width, height } = await shrink(f, 1600)
      onSent(await social.sendImage(conversationId, blob, 'image/jpeg', 'photo', width, height, '', replyTo?.id))
    } catch (e) {
      toast((e as Error).message || "Couldn't send the picture")
    }
  }
  return (
    <div style={{ flexShrink: 0, padding: '8px 12px 12px', borderTop: '1px solid var(--outline-variant)' }}>
      {replyTo && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '0 4px 8px' }}>
          <Icon name="reply" size={18} />
          <div className="body-small ellipsis" style={{ flex: 1 }}>Replying to <b>{replyTo.sender.displayName}</b>: {replyTo.song ? replyTo.song.title : replyTo.body}</div>
          <IconButton icon="close" label="Cancel reply" size={18} onClick={onCancelReply} />
        </div>
      )}
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 6 }}>
        <IconButton icon="image" label="Send a picture" onClick={() => file.current?.click()} />
        <input ref={file} type="file" accept="image/*" hidden onChange={(e) => { void sendPicture(e.target.files?.[0]); e.target.value = '' }} />
        <textarea
          ref={box}
          value={text}
          rows={1}
          placeholder="Message"
          aria-label="Message"
          onChange={(e) => {
            setText(e.target.value)
            sendTyping(conversationId)
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault()
              void send()
            }
          }}
          style={{
            flex: 1, resize: 'none', border: 0, outline: 'none', borderRadius: 20, padding: '10px 16px', maxHeight: 140, minHeight: 40,
            background: 'var(--surface-container-high)', fontSize: 15, lineHeight: 1.35, fieldSizing: 'content',
          } as React.CSSProperties}
        />
        <button className="fab-play" style={{ width: 40, height: 40, boxShadow: 'none' }} aria-label="Send" disabled={!text.trim() || busy} onClick={() => void send()}>
          <Icon name="send" filled size={20} />
        </button>
      </div>
    </div>
  )
}

function EditMessage({ m, onClose, onSaved }: { m: ChatMessage; onClose: () => void; onSaved: (m: ChatMessage) => void }) {
  const [text, setText] = useState(m.body)
  const save = () =>
    social.editMessage(m.conversationId, m.id, text.trim(), m.mentions ?? [])
      .then((x) => { onSaved(x); onClose() })
      .catch((e) => toast((e as Error).message || "Couldn't edit it"))
  return (
    <Dialog
      title="Edit message"
      onClose={onClose}
      actions={<><button className="btn text" onClick={onClose}>Cancel</button><button className="btn" disabled={!text.trim()} onClick={() => void save()}>Save</button></>}
    >
      <div className="field"><textarea autoFocus value={text} onChange={(e) => setText(e.target.value)} /></div>
    </Dialog>
  )
}

/** A picture made at most [max] pixels on its long side, as JPEG. */
async function shrink(file: File, max: number): Promise<{ blob: Blob; width: number; height: number }> {
  const bitmap = await createImageBitmap(file)
  const scale = Math.min(1, max / Math.max(bitmap.width, bitmap.height))
  const width = Math.round(bitmap.width * scale)
  const height = Math.round(bitmap.height * scale)
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  canvas.getContext('2d')!.drawImage(bitmap, 0, 0, width, height)
  const blob = await new Promise<Blob>((resolve, reject) => canvas.toBlob((b) => (b ? resolve(b) : reject(new Error("Couldn't read that picture"))), 'image/jpeg', 0.85))
  return { blob, width, height }
}
