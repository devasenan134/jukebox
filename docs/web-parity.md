# The website and the Android app

The website (`web/`) is meant to do what the Android app does, in the browser. This is where it stands
(checked screen by screen against `android/` on 2026-10-04). Update it when either side changes.

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
  edit, delete, typing, read marks, earlier messages
- **Listening together (jam)**: start, join, leave; listeners follow the host; song requests

## Only in the app (still to do on the website)

Most useful first.

| Area | Missing on the website |
|---|---|
| Player | The **queue**: see what's next, reorder, take out. Save, share and radio buttons in the full player (they work from song menus) |
| Search | **Not in the library** results (the iTunes catalog) with **Request**; the **music requests** page (yours, and everyone's for admins); search history |
| Chat | Recording voice notes; GIFs, stickers and camera; forward; pins; group settings (rename, members, photo, leave, delete for everyone); emoji picker and quick reactions; search in a chat; @mentions; deleting a chat |
| Pages | All singers; all public playlists; listening stats (admins) |
| Smaller | Setting a playlist's picture; the feedback form (bug or feature) |

## Only in the app, on purpose

- **Lock-screen lyrics, app updates, Android notifications**: phone features. Notifications could come to the
  website later as browser push (the server already sends its Firebase settings).
- **Light theme**: the website is dark only, by design. The app will get the same dark look later.
