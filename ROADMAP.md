# Jukebox roadmap

Milestones in the order they're built. Each one leaves the app working.

| # | Milestone | What it brings | Status |
|---|---|---|---|
| 0 | Bootstrap | This repository: the Isaipetti companion server renamed to Jukebox, the design | Done |
| 1 | Catalog and library ([plan](docs/milestone-1.md)) | Scanner (folders, tags, cover art), the catalog (albums, releases, tracks, recordings, versions, people and roles), stable recording ids, streaming with range requests, the Subsonic API subset the app uses. Runs beside Navidrome | Done (fingerprint merges running) |
| 2 | Own the listening data ([plan](docs/milestone-2.md)) | Users and sign-in without Navidrome, likes, playlists, plays, lyrics, the event log; a one-time import from Navidrome | Done (switched over 2026-10-04) |
| 3 | Realtime gateway | One WebSocket per device: presence, playback state across devices, jam, notifications; the social module behind a setting | |
| 4 | Jukebox API v2 | Albums with film details, people, lyrics search in the API; the app and web app move to it; Navidrome is switched off | |
| 5 | Data plane | Mobile-quality copies made ahead of time, prefetch, offline downloads | |
| 6 | Packaging | Multi-arch images on GHCR, `deploy/` compose files (standalone, music + social, + engine), self-hosting guide, APK releases (the Android app and web app are in this repo already) | |
| – | Engine | The recommendation engine in its own repository, following the event stream | Later |

How it all works today: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Next

### Cleanup after the switch-over (October 2026)

- [ ] When everyone runs app 0.12.1 or later (it moves saved addresses to jukebox.craftingtable.cc), delete the
      tunnel routes for the old addresses, `musicnote` and `gamertags`.
- [ ] After a few weeks without problems, remove the stopped Navidrome, isaipetti-social and Isaipetti analyzer
      containers and their data (backups of the final import stay in `music-switch-backups`).
- [ ] Stop the `isaipetti-engine` container (the paused engine prototype); it reads the old servers' frozen
      databases and nothing uses it.
- [ ] Archive the isaipetti repository, with a note that the app and server moved here.
- [ ] Send in-app feedback to this repository's issues instead of isaipetti's.
- [ ] Drop the `NAVIDROME_DATA` mount: mixes read the analyzer's features by recording id now.

### Then, in this order

1. **The website catches up with the app** ([what's missing](docs/web-parity.md)): the queue first, then music
   requests with "Not in the library" search, then the chat group tools.
2. **Milestone 3, the realtime gateway**: one live connection per device for presence, playback across your
   devices ("playing on your phone"), jams and notifications.
3. **Milestone 4, Jukebox's own API**: films with details, people, lyrics search. The app's dark redesign (to
   match the website) and its rename to Jukebox fit here. The app id stays `io.github.devasenan134.isaipetti`,
   so installed apps keep updating in place.
4. **Milestone 5, the data plane**: lighter mobile-quality copies made ahead of time, prefetching, offline
   downloads.
5. **Milestone 6, packaging**: published images, ready-made `deploy/` files and a self-hosting guide, so others
   can run Jukebox.

### The recommendation engine

Built separately, in its own repository. It reads the event log and the recorded suggestions (which songs a
mix offered, and whether they were played, skipped or never reached), learns from them, and replaces the
hand-tuned taste weights in the mix maker step by step: moods from lyrics, audio and what's played together,
and learning from skips. Inference stays free: on the Mac mini, or a free API tier.
