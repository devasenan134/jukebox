# Milestone 2: own the listening data

Jukebox stops depending on Navidrome and the old Isaipetti server: it signs people in itself, keeps likes,
playlists and plays, writes every action to an event log, and makes the mixes from its own catalog. A
re-runnable import brings everything over from the live servers, so the switch-over is one last import.

## 1. Users and sign-in

- **Accounts live in Jukebox** (the `users` table the social module already has), with an `is_admin` flag.
  Sign-up with an invite code creates the Jukebox account directly; no Navidrome admin bot.
- **Passwords**: Subsonic players sign in with `t = md5(password + salt)`, so a Subsonic server has to be able
  to recover each password (Navidrome does the same). Jukebox keeps them **encrypted with its own key**
  (AES-GCM, a key file generated on first start in `data/`, readable only by Jukebox), never in plain text.
- **Changing a password**: a Jukebox endpoint, replacing Navidrome's `/api/user`.
- **Moving accounts over**: the live Navidrome protects its passwords with Navidrome's built-in default key
  (no `ND_PASSWORDENCRYPTIONKEY` is set), which is public. The import can therefore carry everyone's password
  over unchanged, re-encrypted with Jukebox's own key, so nobody has to reset anything.

## 2. Likes, playlists, plays

New tables (schema 21), all pointing at **recordings** (and albums / people for likes):

| Table | One row per |
|---|---|
| `likes` | a person liking a recording, an album or a person (`item_type`, `item_id`, `liked_at`) |
| `playlists`, `playlist_entries` | a playlist (owner, name, comment, public, changed) and its songs in order |
| `plays` (exists) | a song played: how long, skipped or not, from where (a mix, an album…) |

The Subsonic calls that answered "not yet" now work: `star`, `unstar`, `getStarred2`, `scrobble`, the
playlist calls, album lists by `recent`, `frequent` and `starred`, and `starred` / `playCount` on songs.

## 3. The event log

`events (seq, user_id, type, at, payload)`: played, skipped, liked, unliked, added to playlist, a mix or
station shown (with positions), searched. Each has a sequence number. `GET /events?after=<seq>` streams them
(server-sent events) to the recommendation engine later; nothing outside Jukebox reads its database.

## 4. Mixes from Jukebox's own catalog

The mix maker reads a `MusicSource`; today that's Navidrome's database. A second implementation reads
Jukebox's catalog, likes and plays, and the mixes, search, requests and stats switch to it. The analyzer's
sound features are keyed by Navidrome's song ids; until the engine takes over (its own repository), they're
mapped to recordings through the file path, which both share.

## 5. The import (re-runnable)

`jukebox import --navidrome <navidrome.db> --social <isaipetti-social.db>` reads both read-only and:

- creates the users (names, admin flags, passwords as above);
- copies the social data with its ids: friends, invites, chats, messages, pictures and voice notes, groups,
  pins, reactions, jam history, saved mixes, music requests;
- turns Navidrome song ids into recording ids everywhere they appear (plays, shared songs in chats, saved
  mixes) through the file path, and keeps the mapping so an old id still works;
- brings over likes, play counts and playlists from Navidrome.

Running it again replaces what the previous run brought, so it can be tried as often as needed beside the
live servers, then run a last time at the switch-over.

## 6. The switch-over

1. Stop the old server and Navidrome's writes for a few minutes, run the last import.
2. The Android app: Jukebox answers both of its addresses (the Subsonic API and the social API) on one
   server, including Navidrome's `/auth/login`, so the app's two addresses can both point at Jukebox. If that
   needs an app update after all, it ships first.
3. The web app is already on Jukebox.
4. Navidrome and `isaipetti-social` stay stopped but kept for a few weeks, then go.

## 7. The web app in this milestone

Your Library (liked songs, playlists, albums, people), like buttons, playlists (create, add, reorder,
remove), Recently played and Home mixes once the mix maker runs on Jukebox, built screen by screen to match
the Android app, with the smoke test (`scripts/smoke`) growing with each screen.

## Decisions (2026-10-03)

1. Passwords are carried over from Navidrome: nobody resets.
2. Subsonic token sign-in stays, so other Subsonic players work; passwords are kept encrypted with Jukebox's key.
