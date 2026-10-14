# Request fulfilment

Built 2026-10-13: server (`/place`, review stage, `done` with an album), the Requests page's "Ready to check"
on web, Mac and Android, `scripts/requests/fulfil.py` and the analyzer's wake-up signal.

## Using it

1. Look at the requests in Jukebox and run the matcher on the MacBook (one request or a batch) → one JSON file.
2. Copy it to the Mac mini and start the script:
   ```bash
   scp jukebox_requests_matched.json craftingtable:~/fulfil/
   ssh craftingtable '~/jukebox-fulfil/fulfil.py run ~/fulfil/jukebox_requests_matched.json'
   ```
   It prints what it handed in (and anything odd), then keeps waiting in the background.
3. Requests → Everyone's → **Ready to check**: listen, then Approve or Reject.
4. Approved music is in the library within a minute or two and the askers get "ready". `fulfil.py status`
   shows what's in progress; `tail -f /mnt/ugreen/jukebox/requests/fulfil.log` shows what it did.

Other commands: `fulfil.py run <json> --dry-run` (what it would do, nothing downloaded), `fulfil.py resume`
(after a reboot, picks up decisions left over), `fulfil.py undo /mnt/ugreen/jukebox/requests/undo/<time>.jsonl`.

## Setup (once, on the Mac mini)

- `mkdir -p /mnt/ugreen/jukebox/requests`; in `/mnt/ugreen/jukebox/.env`: `REQUESTS_FOLDER=/mnt/ugreen/jukebox/requests`
  and `REQUESTS_DIR=/requests`; in the live `docker-compose.yml`: `- ${REQUESTS_FOLDER}:/requests:ro`.
- The script lives in `~/jukebox-fulfil/` (copied from `scripts/requests/`); it runs with `uv` and needs ffmpeg.
- `~/.config/jukebox-fulfil.env` (chmod 600): `JUKEBOX_URL=http://127.0.0.1:8098`,
  `JUKEBOX_TOKEN=` (from `docker exec jukebox /app/bin/jukebox user token devs`),
  `MUSIC_FOLDER=/mnt/ugreen/NAS/MusicLibrary/tamil/jiosaavn_m4a`, `LIBRARY_NAME=tamil`,
  `REQUESTS_FOLDER=/mnt/ugreen/jukebox/requests`, `ANALYZER=jukebox-analyzer`.
- Lyrics come from LRCLIB (synced `.lrc` or plain `.txt` next to each song, like the rest of the library).


Goal: a music request goes from "asked" to "in the library, everyone told" without copying files by hand.

## Decided (2026-10-13)

- The matcher runs only on the MacBook and writes `jukebox_requests_matched.json`. The download links work
  from anywhere, so downloading happens elsewhere.
- A **fulfil script** does the rest, using the server's existing admin API: download → tag → rescan →
  mark done. Almost no server changes.
- The matcher fetches only the song for a song request and the whole album for an album request; a song is
  filed into its album (below).
- Prefer the 320 kbps file; keep mp3 as mp3 (converting to m4a would only lose quality; the scanner
  reads mp3).
- Lyrics are fetched with the download (LRCLIB), shown per song in the check.
- Every fulfilment is checked by an admin on the Requests page before it moves into the library; a
  rejected one is deleted.
- The fulfil script runs only when started by hand with a batch JSON; approvals drive the rest.

## Flow (decided 2026-10-13)

Nothing runs on a timer. The admin starts each batch; approving in the app starts the rest.

```
1. You look at the requests in Jukebox, run the matcher on one request or a batch → one JSON file.
2. scp it to the Mac mini and start:   fulfil.py run batch.json
   for each request in the file (an album before songs from that album):
     skip    if the request is no longer open, or match_found is false (listed in the report)
     covered a song whose album is also in this batch: no download, it closes with the album
     download 320 kbps (128 if 320 fails) + poster → requests/<id>/
     check    ffprobe opens it, length ±3 s, no track missing, really ≥256 kbps
     tag      site tags dropped; ours written (album tags copied from the album's folder if it exists)
     hand in  POST /admin/requests/{id}/review  → stage "review"; admins get a push "N ready to check"
   prints a report, then keeps running in the background (systemd-run, survives logging out) waiting for
   your decisions on this batch: one cheap call to the local server every 30 s, only while it waits.
3. You check in Jukebox (Requests → "Ready to check", admins only): cover, album, year, composer, where it
   goes, each track playable, length, bitrate → Approve / Reject (note).
4. The waiting script acts on each decision as it comes:
   Rejected → delete requests/<id>/, request back to open with the note.
   Approved → a. look up the destination again (/place), since the album may have arrived since
              b. normalise tags: copy the album tags from the destination album if it exists now
              c. move into the main library (album folder or new <Album> (<Year>)/), undo log
              d. POST /library/scan, wait until it's finished (also runs people merging, score
                 splitting, photos and small covers)
              e. /place → POST /admin/requests/{id}/done {albumId, songId} → askers get "ready"
              f. wake the analyzer (docker kill -s USR1 jukebox-analyzer) so mixes get the new songs
                 now, not at its next 10-minute check
   It exits once every request in the batch is decided (or after 7 days; `fulfil.py resume` picks up
   decisions left over, e.g. after a reboot).
```

- **Why the script moves files, not the server:** the server mounts the music read-only and should stay that
  way. The server stores the decision; the script, on the host, acts on it.
- **Listening before approving:** the server mounts `requests/` read-only (outside `LIBRARIES`, so it's never
  scanned and other users never see it) and streams a track from it to admins only:
  `GET /admin/requests/{id}/review/{n}`.
- **Tags are written before the review**, so what you check is what lands; step 4b only re-copies the album
  tags if the destination changed in between.
- **Links expire about a day after matching**, so downloading happens right away at step 2. Approval can wait.
- Everything goes to the **main library** (no `requests` library).

**Server, on top of `/place` and `done` (done, 955be4b):** schema 26 `music_requests.stage`
(`review` / `approved` / `rejected`) + `review_json`; `POST /admin/requests/{id}/review`,
`POST …/approve`, `POST …/reject {note}`, `GET /admin/requests/decisions?ids=` for the script,
the stream route, the "ready to check" push. Old apps ignore the new fields.
**Analyzer:** SIGUSR1 = check for new songs now.

### Mapping the matcher's JSON

Sample: request 12, the song *Mayiliragae* from *Anbe Aaruyire*. Song requests carry `matched_song`; album
requests list the whole album in `tracks`.

| Field | Used as |
|---|---|
| `request_id`, `kind` (`song` / album) | which request, and what to download |
| `match_found: false` | skipped, reported at the end |
| `match_score`, `matched_song.match_score` | shown in the review step |
| `metadata.year` | the album's **real** year (2005 here; the request's `year` 2013 is the iTunes re-release) |
| `metadata.music` | composer + album artist |
| `metadata.lyricists` | lyricist (album-wide; split on `,` / `&`) |
| `metadata.track_count` | checked against `tracks` for an album request |
| `metadata.poster` | cover |
| `best_match.title` | album, minus a bracketed alias: "Anbe Aaruyire (Ah Aah)" → "Anbe Aaruyire" |
| `matched_song` | the one file for a song request (`download320`, else `download128`) |
| `tracks[i]` | album request: track i + 1 of `track_count`. Song request: the matched song's position in `tracks` gives its track number (Mayiliragae = 3 of 6) |
| `name`, `artist`, `duration` | title (minus a trailing "(<album>)"), singers (split on `,` / `&`), expected length |

Relative links (`/downloader/…`) are resolved against `best_match.url`. Not used: `metadata.zip320`
(per-track files can each be checked), `director`, `starring`, `language`, `downloads`.

### Where a file goes

The scanner groups files into albums by **library + album title + year** (Scanner.kt:174), not by folder.
A song joins an existing album when it is in the same library with the same album tag and year
(tested: RequestPlaceTest).

| Request | Album in the library? | Destination after approval |
|---|---|---|
| Album | no (the app only offers albums that aren't there) | main library, new `<Album> (<Year>)/` |
| Song | **yes** | **that album's own folder**; album, date and album_artist tags copied from a track already there |
| Song | no | main library, new `<Album> (<Year>)/`; a later album request for it joins |

**"Is it in the library" must check the year, not just the title.** The live library has *Anbe Aaruyire*
**(1975)**, 5 songs, a different film. Matching on the title alone would drop the 2005 A.R. Rahman song into
the 1975 film. So the lookup takes the matcher's year and composer: same title **and** (year within 1 **or**
same composer). For request 12 that finds nothing, so the song goes to `requests/Anbe Aaruyire (2005)/`.

Adding to an existing album folder is the only write into the main library: one new file, existing files
never touched, every added path in an undo log (`fulfil.py --undo <log>`).

## Server changes

1. `GET /admin/requests/{id}/place?year=&composer=`: where the request's album is in the library, matched on
   title + (year ±1 or composer), using the matcher's year and composer, not the catalog's. Returns
   `{ albumId, album, year, songId, library, folder, sampleFile }`, or 404. The script reads the exact album
   tags from `sampleFile` with ffprobe.
2. `POST /admin/requests/{id}/done` takes an optional `{ albumId, songId }`. `Shelf.find` uses the catalog's
   year and artist, and for request 12 those are 2013 and "A.R. Rahman, Madhushree, Naresh Iyer & Vaali".
   It would answer "not in the library" even after the song is there. The script knows the album it filled,
   so it passes the id and the server checks it exists. The apps' "Added" button keeps working as today.
3. The review step (see Flow).

## Things to watch

- **Links expire and carry an address.** `/downloader/<token>/1792017876/d320_cdn/…/<base64>`: the number is
  a Unix time (2026-10-14 22:44 UTC, about a day after matching), and the base64 decodes to the IPv6 address
  the match ran from (a Cloudflare WARP one). So run the fulfil script soon after matching. If a link
  refuses, the request is left open and the script says "match again".
- **Rescan time.** `/library/scan` walks every library, but unchanged files are skipped by size + mtime,
  so it's mostly the new album. Polling has a timeout (30 min) and then reports "scan still running".
- **Admin login.** The script signs in with an admin account from `~/.config/jukebox-fulfil.env`
  (chmod 600) on the Mac mini.

## Later

- Lyrics from JioSaavn when LRCLIB has none.
