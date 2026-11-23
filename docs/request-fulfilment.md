# Request fulfilment

Built 2026-10-13: server (`/place`, review stage, `done` with an album), the Requests page's "Ready to check"
on web, Mac and Android, `scripts/requests/fulfil.py` and the analyzer's wake-up signal.

## Using it

The download site can't be reached from the Mac mini (its DNS sends the site to a dead address, and WARP
there didn't help), so the downloading half runs on the MacBook and the waiting half on the Mac mini.

1. Look at the requests in Jukebox and run the matcher on the MacBook (one request or a batch) → one JSON file.
2. On the MacBook: `fulfil.py run jukebox_requests_matched.json`. For each request it downloads, checks, tags and
   fetches lyrics, rsyncs the folder to `craftingtable:/mnt/ugreen/jukebox/requests/<id>/` right away and hands it
   in. At the end it starts the waiting half on the Mac mini over ssh, so the laptop can sleep afterwards.
3. Requests → Everyone's → **Ready to check**: listen, then Approve or Reject.
4. The Mac mini files approved music within a minute or two; the askers get "ready".
   `ssh craftingtable /mnt/ugreen/jukebox/scripts/requests/fulfil.py status` shows what's in progress and
   `/mnt/ugreen/jukebox/requests/fulfil.log` what it did.

Other commands: `fulfil.py run <json> --dry-run` (nothing downloaded), `fulfil.py resume` (starts the waiting half
again, e.g. after a reboot), and on the Mac mini `fulfil.py undo /mnt/ugreen/jukebox/requests/undo/<time>.jsonl`.

## How music is filed

- **One folder per album** (since 2026-11-17): every album is `MusicLibrary/<language>/<Album> (<Year>)/`, whichever
  source its songs came from. A song for an album the library has goes into that album's folder with its tags. Where a
  file came from is a tag: `source=masstamilan` on everything downloaded from masstamilan; a file without one is from
  JioSaavn (those 28,500 files weren't rewritten for a tag: a changed file is analysed again). The language is the
  matcher's `metadata.language` (not always right: it said Tamil for Premam).
- **The newest download wins:** when a download joins an album and brings another year or album artist (JioSaavn
  dated Jailer 2 2025 from its first single, masstamilan 2026), every file of the album, songs and background score,
  gets the new one and its folders are renamed (`Jailer 2 (2025)` → `Jailer 2 (2026)`). The run says so
  (`updates   year 2025 → 2026`, and in a request's review notes) and the undo log keeps the old tags and names, so
  `fulfil.py undo` puts them back. Per-song composers are left alone (an album can have several).
- **How the library got here:** `scripts/library/flatten.py` moved `tamil/jiosaavn_m4a/*` and `<language>/masstamilan/*`
  up into the language folders (renames; the 5 albums in both were merged, JioSaavn's `cover.jpg` kept, masstamilan's
  in `MusicLibrary/_flatten/leftovers/`), the never-scanned repeats to `MusicLibrary/_duplicate_singles/tamil/`, and
  tagged masstamilan's files. The masstamilan library's rows in the database became the Tamil library's
  (`UPDATE files SET library_id = <tamil> WHERE library_id = <tamil-masstamilan>`, after deleting the Tamil library's
  missing rows of the same paths), so every song kept its id. Undo: `flatten.py undo MusicLibrary/_flatten/undo-….jsonl`,
  the database and settings from `backups/before-flatten-20261117/`.
- **Background score vs songs:** a score album ("X BGM", "X Special BGM Additional", "X OST", "… (Original Background
  Score)") is filed as X's score: `<Album> (<Year>) (Original Background Score)/`, album tag `<Album> (Original
  Background Score)`. Inside a songs album, only tracks that say so ("Theme Music", "BGM", "Instrumental",
  "Background Score") go there; Jukebox's own score detection (title and sound) still runs after each scan, and
  `data/score-overrides.txt` (`song: <path>` / `score: <path>`) corrects it by hand.
- **A request for the songs isn't filled by the score:** search keeps offering "Vishwaroopam II" while the library has
  only its score, and the review card warns when a songs request was matched to a score page (or the other way).
- **An album already in the library:** only the songs (or score pieces) it doesn't have are downloaded; if it has them
  all, the request is closed with that album. "Has" is the server's list of the album's songs plus the files in its
  folder and its score folder: the server leaves out what it took for background score ("Roddy Theme", "Das and
  Parvathy (Instrumental)"), and before 2026-11-17 those were downloaded again on every feeds run.
- **Named after the request** (the catalog's name, which the library uses), not the matched page's title.
- **Two or more songs of one album asked for: the whole album comes.** The server already turns 2+ queued songs of an
  album into one album request, but not when the album is partly in the library or the catalog doesn't list it; then
  they arrive as song requests. So the script groups a batch's song requests by album too: the first downloads every
  song of the album (minus other versions and songs the library has), the rest come with it.
- **Named as JioSaavn names them (since 2026-11-23):** the library's 28,500 songs came from JioSaavn, so its names and
  ids are the ones the library goes by. Each download is matched to the album's songs on JioSaavn (the film's album in
  the request's language, from its year ±1, its other editions, then its singles "X (From "Film")"), by name however
  it's written ("Hukum Reloaded - Tamil" is "Hukum Reloaded") and length (within 5 s). A matched file gets JioSaavn's
  title, its id (`saavn_id`, which the scanner keeps on the recording) and its singers and lyricists when JioSaavn names
  them (it often doesn't yet for a new film; then the page's). A song is already there when its id is among the album's
  (the server's `saavnIds`) or its name is. Songs JioSaavn doesn't have keep the page's names, and the run and the
  review notes say which. `fulfil.py saavn-tags` (on the server, `--dry-run` first) did the same for the files
  downloaded before: 116 of 292 named, the rest (Annamalai, Indian, Kaala…) aren't on JioSaavn at all; undo with
  `fulfil.py undo requests/undo/saavn-tags-<time>.jsonl`.
- **One way of cleaning names:** "(From "Film")", "(feat. …)", "- Remastered", "- Tamil" and the like come off a song's
  name the same way everywhere: the server's `Titles.kt` (request search, playlist imports) and fulfil.py's
  `song_name`. A new case goes in `scripts/requests/title-cases.tsv`; the server's tests and
  `uv run --script scripts/requests/test_titles.py` both check it.
- **Every album gets a cover, the best there is:** the catalog's art the request was made from, else iTunes' (1000 px),
  else JioSaavn's (500 px), else the page's poster (masstamilan's are 150 px and stamped with the site's name). iTunes
  and JioSaavn are only taken for an album by its composer: a title alone finds other people's albums ("Delivery Boy -
  Single" by someone else); a single of the film ("Magale (From "Baththa")") counts, as most new films are only there
  as singles. `fulfil.py covers` on the Mac mini gives downloaded albums that still have none a cover (`--dry-run`
  lists them first); `covers --upgrade` also replaces covers under 400 px in every library, puts the new one inside
  the album's mp3s, keeps the old ones for `fulfil.py undo-covers <log>`, and rescans. The scanner picks up a
  cover.jpg put beside songs it has already read, and a new folder cover replaces the old one on the album and its
  releases when the songs change.
- **Other versions left out:** karaoke, remix, EDM, lo-fi, slowed/reverb, 8D, sped-up, mash-up and DJ-mix tracks
  aren't downloaded with an album unless the request is for one; a song request matched to a version is flagged.
- **Lyrics:** LRCLIB (synced first, then plain), else JioSaavn's plain lyrics; only lyrics in the song's language's
  script (or English letters) count, so another language's song of the same name isn't used.
- **Admins correct things** on the Requests page: "Change" on a download picks the album it joins; "Match album"
  closes an open request with an album from the library; "Change album" / "Reopen" fix a closed one.

## Trending and Latest (feeds)

Built 2026-11-16. `fulfil.py feeds` keeps the library up to date with masstamilan's two home-page lists,
"Trending at MassTamilan.com" and "Latest Tamil songs download", and Home shows each as a row of albums
(website, Mac app, Android), in the site's order, as "Trending Now" and "Latest Tamil songs" (the names are the
script's `FEEDS`, sent with each list):

1. It reads both lists with api-anything (the `masstamilan` site's `trending` and `latest` operations), and for a
   Trending album (only a name there) its composer, poster and year from the site's search.
2. Every album on them is downloaded **whole**: the songs the library doesn't have (other versions left out, a song
   JioSaavn names differently, "Hukum" for "Hukum - Thalaivar Alappara", counts as had), tagged, with lyrics and a
   cover, like a request. Latest albums grow as singles come out, so each run looks again.
3. **No check:** these aren't requests. The albums are put together in `requests/feeds/<page>/` and
   `fulfil.py file-feeds` files them (into the album's own folder if it's there, else
   `tamil/<Album> (<Year>)/`), rescans and wakes the analyzer. Undo logs: `requests/undo/feeds-*.jsonl`.
   Run on the server itself (no `REMOTE`), `feeds` does this directly; run on a laptop, it sends them over and
   starts `file-feeds` there over ssh.
4. The lists go to `PUT /admin/feeds/{trending|latest}`. The server keeps them (schema 35), and when one changed it
   sends `feedsChanged` so open apps fetch `GET /feeds` again. Home shows the albums of each list the library has.

**Where it runs:** on craftingtable, every 6 hours (moved from the MacBook 2026-11-17). The home network answers every
plain DNS lookup of `masstamilan.dev` with a block page (156.154.112.141/113.141), even one sent to 1.1.1.1, but
DNS over TLS gets through. So systemd-resolved sends only that domain to Cloudflare over TLS; every other name still
goes to the router. The downloads themselves come from `masstamilan.download`, which isn't blocked.

**Setup on craftingtable (once):**

```sh
# 1. masstamilan.dev over DNS over TLS. The Wi-Fi's own DNS (the router) has to say "no" first: on "default" it
#    takes the global DNSOverTLS=yes and the router, which has no TLS, stops answering.
sudo nmcli connection modify "GL-MT3000-1a2-5G" connection.dns-over-tls no
sudo nmcli device reapply wlp1s0f0
sudo mkdir -p /etc/systemd/resolved.conf.d
sudo tee /etc/systemd/resolved.conf.d/masstamilan.conf >/dev/null <<'EOF'
[Resolve]
DNS=1.1.1.1#cloudflare-dns.com 1.0.0.1#cloudflare-dns.com
Domains=~masstamilan.dev
DNSOverTLS=yes
EOF
sudo systemctl restart systemd-resolved
getent hosts www.masstamilan.dev            # Cloudflare (104.26.x, 172.67.x), not 156.154.x
# undo: sudo rm /etc/systemd/resolved.conf.d/masstamilan.conf && sudo systemctl restart systemd-resolved

# 2. the two lists, as api-anything operations of the masstamilan site (the sidebar is on every page).
#    Pin trending to the GET: the page also makes a POST with a one-time form token.
api-anything add masstamilan trending --trigger 'https://www.masstamilan.dev/' --match '{"path":"/","method":"GET"}' \
  --html '{"items":"div.sb section.wid:first-of-type li","fields":{"title":"a","url":"a@href"}}' \
  --description "The Trending at MassTamilan.com list (sidebar), in order"
api-anything add masstamilan latest --trigger 'https://www.masstamilan.dev/' --match '{"path":"/","method":"GET"}' \
  --html '{"items":"div.a-i","fields":{"title":"h2","url":"a@href","poster":"img@src","info":"p"}}' \
  --description "The home page's Latest Tamil songs download list, in order"
api-anything call masstamilan trending      # should list about 10 albums

# 3. API_ANYTHING in the server's ~/.config/jukebox-fulfil.env, then a dry run
echo "API_ANYTHING=$(which api-anything)" >> ~/.config/jukebox-fulfil.env
uv run --script /mnt/ugreen/jukebox/scripts/requests/fulfil.py feeds --dry-run
```

4. The timer, a user unit (lingering is on, so it runs with no one logged in). It runs the deployed copy of the
   script, so a deploy updates it. `~/.config/systemd/user/jukebox-feeds.service`:

```ini
[Unit]
Description=Jukebox feeds: masstamilan Trending and Latest into the library
Wants=network-online.target
After=network-online.target

[Service]
Type=oneshot
Environment=PATH=%h/.local/bin:/usr/local/bin:/usr/bin:/bin
ExecStart=/usr/bin/uv run --script /mnt/ugreen/jukebox/scripts/requests/fulfil.py feeds
TimeoutStartSec=3h
```

`~/.config/systemd/user/jukebox-feeds.timer`:

```ini
[Unit]
Description=Jukebox feeds every 6 hours

[Timer]
OnCalendar=00/6:00
RandomizedDelaySec=15min
Persistent=true

[Install]
WantedBy=timers.target
```

```sh
systemctl --user daemon-reload
systemctl --user start jukebox-feeds.service      # the first run (the catchup); follow it with the next line
journalctl --user -u jukebox-feeds -f
systemctl --user enable --now jukebox-feeds.timer
systemctl --user list-timers jukebox-feeds.timer
```

**From a laptop instead** (if the DNS route ever stops working): the same api-anything operations, `API_ANYTHING`
and the `REMOTE*` settings in the laptop's `~/.config/jukebox-fulfil.env`, and `fulfil.py feeds` run there by hand.
The ssh to craftingtable has to work without a prompt (`ssh-copy-id craftingtable`; if Cloudflare Access asks for a
login, `cloudflared access login https://tp.craftingtable.cc` once). Don't schedule it on both.

## Setup (once)

MacBook: `brew install ffmpeg uv`; the script from the repo (`scripts/requests/fulfil.py`); `ssh craftingtable`
working (`ssh-copy-id craftingtable` to stop password prompts); `~/.config/jukebox-fulfil.env` (chmod 600):
`JUKEBOX_URL=https://jukebox.craftingtable.cc`, `JUKEBOX_TOKEN`, `MUSIC_FOLDER=/mnt/ugreen/NAS/MusicLibrary/tamil`,
`MUSIC_ROOT=/mnt/ugreen/NAS/MusicLibrary`, `LIBRARY_NAME=tamil`,
`LIBRARY_FOLDERS=malayalam=malayalam,telugu=telugu,hindi=hindi`,
`REQUESTS_FOLDER=/mnt/ugreen/jukebox/requests`, `REMOTE=craftingtable`, `REMOTE_FULFIL=/mnt/ugreen/jukebox/scripts/requests/fulfil.py`.

Mac mini: `/mnt/ugreen/jukebox/requests` (kept by deploys); in `.env` `REQUESTS_FOLDER`, `REQUESTS_DIR=/requests`,
`LIBRARY_ROOT=/mnt/ugreen/NAS/MusicLibrary` and the language libraries in `LIBRARIES`
(`tamil=/music:film:tamil;malayalam=/library/malayalam:film:malayalam;telugu=/library/telugu:film:telugu;hindi=/library/hindi:film:hindi`, `MUSIC_FOLDER=/mnt/ugreen/NAS/MusicLibrary/tamil`, also in the analyzer's `.env`); the live `docker-compose.yml` mounts `/requests` and `/library`
read-only; the analyzer's `.env` has `LIBRARY_ROOT` too. `~/.config/jukebox-fulfil.env` as on the MacBook but
`JUKEBOX_URL=http://127.0.0.1:8098`, no `REMOTE*`, and `ANALYZER=jukebox-analyzer`.
Lyrics come from LRCLIB (synced `.lrc` or plain `.txt` next to each song, like the rest of the library).

## Decided (2026-10-13)

- The matcher runs only on the MacBook and writes `jukebox_requests_matched.json`. The Mac mini can't reach the
  download site, so downloading happens on the MacBook too; files go to the Mac mini by rsync.
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
