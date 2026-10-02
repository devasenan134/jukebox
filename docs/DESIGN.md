# Jukebox design

How Jukebox is put together and why. It borrows the ideas that make large streaming services work, at the
size of one home server and a group of friends: one small machine (the reference box is a Mac mini M1 with
8 GB), tens of thousands of songs, around ten listeners.

## Goals

- **A music server for film music.** Films, songs and people (composers, singers, lyricists, actors) are
  part of the catalog, not tags bolted on.
- **Self-hostable in one container.** A standalone music server, or music plus social, from the same image.
- **Stable identity.** Renaming folders, re-downloading in better quality or the same song appearing on three
  albums never loses a like, a play or a playlist entry.
- **Works without the engine.** Smart mixes come from the separate recommendation engine when it's running;
  without it, Jukebox still makes simple mixes.
- **Replaces Navidrome step by step**, with the app working at every step.

Not goals: many machines, millions of users, DRM, a CDN.

## The big picture

```
                 ┌───────────────────────── Jukebox server (one image) ─────────────────────────┐
 app / web ────▶ │ control plane (JSON over HTTPS)                                              │
                 │   core: auth · catalog (films, people, recordings, tracks) · library ·       │
                 │         playlists · likes · plays · lyrics · search · mixes                  │
                 │   module subsonic: the Subsonic API subset (third-party players, migration)  │
                 │   module social:   friends · chat · jam · push                               │
           ────▶ │ realtime gateway (one WebSocket per device)                                  │
                 │   presence · playback state across devices · jam · notifications             │
           ────▶ │ data plane: audio files (HTTP range requests), cover art                     │
                 │                                                                              │
                 │ event log (append-only table) ── stream ──▶ recommendation engine (optional) │
                 │ mixes / stations ◀──────────────── recommendations ─┘                        │
                 └──────────────────────────────────────────────────────────────────────────────┘
```

### One server with modules, not microservices

The social features need everything the music server has: users, sign-in, the live connection, songs and
plays. As separate services they would duplicate users and check sign-in across the network. So there is
one server, and modules (`social`, `subsonic`) are switched on in its settings. "Standalone" and "with
social" are the same image with a different setting: one container, one database, one thing to back up.

### Control plane and data plane

Small JSON requests (what's in this playlist, what to show on Home) are kept apart from audio bytes. Audio is
served as plain files with HTTP range requests, so the app can seek, prefetch the next song and download for
offline. Lower-quality copies for mobile data are made ahead of time by a background job, never while
someone is listening (the CPU is small).

### Realtime gateway

One WebSocket per device carries presence, playback state ("playing on the phone, controlled from the web"),
jam and notifications. It grows out of the existing listen-together hub.

## The catalog: recordings, not files

The core idea, borrowed from large catalogs: a **recording** (one performance of a song) is different from
a **track** (that recording appearing on a release). One recording often appears on the soundtrack, a
"From X" single, a re-release and a compilation.

```
film ── release (soundtrack, background score, single, re-release)
            └── track (disc, number) ──▶ recording ──▶ file(s) on disk (quality variants)
                                             │
                                             ├── version of ──▶ song (karaoke, remix, unplugged,
                                             │                          female/male version)
                                             └── people with roles (composer, singer, lyricist)
```

- Likes, plays, playlists and lyrics attach to the **recording**, so they survive renames, better-quality
  re-downloads and duplicates.
- A recording's id comes from what identifies it (JioSaavn id, ISRC, audio fingerprint), not from its path.
- Versions link to their song, so a karaoke track or a remix is "the same song, another version" instead of
  a duplicate or a stranger.
- Films, people and their roles are first-class, so "songs by this lyricist", "this actor's songs" and
  "this film's background score" are plain queries.

## The event log

Every action is written once to an append-only `events` table: played (how long), skipped, liked, added to a
playlist, a mix or station shown (with positions), searched. Each event has a sequence number.

- Inside the server, features read it (stats, "On Repeat", friends' activity).
- The recommendation engine follows it through a stream (server-sent events), and catches up from its last
  sequence number after a restart. Nothing reads another program's database file.
- The event format is versioned. It is the one contract between Jukebox and the engine.

## Recommendations

The engine (its own repository, Python) understands songs (sound, lyrics, listened-together), learns each
person's taste from the event stream, and answers requests from the server:

- `mix/{type}` for Home mixes, cached and refreshed when taste changes.
- `station/next`: the app asks for a few songs at a time and says what it skipped or finished, so a station
  adapts within the session without the engine keeping session state.
- `similar` for "more like this".

If the engine is missing or slow, the server falls back to its built-in mixes.

## Storage

SQLite (WAL) for everything; one file to back up. The library itself stays read-only to Jukebox, apart from
the files it is asked to write (lyrics sidecars, cover art cache, mobile-quality copies) in a separate data
folder.

## Moving off Navidrome

Jukebox replaces Navidrome in milestones (see [ROADMAP.md](../ROADMAP.md)), keeping the Subsonic API subset
the app uses, so the app works against Jukebox before it moves to Jukebox's own API. Users, likes, plays
and playlists are imported once from Navidrome; passwords can't be carried over, so everyone sets a new
one.
