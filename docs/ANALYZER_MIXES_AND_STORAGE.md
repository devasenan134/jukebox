# Analyzer, Mixes, Playlists, and Auxiliary Files Guide

This document explains the inner workings of the Jukebox **Audio Analyzer**, how **Mixes and Stations** are generated and saved, how **User-Created Playlists** are persisted, and what **auxiliary or sidecar files** exist across the server and music library.

---

## 1. The Audio Analyzer

The audio analyzer lives in [`analyzer/analyze.py`](file:///home/devs/code/jukebox/analyzer/analyze.py) and is designed to run as an optional background service (`jukebox-analyzer` container).

### Purpose
While Jukebox reads standard audio tags (title, artist, album, composer, year) during library scans, tags cannot tell **how a song sounds**. The analyzer listens to each recording once and calculates acoustic embeddings and characteristics so Jukebox can generate mood mixes, acoustic radio stations, and sound-clustered Daily Mixes without manual tagging.

### Architecture and Workflow
1. **Reads Catalog & Music (Read-Only):**
   - Connects to Jukebox's primary database (`data/jukebox.db`) in read-only mode (`?mode=ro`).
   - Retrieves active recordings from the `recordings` and `files` tables.
   - For each recording, it selects the highest-bitrate audio file on disk.
   - Compares the file version (`<size>-<mtime>`) against previously analyzed files in `features.db`. Only new or updated files are analyzed.
2. **Audio Decoding and CLAP Embeddings:**
   - Extracts three 10-second windows at 20%, 45%, and 70% of the song's duration, skipping intros and outros.
   - Decodes each window using `ffmpeg` to 48 kHz mono float32 PCM.
   - Passes the audio through the **CLAP** model ([`laion/larger_clap_general`](https://huggingface.co/laion/larger_clap_general)).
   - Normalizes and averages the resulting vectors into a single **512-dimensional sound fingerprint** vector (stored as a float32 binary blob).
3. **Acoustic Measurements via Librosa:**
   - Extracts a 30-second window from the middle of the track (at 22,050 Hz).
   - Computes four physical acoustic features:
     - **Tempo:** Beats per minute (BPM) via beat tracking.
     - **Energy:** RMS loudness measured in decibels (dB).
     - **Brightness:** Spectral centroid in Hz (tonal balance between bass and treble).
     - **Rhythm:** Beat punchiness via average onset strength.
4. **Text Description Embeddings (Zero-Shot Mood Prompts):**
   - Computes text embeddings using CLAP's text encoder for 21 predefined musical descriptions:
     - Moods: `"happy"`, `"sad"`, `"romantic"`, `"party"`, `"chill"`, `"devotional"`, `"heroic"`, `"melody"`, `"lullaby"`.
     - Genres / Cultures: `"kuthu"`, `"classical"`, `"instrumental"`, `"retro"`, `"electronic"`, `"hiphop"`, `"rock"`, `"acoustic"`, `"indianfilm"`, `"western"`.
     - Vocals: `"male"`, `"female"`.
5. **Storage in `features.db`:**
   - Writes only to its dedicated SQLite database (`features.db`).
   - `songs` table: `id` (recording ID), `path`, `file_version`, `analyzed_at`, `tempo`, `energy`, `brightness`, `rhythm`, `embedding`, `error`.
   - `prompts` table: `key`, `embedding`.
   - `meta` table: CLAP model identifier and schema version.
   - **Crucially:** It never modifies the audio files and never writes directly into Jukebox's main `jukebox.db`.

---

## 2. Mix and Station Generation

### Components Involved
Mix generation is handled entirely inside the Kotlin server by three core classes:
- [`MixService`](file:///home/devs/code/jukebox/server/src/main/kotlin/io/github/devasenan134/jukebox/server/Mixes.kt): Orchestrates user requests, caching, history retrieval, suggestion logging, and background rebuilds.
- [`MixMaker`](file:///home/devs/code/jukebox/server/src/main/kotlin/io/github/devasenan134/jukebox/server/MixMaker.kt): Pure algorithmic engine that scores, clusters, and sequences mixes for an individual user.
- [`JukeboxLibrary`](file:///home/devs/code/jukebox/server/src/main/kotlin/io/github/devasenan134/jukebox/server/library/JukeboxLibrary.kt): Loads the catalog from `jukebox.db` and merges it with acoustic embeddings from `features.db` into an immutable in-memory [`LibrarySnapshot`](file:///home/devs/code/jukebox/server/src/main/kotlin/io/github/devasenan134/jukebox/server/Models.kt).

### Algorithmic Pipeline
When a user opens Home (`GET /mixes`) or requests an individual mix (`GET /mixes/{id}`):

1. **User Taste Profiling:**
   - **Interaction Weights:** Scores every song the user touched:
     $$\text{weight} = \ln(1 + \text{plays}) + 0.5 \ln(1 + \text{recentPlays}) + 2.0 \cdot \text{liked} + \text{playlisted} - 0.5 \cdot (\text{skips} - \text{listens})$$
   - Liked albums, composers, and singers contribute fractional weights to their associated songs.
   - **Playlists** (`playlistTaste` in `Library.kt`): each song in a playlist the user made adds 1.0 × carefulness
     (+0.3 if added in the last 30 days); in a playlist they liked, 0.4 × carefulness. Carefulness is
     $\sqrt{30 / \text{size}}$ clamped to 0.4–1, so a 30-song playlist says more per song than a 500-song dump.
     A song in several playlists adds up, capped at 2.0. Playlist songs count as known, so Discover Weekly skips them.
   - Heavily skipped songs ($\ge 2$ skips and skips $> 2 \times$ listens) are placed on an exclusion list.
   - **Taste Centroid:** Computes the average 512-dim embedding of the user's top liked songs.
   - **Affinity:** Calculates cosine similarity (dot product) between each song in the library and the user's taste centroid, adjusted for composer/singer preference.
   - **Language Affinity:** Determines language proportions from the user's listening history to prevent unnatural cross-language bleed.

2. **Mix Categories:**
   - **Daily Mixes (Up to 6):** Favourites split by language, clustered by acoustic embeddings using **k-means clustering**, and padded with similar tracks.
   - **Mood Mixes:** Scores songs against prompt embeddings (z-score normalized dot product) combined with acoustic constraints (e.g., Party requires high energy and tempo; Chill restricts energy).
   - **Discover Weekly:** 30 unheard songs matching the taste centroid, refreshed on Mondays.
   - **On Repeat & Rewind:** Recent heavy rotations vs. past favorites not played in over 30 days.
   - **Stations & Radio:** Combines sound similarity with the **"Played Together"** graph from [`Together.kt`](file:///home/devs/code/jukebox/server/src/main/kotlin/io/github/devasenan134/jukebox/server/Together.kt) (tracks frequently played in the same session or co-occurring in user playlists across the server).

### How Mixes are Saved and Cached
Mixes are **not stored as static song lists** in the database. Instead:
- **In-Memory Cache:** [`MixService`](file:///home/devs/code/jukebox/server/src/main/kotlin/io/github/devasenan134/jukebox/server/Mixes.kt) keeps precomputed mixes in a concurrent cache (`built`). A background job asynchronously recalculates mixes only when the catalog changes, listening history updates, or day rolls over.
- **`mix_state` Table (`data/jukebox.db`):** Stores `(user_id, mix_id, songs_hash, updated_at)`. This ensures that a mix's `updatedAt` timestamp only updates when its song list actually changes.
- **`suggestion_lists` & `suggestions` Tables:** Whenever a mix or radio batch is served, the exact sequence of songs returned is logged for audit and future machine learning recommendation training.
- **`followed_mixes` Table:** When a user pins or follows a mix to their library, its metadata and JSON snapshot are persisted so it remains accessible even if the mix rotates off the Home screen.

---

## 3. How User-Created Playlists are Saved

User-created playlists are managed by [`Listening.kt`](file:///home/devs/code/jukebox/server/src/main/kotlin/io/github/devasenan134/jukebox/server/library/Listening.kt) and exposed via both the Web API ([`CatalogApi.kt`](file:///home/devs/code/jukebox/server/src/main/kotlin/io/github/devasenan134/jukebox/server/CatalogApi.kt)) and Subsonic API ([`SubsonicApi.kt`](file:///home/devs/code/jukebox/server/src/main/kotlin/io/github/devasenan134/jukebox/server/subsonic/SubsonicApi.kt)).

### Storage in SQLite (`data/jukebox.db`):
- **`playlists` table:**
  - `id`: Unique 16-character alphanumeric identifier.
  - `owner_id`: User ID of the playlist creator.
  - `name`: Playlist title (up to 100 characters).
  - `comment`: Optional description.
  - `public`: Integer flag (`0` for private, `1` for public to friends).
  - `cover_id`: Optional custom playlist cover art ID referencing the `artwork` table.
  - `created_at` and `changed_at`: Millisecond timestamps.
- **`playlist_entries` table:**
  - `(playlist_id, position, recording_id, added_at)` with primary key `(playlist_id, position)`.
- **`liked_playlists` table:**
  - Stores user likes on public playlists ([`PlaylistLikes.kt`](file:///home/devs/code/jukebox/server/src/main/kotlin/io/github/devasenan134/jukebox/server/PlaylistLikes.kt)).
- **`events` table:**
  - Logs audit events: `playlist_created`, `playlist_changed`, `playlist_deleted`.

---

## 4. Auxiliary and Generated Files Guide

### Category A: Files in the Music Library Folder (`/music`)
> [!IMPORTANT]
> Jukebox mounts the music folder **strictly read-only** (`:ro`) and **never creates, modifies, or deletes files** inside your music directory.

1. **Sidecar Lyrics (`.lrc` and `.txt`):**
   - **What they are:** Synchronized lyrics files (`.lrc`) or plain text files (`.txt`) placed alongside audio tracks (e.g., `track.mp3` and `track.lrc`).
   - **Are they generated by Jukebox?** **No.** Users or third-party lyric downloaders supply these files.
   - **How Jukebox uses them:** During library scans, [`Scanner.readLyrics`](file:///home/devs/code/jukebox/server/src/main/kotlin/io/github/devasenan134/jukebox/server/library/Scanner.kt#L281) indexes their contents directly into the `lyrics` table in `jukebox.db`.
2. **Folder Cover Images (`cover.jpg`, `folder.jpg`, `front.jpg`):**
   - **What they are:** Album artwork files stored in music folders.
   - **Are they generated by Jukebox?** **No.** Supplied by the user. Read by [`Scanner.folderCover`](file:///home/devs/code/jukebox/server/src/main/kotlin/io/github/devasenan134/jukebox/server/library/Scanner.kt#L290).

### Category B: Files in the Data Folder (`data/`)
When someone self-hosts Jukebox, the server automatically generates and maintains the following state files:

| File / Directory | Generated For Self-Hosters? | Creator Component | Description |
|---|---|---|---|
| `data/jukebox.db` | **Yes** | [`Db.kt`](file:///home/devs/code/jukebox/server/src/main/kotlin/io/github/devasenan134/jukebox/server/Db.kt) & [`Scanner.kt`](file:///home/devs/code/jukebox/server/src/main/kotlin/io/github/devasenan134/jukebox/server/library/Scanner.kt) | Primary SQLite database containing catalog tables, users, playlists, listening history, and chat. |
| `data/secret.key` | **Yes** | [`Passwords.kt`](file:///home/devs/code/jukebox/server/src/main/kotlin/io/github/devasenan134/jukebox/server/Passwords.kt#L38) | AES-256-GCM symmetric key generated on first startup, used to reversibly encrypt Subsonic passwords. |
| `data/artwork/` | **Yes** | [`Scanner.kt`](file:///home/devs/code/jukebox/server/src/main/kotlin/io/github/devasenan134/jukebox/server/library/Scanner.kt#L299) & [`Covers.kt`](file:///home/devs/code/jukebox/server/src/main/kotlin/io/github/devasenan134/jukebox/server/library/Covers.kt) | Deduplicated, SHA-256 content-addressed artwork cache extracted from audio tags and folder images. |
| `data/artwork/sized/` | **Yes** | [`Covers.kt`](file:///home/devs/code/jukebox/server/src/main/kotlin/io/github/devasenan134/jukebox/server/library/Covers.kt#L42) | Resized thumbnail cache (150px mini-player thumbnails, 300px tiles). |
| `data/features.db` | **Yes (if analyzer is enabled)** | [`analyzer/analyze.py`](file:///home/devs/code/jukebox/analyzer/analyze.py) (`jukebox-analyzer` container) | Acoustic fingerprints and librosa audio features. Not generated if running standalone mode without analyzer. |
| `data/people-overrides.txt` | **No** (Optional manual file) | Server Administrator | Optional plain-text file to manually override or correct artist and composer merging rules. |
| `scripts/metadata/*` | **No** | Offline maintenance scripts | Standalone Python scripts used for catalog preparation; not executed by the server. |
