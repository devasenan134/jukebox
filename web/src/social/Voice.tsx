import { useEffect, useRef, useState } from 'react'
import type { ChatMessage } from '../api/socialTypes'
import { social } from '../api/social'
import * as player from '../player/player'
import { usePlayer } from '../player/player'
import { useFriendsServerPicture } from './avatars'
import { useListen } from './listen'
import { formatDuration } from '../ui/components'
import { Icon, IconButton, toast } from '../ui/kit'

// Voice messages (ui/social/ChatVoice.kt): record with the microphone, up to 5 minutes, and play them with a
// seek bar. The server keeps MP4 (AAC) recordings, which Chrome, Edge and Safari can make; Firefox can't.

const MAX_MS = 5 * 60_000

/** The MP4 type this browser can record, or undefined. */
const RECORD_TYPE = typeof MediaRecorder === 'undefined' ? undefined
  : ['audio/mp4;codecs=mp4a.40.2', 'audio/mp4'].find((t) => MediaRecorder.isTypeSupported(t))

export const canRecordVoice = () => RECORD_TYPE != null && !!navigator.mediaDevices?.getUserMedia

/**
 * Recording a voice message: start() asks for the microphone; stop(true) sends it (as a reply when replyTo is set),
 * stop(false) throws it away. It stops and sends by itself at 5 minutes.
 */
export function useVoiceRecorder(conversationId: number, onSent: (m: ChatMessage) => void) {
  const [recording, setRecording] = useState(false)
  const [elapsed, setElapsed] = useState(0)
  const [sending, setSending] = useState(false)
  const rec = useRef<{ recorder: MediaRecorder; stream: MediaStream; chunks: Blob[]; startedAt: number; replyTo?: number } | null>(null)

  const finish = (send: boolean) => {
    const r = rec.current
    if (!r) return
    rec.current = null
    setRecording(false)
    r.recorder.onstop = async () => {
      r.stream.getTracks().forEach((t) => t.stop())
      if (!send) return
      const durationMs = Math.min(MAX_MS, Date.now() - r.startedAt)
      if (durationMs < 500) return toast('Hold on a little longer: that was too short to send')
      const blob = new Blob(r.chunks, { type: 'audio/mp4' })
      setSending(true)
      try {
        onSent(await social.sendVoice(conversationId, blob, 'audio/mp4', durationMs, r.replyTo))
      } catch (e) {
        toast((e as Error).message || "Couldn't send the voice message")
      }
      setSending(false)
    }
    r.recorder.stop()
  }

  // The clock, and the 5-minute limit.
  useEffect(() => {
    if (!recording) return
    const t = setInterval(() => {
      const ms = Date.now() - (rec.current?.startedAt ?? Date.now())
      setElapsed(ms)
      if (ms >= MAX_MS) finish(true)
    }, 200)
    return () => clearInterval(t)
  }) // eslint-disable-line react-hooks/exhaustive-deps

  // Leaving the chat while recording throws the recording away.
  useEffect(() => () => {
    const r = rec.current
    if (r) {
      r.recorder.onstop = null
      r.recorder.stop()
      r.stream.getTracks().forEach((t) => t.stop())
    }
  }, [])

  const start = async (replyTo?: number) => {
    if (!canRecordVoice()) {
      return toast("This browser can't record voice messages the way Jukebox keeps them. Chrome, Edge and Safari can.", true)
    }
    let stream: MediaStream
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    } catch {
      return toast('Allow the microphone for this site to record a voice message', true)
    }
    const recorder = new MediaRecorder(stream, { mimeType: RECORD_TYPE, audioBitsPerSecond: 64_000 })
    const chunks: Blob[] = []
    recorder.ondataavailable = (e) => e.data.size && chunks.push(e.data)
    recorder.start(1000)
    rec.current = { recorder, stream, chunks, startedAt: Date.now(), replyTo }
    setElapsed(0)
    setRecording(true)
  }

  return { recording, elapsed, sending, start, send: () => finish(true), cancel: () => finish(false) }
}

/** Instead of the message box while recording: the time, the bin and send. */
export function RecordingBar({ elapsed, onCancel, onSend }: { elapsed: number; onCancel: () => void; onSend: () => void }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, flex: 1, minHeight: 40 }}>
      <IconButton icon="delete" label="Throw away the recording" onClick={onCancel} />
      <span className="rec-dot" />
      <span className="body-large" style={{ flex: 1, fontVariantNumeric: 'tabular-nums' }}>
        {formatDuration(elapsed / 1000)} <span className="muted body-small">/ 5:00</span>
      </span>
      <button className="fab-play" style={{ width: 40, height: 40, boxShadow: 'none' }} aria-label="Send the voice message" onClick={onSend}>
        <Icon name="send" filled size={20} />
      </button>
    </div>
  )
}

/**
 * A voice message in the chat: play / pause and a seek bar. Your music pauses while it plays and carries on
 * after, except in a jam, where the music keeps playing for everyone.
 */
export function VoicePlayer({ m }: { m: ChatMessage }) {
  const url = useFriendsServerPicture(social.voicePath(m))
  const audio = useRef<HTMLAudioElement>(null)
  const [playing, setPlaying] = useState(false)
  const [at, setAt] = useState(0)
  const pausedMusic = useRef(false)
  const total = (m.voiceMs ?? 0) / 1000

  const resumeMusic = () => {
    if (pausedMusic.current) {
      pausedMusic.current = false
      player.togglePlay()
    }
  }
  const toggle = () => {
    const a = audio.current
    if (!a) return
    if (!a.paused) return a.pause()
    if (usePlayer.getState().isPlaying && useListen.getState().joined == null) {
      player.pause()
      pausedMusic.current = true
    }
    if (a.ended) a.currentTime = 0
    void a.play().catch(() => toast("Couldn't play it"))
  }
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 240 }}>
      <button className="fab-play" style={{ width: 36, height: 36, boxShadow: 'none', flexShrink: 0 }} disabled={!url} onClick={toggle} aria-label={playing ? 'Pause' : 'Play the voice message'}>
        <Icon name={playing ? 'pause' : 'play_arrow'} filled size={22} />
      </button>
      <input
        type="range" className="slider" min={0} max={Math.max(1, total)} step={0.1} value={at} aria-label="Seek in the voice message"
        style={{ flex: 1, ['--fill' as string]: `${total ? (at / total) * 100 : 0}%` }}
        onChange={(e) => {
          const t = Number(e.target.value)
          setAt(t)
          if (audio.current) audio.current.currentTime = t
        }}
      />
      <span className="body-small muted" style={{ fontVariantNumeric: 'tabular-nums', minWidth: 34 }}>{formatDuration(playing || at > 0 ? at : total)}</span>
      {url && (
        <audio
          ref={audio} src={url} preload="metadata"
          onPlay={() => setPlaying(true)}
          onPause={() => { setPlaying(false); resumeMusic() }}
          onEnded={() => { setPlaying(false); setAt(0) }}
          onTimeUpdate={(e) => setAt(e.currentTarget.currentTime)}
        />
      )}
    </div>
  )
}
