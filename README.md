# Jukebox

A self-hosted music server built for film music: songs belong to films, and films have composers, singers,
lyricists and actors. It streams your library to the Jukebox Android app and web app, makes mixes and
stations, and (optionally) lets friends chat, share songs and listen together.

> Status: early. Jukebox grew out of the Isaipetti companion server and replaces Navidrome step by step
> (see [ROADMAP.md](ROADMAP.md)). It has its own library and accounts; the move from Navidrome is under way.

## What's here

| Folder | What |
|---|---|
| [`server/`](server/) | The Jukebox server (Kotlin + Ktor + SQLite), one Docker image; it also serves the web app |
| [`web/`](web/) | The web app (React + TypeScript), at the server's own address |
| [`android/`](android/) | The Android app (Kotlin + Jetpack Compose); APKs are on [Releases](../../releases) |
| [`analyzer/`](analyzer/) | The audio analyzer (optional): how each song sounds, for mood mixes and radio |
| [`docs/DESIGN.md`](docs/DESIGN.md) | How it's designed and why |

Coming next: `deploy/` with Docker Compose files for a standalone music server and for music + social. The
recommendation engine will live in its own repository.

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).
