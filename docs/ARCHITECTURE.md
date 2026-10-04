# How Jukebox works today

[DESIGN.md](DESIGN.md) says what Jukebox is meant to become and why. This page describes what runs now
(October 2026, after milestone 2): the pieces, how the catalog is built, the one model in it, how mixes are
made, and what is recorded for the recommendation engine to learn from later.

## The pieces

```
              jukebox.craftingtable.cc  (also the app's old addresses, musicnote and gamertags)
                                   │  Cloudflare tunnel
                                   ▼
┌──────────────────────── jukebox (one container) ────────────────────────┐
│  Subsonic API  /rest/*        music for the app and any Subsonic player │
│  Friends API   /auth, /friends, /conversations, /mixes, /ws (live)      │
│  Web app       /, /album/…    the React site, same address              │
│  Library       scanner, catalog, covers, lyrics, people                 │
│  Mixes         mix maker, stations, "played together"                   │
│  SQLite        data/jukebox.db (catalog, accounts, listening, chat,     │
│                event log); covers in data/artwork                       │
└─────────────┬───────────────────────────────────────────────────────────┘
              │ reads features.db
┌─────────────┴──────────── jukebox-analyzer (optional) ──────────────────┐
│  Listens to each new song once (CLAP model) and saves how it sounds     │
│  Reads jukebox.db and the music folder; writes only its features.db     │
└─────────────────────────────────────────────────────────────────────────┘
```

- **One server.** Jukebox is a single Kotlin program (Ktor) with an SQLite database. It replaced Navidrome
  and the old Isaipetti companion server on 2026-10-04 ([milestone 2](milestone-2.md)).
- **Reads run side by side; writes go one at a time.** Requests read through a small pool of read-only
  connections. Album lists, people and the search index are kept in memory and rebuilt only when a scan or
  a merge changes the catalog. Every change people make (likes, plays, playlists) is also written to the
  event log.
- **The music folder is only read.** Jukebox never changes music files.

## The catalog: how albums are built

The scanner reads the music folders and their tags (`server/.../library/`):

- **Albums and releases.** A film is an album. Its songs, its background score and singles are releases of
  it. Each track on a release points to a **recording**: the song itself, whichever file it's in.
- **Recognising the same song in two files.** A file is matched to a recording by, in order: the same path,
  size and time; a JioSaavn, ISRC or MusicBrainz id; the same length plus the same size or audio checksum
  (a moved file); and last an audio fingerprint (Chromaprint), so a re-ripped copy is recognised too.
- **People.** Composers, singers, lyricists and actors come from the tags. Spellings of one person are
  folded together ("S.P.B." and "S.P. Balasubrahmanyam") by fuzzy, phonetic name matching, with strict
  rules for one-word names and no chains of merges. `people-overrides.txt` next to the database corrects it.
- **Covers and lyrics.** Folder pictures and embedded art are stored once each (by content); the 150 and
  300 px sizes are made after each scan. Lyrics come from `.lrc` / `.txt` files next to the songs.
- **Ids.** Recordings, albums and people get stable random ids. Ids from before Jukebox (Navidrome's) are
  kept in a map, so old links and shared songs in chats still work.

## The one model: how a song sounds

The analyzer (`analyzer/analyze.py`) listens to every song once with **CLAP** (`laion/larger_clap_general`),
a model trained on audio and text together. For each recording it saves:

- a **sound fingerprint**: 512 numbers from three 10-second pieces of the song. Songs that sound alike get
  similar fingerprints;
- how well each of **21 descriptions** fits ("a sad melancholic song", "a fast Indian folk dance song with
  loud thappu drums", "a song sung by a female singer"...). The descriptions get fingerprints too, and
  comparing the two says how well a description fits a song. That is how mood mixes work without anyone
  tagging moods;
- **tempo, loudness, brightness and rhythm**, measured with librosa.

It runs on the Mac mini's CPU (about 1.5 seconds a song) and loads the model only when there are new songs.
Nothing is sent anywhere. Search and people merging use plain fuzzy string matching; there is no other model.

## How mixes are made

The mix maker (`server/.../MixMaker.kt`) works every mix out fresh from the library, the sound data and
your listening. Nothing is trained ahead of time.

**Your taste.** Every song you've touched gets a weight:

- more plays raise it (on a log scale), plays in the last 30 days count extra;
- a like adds a lot; liked albums, composers and singers add a little to each of their songs;
- songs you skip more often than you finish lose weight, and songs you skip a lot are left out of your
  mixes.

From your top songs come a **taste profile** (their average sound) and how much you favour each composer
and singer. How well any song fits you is how close it sounds to your profile, plus your fondness for its
composer and singers. Before you've played much, popularity on the server fills in.

**Played together.** Songs people play in one sitting, or put in the same playlist, are linked (plays from
the last 180 days, not counting ones a mix started, so mixes don't teach themselves). This is the strongest
signal for "what goes with this", as on the big streaming services.

**The mixes.**

| Mix | Made from |
|---|---|
| Daily Mixes (up to 6) | Your favourites split by language, then grouped by sound (k-means), each padded with songs like them |
| Discover Weekly | 30 songs you haven't heard that fit you; changes on Mondays |
| On Repeat, Rewind | What you play most lately; old favourites you haven't played in a while |
| New arrivals, Friends Mix | Songs added lately, and what your friends play, the ones that suit you first |
| Moods | How well the descriptions fit, with tempo and loudness limits (calm moods leave out party songs) |
| Decades, This Is, Popular | The library by era, by composer or singer, and by plays across the server |
| Stations (radio) | Played-together, similar sound, same composer and singers, nearby year; topped up as they play |

Every mix follows the same rules: a limit per film and per composer, no two songs from one film back to
back, and languages kept apart (a mood blends the languages you listen to; a Daily Mix or a station stays in
its songs' language).

**When it changes.** After you play something, Home answers at once with your last mixes while new ones are
worked out in the background (about two seconds for 28,000 songs).

## Learning: what happens now, and what's recorded for later

Today Jukebox "learns" only through your history. Plays, likes, skips and playlists change the weights
above the next time mixes are worked out. No model is trained on how people listen.

What's recorded for the recommendation engine to learn from:

- **The event log** (`events` table, `GET /events?after=` for admins): played, listened, skipped, liked,
  unliked, playlist changes, mixes shown.
- **Every suggestion** (`suggestion_lists`, `suggestions`): which mix or station offered which song at which
  position. Together with plays, this says which suggestions were played, skipped or never reached.
- **Plays with skips** (`plays`): how long each song played and where it was started from.

The engine will live in its own repository, read these, and replace the hand-tuned weights step by step (see
the [roadmap](../ROADMAP.md)).
