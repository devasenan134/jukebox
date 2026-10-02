# Milestone 1: catalog and library

Jukebox learns the library itself: it scans the music folders, builds the catalog (albums, releases, tracks,
recordings, songs, people), serves cover art, lyrics and audio, and answers the Subsonic calls the app makes
to browse, search and play. It runs **beside** the live Navidrome and Isaipetti server, read-only on the
music, and changes nothing they use.

Likes, playlists, play history and sign-in without Navidrome are Milestone 2.

## 1. The catalog tables

```
albums ──< releases ──< tracks >── recordings >── songs
   │                                   │
   └──< album_credits >── people ──< recording_credits
                                       │
recordings ──< files (on disk)         └── (roles: composer, singer, lyricist, …)
recordings ──< lyrics
```

| Table | One row per | Main columns |
|---|---|---|
| `albums` | What people browse: a film, or any album | `id`, `title`, `sort_title`, `year`, `kind` (`film`, `album`, `compilation`), `cover_id`, `library_id` |
| `releases` | A published version of the album's music | `album_id`, `title`, `kind` (`soundtrack`, `score`, `single`, `rerelease`, `other`), `year`, `source`, `source_id` (e.g. the JioSaavn album id) |
| `tracks` | A recording appearing on a release | `release_id`, `recording_id`, `disc`, `number`, `title` (as printed on that release) |
| `recordings` | One performance; **this is what the app calls a song** | `id`, `song_id`, `title`, `version` (`original`, `karaoke`, `remix`, `instrumental`, `live`, `female`, `male`, …), `duration_ms`, `isrc`, `saavn_id`, `mbid`, `fingerprint` |
| `songs` | The composition, grouping its versions | `id`, `title`, `sort_title` |
| `people` | A person or group | `id`, `name`, `sort_name`, `aliases` (other spellings: "M.S. Viswanathan" = "M. S. Viswanathan") |
| `recording_credits`, `album_credits` | A person's role | `person_id`, `role` (`composer`, `singer`, `lyricist`, `actor`, `director`), `position` |
| `files` | An audio file on disk | `recording_id`, `library_id`, `path`, `size`, `mtime`, `format`, `bitrate`, `sample_rate`, `duration_ms`, `audio_hash`, `missing_since` |
| `artwork` | A cover image | `id`, `hash`, `path`, `width`, `height`, `source` (`folder`, `embedded`) |
| `lyrics` | Lyrics for a recording from one source | `recording_id`, `source`, `script` (`ta`, `en`…), `synced`, `text` |
| `libraries` | A music folder | `id`, `name`, `path`, `language` (e.g. `tamil` for `MusicLibrary/tamil/jiosaavn_m4a`) |
| `scans` | A scan run | `started_at`, `finished_at`, counts |

Likes, plays and playlists (Milestone 2) attach to **recordings**, never to files or tracks.

## 2. Stable ids

Ids are short random strings, given once and stored. A file is matched to an existing recording before a
new one is made, in this order:

1. **Same file**: same path, size and modified time → nothing to do (most of every rescan).
2. **An external id in the tags**: MusicBrainz recording id, ISRC, or the JioSaavn id → that recording.
3. **Same audio**: the hash of the audio data (tags excluded) → that recording. This catches renames and
   retagging, like the Fresh → `MusicLibrary/tamil` move.
4. **Same sound**: a Chromaprint fingerprint close to an existing one → that recording. This catches
   re-downloads in better quality and the same song uploaded on two albums.
5. Otherwise a new recording.

Our library has no JioSaavn id in its tags. Instead of writing to the live files, Jukebox imports the
mapping once from `signals/songs.csv` (path → JioSaavn id). Other people self-hosting rely on 2–4.

A file that disappears is marked missing, not deleted, so its recording keeps its likes and plays if the file
comes back or a better copy turns up.

## 3. The scanner

For each library folder:

1. **Walk** the folder; skip files whose path, size and time haven't changed.
2. **Read** tags and audio details with `ffprobe` (handles m4a, mp3, flac, ogg alike). `ffmpeg` and
   `fpcalc` (Chromaprint) go into the Docker image.
3. **Match** each file to a recording (section 2).
4. **Group** into albums and releases:
   - album = the album tag with known suffixes removed ("(Original Background Score)",
     "(Original Motion Picture Soundtrack)", `(From "…")`), plus album artist and year;
   - release kind from the album tag suffix (`score`), the disc subtitle ("Soundtrack", "Singles"), or
     `other`.
5. **Versions**: "(Karaoke)", "(Remix)", "- Female", "(Unplugged)"… are detected in titles and set
   `version`; recordings with the same base title on the same album share a `song`.
6. **People**: split artist, album artist and composer tags on commas, "&" and "and"; spellings that sound
   the same become one person (the rule the server's search already uses); roles from which tag they came
   from (singer, composer).
7. **Cover art**: `cover.jpg` (or `folder.jpg`, `front.jpg`) in the folder, else the embedded picture;
   stored once per image hash; resized copies cached on request.
8. **Lyrics**: `<song>.lrc` (synced) and `<song>.txt` next to the audio file.

When: a full scan on start if the catalog is empty, a quick rescan (changed files only) every hour, and on
request from an admin endpoint. Fingerprints are computed in the background after the scan, a few at a
time, so the first scan isn't held up. On the Mac mini that is roughly a few hours for 28,000 songs, once.

## 4. Audio and cover art

- `stream` serves the original file with HTTP range requests (seeking, prefetching the next song), the
  right content type, and an ETag so repeat requests are cheap. No conversion yet (Milestone 5).
- `getCoverArt` serves the cover at the size asked for, from a cache of resized images.

## 5. The Subsonic calls

The Android app and the web app make the same calls. Milestone 1 answers the read-only ones, in the same
shapes Navidrome returns:

| Call | Answered from | In M1 |
|---|---|---|
| `ping` | | yes |
| `getAlbumList2`, `getAlbum` | albums, releases, tracks | yes |
| `getArtists`, `getArtist` | people with a singer or composer role | yes |
| `search3` | albums, people, recordings (the server's sound-alike search) | yes |
| `getSong` | a recording | yes |
| `getCoverArt`, `stream` | artwork, files | yes |
| `getLyricsBySongId` | lyrics | yes |
| `getStarred2`, `star`, `unstar`, `scrobble` | likes, plays | M2 (empty answers in M1) |
| `getPlaylists`, `getPlaylist`, `createPlaylist`, `updatePlaylist`, `deletePlaylist` | playlists | M2 |
| Navidrome's `/auth/login`, `/api/user/{id}` (changing a password) | users | M2 |

**How albums appear to the app**: today the app shows each film as two albums, the songs and the
"(Original Background Score)". Jukebox keeps that look through Subsonic: one album with the soundtrack
and singles as discs, and the score release as a second album. Jukebox's own API (Milestone 4) shows one
album with all its releases.

**Sign-in in M1**: Subsonic sign-in is still checked against Navidrome, the way the server already does it.

**Ids the app sees**: Subsonic song ids are recording ids. A song id from Navidrome doesn't work on
Jukebox; the Milestone 2 import maps old ids to new ones.

## 6. Running it beside the live server

- A separate container, `jukebox`, on the Mac mini at `/mnt/ugreen/jukebox`, on its own port (8098).
- The music folder mounted **read-only**; its own `jukebox.db`; its own cover art and fingerprint cache.
- Memory limit 768 MB (JVM heap capped). Scans and fingerprinting run at low priority so Navidrome and the
  live server aren't slowed down.
- Tried with the web app pointed at it (and a debug build of the Android app), with a test account.

## 7. How we know it works

- **Tests** on tiny audio files made with `ffmpeg` during the test run: tags, grouping into albums and
  releases, versions, people, renaming a file (same recording), a better-quality copy (same recording), a
  missing file coming back.
- **Contract tests** for each Subsonic call: the JSON has every field the app reads, checked against saved
  Navidrome answers.
- **A real scan report** on the live library, compared with what we know: about 4,972 album folders → about
  4,450 albums (songs and score folders merged); 28,627 songs plus the 3,518 set-aside repeats → each repeat
  joined to its recording as a second track, not a new song.

**Done when**: the full library is scanned; a rename or a move keeps every id; the web app browses,
searches, shows lyrics and plays the whole library from Jukebox; Navidrome and the live server are
untouched.

## 8. Not in Milestone 1

Likes, playlists, plays and the event log; sign-in without Navidrome; mixes and search running on Jukebox's
own catalog (they keep reading Navidrome until Milestone 2); converting audio; the realtime gateway.

## Decisions (2026-10-02)

1. Mixes are credited to "Jukebox" (they said "Isai Pettai").
2. The repeats folder (`jiosaavn_m4a_duplicate_singles`) is not scanned.
3. Fingerprinting is on.
