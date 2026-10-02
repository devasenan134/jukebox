# Jukebox

A self-hosted music server built for film music: songs belong to films, and films have composers, singers,
lyricists and actors. It streams your library to the Jukebox Android app and web app, makes mixes and
stations, and (optionally) lets friends chat, share songs and listen together.

> Status: early. Jukebox grows out of the Isaipetti companion server and replaces Navidrome step by step
> (see [ROADMAP.md](ROADMAP.md)). Until the library milestones land, it still reads a Navidrome library.

## What's here

| Folder | What |
|---|---|
| [`server/`](server/) | The Jukebox server (Kotlin + Ktor + SQLite), one Docker image |
| [`docs/DESIGN.md`](docs/DESIGN.md) | How it's designed and why |

Coming next: the Android app, the web app, and `deploy/` with Docker Compose files for a standalone music
server and for music + social. The recommendation engine lives in its own repository.

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).
