import { useRef, useState, type ReactNode } from 'react'
import { social } from '../api/social'
import { credentialsFor } from '../api/subsonic'
import type { Invite } from '../api/socialTypes'
import * as player from '../player/player'
import { useSession } from '../state/session'
import { Avatar } from '../social/avatars'
import { ScreenHeader, useLoad } from '../ui/components'
import { Dialog, IconButton, NameDialog, toast } from '../ui/kit'
import { checkPassword, PasswordStrength } from '../ui/password'
import { useNav } from '../ui/nav'

/** Settings (ui/settings/SettingsScreen.kt): your profile, password, invites for friends, and signing out. */
export function SettingsScreen() {
  const nav = useNav()
  const credentials = useSession((s) => s.credentials)
  const me = useSession((s) => s.social)
  const [renaming, setRenaming] = useState(false)
  const [confirmLogout, setConfirmLogout] = useState(false)
  const file = useRef<HTMLInputElement>(null)
  const username = credentials?.username ?? ''
  const name = me?.user.displayName || username

  const saveUser = (user: NonNullable<typeof me>['user'], done: string) => {
    if (me) useSession.getState().saveSocial({ ...me, user })
    toast(done)
  }
  const pickPicture = async (f: File | undefined) => {
    if (!f) return
    try {
      saveUser(await social.setAvatar(await squareJpeg(f, 512)), 'Profile picture updated')
    } catch (e) {
      toast((e as Error).message || "Couldn't change your picture")
    }
  }

  return (
    <div className="page">
      <ScreenHeader title="Settings" onBack={nav.back} />
      <div style={{ maxWidth: 680, padding: '0 16px', display: 'flex', flexDirection: 'column', gap: 16 }}>
        <Card>
          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            <button
              onClick={() => file.current?.click()}
              disabled={!me}
              aria-label="Change profile picture"
              style={{ border: 0, padding: 0, background: 'none', cursor: me ? 'pointer' : 'default', position: 'relative', borderRadius: '50%' }}
            >
              <Avatar name={name} userKey={username} size={88} user={me?.user} />
              {me && (
                <span className="icon filled" style={{ position: 'absolute', right: 0, bottom: 0, fontSize: 18, background: 'var(--primary)', color: 'var(--on-primary)', borderRadius: '50%', padding: 5 }}>
                  photo_camera
                </span>
              )}
            </button>
            <input ref={file} type="file" accept="image/*" hidden onChange={(e) => void pickPicture(e.target.files?.[0])} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="headline-small ellipsis">{name}</div>
              <div className="body-medium muted">@{username}</div>
            </div>
            {me && <button className="btn outlined" onClick={() => setRenaming(true)}>Edit name</button>}
          </div>
          {me?.user.avatar != null && (
            <button
              className="btn text"
              style={{ marginTop: 8, paddingLeft: 0 }}
              onClick={() => social.removeAvatar().then((u) => saveUser(u, 'Picture removed')).catch(() => toast("Couldn't remove it"))}
            >
              Remove picture
            </button>
          )}
        </Card>

        <ChangePassword username={username} />

        {me && <Invites />}

        <Card title="Signed in devices">
          <div className="body-medium muted" style={{ marginBottom: 12 }}>Sign out everywhere else, for example on a phone you no longer use.</div>
          <button
            className="btn tonal"
            onClick={() => social.logoutOthers().then(() => toast('Signed out on your other devices')).catch(() => toast("Couldn't do that right now"))}
            disabled={!me}
          >
            Sign out other devices
          </button>
        </Card>

        <button className="btn outlined" style={{ color: 'var(--error)', boxShadow: 'inset 0 0 0 1px var(--error)' }} onClick={() => setConfirmLogout(true)}>
          Log out
        </button>
        <div className="body-small muted" style={{ textAlign: 'center', padding: '8px 0 24px' }}>Jukebox</div>
      </div>

      {renaming && me && (
        <NameDialog
          title="Your name"
          confirm="Save"
          label="Shown to friends in chats and their friends list"
          initial={me.user.displayName}
          max={40}
          onClose={() => setRenaming(false)}
          onConfirm={(n) => {
            setRenaming(false)
            social.rename(n).then((u) => saveUser(u, 'Name saved')).catch((e) => toast((e as Error).message || "Couldn't save it"))
          }}
        />
      )}
      {confirmLogout && (
        <Dialog
          title="Log out?"
          onClose={() => setConfirmLogout(false)}
          actions={
            <>
              <button className="btn text" onClick={() => setConfirmLogout(false)}>Cancel</button>
              <button
                className="btn"
                onClick={() => {
                  player.stop()
                  void social.logout().catch(() => {})
                  useSession.getState().clear()
                }}
              >
                Log out
              </button>
            </>
          }
        >
          Music stops and friends will see you offline.
        </Dialog>
      )}
    </div>
  )
}

function Card({ title, children }: { title?: string; children: ReactNode }) {
  return (
    <section style={{ background: 'var(--surface-container-low)', borderRadius: 10, padding: 20 }}>
      {title && <h2 className="title-medium" style={{ margin: '0 0 8px' }}>{title}</h2>}
      {children}
    </section>
  )
}

/** Change your password: Jukebox checks the current one; this browser stays signed in with the new one. */
function ChangePassword({ username }: { username: string }) {
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [busy, setBusy] = useState(false)
  const check = next ? checkPassword(next, username) : null
  const can = !busy && current && next && !check?.problem
  const save = async () => {
    setBusy(true)
    try {
      await social.changePassword(current, next)
      // The music side signs in with a token made from the password: make the new one.
      useSession.getState().saveCredentials(credentialsFor(username, next))
      setCurrent('')
      setNext('')
      toast('Password changed. Your other devices were signed out')
    } catch (e) {
      toast((e as Error).message || "Couldn't change it")
    }
    setBusy(false)
  }
  return (
    <Card title="Change password">
      <form onSubmit={(e) => { e.preventDefault(); if (can) void save() }} style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        <input type="text" autoComplete="username" value={username} readOnly hidden />
        <div className="field">
          <label>Current password</label>
          <input type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} />
        </div>
        <div className="field">
          <label>New password</label>
          <input type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} />
          {check && <PasswordStrength check={check} />}
        </div>
        <div>
          <button className="btn" type="submit" disabled={!can}>{busy ? 'Saving…' : 'Update password'}</button>
        </div>
      </form>
    </Card>
  )
}

/** Invite codes: a friend signs up with one and becomes your friend straight away. */
function Invites() {
  const data = useLoad(['invites'], () => social.invites(), 30_000)
  const [made, setMade] = useState<Invite | null>(null)
  const [now] = useState(() => Date.now())
  const open = (data.data ?? []).filter((i) => !i.usedBy && i.expiresAt > now)
  const create = async () => {
    try {
      const invite = await social.createInvite()
      setMade(invite)
      data.retry()
    } catch (e) {
      toast((e as Error).message || "Couldn't make one")
    }
  }
  const copy = (code: string) => navigator.clipboard?.writeText(code).then(() => toast('Copied'), () => toast(code))
  return (
    <Card title="Invite a friend">
      <div className="body-medium muted" style={{ marginBottom: 12 }}>
        They sign up at {location.host} with the code and you become friends. A code works once and lasts a week.
      </div>
      <button className="btn tonal" onClick={() => void create()}>New invite code</button>
      {open.length > 0 && (
        <div style={{ marginTop: 12 }}>
          {open.map((i) => (
            <div key={i.code} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 0' }}>
              <span className="title-medium" style={{ fontFamily: 'ui-monospace, monospace', letterSpacing: '0.08em', flex: 1 }}>{i.code}</span>
              <span className="body-small muted">until {new Date(i.expiresAt).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })}</span>
              <IconButton icon="content_copy" label="Copy" onClick={() => void copy(i.code)} />
              <IconButton icon="delete" label="Delete" onClick={() => social.deleteInvite(i.code).then(data.retry).catch(() => toast("Couldn't delete it"))} />
            </div>
          ))}
        </div>
      )}
      {made && (
        <Dialog title="Invite code" onClose={() => setMade(null)} actions={<button className="btn" onClick={() => { void copy(made.code); setMade(null) }}>Copy</button>}>
          <div className="display-small" style={{ textAlign: 'center', fontFamily: 'ui-monospace, monospace', letterSpacing: '0.08em', margin: '8px 0' }}>{made.code}</div>
          Send it to your friend; they choose “Got an invite code? Sign up” on the login page.
        </Dialog>
      )}
    </Card>
  )
}

/** The middle square of a picture, as a JPEG at most [size] pixels wide (profile pictures). */
async function squareJpeg(file: File, size: number): Promise<Blob> {
  const bitmap = await createImageBitmap(file)
  const side = Math.min(bitmap.width, bitmap.height)
  const out = Math.min(size, side)
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = out
  canvas.getContext('2d')!.drawImage(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side, 0, 0, out, out)
  return new Promise((resolve, reject) => canvas.toBlob((b) => (b ? resolve(b) : reject(new Error("Couldn't read that picture"))), 'image/jpeg', 0.88))
}
