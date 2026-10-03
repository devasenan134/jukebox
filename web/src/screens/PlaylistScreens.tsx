import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import type { Playlist, Song } from '../api/types'
import { mixSongToSong, mixSource, MIX_AUTHOR } from '../api/types'
import { social } from '../api/social'
import { subsonic } from '../api/subsonic'
import * as player from '../player/player'
import { activity, useRecentPlaylists } from '../state/history'
import { useMixes, useMyPlaylists } from '../state/library'
import { likes, useLikes } from '../state/likes'
import { useSession } from '../state/session'
import { Cover, formatTotalDuration, LikeButton, PlayShuffleRow, ScreenHeader, songCount, SongRow, useLoad } from '../ui/components'
import { Dialog, ErrorBox, IconButton, Loading, MoreMenu, NameDialog, toast } from '../ui/kit'
import { LikedTile, madeForName, MixCover, updatedText } from '../ui/mixes'
import { useNav } from '../ui/nav'

// Liked songs, a playlist and a mix: a picture, a name, Play and Shuffle, then the songs
// (ui/library/LibraryScreen.kt, DetailScreens.kt and PlaylistEditing.kt; ui/mixes/MixScreen.kt).

/** The big picture and the lines under it, centred, at the top of a page. */
function Header({ art, title, lines }: { art: React.ReactNode; title: string; lines: (string | null | undefined)[] }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', padding: '0 24px', textAlign: 'center' }}>
      {art}
      <div className="headline-small" style={{ marginTop: 16, fontWeight: 700 }} data-testid="page-title">{title}</div>
      {lines.filter(Boolean).map((l, i) => (
        <div key={i} className={i === 0 ? 'body-medium muted' : 'body-small muted'} style={{ marginTop: 2 }}>{l}</div>
      ))}
    </div>
  )
}

const BIG_ART = 'min(240px, 64vw)'
const totals = (songs: { duration?: number }[]) => `${songCount(songs.length)} · ${formatTotalDuration(songs.reduce((t, s) => t + (s.duration ?? 0), 0))}`

/** All your liked songs, newest likes first. */
export function LikedSongsScreen() {
  const nav = useNav()
  const songs = useLikes((s) => s.songs)
  useEffect(() => likes().refresh(), [])
  const play = (index: number, shuffle = false) => {
    activity.liked()
    player.play(songs, index, shuffle, 'liked')
  }
  return (
    <div className="page">
      <ScreenHeader title="" onBack={nav.back} />
      <Header art={<div style={{ width: BIG_ART }}><LikedTile size={240} fill /></div>} title="Liked songs" lines={[totals(songs)]} />
      {songs.length > 0 && <PlayShuffleRow onPlay={() => play(0)} onShuffle={() => play(0, true)} />}
      {songs.length === 0 && <div className="muted" style={{ padding: 16 }}>Songs you like show up here. Tap ♡ in the player, or Like in a song's menu.</div>}
      {songs.map((s, i) => (
        <SongRow key={s.id} song={s} showCover inLikedSongs onOpenAlbum={nav.openAlbum} onClick={() => play(i)} />
      ))}
    </div>
  )
}

/** A playlist: yours to play, rename, reorder and trim, or someone else's to play and like. */
export function PlaylistScreen() {
  const { id = '' } = useParams()
  const nav = useNav()
  const username = useSession((s) => s.credentials?.username)
  const data = useLoad(() => subsonic.playlist(id), [id])
  const liked = useLikes((s) => s.playlists.some((p) => p.id === id))
  const [renaming, setRenaming] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [editing, setEditing] = useState<Song[] | null>(null)

  if (data.loading && !data.data) return <Loading />
  if (data.error) return <ErrorBox message={data.error} onRetry={data.retry} />
  const playlist = data.data!
  const songs = playlist.entry ?? []
  const mine = playlist.owner === username

  const play = (index: number, shuffle = false) => {
    activity.playlist(playlist)
    useRecentPlaylists.getState().played(playlist)
    player.play(songs, index, shuffle, `playlist:${playlist.id}`)
  }
  /** Runs a change, then shows the playlist as the server now has it. */
  const change = async (what: () => Promise<unknown>, done?: string) => {
    try {
      await what()
      data.set(await subsonic.playlist(id))
      useMyPlaylists.getState().refresh()
      if (done) toast(done)
    } catch (e) {
      toast((e as Error).message || "Couldn't change the playlist")
    }
  }
  const remove = (index: number) => change(() => subsonic.updatePlaylist(id, { removeIndexes: [index] }), 'Removed from this playlist')
  const saveOrder = (list: Song[]) => {
    setEditing(null)
    void change(() => subsonic.replacePlaylist(id, list.map((s) => s.id)), 'Saved')
  }

  return (
    <div className="page">
      <ScreenHeader
        title=""
        onBack={nav.back}
        actions={
          mine && !editing ? (
            <MoreMenu
              items={[
                { label: 'Edit songs', onClick: () => setEditing(songs), hidden: songs.length === 0 },
                { label: 'Rename', onClick: () => setRenaming(true) },
                {
                  label: playlist.public ? 'Make private' : 'Make public',
                  onClick: () => void change(() => subsonic.updatePlaylist(id, { public: !playlist.public }), playlist.public ? 'Only you can see it now' : 'Your friends can find it now'),
                },
                { label: 'Delete playlist', onClick: () => setDeleting(true) },
              ]}
            />
          ) : undefined
        }
      />
      <Header
        art={<Cover coverArt={playlist.coverArt} size={600} corner={12} style={{ width: BIG_ART, aspectRatio: '1', boxShadow: '0 12px 32px rgba(0,0,0,.25)' }} />}
        title={playlist.name}
        lines={[
          ['Playlist', playlist.owner && `by ${playlist.owner}`, mine ? (playlist.public ? 'Public' : 'Private') : null].filter(Boolean).join(' · '),
          playlist.comment,
          totals(songs),
        ]}
      />
      {editing ? (
        <EditSongs songs={editing} onChange={setEditing} onCancel={() => setEditing(null)} onSave={saveOrder} />
      ) : (
        <>
          {songs.length > 0 ? (
            <PlayShuffleRow onPlay={() => play(0)} onShuffle={() => play(0, true)}>
              {!mine && <LikeButton liked={liked} onToggle={() => likes().togglePlaylist(playlist)} />}
            </PlayShuffleRow>
          ) : (
            <div className="muted" style={{ padding: 16, textAlign: 'center' }}>
              {mine ? 'Nothing here yet. Add songs with "Add to playlist" in any song\'s menu.' : 'This playlist is empty.'}
            </div>
          )}
          {songs.map((s, i) => (
            <SongRow
              key={`${s.id}-${i}`}
              song={s}
              showCover
              onOpenAlbum={nav.openAlbum}
              onClick={() => play(i)}
              onRemoveFromPlaylist={mine ? () => void remove(i) : undefined}
              inOwnPlaylist={mine ? playlist.name : undefined}
            />
          ))}
        </>
      )}
      {renaming && (
        <NameDialog
          title="Rename playlist"
          confirm="Save"
          initial={playlist.name}
          onClose={() => setRenaming(false)}
          onConfirm={(name) => {
            setRenaming(false)
            void change(() => subsonic.updatePlaylist(id, { name }))
          }}
        />
      )}
      {deleting && <DeleteDialog playlist={playlist} onClose={() => setDeleting(false)} onDeleted={() => nav.back()} />}
    </div>
  )
}

/** Move songs up and down or take them out, then save the new order in one go. */
function EditSongs({ songs, onChange, onCancel, onSave }: { songs: Song[]; onChange: (s: Song[]) => void; onCancel: () => void; onSave: (s: Song[]) => void }) {
  const move = (from: number, to: number) => {
    if (to < 0 || to >= songs.length) return
    const next = [...songs]
    const [s] = next.splice(from, 1)
    next.splice(to, 0, s)
    onChange(next)
  }
  return (
    <>
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, padding: '12px 16px' }}>
        <button className="btn text" onClick={onCancel}>Cancel</button>
        <button className="btn" onClick={() => onSave(songs)}>Save</button>
      </div>
      {songs.map((s, i) => (
        <div key={`${s.id}-${i}`} className="list-row" style={{ cursor: 'default' }} data-testid="edit-row">
          <Cover coverArt={s.coverArt} size={150} corner={4} style={{ width: 44, height: 44, flexShrink: 0 }} />
          <div style={{ minWidth: 0, flex: 1 }}>
            <div className="title-small ellipsis">{s.title}</div>
            <div className="body-small muted ellipsis">{s.artist}</div>
          </div>
          <IconButton icon="arrow_upward" label="Move up" disabled={i === 0} onClick={() => move(i, i - 1)} />
          <IconButton icon="arrow_downward" label="Move down" disabled={i === songs.length - 1} onClick={() => move(i, i + 1)} />
          <IconButton icon="remove_circle_outline" label="Take out" onClick={() => onChange(songs.filter((_, j) => j !== i))} />
        </div>
      ))}
    </>
  )
}

function DeleteDialog({ playlist, onClose, onDeleted }: { playlist: Playlist; onClose: () => void; onDeleted: () => void }) {
  const [busy, setBusy] = useState(false)
  const remove = async () => {
    setBusy(true)
    try {
      await subsonic.deletePlaylist(playlist.id)
      useRecentPlaylists.getState().forget(playlist.id)
      useMyPlaylists.getState().refresh()
      toast('Playlist deleted')
      onDeleted()
    } catch (e) {
      toast((e as Error).message || "Couldn't delete it")
      setBusy(false)
    }
  }
  return (
    <Dialog
      title="Delete playlist?"
      onClose={onClose}
      actions={
        <>
          <button className="btn text" onClick={onClose}>Cancel</button>
          <button className="btn" disabled={busy} onClick={remove}>Delete</button>
        </>
      }
    >
      “{playlist.name}” goes for good, for everyone who saved it.
    </Dialog>
  )
}

/** A mix by Jukebox: its cover, what it is, Save to Your Library, and its songs. */
export function MixScreen() {
  const { id = '' } = useParams()
  const nav = useNav()
  const data = useLoad(() => social.mix(id), [id])
  const followed = useMixes((s) => s.followed.some((m) => m.id === id))
  if (data.loading && !data.data) return <Loading />
  if (data.error) return <ErrorBox message={data.error} onRetry={data.retry} />
  const mix = data.data!
  const songs = mix.songs.map(mixSongToSong)
  const madeFor = madeForName()
  const play = (index: number, shuffle = false) => {
    activity.mix(mix)
    player.play(songs, index, shuffle, mixSource(mix))
  }
  const toggle = () =>
    useMixes.getState().toggleFollow(mix)
      .then(() => toast(followed ? 'Removed from Your Library' : 'Saved to Your Library'))
      .catch(() => toast("Couldn't save it"))
  return (
    <div className="page">
      <ScreenHeader title="" onBack={nav.back} />
      <Header
        art={<div style={{ width: BIG_ART }}><MixCover mix={mix} size={240} fill /></div>}
        title={mix.title}
        lines={[
          mix.description,
          [mix.personal && madeFor ? `Made for ${madeFor}` : null, `by ${MIX_AUTHOR}`, mix.endless ? 'Station' : songCount(songs.length), updatedText(mix.updatedAt)].filter(Boolean).join(' · '),
        ]}
      />
      <PlayShuffleRow onPlay={() => play(0)} onShuffle={mix.endless ? undefined : () => play(0, true)}>
        <LikeButton liked={followed} onToggle={() => void toggle()} />
      </PlayShuffleRow>
      {songs.map((s, i) => (
        <SongRow key={`${s.id}-${i}`} song={s} showCover onOpenAlbum={nav.openAlbum} onClick={() => play(i)} />
      ))}
    </div>
  )
}
