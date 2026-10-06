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
import { Cover, formatTotalDuration, likeCount, LikeButton, PlayShuffleRow, songCount, SongRow, useLoad } from '../ui/components'
import { usePhotoPicker } from '../ui/PhotoPicker'
import { Collection, Meta, PageSkeleton, TrackHead } from '../ui/collection'
import { Dialog, ErrorBox, IconButton, MoreMenu, NameDialog, toast } from '../ui/kit'
import { LikedTile, madeForName, MixCover, mixTint, updatedText } from '../ui/mixes'
import { useNav } from '../ui/nav'
import { keys, playlistChanged } from '../state/queries'

// Liked songs, a playlist and a mix: a picture, a name, Play and Shuffle, then the songs
// (ui/library/LibraryScreen.kt, DetailScreens.kt and PlaylistEditing.kt; ui/mixes/MixScreen.kt).

const total = (songs: { duration?: number }[]) => formatTotalDuration(songs.reduce((t, s) => t + (s.duration ?? 0), 0))

/** All your liked songs, newest likes first. */
export function LikedSongsScreen() {
  const nav = useNav()
  const songs = useLikes((s) => s.songs)
  useEffect(() => likes().refresh(), [])
  const play = (index: number, shuffle = false) => {
    activity.liked()
    player.play(songs, index, shuffle, 'liked')
  }
  const name = useSession((s) => s.social?.user.displayName || s.credentials?.username)
  return (
    <Collection
      art={<div className="art"><LikedTile size={240} fill /></div>}
      tint="hsl(250 45% 38%)"
      kind="Playlist"
      title="Liked songs"
      meta={<Meta parts={[name && <b>{name}</b>, songCount(songs.length), songs.length > 0 && total(songs)]} />}
    >
      {songs.length > 0 && <PlayShuffleRow onPlay={() => play(0)} onShuffle={() => play(0, true)} resume={{ source: 'liked', songs, onResume: () => activity.liked() }} />}
      {songs.length === 0 && <div className="muted" style={{ padding: 24 }}>Songs you like show up here. Tap ♡ in the player, or Like in a song's menu.</div>}
      <div className="tracks">
        {songs.length > 0 && <TrackHead />}
        {songs.map((s, i) => (
          <SongRow key={s.id} song={s} index={i} showCover inLikedSongs onOpenAlbum={nav.openAlbum} onClick={() => play(i)} />
        ))}
      </div>
    </Collection>
  )
}

/** A playlist: yours to play, rename, reorder and trim, or someone else's to play and like. */
export function PlaylistScreen() {
  const { id = '' } = useParams()
  const nav = useNav()
  const username = useSession((s) => s.credentials?.username)
  const data = useLoad(keys.playlist(id), () => subsonic.playlist(id))
  const liked = useLikes((s) => s.playlists.some((p) => p.id === id))
  const [renaming, setRenaming] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [editing, setEditing] = useState<Song[] | null>(null)
  const mineNow = data.data != null && data.data.owner === username
  // How many friends liked a playlist you made (your own like doesn't count).
  const likeTotal = useLoad<Record<string, number>>(['playlist-likes', id, mineNow], () => (mineNow ? social.playlistLikeCounts([id]) : Promise.resolve({})), 30_000).data?.[id] ?? 0
  const cover = usePhotoPicker({
    title: 'Playlist cover',
    onPicked: async (jpeg) => {
      try {
        await social.setPlaylistCover(id, jpeg)
        data.set(await subsonic.playlist(id))
        playlistChanged()
        useMyPlaylists.getState().refresh()
        toast('Cover changed')
      } catch (e) {
        toast((e as Error).message || "Couldn't change the cover")
      }
    },
  })

  if (data.loading && !data.data) return <PageSkeleton />
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
      playlistChanged()
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

  const menu = mine && (
    <MoreMenu
      items={[
        { label: 'Edit songs', onClick: () => setEditing(songs), hidden: songs.length === 0 },
        { label: 'Rename', onClick: () => setRenaming(true) },
        { label: 'Change cover', onClick: () => cover.open() },
        {
          label: 'Use the automatic cover', hidden: !playlist.coverArt?.startsWith('pl-'),
          onClick: () => void change(() => social.removePlaylistCover(id), 'Back to the automatic cover'),
        },
        {
          label: playlist.public ? 'Make private' : 'Make public',
          onClick: () => void change(() => subsonic.updatePlaylist(id, { public: !playlist.public }), playlist.public ? 'Only you can see it now' : 'Your friends can find it now'),
        },
        { label: 'Delete playlist', onClick: () => setDeleting(true) },
      ]}
    />
  )
  return (
    <Collection
      coverArt={playlist.coverArt}
      kind={mine ? (playlist.public ? 'Public playlist' : 'Private playlist') : 'Playlist'}
      title={playlist.name}
      meta={
        <>
          {playlist.comment && <div style={{ width: '100%', marginBottom: 4 }}>{playlist.comment}</div>}
          <Meta parts={[mine ? <b>By you</b> : playlist.owner && <b>{playlist.owner}</b>, mine && likeCount(likeTotal), songCount(songs.length), songs.length > 0 && total(songs)]} />
        </>
      }
    >
      {editing ? (
        <EditSongs songs={editing} onChange={setEditing} onCancel={() => setEditing(null)} onSave={saveOrder} />
      ) : (
        <>
          {songs.length > 0 ? (
            <PlayShuffleRow
              onPlay={() => play(0)}
              onShuffle={() => play(0, true)}
              resume={{ source: `playlist:${playlist.id}`, songs, onResume: () => { activity.playlist(playlist); useRecentPlaylists.getState().played(playlist) } }}
            >
              {!mine && <LikeButton liked={liked} onToggle={() => likes().togglePlaylist(playlist)} big />}
              {menu}
            </PlayShuffleRow>
          ) : (
            <div className="hero-bar">
              {menu}
              <span className="muted">{mine ? 'Nothing here yet. Add songs with "Add to playlist" in any song\'s menu.' : 'This playlist is empty.'}</span>
            </div>
          )}
          <div className="tracks">
            {songs.length > 0 && <TrackHead />}
            {songs.map((s, i) => (
              <SongRow
                key={`${s.id}-${i}`}
                song={s}
                index={i}
                showCover
                onOpenAlbum={nav.openAlbum}
                onClick={() => play(i)}
                onRemoveFromPlaylist={mine ? () => void remove(i) : undefined}
                inOwnPlaylist={mine ? playlist.name : undefined}
              />
            ))}
          </div>
          {mine && <RecommendedSongs playlist={playlist} onAdded={() => void change(async () => {})} />}
        </>
      )}
      {cover.element}
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
    </Collection>
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
      playlistChanged(playlist.id)
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

/** Today's songs of a mix as a normal playlist of yours ("By Jukebox" in its description), then opens it. */
async function saveCopy(title: string, songIds: string[], nav: ReturnType<typeof useNav>) {
  try {
    const playlist = await subsonic.createPlaylist(title)
    const today = new Date().toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })
    await subsonic.updatePlaylist(playlist.id, { add: songIds, comment: `By ${MIX_AUTHOR}. ${title} as it was on ${today}.` })
    playlistChanged()
    useMyPlaylists.getState().refresh()
    toast('Saved as a playlist')
    nav.openPlaylist(playlist.id)
  } catch (e) {
    toast((e as Error).message || "Couldn't save it")
  }
}

/** Under a playlist you made: songs that would fit it, each with + to add it (Recommendations.kt). */
function RecommendedSongs({ playlist, onAdded }: { playlist: Playlist; onAdded: () => void }) {
  const ids = (playlist.entry ?? []).map((s) => s.id)
  const [page, setPage] = useState(0)
  const [added, setAdded] = useState<string[]>([])
  // Nothing shows when the server has no suggestions (or no mixes).
  const songs = useLoad(['recommend', playlist.id, ids.length, page], () => social.recommend(ids, 10, page)).data?.map(mixSongToSong)
  if (!songs) return null
  const add = (s: Song) =>
    subsonic.updatePlaylist(playlist.id, { add: [s.id] })
      .then(() => { setAdded((a) => [...a, s.id]); onAdded(); toast(`Added to ${playlist.name}`) })
      .catch((e) => toast((e as Error).message || "Couldn't add it"))
  return (
    <div style={{ marginTop: 16 }}>
      <div className="section-title-row" style={{ display: 'flex', alignItems: 'center', padding: '0 8px 0 16px' }}>
        <h2 className="section-title" style={{ flex: 1, margin: '12px 0 4px' }}>Recommended songs</h2>
        {ids.length > 0 && <button className="btn text" onClick={() => setPage((p) => (p + 1) % 10)}>Refresh</button>}
      </div>
      <div className="body-small muted" style={{ padding: '0 16px 8px' }}>
        {ids.length ? `By ${MIX_AUTHOR}, based on the songs in this playlist` : `Add a few songs, and ${MIX_AUTHOR} will suggest more like them.`}
      </div>
      <div className="tracks">
        {songs.filter((s) => !added.includes(s.id)).map((s) => (
          <div key={s.id} style={{ display: 'flex', alignItems: 'center' }}>
            <div style={{ flex: 1, minWidth: 0 }}>
              <SongRow song={s} showCover onClick={() => { activity.song(s); player.play([s]) }} note={[s.artist, s.album].filter(Boolean).join(' · ')} />
            </div>
            <IconButton icon="add_circle" label={`Add to ${playlist.name}`} onClick={() => void add(s)} />
          </div>
        ))}
      </div>
    </div>
  )
}

/** A mix by Jukebox: its cover, what it is, Save to Your Library, and its songs. */
export function MixScreen() {
  const { id = '' } = useParams()
  const nav = useNav()
  const data = useLoad(['mix', id], () => social.mix(id))
  const followed = useMixes((s) => s.followed.some((m) => m.id === id))
  if (data.loading && !data.data) return <PageSkeleton />
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
    <Collection
      art={<div className="art"><MixCover mix={mix} size={240} fill /></div>}
      tint={mixTint(mix)}
      kind={mix.endless ? 'Station' : 'Mix'}
      title={mix.title}
      meta={
        <>
          {mix.description && <div style={{ width: '100%', marginBottom: 4 }}>{mix.description}</div>}
          <Meta parts={[<b>{MIX_AUTHOR}</b>, mix.personal && madeFor && `Made for ${madeFor}`, !mix.endless && songCount(songs.length), updatedText(mix.updatedAt)]} />
        </>
      }
    >
      <PlayShuffleRow onPlay={() => play(0)} onShuffle={mix.endless ? undefined : () => play(0, true)}>
        <LikeButton liked={followed} onToggle={() => void toggle()} big />
        {songs.length > 0 && <MoreMenu items={[{ label: 'Save a copy as a playlist', onClick: () => void saveCopy(mix.title, songs.map((s) => s.id), nav) }]} />}
      </PlayShuffleRow>
      <div className="tracks">
        <TrackHead />
        {songs.map((s, i) => (
          <SongRow key={`${s.id}-${i}`} song={s} index={i} showCover onOpenAlbum={nav.openAlbum} onClick={() => play(i)} />
        ))}
      </div>
    </Collection>
  )
}
