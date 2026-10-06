# The Jukebox server

One Docker container that does everything: it scans your music, serves it to the app and to any Subsonic
player (`/rest/...`), keeps accounts, likes, playlists and plays, makes mixes and stations, runs friends,
chat and listening together, and serves the web app at the same address. It keeps one SQLite database in
`data/`. How it works inside: [../docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md).

## What you need

- Docker and Docker Compose
- Your music in a folder (Jukebox only reads it), with tags and cover art
- An HTTPS address for the server, like `https://music.example.com` (a Cloudflare Tunnel or a reverse proxy)

## Run it

```bash
cd server
cp .env.example .env
nano .env                  # MUSIC_FOLDER, LIBRARIES, PUID/PGID
mkdir -p data secrets
docker compose up -d --build
docker logs jukebox        # should end with "jukebox ready on port 8095, library on, ..."
```

The server listens on `127.0.0.1:8095` only. Publish it over HTTPS (a Cloudflare Tunnel hostname or a
reverse proxy pointing to `http://localhost:8095`). That address is what people type in the app, and the
web app is at the same address.

On start it scans everything (quick rescans every hour after that), then fingerprints the songs in the
background, which takes a few hours once for a large library. `LIBRARIES` lists what to scan as
`name=path:kind:language`, separated by `;`, as seen inside the container (`MUSIC_FOLDER` is mounted at
`/music`): for example `tamil=/music:film:tamil`. Kind `film` is for film soundtracks, else `album`. Lyrics
come from `.lrc` and `.txt` files next to the songs. Admins see the scan at `GET /library/status` and can
ask for a rescan with `POST /library/scan`. More in [../docs/milestone-1.md](../docs/milestone-1.md).

## Accounts

A new server has nobody on it. Make the first account, an admin, from the command line:

```bash
docker exec -it jukebox /app/bin/jukebox user add <username> --admin    # asks for the password twice
```

From then on, anyone can invite friends from the app's Friends tab or the web app's Settings. An invite
code works once, for 7 days, and the new person becomes friends with whoever invited them. Passwords are
kept encrypted with a key in `data/secret.key` (the Subsonic sign-in needs them, see
[../docs/milestone-2.md](../docs/milestone-2.md)), so back that file up with the database.

The rest of the `user` command:

```bash
docker exec -it jukebox /app/bin/jukebox user password <username>    # set a forgotten password; signs them out
docker exec    jukebox /app/bin/jukebox user admin <username> on|off # admins see listening stats and music requests
docker exec    jukebox /app/bin/jukebox user remove <username>       # their messages stay, marked as left
docker exec    jukebox /app/bin/jukebox user list
```

For scripts, pass the password as `-e JUKEBOX_PASSWORD=...` instead of typing it.

## Mixes and the audio analyzer (optional)

The server makes mixes, playlists and stations for everyone, with **Jukebox** as their author: up to six
**Daily Mixes** (one per side of your taste), **Discover Weekly** (songs you haven't played), **On Repeat**,
**Rewind**, **New Arrivals**, **Friends Mix**, **Top 50**, composer, singer and decade mixes, and **stations**
from any song, movie, composer or singer that never run out. Your own playlists get **Recommended songs**.

They're worked out from the catalog and everyone's plays, likes, skips and playlists. Nothing is stored as
a finished list: when the library, your listening or the day changes, the mixes are worked out again, so
new songs that fit a mix appear in it by themselves. `MIX_TIMEZONE` says when "today" starts.

With only the library, mixes group songs by composer, singers and era. For **mood mixes** and radio that
follows how songs **sound**, also run the [audio analyzer](../analyzer/README.md) and set `FEATURES_FOLDER`
to its data folder.

## Push notifications (optional)

Notifications go through Firebase Cloud Messaging, with **your own** Firebase project. Nothing about it is
built into the app: the app asks this server for the settings after login.

1. In the [Firebase console](https://console.firebase.google.com), create a project.
2. **Add app → Android** with the app's package name (`io.github.devasenan134.isaipetti`, or your own if you
   build the app with a different id). Add the SHA-1 fingerprint of the certificate the app is signed with.
   Download **google-services.json**.
3. **Project settings → Service accounts → Generate new private key.** This downloads the server key.
   **It's a secret.**
4. Put both files in `secrets/` and lock them down:

   ```bash
   mv ~/Downloads/google-services.json secrets/google-services.json
   mv ~/Downloads/*-firebase-adminsdk-*.json secrets/firebase-key.json
   chmod 700 secrets && chmod 600 secrets/*
   docker compose up -d
   ```

   The log line now says `push on`.
5. Recommended: in the [Google Cloud console](https://console.cloud.google.com/apis/credentials), open the
   project's **Android key (auto created by Firebase)** and under *Application restrictions* allow only your
   app's package name and SHA-1.

## Bug reports and feature requests (optional)

The app's *Settings → Feedback* (**Report a bug** and **Suggest a feature**) creates GitHub issues through
this server, labelled `bug` or `enhancement`, so the token never ships inside the app. Create a
[fine-grained token](https://github.com/settings/personal-access-tokens/new) for just your repository with
only **Issues: Read and write**, then set `GITHUB_REPO=owner/repo` and `GITHUB_TOKEN=...` in `.env` and
restart; the log line then says `feedback on`. Issues don't say who sent them.

## Coming from Navidrome

`jukebox import` copies users (with their passwords), likes, play counts and history, and playlists from a
copy of Navidrome's database, together with the old Isaipetti companion server's database (friends, chats).
Old Navidrome ids keep working, so saved queues and shared songs still play. It's how this server took over
on 2026-10-04; the steps and what's carried over are in [../docs/milestone-2.md](../docs/milestone-2.md).

## Updating

```bash
git pull
docker compose up -d --build
```

The database updates itself when a new version starts. To back it up, copy `data/` while the server is
stopped (`docker compose stop`).

## Safety notes

- `.env`, `data/` and `secrets/` never leave the server and are ignored by git.
- The container runs as your user, not root, and is only reachable from the machine itself.
- Logins and sign-ups are rate-limited per address, session tokens are stored only as hashes, passwords are
  encrypted with a key only this server has, and oversized requests are refused.

## Development

```bash
./gradlew test                       # everything, with throwaway databases and generated audio
./gradlew runDev                     # a local server on port 8095, with users dev, bot_1, bot_2 (password dev-password)
./gradlew runDev -Plibraries="test=/path/to/music:film:tamil"     # ... with music
# The mixes someone would get, or what search finds, from copies of jukebox.db (and features.db):
./gradlew previewMixes -Pdb=jukebox.db -PfeaturesDb=features.db -Puser=<username>
./gradlew previewSearch -Pdb=jukebox.db -PcastFile=movie-cast.jsonl -Pqueries="kanave|vairamuthu"
```

`scripts/scan-report.sql` summarizes a scan; `../scripts/smoke/` walks the web app in a browser.
Kotlin, Ktor and SQLite. Code is in `src/main/kotlin/io/github/devasenan134/jukebox/server/`.
