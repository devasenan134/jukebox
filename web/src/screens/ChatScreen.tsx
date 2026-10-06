import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import type { ChatMessage, Conversation } from '../api/socialTypes'
import type { SongRef } from '../api/types'
import { isClip, refToSong } from '../api/types'
import { social } from '../api/social'
import * as player from '../player/player'
import { Avatar, GroupAvatar, useFriendsServerPicture } from '../social/avatars'
import { useListen } from '../social/listen'
import { deleteConversation, markRead, me, onMessage, onRemoved, refresh, refreshConversationsSoon, sendTyping, setOpenConversation, useSocial } from '../social/social'
import { PicturePreview, sendPicture, ShareMusicSheet } from '../social/ChatMedia'
import { QueueSheet } from '../player/QueuePanel'
import { RecordingBar, useVoiceRecorder, VoicePlayer } from '../social/Voice'
import { EditQuickReactions, EmojiPicker, myReaction, ReactionRow, WhoReacted } from '../social/Reactions'
import { ChatSearch, ForwardDialog, MentionList, mentionSuggestions, PinDialog, PinnedBar, quoteText, WithMentions } from '../social/ChatTools'
import type { SocialUser } from '../api/types'
import { GroupInfoSheet, GroupMenu } from '../social/GroupInfo'
import { Cover, formatDuration } from '../ui/components'
import { Dialog, Icon, IconButton, Menu, toast, type MenuItem } from '../ui/kit'
import { useNav } from '../ui/nav'
import { chatTitle, dmPartner, shortTime } from './FriendsScreen'

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
  const [members, setMembers] = useState(false)
  const [searching, setSearching] = useState(false)
  const [forwarding, setForwarding] = useState<ChatMessage | null>(null)
  const [pinning, setPinning] = useState<ChatMessage | null>(null)
  const latest = useRef(messages)
  latest.current = messages
  const [deleting, setDeleting] = useState(false)
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

  /** Goes to a message (a pin, a reply's quote, a search result), loading earlier pages until it's there. */
  const jumpTo = async (target: number) => {
    setSearching(false)
    stick.current = false
    for (let guard = 0; guard < 40 && !latest.current.some((m) => m.id === target); guard++) {
      const first = latest.current[0]
      if (!first || first.id < target) break
      const page = await social.messages(id, first.id).catch(() => [] as ChatMessage[])
      if (!page.length) break
      setMore(page.length >= PAGE)
      merge(page)
      latest.current = [...page, ...latest.current]
    }
    setTimeout(() => {
      const el = list.current?.querySelector<HTMLElement>(`[data-mid="${target}"]`)
      if (!el) return toast("That message isn't in the chat anymore")
      el.scrollIntoView({ block: 'center', behavior: 'smooth' })
      el.classList.add('flash')
      setTimeout(() => el.classList.remove('flash'), 1600)
    }, 60)
  }
  const pin = (m: ChatMessage, hours: number) =>
    social.pin(id, m.id, hours).then(refreshConversationsSoon).catch((e) => toast((e as Error).message || "Couldn't pin it"))
  const unpin = (messageId: number) =>
    social.unpin(id, messageId).then(refreshConversationsSoon).catch((e) => toast((e as Error).message || "Couldn't unpin it"))
  const forward = (m: ChatMessage, to: number[]) =>
    social.forward(id, m.id, to)
      .then(() => { refreshConversationsSoon(); toast(to.length > 1 ? `Forwarded to ${to.length} chats` : 'Forwarded') })
      .catch((e) => toast((e as Error).message || "Couldn't forward it"))

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
        <div
          style={{ flex: 1, minWidth: 0, cursor: partner ? 'default' : 'pointer' }}
          onClick={() => !partner && setMembers(true)}
          title={partner ? undefined : 'Members'}
        >
          <div className="title-medium ellipsis" data-testid="chat-title">{chatTitle(conversation)}</div>
          <div className={`body-small ellipsis ${typers.length ? 'primary-text' : 'muted'}`}>{subtitle}</div>
        </div>
        <IconButton icon="search" label="Search this chat" onClick={() => setSearching(true)} />
        <JamQueueButton conversationId={id} />
        <JamButton conversation={conversation} />
        {!partner && <GroupMenu conversation={conversation} onShowMembers={() => setMembers(true)} onGone={nav.back} />}
      </div>
      {members && <GroupInfoSheet conversation={conversation} onClose={() => setMembers(false)} />}
      <PinnedBar pins={(conversation.pins ?? []).filter((p) => p.expiresAt > Date.now())} onJump={(m) => void jumpTo(m)} onUnpin={(m) => void unpin(m)} />
      {searching && <ChatSearch conversationId={id} onPick={(m) => void jumpTo(m)} onClose={() => setSearching(false)} />}
      <div
        ref={list}
        onScroll={(e) => {
          const el = e.currentTarget
          stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80
        }}
        style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '8px 12px 12px', display: searching ? 'none' : undefined }}
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
            onJump={(x) => void jumpTo(x)}
            onPin={() => setPinning(m)}
            onUnpin={() => void unpin(m.id)}
            onForward={() => setForwarding(m)}
          />
        ))}
        <Seen conversation={conversation} messages={messages} />
      </div>
      {conversation.canMessage === false ? (
        <div className="body-medium muted" style={{ padding: 16, textAlign: 'center' }}>
          You can't message this chat anymore.
          <button className="btn text" style={{ marginLeft: 8 }} onClick={() => setDeleting(true)}>Delete chat</button>
        </div>
      ) : (
        <Composer
          conversation={conversation}
          replyTo={replyTo}
          onCancelReply={() => setReplyTo(null)}
          onSent={(m) => {
            stick.current = true
            setReplyTo(null)
            merge([m])
          }}
        />
      )}
      {editing && <EditMessage m={editing} conversation={conversation} onClose={() => setEditing(null)} onSaved={(m) => merge([m])} />}
      {pinning && <PinDialog onClose={() => setPinning(null)} onPin={(hours) => void pin(pinning, hours)} />}
      {forwarding && <ForwardDialog onClose={() => setForwarding(null)} onForward={(to) => { const m = forwarding; setForwarding(null); void forward(m, to) }} />}
      {deleting && (
        <Dialog
          title="Delete this chat?"
          onClose={() => setDeleting(false)}
          actions={
            <>
              <button className="btn text" onClick={() => setDeleting(false)}>Cancel</button>
              <button className="btn danger" onClick={() => { setDeleting(false); deleteConversation(id).then(nav.back).catch((e) => toast((e as Error).message || "Couldn't delete it")) }}>Delete</button>
            </>
          }
        >
          It's removed for you only.
        </Dialog>
      )}
    </div>
  )
}

/** "Seen" (a DM) or "Seen by Alice, Bob" / "Seen by everyone" (a group) under your newest message, if it's the last one. */
function Seen({ conversation, messages }: { conversation: Conversation; messages: ChatMessage[] }) {
  const myId = me()?.id
  const last = [...messages].reverse().find((m) => !m.system)
  if (!last || last.sender.id !== myId || last.deleted) return null
  const others = conversation.members.filter((m) => m.id !== myId)
  const seenBy = others.filter((m) => (conversation.readMarks ?? []).some((r) => r.userId === m.id && r.lastReadId >= last.id))
  if (!seenBy.length) return null
  const text = conversation.kind !== 'group' ? 'Seen'
    : seenBy.length === others.length ? 'Seen by everyone' : `Seen by ${seenBy.map((m) => m.displayName).join(', ')}`
  return <div className="body-small muted" style={{ textAlign: 'right', fontSize: 11, margin: '2px 8px 0' }}>{text}</div>
}

/** While you're in this chat's jam: what's coming up (the host can change it, others see it). */
function JamQueueButton({ conversationId }: { conversationId: number }) {
  const joined = useListen((s) => s.joined)
  const [open, setOpen] = useState(false)
  if (joined !== conversationId) return null
  return (
    <>
      <IconButton icon="queue_music" label="Jam queue" onClick={() => setOpen(true)} />
      {open && <QueueSheet onClose={() => setOpen(false)} />}
    </>
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

function Bubble({ m, conversation, first, onReply, onEdit, onChanged, onJump, onPin, onUnpin, onForward }: {
  m: ChatMessage
  conversation: Conversation
  first: boolean
  onReply: () => void
  onEdit: () => void
  onChanged: (m: ChatMessage) => void
  /** Goes to another message (a quote, or the one a "pinned a message" line is about). */
  onJump: (id: number) => void
  onPin: () => void
  onUnpin: () => void
  onForward: () => void
}) {
  const mine = m.sender.id === me()?.id
  const [menu, setMenu] = useState<DOMRect | null>(null)
  const [reacting, setReacting] = useState<'more' | 'edit' | 'who' | null>(null)
  const isGroup = conversation.kind === 'group'
  const pinned = (conversation.pins ?? []).some((p) => p.message.id === m.id && p.expiresAt > Date.now())
  const canMessage = conversation.canMessage !== false
  if (m.system) {
    // "… pinned a message" goes to that message.
    const target = m.replyTo && !m.replyTo.hidden ? m.replyTo.id : null
    return (
      <div data-mid={m.id} className="body-small muted" onClick={() => target != null && onJump(target)} style={{ textAlign: 'center', padding: '10px 0', cursor: target != null ? 'pointer' : undefined }}>
        {m.body}
      </div>
    )
  }

  const react = (emoji: string) => {
    // One reaction each: choosing another replaces it, choosing yours again takes it back.
    social.react(m.conversationId, m.id, myReaction(m) === emoji ? null : emoji).then(onChanged).catch(() => toast("Couldn't react"))
  }
  const items: MenuItem[] = [
    { label: 'Reply', onClick: onReply, hidden: m.deleted },
    { label: 'Play song', onClick: () => m.song && playShared(m.song), hidden: !m.song || m.deleted },
    { label: 'Copy text', onClick: () => void navigator.clipboard?.writeText(m.body), hidden: !m.body || m.deleted },
    { label: 'Pin', onClick: onPin, hidden: pinned || m.deleted || !canMessage },
    { label: 'Unpin', onClick: onUnpin, hidden: !pinned },
    { label: 'Forward', onClick: onForward, hidden: m.deleted },
    { label: 'Edit', onClick: onEdit, hidden: !mine || m.deleted || !!m.song || !!m.image || !!m.voiceMs },
    { label: 'Delete', onClick: () => social.deleteMessage(m.conversationId, m.id).then(onChanged).catch(() => toast("Couldn't delete it")), hidden: !mine || m.deleted },
  ]
  // Stickers show on their own, without a bubble.
  const bg = m.image?.kind === 'sticker' && !m.body ? 'transparent' : mine ? 'var(--primary-container)' : 'var(--surface-container-high)'
  return (
    <div className="bubble-row" data-mid={m.id} style={{ display: 'flex', flexDirection: mine ? 'row-reverse' : 'row', alignItems: 'flex-end', gap: 8, marginTop: first ? 12 : 3 }}>
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
            <div
              className="body-small"
              onClick={() => !m.replyTo!.hidden && onJump(m.replyTo!.id)}
              style={{ borderLeft: '3px solid var(--primary)', padding: '2px 8px', marginBottom: 6, opacity: 0.8, cursor: m.replyTo.hidden ? undefined : 'pointer' }}
            >
              <b>{m.replyTo.sender.displayName}</b>
              <div className="ellipsis">{quoteText(m.replyTo)}</div>
            </div>
          )}
          {m.deleted ? (
            <i className="muted">Message deleted</i>
          ) : (
            <>
              {m.song && <SongCard song={m.song} />}
              {m.image && <ChatPicture m={m} />}
              {m.voiceMs != null && m.voiceMs > 0 && <VoicePlayer m={m} />}
              {m.request && <RequestLine m={m} conversation={conversation} onChanged={onChanged} />}
              {m.body && <div style={{ padding: m.image ? '6px 8px 4px' : 0, marginTop: m.song ? 6 : 0 }}><WithMentions body={m.body} m={m} conversation={conversation} /></div>}
            </>
          )}
        </div>
        {m.reactions && m.reactions.length > 0 && (
          <div style={{ display: 'flex', gap: 4, marginTop: -6, zIndex: 1 }}>
            {m.reactions.map((r) => (
              <button key={r.emoji} className="chip" title="Who reacted" onClick={() => setReacting('who')} style={{ height: 24, padding: '0 8px', fontSize: 13, background: r.userIds.includes(me()?.id ?? -1) ? 'var(--primary-container)' : 'var(--surface-container-highest)' }}>
                {r.emoji}{r.userIds.length > 1 ? ` ${r.userIds.length}` : ''}
              </button>
            ))}
          </div>
        )}
        <div className="body-small muted" style={{ fontSize: 11, margin: '2px 8px 0' }}>
          {pinned && <Icon name="push_pin" size={11} style={{ verticalAlign: '-1px', marginRight: 3 }} />}
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
          header={!m.deleted && canMessage ? (
            <ReactionRow
              m={m}
              onReact={(e) => { setMenu(null); react(e) }}
              onMore={() => { setMenu(null); setReacting('more') }}
              onEdit={() => { setMenu(null); setReacting('edit') }}
            />
          ) : undefined}
          items={items}
        />
      )}
      {reacting === 'more' && <EmojiPicker onClose={() => setReacting(null)} onPick={(e) => { setReacting(null); react(e) }} />}
      {reacting === 'edit' && <EditQuickReactions onClose={() => setReacting(null)} />}
      {reacting === 'who' && <WhoReacted m={m} conversation={conversation} onClose={() => setReacting(null)} onRemoveMine={() => social.react(m.conversationId, m.id, null).then(onChanged).catch(() => toast("Couldn't change it"))} />}
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
        <div className="scrim center" onClick={() => setBig(false)} style={{ flexDirection: 'column', gap: 12 }}>
          <img src={url} alt="" style={{ maxWidth: '92vw', maxHeight: '80vh', borderRadius: 8 }} />
          <a className="btn tonal" href={url} download={`jukebox-${m.id}.${m.image!.kind === 'gif' ? 'gif' : 'jpg'}`} onClick={(e) => e.stopPropagation()}>
            <Icon name="download" size={18} />Save
          </a>
        </div>
      )}
    </>
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

function Composer({ conversation, replyTo, onCancelReply, onSent }: {
  conversation: Conversation
  replyTo: ChatMessage | null
  onCancelReply: () => void
  onSent: (m: ChatMessage) => void
}) {
  const conversationId = conversation.id
  const [text, setText] = useState('')
  const voice = useVoiceRecorder(conversationId, (m) => { onCancelReply(); onSent(m) })
  // @mentions put in the text: who, and the name typed for them. A name deleted from the text isn't mentioned.
  const [mentioned, setMentioned] = useState<Record<number, string>>({})
  const [cursor, setCursor] = useState(0)
  const suggest = mentionSuggestions(conversation, text, cursor)
  const mentionsIn = (body: string) => Object.entries(mentioned).filter(([, name]) => body.includes('@' + name)).map(([uid]) => Number(uid))
  const pickMention = (u: SocialUser) => {
    const inserted = `@${u.displayName} `
    const next = text.slice(0, suggest.at) + inserted + text.slice(cursor)
    const at = suggest.at + inserted.length
    setText(next)
    setMentioned({ ...mentioned, [u.id]: u.displayName })
    setCursor(at)
    requestAnimationFrame(() => box.current?.setSelectionRange(at, at))
  }
  const [busy, setBusy] = useState(false)
  const box = useRef<HTMLTextAreaElement>(null)
  const file = useRef<HTMLInputElement>(null)
  useEffect(() => { if (replyTo) box.current?.focus() }, [replyTo])
  const send = async () => {
    const body = text.trim()
    if (!body || busy) return
    setBusy(true)
    try {
      onSent(await social.sendMessage(conversationId, body, null, replyTo?.id ?? null, mentionsIn(body)))
      setText('')
      setMentioned({})
    } catch (e) {
      toast((e as Error).message || "Couldn't send it")
    }
    setBusy(false)
    box.current?.focus()
  }
  // A picture (chosen, pasted or dropped) is shown first, with a caption box.
  const [picture, setPicture] = useState<File | null>(null)
  const [sharingMusic, setSharingMusic] = useState(false)
  const sendPicked = async (f: File, caption: string) => {
    setPicture(null)
    try {
      onSent(await sendPicture(conversationId, f, caption, replyTo?.id))
    } catch (e) {
      toast((e as Error).message || "Couldn't send the picture")
    }
  }
  const pickImage = (files: FileList | null | undefined) => {
    const f = [...(files ?? [])].find((x) => x.type.startsWith('image/'))
    if (f) setPicture(f)
    return !!f
  }
  return (
    <div
      style={{ flexShrink: 0, padding: '8px 12px 12px', borderTop: '1px solid var(--outline-variant)' }}
      onDragOver={(e) => e.dataTransfer.types.includes('Files') && e.preventDefault()}
      onDrop={(e) => { if (pickImage(e.dataTransfer.files)) e.preventDefault() }}
    >
      {picture && <PicturePreview file={picture} onClose={() => setPicture(null)} onSend={(caption) => void sendPicked(picture, caption)} />}
      {sharingMusic && <ShareMusicSheet conversationId={conversationId} replyTo={replyTo?.id} onClose={() => setSharingMusic(false)} onSent={(m) => { onCancelReply(); onSent(m) }} />}
      {replyTo && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '0 4px 8px' }}>
          <Icon name="reply" size={18} />
          <div className="body-small ellipsis" style={{ flex: 1 }}>Replying to <b>{replyTo.sender.displayName}</b>: {replyTo.song ? replyTo.song.title : replyTo.body}</div>
          <IconButton icon="close" label="Cancel reply" size={18} onClick={onCancelReply} />
        </div>
      )}
      {suggest.members.length > 0 && <MentionList members={suggest.members} onPick={pickMention} />}
 {voice.recording ? (
        <RecordingBar elapsed={voice.elapsed} onCancel={voice.cancel} onSend={voice.send} />
      ) : (
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 6 }}>
        <IconButton icon="image" label="Send a picture" onClick={() => file.current?.click()} />
        <input ref={file} type="file" accept="image/*" hidden onChange={(e) => { pickImage(e.target.files); e.target.value = '' }} />
        <IconButton icon="music_note" label="Share music" onClick={() => setSharingMusic(true)} />
        <textarea
          ref={box}
          value={text}
          rows={1}
          placeholder="Message"
          aria-label="Message"
          onChange={(e) => {
            setText(e.target.value)
            setCursor(e.target.selectionStart)
            sendTyping(conversationId)
          }}
          onSelect={(e) => setCursor(e.currentTarget.selectionStart)}
          onPaste={(e) => { if (pickImage(e.clipboardData.files)) e.preventDefault() }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey && suggest.members.length) {
              e.preventDefault()
              pickMention(suggest.members[0])
            } else if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault()
              void send()
            }
          }}
          style={{
            flex: 1, resize: 'none', border: 0, outline: 'none', borderRadius: 20, padding: '10px 16px', maxHeight: 140, minHeight: 40,
            background: 'var(--surface-container-high)', fontSize: 15, lineHeight: 1.35, fieldSizing: 'content',
          } as React.CSSProperties}
        />
        {/* With nothing typed, the button records a voice message instead. */}
        {text.trim() ? (
          <button className="fab-play" style={{ width: 40, height: 40, boxShadow: 'none' }} aria-label="Send" disabled={busy} onClick={() => void send()}>
            <Icon name="send" filled size={20} />
          </button>
        ) : (
          <button className="fab-play" style={{ width: 40, height: 40, boxShadow: 'none' }} aria-label="Record a voice message" disabled={voice.sending} onClick={() => void voice.start(replyTo?.id)}>
            <Icon name="mic" filled size={20} />
          </button>
        )}
      </div>
      )}
    </div>
  )
}

function EditMessage({ m, conversation, onClose, onSaved }: { m: ChatMessage; conversation: Conversation; onClose: () => void; onSaved: (m: ChatMessage) => void }) {
  const [text, setText] = useState(m.body)
  // Mentions stay while their "@Name" is still in the text.
  const kept = (m.mentions ?? []).filter((uid) => {
    const name = conversation.members.find((x) => x.id === uid)?.displayName
    return name != null && text.includes('@' + name)
  })
  const save = () =>
    social.editMessage(m.conversationId, m.id, text.trim(), kept)
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
