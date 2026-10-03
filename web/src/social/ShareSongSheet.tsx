import { useState } from 'react'
import type { Song, SongRef } from '../api/types'
import { songToRef } from '../api/types'
import { social as api } from '../api/social'
import { conversationTitle, isGroup } from '../api/socialTypes'
import { useSocial, me, refreshConversationsSoon } from './social'
import { Avatar, GroupAvatar } from './avatars'
import { ClipOptions } from './ClipPicker'
import { Sheet, toast } from '../ui/kit'

/** Pick a chat or friend to send a song to, optionally just a part of it (ui/social/SocialComponents.kt). */
export function ShareSongSheet({ song, onClose }: { song: Song | SongRef; onClose: () => void }) {
  const ref: SongRef = 'clipStartMs' in song ? song : songToRef(song as Song)
  const conversations = useSocial((s) => s.conversations)
  const friends = useSocial((s) => s.friends)
  const myId = me()?.id
  const [shared, setShared] = useState<SongRef>(ref)

  // Friends you don't have a DM with yet also appear, so you can share with anyone.
  const dmPartners = new Set(conversations.filter((c) => !isGroup(c)).flatMap((c) => c.members.map((m) => m.id)))
  const newPeople = friends.filter((f) => !dmPartners.has(f.user.id))
  const targets = conversations.filter((c) => c.canMessage !== false)
  const groups = targets.filter(isGroup)
  const dms = targets.filter((c) => !isGroup(c))
  const online = new Set(friends.filter((f) => f.online).map((f) => f.user.id))

  async function send(title: string, conversationId: () => Promise<number>) {
    try {
      await api.sendMessage(await conversationId(), '', shared)
      toast(`Sent to ${title}`)
      refreshConversationsSoon()
      onClose()
    } catch (e) {
      toast((e as Error).message || "Couldn't send")
    }
  }

  const target = (key: string, title: string, avatar: React.ReactNode, subtitle: string | undefined, onClick: () => void) => (
    <div key={key} className="list-row" onClick={onClick}>
      {avatar}
      <div className="text" style={{ paddingLeft: 2 }}>
        <div className="body-large ellipsis">{title}</div>
        {subtitle?.trim() && <div className="body-small muted ellipsis">{subtitle}</div>}
      </div>
    </div>
  )

  return (
    <Sheet onClose={onClose}>
      <div className="section-title title-large" style={{ marginTop: 0, fontWeight: 700 }}>Share "{ref.title}"</div>
      <ClipOptions song={ref} onChange={setShared} />
      {!targets.length && !newPeople.length && <div className="muted" style={{ padding: 16 }}>Add friends first, from the Friends tab.</div>}
      {/* Groups and people are listed separately, so it's clear who will get it. */}
      {groups.length > 0 && <div className="section-title title-large" style={{ fontWeight: 700 }}>Groups</div>}
      {groups.map((c) => {
        const title = conversationTitle(c, myId)
        return target(
          `c${c.id}`, title, <GroupAvatar groupKey={`c${c.id}`} size={40} conversation={c} />,
          c.members.filter((m) => m.id !== myId).map((m) => m.displayName).join(', '),
          () => void send(title, async () => c.id),
        )
      })}
      {(dms.length > 0 || newPeople.length > 0) && <div className="section-title title-large" style={{ fontWeight: 700 }}>People</div>}
      {dms.map((c) => {
        const title = conversationTitle(c, myId)
        const other = c.members.find((m) => m.id !== myId)
        return target(
          `c${c.id}`, title, <Avatar name={title} userKey={other?.username ?? `c${c.id}`} size={40} user={other} online={other ? online.has(other.id) : false} />,
          undefined, () => void send(title, async () => c.id),
        )
      })}
      {newPeople.map((f) =>
        target(
          `f${f.user.id}`, f.user.displayName, <Avatar name={f.user.displayName} userKey={f.user.username} size={40} user={f.user} online={f.online} />,
          undefined, () => void send(f.user.displayName, async () => (await api.openDm(f.user.id)).id),
        ),
      )}
      <div style={{ height: 16 }} />
    </Sheet>
  )
}
