import { useEffect, useState } from 'react'
import type { Conversation } from '../api/socialTypes'
import type { SocialUser } from '../api/types'
import { social } from '../api/social'
import { Avatar } from './avatars'
import { useListen } from './listen'
import { deleteForEveryone, leaveGroup, me, refreshConversationsSoon, useSocial } from './social'
import { Checkbox, Dialog, Icon, IconButton, Menu, NameDialog, Sheet, toast } from '../ui/kit'
import { usePhotoPicker } from '../ui/PhotoPicker'

// A group's members and settings (ui/social/GroupInfoSheet.kt and the group menu in ChatScreen.kt): who's in
// it (in the jam, online, offline), and for the owner adding, removing and renaming. Anyone can change the
// group's photo or leave; the owner can delete it for everyone.

/** Runs a change to the group, then reloads the chats so the new state shows everywhere. */
async function change(what: () => Promise<unknown>, done: string, failed = "Couldn't change the group") {
  try {
    await what()
    refreshConversationsSoon()
    toast(done)
  } catch (e) {
    toast((e as Error).message || failed)
  }
}

/** The members sheet: opened from the chat's name, or ⋮ › Members. */
export function GroupInfoSheet({ conversation, onClose }: { conversation: Conversation; onClose: () => void }) {
  const myId = me()?.id
  const isOwner = conversation.createdBy === myId
  const listeners = useListen((s) => s.sessions[conversation.id]) ?? conversation.listeners ?? []
  const host = useListen((s) => s.owners[conversation.id]) ?? conversation.listenOwner
  const [online, setOnline] = useState<number[]>([])
  const [renaming, setRenaming] = useState(false)
  const [adding, setAdding] = useState(false)
  const [removing, setRemoving] = useState<SocialUser | null>(null)
  useEffect(() => {
    social.onlineMembers(conversation.id).then(setOnline).catch(() => {})
  }, [conversation.id])

  const inJam = conversation.members.filter((m) => listeners.includes(m.id))
  const onlineNow = conversation.members.filter((m) => !listeners.includes(m.id) && (online.includes(m.id) || m.id === myId))
  const offline = conversation.members.filter((m) => !inJam.includes(m) && !onlineNow.includes(m))

  return (
    <Sheet onClose={onClose}>
      <div style={{ padding: '0 16px 8px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          <div className="headline-small ellipsis" style={{ flex: 1 }}>{conversation.name || 'Group'}</div>
          {isOwner && <IconButton icon="edit" label="Rename the group" onClick={() => setRenaming(true)} />}
        </div>
        <div className="body-medium muted">Group · {conversation.members.length} members</div>
      </div>
      {isOwner && (
        <div className="list-row" onClick={() => setAdding(true)} style={{ padding: '10px 16px' }}>
          <span style={{ width: 40, height: 40, borderRadius: '50%', background: 'var(--primary-container)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <Icon name="person_add" />
          </span>
          <span className="text body-large primary-text">Add people</span>
        </div>
      )}
      {([['In the jam', inJam], ['Online', onlineNow], ['Offline', offline]] as const).map(([title, people]) =>
        people.length === 0 ? null : (
          <div key={title}>
            <div className="label-large muted" style={{ padding: '12px 16px 4px' }}>{title}</div>
            {people.map((u) => (
              <div key={u.id} className="list-row" style={{ padding: '6px 8px 6px 16px', cursor: 'default' }}>
                <Avatar name={u.displayName} userKey={u.username} user={u} size={40} online={online.includes(u.id) || listeners.includes(u.id)} />
                <div className="text">
                  <div className="body-large ellipsis">{u.id === myId ? 'You' : u.displayName}</div>
                  <div className="body-small muted ellipsis">
                    {[`@${u.username}`, u.id === host ? 'Hosting the jam' : null, u.id === conversation.createdBy ? 'Group owner' : null].filter(Boolean).join(' · ')}
                  </div>
                </div>
                {isOwner && u.id !== myId && <IconButton icon="close" label={`Remove ${u.displayName}`} onClick={() => setRemoving(u)} />}
              </div>
            ))}
          </div>
        ),
      )}
      {renaming && (
        <NameDialog
          title="Rename group" confirm="Rename" label="Group name" max={50} initial={conversation.name ?? ''}
          onClose={() => setRenaming(false)}
          onConfirm={(name) => {
            setRenaming(false)
            void change(() => social.renameGroup(conversation.id, name), 'Group renamed')
          }}
        />
      )}
      {adding && (
        <AddPeople
          conversation={conversation}
          onClose={() => setAdding(false)}
          onAdd={(ids) => {
            setAdding(false)
            void change(() => social.addMembers(conversation.id, ids), ids.length === 1 ? 'Added to the group' : `Added ${ids.length} people`)
          }}
        />
      )}
      {removing && (
        <Dialog
          title={`Remove ${removing.displayName}?`}
          onClose={() => setRemoving(null)}
          actions={
            <>
              <button className="btn text" onClick={() => setRemoving(null)}>Cancel</button>
              <button className="btn danger" onClick={() => { const u = removing; setRemoving(null); void change(() => social.removeMember(conversation.id, u.id), `${u.displayName} removed`) }}>Remove</button>
            </>
          }
        >
          They'll leave {conversation.name || 'the group'} and the chat will disappear for them.
        </Dialog>
      )}
    </Sheet>
  )
}

/** Picking friends who aren't in the group yet. */
function AddPeople({ conversation, onClose, onAdd }: { conversation: Conversation; onClose: () => void; onAdd: (ids: number[]) => void }) {
  const friends = useSocial((s) => s.friends).filter((f) => !conversation.members.some((m) => m.id === f.user.id))
  const [picked, setPicked] = useState<number[]>([])
  return (
    <Dialog
      title="Add people"
      onClose={onClose}
      actions={
        <>
          <button className="btn text" onClick={onClose}>Cancel</button>
          <button className="btn" disabled={!picked.length} onClick={() => onAdd(picked)}>{picked.length > 1 ? `Add ${picked.length}` : 'Add'}</button>
        </>
      }
    >
      {friends.length === 0 ? 'All your friends are already in this group.' : (
        <div style={{ maxHeight: 320, overflowY: 'auto' }}>
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
        </div>
      )}
    </Dialog>
  )
}

/** The ⋮ menu at the top of a group chat. onGone runs after leaving or deleting it. */
export function GroupMenu({ conversation, onShowMembers, onGone }: { conversation: Conversation; onShowMembers: () => void; onGone: () => void }) {
  const isOwner = conversation.createdBy === me()?.id
  const title = conversation.name || 'the group'
  const [anchor, setAnchor] = useState<DOMRect | null>(null)
  const [confirm, setConfirm] = useState<'leave' | 'delete' | null>(null)
  const picker = usePhotoPicker({
    title: 'Group photo',
    round: true,
    onPicked: (jpeg) => change(() => social.setGroupPicture(conversation.id, jpeg), 'Group photo updated', "Couldn't change the photo"),
  })
  const go = async () => {
    const leaving = confirm === 'leave'
    setConfirm(null)
    try {
      if (leaving) await leaveGroup(conversation.id)
      else await deleteForEveryone(conversation.id)
      onGone()
    } catch (e) {
      toast((e as Error).message || 'Something went wrong')
    }
  }
  return (
    <>
      <IconButton icon="more_vert" label="More" onClick={(e) => setAnchor((e.currentTarget as HTMLElement).getBoundingClientRect())} />
      {picker.element}
      {anchor && (
        <Menu
          anchor={anchor}
          onClose={() => setAnchor(null)}
          items={[
            { label: isOwner ? 'Members · add or remove' : 'Members', onClick: onShowMembers },
            { label: 'Change group photo', onClick: () => picker.open() },
            {
              label: 'Remove group photo', hidden: conversation.picture == null,
              onClick: () => void change(() => social.removeGroupPicture(conversation.id), 'Group photo removed', "Couldn't change the photo"),
            },
            { label: 'Leave group', onClick: () => setConfirm('leave') },
            { label: 'Delete for everyone', onClick: () => setConfirm('delete'), hidden: !isOwner, danger: true },
          ]}
        />
      )}
      {confirm && (
        <Dialog
          title={confirm === 'leave' ? `Leave "${title}"?` : `Delete "${title}" for everyone?`}
          onClose={() => setConfirm(null)}
          actions={
            <>
              <button className="btn text" onClick={() => setConfirm(null)}>Cancel</button>
              <button className={`btn${confirm === 'delete' ? ' danger' : ''}`} onClick={() => void go()}>{confirm === 'leave' ? 'Leave' : 'Delete'}</button>
            </>
          }
        >
          {confirm === 'leave'
            ? `You won't get its messages anymore, and the others will see that you left.${isOwner ? ' Someone else in the group becomes its owner.' : ''}`
            : 'The group and all its messages are deleted for every member. This can’t be undone.'}
        </Dialog>
      )}
    </>
  )
}
