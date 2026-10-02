# Jukebox roadmap

Milestones in the order they're built. Each one leaves the app working.

| # | Milestone | What it brings | Status |
|---|---|---|---|
| 0 | Bootstrap | This repository: the Isaipetti companion server renamed to Jukebox, the design | Done |
| 1 | Catalog and library ([plan](docs/milestone-1.md)) | Scanner (folders, tags, cover art), the catalog (albums, releases, tracks, recordings, versions, people and roles), stable recording ids, streaming with range requests, the Subsonic API subset the app uses. Runs beside Navidrome | Next |
| 2 | Own the listening data | Users and sign-in without Navidrome, likes, playlists, plays, lyrics, the event log; a one-time import from Navidrome | |
| 3 | Realtime gateway | One WebSocket per device: presence, playback state across devices, jam, notifications; the social module behind a setting | |
| 4 | Jukebox API v2 | Albums with film details, people, lyrics search in the API; the app and web app move to it; Navidrome is switched off | |
| 5 | Data plane | Mobile-quality copies made ahead of time, prefetch, offline downloads | |
| 6 | Packaging | Multi-arch images on GHCR, `deploy/` compose files (standalone, music + social, + engine), self-hosting guide, the Android app and web app in this repo, APK releases | |
| – | Engine | The recommendation engine in its own repository, following the event stream | Later |

