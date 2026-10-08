# Jukebox

A self-hosted music server built for film music: songs belong to films, and films have composers, singers,
lyricists and actors. It streams your library to the Jukebox Android app and web app, makes mixes and
stations, and (optionally) lets friends chat, share songs and listen together.

> Status: early, and in daily use. Jukebox grew out of the Isaipetti companion server and replaced Navidrome
> on 2026-10-04; it has its own library, accounts and listening data (see [ROADMAP.md](ROADMAP.md)). To run
> one, see [server/README.md](server/README.md).

## What's here

| Folder | What |
|---|---|
| [`deploy/`](deploy/) | Ready-to-run Docker Compose setups (Standalone, Social, Full Stack) |
| [`server/`](server/) | The Jukebox server (Kotlin + Ktor + SQLite), one Docker image; it also serves the web app |
| [`web/`](web/) | The web app (React + TypeScript), at the server's own address |
| [`android/`](android/) | The Android app (Kotlin + Jetpack Compose); APKs are on [Releases](../../releases) |
| [`analyzer/`](analyzer/) | The audio analyzer (optional): how each song sounds, for mood mixes and radio |
| [`docs/SELF_HOSTING.md`](docs/SELF_HOSTING.md) | Complete guide to running and maintaining Jukebox |
| [`docs/DESIGN.md`](docs/DESIGN.md) | How it's designed and why |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | How it works today: the catalog, the analyzer, how mixes are made |
| [`docs/ANALYZER_MIXES_AND_STORAGE.md`](docs/ANALYZER_MIXES_AND_STORAGE.md) | Deep dive into the analyzer, mix generation, playlists, and auxiliary files |
| [`docs/web-parity.md`](docs/web-parity.md) | What the website does compared with the app |
| [`ROADMAP.md`](ROADMAP.md) | Milestones, and what comes next |

## Self-Hosting

To run your own Jukebox instance, check the [`docs/SELF_HOSTING.md`](docs/SELF_HOSTING.md) guide or pick a deployment from [`deploy/`](deploy/). Pre-built multi-architecture container images (`linux/amd64`, `linux/arm64`) are published to GHCR.

The recommendation engine will live in its own repository, learning from the event stream.

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).
