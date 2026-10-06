import { useEffect, useState } from 'react'
import type { Conversation, Friend } from '../api/socialTypes'
import type { SocialUser } from '../api/types'
import { refToSong } from '../api/types'
import { social } from '../api/social'
import * as player from '../player/player'
import { Avatar, GroupAvatar } from '../social/avatars'
import { deleteConversation, me, refresh, useSocial } from '../social/social'
import { load, save } from '../state/storage'
import { ScreenHeader } from '../ui/components'
import { Checkbox, Dialog, Icon, IconButton, Menu, MoreMenu, toast } from '../ui/kit'
import { useNav } from '../ui/nav'

/** A chat's name: the other person in a DM, the group's name otherwise. */
export function chatTitle(c: Conversation): string {
  if (c.kind === 'group') return c.name || c.members.map((m) => m.displayName).join(', ')
  return c.members.find((m) => m.id !== me()?.id)?.displayName ?? 'Chat'
}

/** The other person in a DM. */
export const dmPartner = (c: Conversation): SocialUser | undefined => (c.kind === 'group' ? undefined : c.members.find((m) => m.id !== me()?.id))

/** "now", "5 min", "14:02", "Mon", "12 Sep". */
export function shortTime(at: number): string {
  const d = new Date(at)
  const mins = (Date.now() - at) / 60_000
  if (mins < 1) return 'now'
  if (mins < 60) return `${Math.floor(mins)} min`
  if (d.toDateString() === new Date().toDateString()) return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  if (mins < 7 * 24 * 60) return d.toLocaleDateString('en-GB', { weekday: 'short' })
  return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })
}

/** One line for a chat's latest message. */
function preview(c: Conversation): string {
  const m = c.lastMessage
  if (!m) return 'No messages yet'
  const who = m.sender.id === me()?.id ? 'You: ' : c.kind === 'group' ? `${m.sender.displayName}: ` : ''
  if (m.deleted) return who + 'Message deleted'
  if (m.system) return m.body
  if (m.song) return who + `🎵 ${m.song.title}`
  if (m.image) return who + (m.image.kind === 'photo' ? '📷 Photo' : m.image.kind === 'gif' ? 'GIF' : 'Sticker')
  if (m.voiceMs) return who + '🎤 Voice message'
  return who + m.body
}

/** Friends (ui/social/SocialScreen.kt): your chats, friend requests, and friends with what they're playing. */
export function FriendsScreen() {
  const nav = useNav()
  const status = useSocial((s) => s.status)
  const friends = useSocial((s) => s.friends)
  const requests = useSocial((s) => s.requests)
  const conversations = useSocial((s) => s.conversations)
  const [tab, setTab] = useState<'Chats' | 'Friends'>(() => load('friends.tab', 'Chats'))
  const [dialog, setDialog] = useState<'add' | 'group' | null>(null)
  useEffect(() => refresh(), [])
  const choose = (t: 'Chats' | 'Friends') => {
    setTab(t)
    save('friends.tab', t)
  }
  const message = async (user: SocialUser) => {
    try {
      const c = await social.openDm(user.id)
      nav.openChat(c.id)
    } catch (e) {
      toast((e as Error).message || "Couldn't open the chat")
    }
  }
  const unread = conversations.reduce((n, c) => n + (c.unread > 0 ? 1 : 0), 0)
  const sorted = [...conversations].sort((a, b) => (b.lastMessage?.createdAt ?? 0) - (a.lastMessage?.createdAt ?? 0))

  return (
    <div className="page">
      <ScreenHeader
        title="Friends"
        actions={
          <>
            <IconButton icon="person_add" label="Add friend" onClick={() => setDialog('add')} />
            <IconButton icon="group_add" label="New group chat" onClick={() => setDialog('group')} />
          </>
        }
      />
      {status === 'Unavailable' && <div className="body-small muted" style={{ padding: '0 16px 8px' }}>Can't reach the server right now. Trying again…</div>}
      <div style={{ display: 'flex', gap: 8, padding: '0 16px 12px' }}>
        {(['Chats', 'Friends'] as const).map((t) => {
          const count = t === 'Chats' ? unread : requests.incoming.length
          return (
            <button key={t} className={`chip${tab === t ? ' selected' : ''}`} onClick={() => choose(t)}>
              {t}
              {count > 0 && <span className="badge" style={{ position: 'static' }}>{count}</span>}
            </button>
          )
        })}
      </div>

      {tab === 'Chats' ? (
        <div style={{ padding: '0 8px' }}>
          {sorted.length === 0 && <div className="muted" style={{ padding: 16 }}>No chats yet. Message a friend from the Friends list.</div>}
          {sorted.map((c) => (
            <ChatRow key={c.id} c={c} friends={friends} onOpen={() => nav.openChat(c.id)} />
          ))}
        </div>
      ) : (
        <div style={{ padding: '0 8px' }}>
          {requests.incoming.length > 0 && (
            <>
              <h2 className="section-title" style={{ margin: '8px 8px 8px' }}>Friend requests</h2>
              {requests.incoming.map((u) => (
                <div key={u.id} className="list-row">
                  <Avatar name={u.displayName} userKey={u.username} user={u} />
                  <div className="text">
                    <div className="title-medium ellipsis">{u.displayName}</div>
                    <div className="body-small muted">@{u.username}</div>
                  </div>
                  <button className="btn text" onClick={() => social.declineFriend(u.id).then(refresh).catch(() => toast("Couldn't do that"))}>Ignore</button>
                  <button className="btn" onClick={() => social.acceptFriend(u.id).then(refresh).catch(() => toast("Couldn't do that"))}>Accept</button>
                </div>
              ))}
            </>
          )}
          <h2 className="section-title" style={{ margin: '16px 8px 8px' }}>Friends · {friends.filter((f) => f.online).length} online</h2>
          {friends.length === 0 && (
            <div className="muted" style={{ padding: '8px 8px 16px' }}>
              Add friends by their username, or invite someone from Settings.
              <div style={{ marginTop: 12 }}><button className="btn tonal" onClick={nav.openSettings}>Invite a friend</button></div>
            </div>
          )}
          {friends.map((f) => (
            <FriendRow key={f.user.id} f={f} onMessage={() => void message(f.user)} />
          ))}
          {requests.outgoing.length > 0 && (
            <>
              <h2 className="section-title" style={{ margin: '16px 8px 8px' }}>Sent requests</h2>
              {requests.outgoing.map((u) => (
                <div key={u.id} className="list-row">
                  <Avatar name={u.displayName} userKey={u.username} user={u} />
                  <div className="text"><div className="title-medium ellipsis">{u.displayName}</div><div className="body-small muted">@{u.username}</div></div>
                  <button className="btn text" onClick={() => social.declineFriend(u.id).then(refresh).catch(() => toast("Couldn't do that"))}>Cancel</button>
                </div>
              ))}
            </>
          )}
        </div>
      )}
      {dialog === 'add' && <AddFriend onClose={() => setDialog(null)} />}
      {dialog === 'group' && <NewGroup friends={friends} onClose={() => setDialog(null)} onMade={(id) => nav.openChat(id)} />}
    </div>
  )
}

function ChatRow({ c, friends, onOpen }: { c: Conversation; friends: Friend[]; onOpen: () => void }) {
  const partner = dmPartner(c)
  const online = partner ? friends.some((f) => f.user.id === partner.id && f.online) : false
  const jamming = (c.listeners?.length ?? 0) > 0
  // A chat you can't message anymore (they left, or aren't your friend now) can be deleted: right-click or long-press it.
  const [menu, setMenu] = useState<DOMRect | null>(null)
  const [confirm, setConfirm] = useState(false)
  return (
    <div
      className="list-row" onClick={onOpen} data-testid="chat-row"
      onContextMenu={(e) => { if (c.canMessage === false) { e.preventDefault(); setMenu(new DOMRect(e.clientX, e.clientY, 0, 0)) } }}
    >
      {/* Clicks in the menu and dialog bubble up through React to this row: keep them from opening the chat. */}
      <span onClick={(e) => e.stopPropagation()} style={{ display: 'contents' }}>
      {menu && <Menu anchor={menu} onClose={() => setMenu(null)} items={[{ label: 'Delete chat', danger: true, onClick: () => setConfirm(true) }]} />}
      {confirm && (
        <Dialog
          title="Delete this chat?"
          onClose={() => setConfirm(false)}
          actions={
            <>
              <button className="btn text" onClick={() => setConfirm(false)}>Cancel</button>
              <button className="btn danger" onClick={() => { setConfirm(false); deleteConversation(c.id).catch((x) => toast((x as Error).message || "Couldn't delete it")) }}>Delete</button>
            </>
          }
        >
          It's removed for you only.
        </Dialog>
      )}
      </span>
      {partner ? <Avatar name={partner.displayName} userKey={partner.username} user={partner} online={online} size={52} /> : <GroupAvatar groupKey={String(c.id)} conversation={c} size={52} />}
      <div className="text">
        <div className="title-medium ellipsis" style={{ fontWeight: c.unread > 0 ? 800 : 600 }}>{chatTitle(c)}</div>
        <div className="body-small ellipsis" style={{ color: c.unread > 0 ? 'var(--on-surface)' : 'var(--on-surface-variant)' }}>
          {jamming && <span className="primary-text">🎧 Listening together · </span>}
          {preview(c)}
        </div>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 4 }}>
        {c.lastMessage && <span className="body-small muted">{shortTime(c.lastMessage.createdAt)}</span>}
        {c.unread > 0 && <span className="badge" style={{ position: 'static' }}>{(c.unreadMentions ?? 0) > 0 ? '@' : c.unread}</span>}
      </div>
    </div>
  )
}

function FriendRow({ f, onMessage }: { f: Friend; onMessage: () => void }) {
  const playing = f.online ? f.nowPlaying : undefined
  return (
    <div className="list-row" onClick={onMessage}>
      <Avatar name={f.user.displayName} userKey={f.user.username} user={f.user} online={f.online} size={48} />
      <div className="text">
        <div className="title-medium ellipsis">{f.user.displayName}</div>
        <div className="body-small muted ellipsis">
          {playing ? <><Icon name="graphic_eq" size={14} style={{ verticalAlign: '-2px', color: 'var(--primary)' }} /> {playing.title}{playing.artist ? ` · ${playing.artist}` : ''}</> : f.online ? 'Online' : `@${f.user.username}`}
        </div>
      </div>
      {playing && <IconButton icon="play_circle" label={`Play ${playing.title}`} onClick={() => player.play([refToSong(playing)])} />}
      <MoreMenu
        items={[
          { label: 'Message', onClick: onMessage },
          { label: 'Remove friend', onClick: () => social.removeFriend(f.user.id).then(refresh).catch(() => toast("Couldn't do that")) },
        ]}
      />
    </div>
  )
}

function AddFriend({ onClose }: { onClose: () => void }) {
  const [username, setUsername] = useState('')
  const [busy, setBusy] = useState(false)
  const send = async () => {
    setBusy(true)
    try {
      const r = await social.addFriend(username.trim())
      toast(r.status === 'accepted' || r.status === 'friends' ? `You and ${username.trim()} are friends now` : `Request sent to ${username.trim()}`)
      refresh()
      onClose()
    } catch (e) {
      toast((e as Error).message || "Couldn't send it")
      setBusy(false)
    }
  }
  return (
    <Dialog
      title="Add a friend"
      onClose={onClose}
      actions={
        <>
          <button className="btn text" onClick={onClose}>Close</button>
          <button className="btn" disabled={busy || !username.trim()} onClick={() => void send()}>Send request</button>
        </>
      }
    >
      <div className="field">
        <label>Their username</label>
        <input autoFocus value={username} onChange={(e) => setUsername(e.target.value.trim())} onKeyDown={(e) => e.key === 'Enter' && username && void send()} />
      </div>
    </Dialog>
  )
}

function NewGroup({ friends, onClose, onMade }: { friends: Friend[]; onClose: () => void; onMade: (id: number) => void }) {
  const [name, setName] = useState('')
  const [picked, setPicked] = useState<number[]>([])
  const create = async () => {
    try {
      const c = await social.createGroup(name.trim(), picked)
      refresh()
      onClose()
      onMade(c.id)
    } catch (e) {
      toast((e as Error).message || "Couldn't make the group")
    }
  }
  return (
    <Dialog
      title="New group chat"
      onClose={onClose}
      actions={
        <>
          <button className="btn text" onClick={onClose}>Cancel</button>
          <button className="btn" disabled={!name.trim() || picked.length === 0} onClick={() => void create()}>Create</button>
        </>
      }
    >
      <div className="field" style={{ marginBottom: 12 }}>
        <label>Group name</label>
        <input autoFocus value={name} maxLength={50} onChange={(e) => setName(e.target.value)} />
      </div>
      <div style={{ maxHeight: 300, overflowY: 'auto' }}>
        {friends.map((f) => {
          const on = picked.includes(f.user.id)
          const toggle = () => setPicked(on ? picked.filter((x) => x !== f.user.id) : [...picked, f.user.id])
          return (
            <div key={f.user.id} className="list-row" onClick={toggle} style={{ padding: '6px 4px' }}>
              <Avatar name={f.user.displayName} userKey={f.user.username} user={f.user} size={36} />
              <div className="text body-large ellipsis">{f.user.displayName}</div>
              <Checkbox checked={on} onChange={toggle} />
            </div>
          )
        })}
        {friends.length === 0 && <div className="muted">Add friends first.</div>}
      </div>
    </Dialog>
  )
}
