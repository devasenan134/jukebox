# The website and the Android app

The website (`web/`) is meant to do what the Android app does, in the browser. This is where it stands
(checked screen by screen against `android/` on 2026-10-04, again on 2026-10-06). Update it when either side changes.

## On both

- **Home**: Made for you and the other mix rows, Recently played, Jump back in, album rows, Refresh
- **Search**: songs, albums, music directors, singers, lyricists and actors, forgiving spelling
- **Browsing**: Albums (sorted four ways), Music directors, album, composer, singer, lyricist and actor pages
- **Mixes**: every mix and station, save to Your Library
- **Your Library**: Liked songs, saved mixes, liked and own playlists, liked albums; list or grid
- **Playlists**: create, add to (from any song), rename, reorder, take out, public or private, delete, like
  someone else's
- **Player**: play, pause, skip, seek, shuffle, repeat, synced lyrics, like; the website also has volume
- **Song menu**: like, play next, add to queue, add to playlist, song radio, share with friends, go to the album
- **Settings**: profile picture and name, change password, invite codes, sign out other devices, log out
- **Friends**: friend requests, friends with what they're playing, add a friend, new group chat
- **Chat**: live messages, shared songs and clips that play, pictures, voice notes, replies, reactions,
  edit, delete, typing, earlier messages
- **Listening together (jam)**: start, join, leave; listeners follow the host; song requests

## Only in the app (still to do on the website)

Checked again against `android/CHANGELOG.md` on 2026-10-06. In the order they're being built; ticked when the
website has them.

**1. Player**
- [x] The **queue**: see what's next, reorder, take out; in a jam, the host edits it and the others see it
- [x] **Resume** a playlist, album or Liked songs where you left off (the store, `queueMemory`, is there unused)
- [x] **About this song** in the full player: artists, composer, lyricist, album, year, genre, track, length, quality
- [x] The **lyric being sung** under the cover; save, share and radio buttons in the full player

**2. Music requests**
- [x] **Not in the library** results in Search (the iTunes catalog) with **Request** / **Requested ✓**
- [x] **Your requests** (waiting, added, can't find) and, for admins, **Everyone's** with Added / Can't find
- [x] **Search history**: past searches to tap again, ✕ to remove, Clear (the store is there unused)

**3. Chat**
- [x] Group settings: members (in the jam, online, offline), add and remove people, rename, group photo, leave,
      delete for everyone; deleting a chat with someone who left
- [x] **Seen** / **Seen by …** under your newest message (the read marks already arrive)
- [x] **@mentions** in groups (picker while typing, highlighted, "@" in the chat list)
- [x] **Pins** (24 hours, 7 days, 30 days; the pinned bar)
- [x] **Forward** to up to 10 chats; **search in a chat**
- [ ] **Recording voice notes** (the server takes MP4/AAC: Chrome, Edge and Safari can record that, Firefox can't)
      and a voice player with a seek bar
- [ ] **Who reacted** (a tab per emoji), any emoji, your own quick reactions
- [ ] **GIFs** that stay animated, pasting a picture, saving a picture; **share music from the chat** (what's
      playing and recent songs, whole or a part); the jam queue in the chat

**4. Pages and smaller things**
- [ ] **Recommended songs** under your own playlists; **Save a copy as a playlist** for mixes
- [ ] A playlist's **picture**; **likes** on your playlists ("By you · 3 likes")
- [x] Framing a picture (zoom, move, rotate) for profiles and groups
- [ ] All singers; all public playlists
- [ ] **Listening stats** (admins); the **feedback** form (bug or feature)

**5. The Mac app** (`desktop/`, the website in a window)
- [ ] Microphone for voice notes (macOS asks once; the app has to say why)
- [ ] Saving pictures and files, and links that open a new tab (to the browser)
- [ ] Notifications for messages, friend requests and jams (the window's web view has no browser
      notifications, so the app shows them itself)

## Only in the app, on purpose

- **Lock-screen lyrics, app updates, Android notifications**: phone features. Notifications could come to the
  website later as browser push (the server already sends its Firebase settings).
- **Light theme**: the website is dark only, by design. The app will get the same dark look later.
