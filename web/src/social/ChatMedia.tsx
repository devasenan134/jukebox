import { useEffect, useState } from 'react'
import type { ChatMessage } from '../api/socialTypes'
import type { SongRef } from '../api/types'
import { songToRef } from '../api/types'
import { social } from '../api/social'
import { currentItem, usePlayer } from '../player/player'
import { useRecentSongs } from '../state/history'
import { ClipOptions } from './ClipPicker'
import { Cover } from '../ui/components'
import { Dialog, Sheet, toast } from '../ui/kit'

// Pictures and music sent from a chat (ui/social/ChatMedia.kt and ShareMusicSheet.kt): a picture is shown before
// it's sent, with a caption; GIFs go as they are so they keep moving; photos are shrunk to send fast.

/** A picture made at most max pixels on its long side, as JPEG. */
async function shrink(bitmap: ImageBitmap, max: number): Promise<{ blob: Blob; width: number; height: number }> {
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

/** Sends a picture: a GIF (or a small animated WebP sticker) as it is, anything else as a shrunk JPEG. */
export async function sendPicture(conversationId: number, file: File, caption: string, replyTo?: number): Promise<ChatMessage> {
  const bitmap = await createImageBitmap(file).catch(() => {
    throw new Error("Couldn't read that picture")
  })
  if (file.type === 'image/gif' && file.size <= 5 * 1024 * 1024) {
    return social.sendImage(conversationId, file, 'image/gif', 'gif', bitmap.width, bitmap.height, caption, replyTo)
  }
  const { blob, width, height } = await shrink(bitmap, 1600)
  return social.sendImage(conversationId, blob, 'image/jpeg', 'photo', width, height, caption, replyTo)
}

/** The picture you're about to send, with a caption box. */
export function PicturePreview({ file, onSend, onClose }: { file: File; onSend: (caption: string) => void; onClose: () => void }) {
  const [url, setUrl] = useState<string>()
  const [caption, setCaption] = useState('')
  useEffect(() => {
    const u = URL.createObjectURL(file)
    setUrl(u)
    return () => URL.revokeObjectURL(u)
  }, [file])
  return (
    <Dialog
      title={file.type === 'image/gif' ? 'Send this GIF?' : 'Send this picture?'}
      onClose={onClose}
      actions={<><button className="btn text" onClick={onClose}>Cancel</button><button className="btn" onClick={() => onSend(caption.trim())}>Send</button></>}
    >
      {url && <img src={url} alt="" style={{ display: 'block', maxWidth: '100%', maxHeight: '50vh', borderRadius: 8, margin: '0 auto 12px' }} />}
      <div className="field">
        <input
          autoFocus value={caption} maxLength={1000} placeholder="Add a caption" aria-label="Caption"
          onChange={(e) => setCaption(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && onSend(caption.trim())}
        />
      </div>
    </Dialog>
  )
}

/**
 * The music button by the message box: what's playing and your recent songs. Pick one and send it whole, or turn on
 * "Share only a part" to choose a start and end first.
 */
export function ShareMusicSheet({ conversationId, replyTo, onSent, onClose }: {
  conversationId: number
  replyTo?: number
  onSent: (m: ChatMessage) => void
  onClose: () => void
}) {
  const playing = usePlayer((s) => currentItem(s)?.song)
  const recent = useRecentSongs((s) => s.songs)
  const songs: SongRef[] = [...(playing ? [songToRef(playing)] : []), ...recent.filter((r) => r.id !== playing?.id)]
  const [picked, setPicked] = useState<SongRef | null>(null)
  const [shared, setShared] = useState<SongRef | null>(null)
  const send = async () => {
    if (!shared) return
    try {
      onSent(await social.sendMessage(conversationId, '', shared, replyTo ?? null))
      onClose()
    } catch (e) {
      toast((e as Error).message || "Couldn't send it")
    }
  }
  return (
    <Sheet onClose={onClose}>
      <div className="title-medium" style={{ padding: '0 16px 8px', fontWeight: 700 }}>Share music</div>
      {picked ? (
        <div style={{ padding: '0 16px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 8 }}>
            <Cover coverArt={picked.coverArt} size={150} corner={4} style={{ width: 48, height: 48 }} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="body-large ellipsis">{picked.title}</div>
              <div className="body-small muted ellipsis">{picked.artist}</div>
            </div>
          </div>
          <ClipOptions song={picked} onChange={setShared} />
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 12 }}>
            <button className="btn text" onClick={() => setPicked(null)}>Back</button>
            <button className="btn" onClick={() => void send()}>Send</button>
          </div>
        </div>
      ) : songs.length === 0 ? (
        <div className="body-medium muted" style={{ padding: '8px 16px 16px' }}>Play something first: what's playing and your recent songs show up here.</div>
      ) : (
        songs.map((s, i) => (
          <div key={s.id} className="list-row" style={{ padding: '6px 16px' }} onClick={() => { setPicked(s); setShared(s) }}>
            <Cover coverArt={s.coverArt} size={150} corner={4} style={{ width: 44, height: 44 }} />
            <div className="text">
              <div className="body-large ellipsis">{s.title}</div>
              <div className="body-small muted ellipsis">{i === 0 && playing ? 'Playing now' : s.artist}</div>
            </div>
          </div>
        ))
      )}
    </Sheet>
  )
}
