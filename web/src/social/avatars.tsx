import { useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { authedUrl, social } from '../api/social'
import type { Conversation } from '../api/socialTypes'
import type { SocialUser } from '../api/types'
import { Icon } from '../ui/kit'

// Profile and group pictures, as ui/social/SocialComponents.kt: a coloured circle with the
// person's first letter (or their picture), and a green dot when they're online.

const AVATAR_COLORS = ['#F5B942', '#E8643C', '#7FB8A4', '#8C7BD8', '#E38FB0', '#6FA8DC', '#C9A26B']

/** Java's String.hashCode, so a person gets the same colour as in the Android app. */
function hashCode(s: string) {
  let h = 0
  for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0
  return h
}
export const colorFor = (key: string) => AVATAR_COLORS[Math.abs(hashCode(key)) % AVATAR_COLORS.length]

/** A picture kept on the friends server (it needs the login with the request). */
export function useFriendsServerPicture(path?: string) {
  const [url, setUrl] = useState<string>()
  useEffect(() => {
    setUrl(undefined)
    if (!path) return
    let alive = true
    authedUrl(path).then((u) => alive && setUrl(u)).catch(() => {})
    return () => {
      alive = false
    }
  }, [path])
  return url
}

export function Avatar({ name, userKey, size = 44, online, user }: { name: string; userKey: string; size?: number; online?: boolean; user?: SocialUser }) {
  const picture = useFriendsServerPicture(user ? social.avatarPath(user) : undefined)
  const [enlarged, setEnlarged] = useState(false)
  return (
    <div style={{ position: 'relative', width: size, height: size, flexShrink: 0 }}>
      <div
        onContextMenu={(e) => {
          if (!picture) return
          e.preventDefault()
          setEnlarged(true)
        }}
        style={{
          width: size, height: size, borderRadius: '50%', background: colorFor(userKey), color: '#1B1726', display: 'flex',
          alignItems: 'center', justifyContent: 'center', overflow: 'hidden', fontFamily: 'var(--display)', fontWeight: 600,
          fontSize: size > 40 ? 22 : 14,
        }}
      >
        {picture ? <img src={picture} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} /> : (name.trim()[0]?.toUpperCase() ?? '?')}
      </div>
      {online && (
        <div style={{ position: 'absolute', right: 0, bottom: 0, width: size / 3.5, height: size / 3.5, borderRadius: '50%', background: '#4CC38A', border: '2px solid var(--surface)' }} />
      )}
      {enlarged && picture && <PictureViewer url={picture} name={name} onClose={() => setEnlarged(false)} />}
    </div>
  )
}

/** A coloured circle with a group icon, so groups don't look like a person. */
export function GroupAvatar({ groupKey, size = 44, conversation }: { groupKey: string; size?: number; conversation?: Conversation }) {
  const picture = useFriendsServerPicture(conversation ? social.groupPicturePath(conversation) : undefined)
  const [enlarged, setEnlarged] = useState(false)
  return (
    <div
      onContextMenu={(e) => {
        if (!picture) return
        e.preventDefault()
        setEnlarged(true)
      }}
      style={{
        width: size, height: size, borderRadius: '50%', background: colorFor(groupKey), color: '#1B1726', display: 'flex',
        alignItems: 'center', justifyContent: 'center', overflow: 'hidden', flexShrink: 0,
      }}
    >
      {picture ? <img src={picture} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} /> : <Icon name="group" filled size={size * 0.55} />}
      {enlarged && picture && <PictureViewer url={picture} name={conversation?.name ?? ''} onClose={() => setEnlarged(false)} />}
    </div>
  )
}

/** A profile or group picture, large, over everything else. Tap anywhere to close it. */
export function PictureViewer({ url, name, onClose }: { url: string; name: string; onClose: () => void }) {
  return createPortal(
    <div className="scrim center" onClick={onClose} style={{ flexDirection: 'column' }}>
      <img src={url} alt="" style={{ width: 'min(90vw, 480px)', aspectRatio: 1, objectFit: 'cover', borderRadius: 16 }} />
      {name.trim() && <div className="title-large" style={{ color: '#fff', marginTop: 12 }}>{name}</div>}
    </div>,
    document.body,
  )
}
