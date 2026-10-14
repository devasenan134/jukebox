#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["httpx>=0.27"]
# ///
"""
Fills music requests from the matcher's JSON (docs/request-fulfilment.md). Two halves, one script:

- On the laptop (REMOTE set), where the download site can be reached: download, check, tag and fetch
  lyrics, rsync each request's folder to the server's REQUESTS_FOLDER right away, hand it in for an
  admin's check, then start the waiting half on the server over ssh.
- On the server's host, beside the music folder: wait for the admin's decisions and file approved music.
  (Without REMOTE it can also do the downloading itself, if that machine can reach the site.)

    fulfil.py run batch.json [--dry-run]   download, check, tag and hand in each request for an admin's
                                           check, then wait in the background for the decisions
    fulfil.py resume                       act on decisions left over (after a reboot), same waiting
    fulfil.py status                       what's waiting for a check, approved or rejected
    fulfil.py undo <log.jsonl>             take music filed by a run back out of the library

The admin listens on the Requests page and approves or rejects. Approved music is moved into the
library (into its album's folder if the album is there), the library is rescanned, the request is
closed (askers get "ready") and the analyzer is woken. Rejected downloads are deleted and the request
waits again.

Settings: ~/.config/jukebox-fulfil.env (KEY=value lines, chmod 600):
    JUKEBOX_URL      server: http://127.0.0.1:8098 · laptop: https://jukebox.craftingtable.cc
    JUKEBOX_TOKEN    an admin's token: docker exec jukebox /app/bin/jukebox user token <admin>
    MUSIC_FOLDER     the main library's folder on the server (the one mounted at /music)
    LIBRARY_NAME     its name in LIBRARIES (tamil)
    MUSIC_ROOT       the folder holding every library (/mnt/ugreen/NAS/MusicLibrary); default MUSIC_FOLDER
    LANGUAGES        other languages, each a library in MUSIC_ROOT/<language> (malayalam,telugu,hindi →
                     MUSIC_ROOT/malayalam …); music goes by the matcher's language
    REQUESTS_FOLDER  where downloads wait on the server (mounted read-only in the container as REQUESTS_DIR)
    ANALYZER         server: the analyzer's container (jukebox-analyzer), woken after a scan; blank: don't
  laptop only:
    REMOTE           the server's ssh name (craftingtable); MUSIC_FOLDER and REQUESTS_FOLDER are then on it
    REMOTE_FULFIL    this script's path on the server (/mnt/ugreen/jukebox/scripts/requests/fulfil.py)
    STAGING_FOLDER   where downloads are put together on the laptop (default ~/.cache/jukebox-fulfil)
Needs ffmpeg and ffprobe (and rsync + ssh on the laptop).
"""

import argparse
import fcntl
import ipaddress
import json
import os
import re
import shlex
import shutil
import socket
import subprocess
import sys
import time
import unicodedata
from datetime import datetime
from pathlib import Path
from urllib.parse import urljoin, urlparse

import httpx

POLL_SECONDS = 30
WAIT_DAYS = 7
MAX_FILE_BYTES = 60 * 1024 * 1024
LENGTH_SLACK_MS = 3000
LRCLIB = os.environ.get("LRCLIB_URL", "https://lrclib.net")
# Tests point the downloads at a local server; real runs only ever fetch https from public addresses.
ALLOW_LOCAL = os.environ.get("FULFIL_ALLOW_LOCAL") == "1"
AGENT = "jukebox-fulfil (https://github.com/devasenan134/jukebox)"


TTY = sys.stdout.isatty()


def say(text="", end="\n"):
    """Progress for the person running a batch (no timestamps; the server half logs with them)."""
    print(text, end=end, flush=True)


def live(text):
    """A line that's rewritten as it changes (download progress), when the output is a terminal."""
    if TTY:
        print("\r\033[K" + text, end="", flush=True)


def megabytes(n):
    return f"{n / 1_048_576:.1f} MB"


def log(*parts):
    print(datetime.now().strftime("%H:%M:%S"), *parts, flush=True)


# ---------------------------------------------------------------- settings and the server


def settings():
    path = Path(os.path.expanduser(str(os.environ.get("FULFIL_ENV") or "~/.config/jukebox-fulfil.env")))
    values = {}
    if path.is_file():
        for line in path.read_text().splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.split("=", 1)
                values[k.strip()] = v.strip().strip('"')
    values.update({k: v for k, v in os.environ.items() if k in KEYS})
    optional = {"ANALYZER", "REMOTE", "REMOTE_FULFIL", "STAGING_FOLDER", "LANGUAGES", "MUSIC_ROOT"}
    missing = [k for k in KEYS if k not in optional and not values.get(k)]
    if values.get("REMOTE") and not values.get("REMOTE_FULFIL"):
        missing.append("REMOTE_FULFIL")
    if missing:
        sys.exit(f"Missing settings in {path}: {', '.join(missing)}")
    # Every path below is inside ROOT; each library's folder is FOLDERS[library] there.
    values["ROOT"] = (values.get("MUSIC_ROOT") or values["MUSIC_FOLDER"]).rstrip("/")
    main = os.path.relpath(values["MUSIC_FOLDER"].rstrip("/"), values["ROOT"])
    values["FOLDERS"] = {values["LIBRARY_NAME"]: "" if main == "." else main}
    for language in [x.strip().lower() for x in (values.get("LANGUAGES") or "").split(",") if x.strip()]:
        values["FOLDERS"].setdefault(language, language)
    # Where downloads are put together: the waiting folder itself on the server, a staging folder on the laptop.
    values["WORK_FOLDER"] = (os.path.expanduser(values.get("STAGING_FOLDER") or "~/.cache/jukebox-fulfil")
                             if values.get("REMOTE") else values["REQUESTS_FOLDER"])
    return values


KEYS = ["JUKEBOX_URL", "JUKEBOX_TOKEN", "MUSIC_FOLDER", "LIBRARY_NAME", "REQUESTS_FOLDER", "ANALYZER",
        "REMOTE", "REMOTE_FULFIL", "STAGING_FOLDER", "LANGUAGES", "MUSIC_ROOT"]


def libraries(cfg):
    """The libraries music can go to: the main one and one per language."""
    return list(cfg["FOLDERS"])


def in_library(cfg, library, path):
    """[path] inside [library]'s folder, as a path inside MUSIC_ROOT ("malayalam/Premam (2015)")."""
    return f"{cfg['FOLDERS'][library]}/{path}".lstrip("/")


# ---------------------------------------------------------------- the server's files, from either machine


# One ssh connection for the whole run: the first one stays open in the background and every later ssh and
# rsync goes through it, so the server's password (if it asks for one) is typed once, not at every step.
SSH_SHARE = ["-o", "ControlMaster=auto", "-o", "ControlPath=~/.ssh/jukebox-fulfil-%C", "-o", "ControlPersist=15m"]


def ssh(cfg, command, **kw):
    """Runs [command] (a list of words) on the server, quoted for its shell."""
    return subprocess.run(["ssh", *SSH_SHARE, cfg["REMOTE"], " ".join(shlex.quote(c) for c in command)], stdin=subprocess.DEVNULL, **kw)


def connect(cfg):
    """Opens the shared connection (the only place a password can be asked for). False if it can't."""
    return subprocess.run(["ssh", *SSH_SHARE, cfg["REMOTE"], "true"]).returncode == 0


def disconnect(cfg):
    subprocess.run(["ssh", *SSH_SHARE, "-O", "exit", cfg["REMOTE"]], capture_output=True, stdin=subprocess.DEVNULL)


def music_exists(cfg, relative):
    path = str(Path(cfg["ROOT"]) / relative)
    if cfg.get("REMOTE"):
        return ssh(cfg, ["test", "-e", path], capture_output=True).returncode == 0
    return Path(path).exists()


def music_tags(cfg, relative):
    """The album tags of a file in the library (on the server)."""
    path = str(Path(cfg["ROOT"]) / relative)
    if not cfg.get("REMOTE"):
        return album_tags_from(path)
    out = ssh(cfg, ["ffprobe", "-v", "error", "-show_entries", "format_tags", "-of", "json", path], capture_output=True, text=True)
    t = {k.lower(): v for k, v in ((json.loads(out.stdout or "{}").get("format") or {}).get("tags") or {}).items()} if out.returncode == 0 else {}
    return tags_of(t)


def send_to_server(cfg, rid):
    """Copies request [rid]'s folder from the laptop into the server's waiting folder, then drops the local copy."""
    local = Path(cfg["WORK_FOLDER"]) / str(rid)
    remote = f"{cfg['REQUESTS_FOLDER'].rstrip('/')}/{rid}"
    ssh(cfg, ["mkdir", "-p", remote], check=True, capture_output=True)
    done = subprocess.run(["rsync", "-a", "--delete", "--exclude", ".*", "-e", " ".join(["ssh", *SSH_SHARE]),
                           f"{local}/", f"{cfg['REMOTE']}:{shlex.quote(remote)}/"],
                          stdin=subprocess.DEVNULL, capture_output=True, text=True)
    if done.returncode != 0:
        raise RuntimeError(f"rsync to {cfg['REMOTE']} failed: {done.stderr.strip()[:300]}")
    shutil.rmtree(local, ignore_errors=True)


class Server:
    def __init__(self, cfg):
        self.cfg = cfg
        self.http = httpx.Client(base_url=cfg["JUKEBOX_URL"].rstrip("/"), timeout=60,
                                 headers={"Authorization": f"Bearer {cfg['JUKEBOX_TOKEN']}"})

    def call(self, method, path, ok=(200, 204), **kw):
        r = self.http.request(method, path, **kw)
        if r.status_code not in ok:
            try:
                why = r.json().get("error") or r.text
            except ValueError:
                why = r.text
            raise ServerError(r.status_code, f"{method} {path}: {r.status_code} {why}")
        return r.json() if r.content and r.headers.get("content-type", "").startswith("application/json") else None

    def requests(self):
        return {r["id"]: r for r in self.call("GET", "/admin/requests")}

    def place(self, rid, year=None, composer=None, title=None, album_id=None, movie=None):
        params = {k: v for k, v in {"year": year, "composer": composer, "title": title, "albumId": album_id, "movie": movie}.items() if v}
        try:
            place = self.call("GET", f"/admin/requests/{rid}/place", params=params)
        except ServerError as e:
            if e.status == 404:
                return None
            raise
        # Only albums in a library this script files into; their paths as paths inside MUSIC_ROOT. (The folder can be
        # unknown, which is enough for closing a request but not for filing music into it.)
        if not place or place.get("library") not in libraries(self.cfg):
            return None
        if place.get("folder") is not None:
            place["folder"] = in_library(self.cfg, place["library"], place["folder"])
        if place.get("sampleFile"):
            place["sampleFile"] = in_library(self.cfg, place["library"], place["sampleFile"])
        return place

    def scan(self):
        self.call("POST", "/library/scan", ok=(200, 202))
        started = time.time()
        while time.time() - started < 30 * 60:
            time.sleep(3)
            if not self.call("GET", "/library/status")["scanning"]:
                return True
        return False


class ServerError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


# ---------------------------------------------------------------- names


# The library keeps an album's background score beside it: "<Album> (<Year>) (Original Background Score)/", album tag
# "<Album> (Original Background Score)", which the scanner files as the album's score.
SCORE = " (Original Background Score)"
SCORE_ALBUM = re.compile(r"\b(bgm|bgms|ost|background\s+(score|music)|original\s+score)\b", re.I)
SCORE_TRACK = re.compile(r"\b(bgm|theme\s+music|background\s+(score|music)|instrumental|original\s+score)\b", re.I)


def score_base(title):
    """"Remo Special BGM Additional" → "Remo": the album whose background score this is."""
    t = re.sub(r"\b(original|background|score|music|bgms?|ost|special|additional|songs?|tracks?|soundtrack|and|\+)\b", " ", title, flags=re.I)
    t = re.sub(r"[()\[\]&+]", " ", t)
    return re.sub(r"\s+", " ", t).strip() or title


def loose(text):
    """For "is this song already there": spelling doubles fold ("Unakkaga" = "Unakaga")."""
    return re.sub(r"(.)\1+", r"\1", key(text))


def clean_album(title):
    """"Anbe Aaruyire (Ah Aah)" → "Anbe Aaruyire"; soundtrack labels go too."""
    t = re.sub(r"\s*[\(\[][^)\]]*[\)\]]\s*", " ", title or "")
    t = re.sub(r"\b(original motion picture soundtrack|soundtrack|songs?)\b", " ", t, flags=re.I)
    return re.sub(r"\s+", " ", t).strip() or (title or "").strip()


def clean_title(name, album):
    """"Aararai Kodi (Anbe Aaruyire)" → "Aararai Kodi": the album's name after a song adds nothing."""
    m = re.match(r"^(.*?)\s*\(([^)]*)\)\s*$", name or "")
    if m and key(m.group(2)) == key(album):
        return m.group(1).strip()
    return (name or "").strip()


def key(text):
    text = unicodedata.normalize("NFKD", text or "").lower()
    return re.sub(r"[^a-z0-9]+", "", text)


def people(text):
    """"Naresh Iyer, Madhushree" / "A & B" → "Naresh Iyer, Madhushree" (the way the scanner splits names)."""
    parts = re.split(r"\s*(?:,|&|\band\b)\s*", text or "")
    return ", ".join(p.strip() for p in parts if p.strip())


def safe_name(text):
    return re.sub(r'[\\/:*?"<>|\x00-\x1f]', "", text).strip().rstrip(".") or "Untitled"


def millis(duration):
    """"05:21" or "1:02:03" → milliseconds."""
    try:
        parts = [int(p) for p in str(duration).split(":")]
    except ValueError:
        return None
    total = 0
    for p in parts:
        total = total * 60 + p
    return total * 1000 if total else None


def year_of(match):
    meta = match.get("metadata") or {}
    if meta.get("year"):
        return int(meta["year"])
    poster = meta.get("poster") or (match.get("best_match") or {}).get("poster") or ""
    found = re.search(r"-((?:19|20)\d\d)\.(?:jpe?g|png|webp)$", poster)
    return int(found.group(1)) if found else None


# ---------------------------------------------------------------- downloading


def public_url(url):
    """Refuses anything but https to a public address: the server sits on the home network."""
    u = urlparse(url)
    if ALLOW_LOCAL and u.scheme in ("http", "https"):
        return
    if u.scheme != "https" or not u.hostname:
        raise ValueError(f"not an https link: {url}")
    for info in socket.getaddrinfo(u.hostname, u.port or 443):
        ip = ipaddress.ip_address(info[4][0])
        if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved or ip.is_multicast:
            raise ValueError(f"{u.hostname} is a local address")


def reachable(url):
    """Whether this machine can open a connection to [url]'s site; a reason if not."""
    u = urlparse(url)
    port = u.port or (443 if u.scheme == "https" else 80)
    try:
        addresses = sorted({i[4][0] for i in socket.getaddrinfo(u.hostname, port, type=socket.SOCK_STREAM)})
    except OSError as e:
        return f"{u.hostname} doesn't resolve here ({e})"
    for address in addresses:
        try:
            socket.create_connection((address, port), timeout=10).close()
            return None
        except OSError:
            continue
    return (f"can't connect to {u.hostname}: this machine's DNS gives {', '.join(addresses)} and none of them answer. "
            f"The network's DNS may be blocking the site")


def fetch(client, url, dest, tries=3, progress=None):
    """Downloads [url] to [dest], following redirects by hand (each hop checked). Returns bytes written."""
    last = None
    for attempt in range(tries):
        try:
            current = url
            for _ in range(6):
                public_url(current)
                with client.stream("GET", current, follow_redirects=False) as r:
                    if r.status_code in (301, 302, 303, 307, 308):
                        current = urljoin(current, r.headers["location"])
                        continue
                    r.raise_for_status()
                    size = 0
                    expected = int(r.headers.get("content-length") or 0)
                    part = dest.with_name(dest.name + ".part")
                    with open(part, "wb") as f:
                        for chunk in r.iter_bytes(64 * 1024):
                            size += len(chunk)
                            if size > MAX_FILE_BYTES:
                                raise ValueError("bigger than 60 MB")
                            f.write(chunk)
                            if progress:
                                progress(size, expected)
                    part.rename(dest)
                    return size
            raise ValueError("too many redirects")
        except httpx.HTTPStatusError as e:
            last = f"HTTP {e.response.status_code}"
            if e.response.status_code in (403, 404, 410):
                break  # a dead link stays dead
            time.sleep(2 * (attempt + 1))
            continue
        except (httpx.ConnectError, httpx.ConnectTimeout) as e:
            last = f"can't connect ({type(e).__name__})"
            if attempt >= 1:
                break  # an unreachable site won't start answering in a few seconds
            time.sleep(2)
            continue
        except (httpx.HTTPError, ValueError, OSError) as e:
            last = str(e).splitlines()[0] if str(e) else type(e).__name__
            if isinstance(e, ValueError) and ("local address" in last or "not an https" in last):
                break
            time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"couldn't download: {last}")


def probe(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration,bit_rate:format_tags:stream=codec_name,codec_type",
                          "-of", "json", str(path)], capture_output=True, text=True, stdin=subprocess.DEVNULL)
    if out.returncode != 0:
        return None
    data = json.loads(out.stdout or "{}")
    fmt = data.get("format") or {}
    audio = [s for s in data.get("streams", []) if s.get("codec_type") == "audio"]
    if not audio:
        return None
    return {
        "durationMs": int(float(fmt.get("duration", 0)) * 1000),
        "bitrate": int(int(fmt.get("bit_rate", 0)) / 1000),
        "codec": audio[0].get("codec_name"),
        "tags": {k.lower(): v for k, v in (fmt.get("tags") or {}).items()},
    }


def looks_like_mp3(path):
    head = path.read_bytes()[:3]
    return head == b"ID3" or (len(head) >= 2 and head[0] == 0xFF and head[1] & 0xE0 == 0xE0)


def tag(src, dest, tags, cover=None):
    """Rewrites [src] into [dest] with only our tags (the site's own name and cover go) and the cover."""
    cmd = ["ffmpeg", "-v", "error", "-nostdin", "-y", "-i", str(src)]
    if cover:
        cmd += ["-i", str(cover), "-map", "0:a", "-map", "1:v", "-c", "copy", "-disposition:v", "attached_pic",
                "-metadata:s:v", "title=Album cover", "-metadata:s:v", "comment=Cover (front)"]
    else:
        cmd += ["-map", "0:a", "-c", "copy"]
    cmd += ["-map_metadata", "-1", "-id3v2_version", "3", "-write_id3v1", "0"]
    for k, v in tags.items():
        if v:
            cmd += ["-metadata", f"{k}={v}"]
    subprocess.run(cmd + [str(dest)], check=True, capture_output=True, stdin=subprocess.DEVNULL)


def lyrics(client, title, artist, album, duration_ms):
    """Synced (LRC) or plain lyrics from LRCLIB: ("synced", text), ("plain", text) or ("none", None)."""
    secs = round((duration_ms or 0) / 1000)
    found = None
    try:
        r = client.get(f"{LRCLIB}/api/get", params={"track_name": title, "artist_name": artist.split(",")[0].strip(),
                                                    "album_name": album, "duration": secs}, timeout=20)
        if r.status_code == 200:
            found = r.json()
        else:
            r = client.get(f"{LRCLIB}/api/search", params={"track_name": title, "album_name": album}, timeout=20)
            if r.status_code == 200:
                close = [x for x in r.json() if not secs or abs((x.get("duration") or 0) - secs) <= 3]
                found = next((x for x in close if x.get("syncedLyrics")), close[0] if close else None)
    except (httpx.HTTPError, ValueError):
        return "none", None
    if found and found.get("syncedLyrics"):
        return "synced", found["syncedLyrics"]
    if found and found.get("plainLyrics"):
        return "plain", found["plainLyrics"]
    return "none", None


# ---------------------------------------------------------------- handing in


def album_tags_from(folder_file):
    """The album tags of a file already in the album, so a new song joins it (the scanner groups by album + year)."""
    return tags_of((probe(folder_file) or {"tags": {}})["tags"])


def tags_of(t):
    return {
        "album": t.get("album"),
        "date": t.get("date") or t.get("year"),
        "album_artist": t.get("album_artist") or t.get("album artist"),
        "composer": t.get("composer"),
        "genre": t.get("genre"),
        "discsubtitle": t.get("discsubtitle") or t.get("tsst"),
    }


def prepare(cfg, server, client, match, request, covered_by, notes, dry):
    rid = match["request_id"]
    meta = match.get("metadata") or {}
    best = match.get("best_match") or {}
    base = best.get("url") or ""
    album = clean_album(best.get("title") or request["item"]["movie"])
    year = year_of(match)
    composer = people(meta.get("music") or "")
    lyricists = people(meta.get("lyricists") or "")
    cover_url = urljoin(base, meta.get("poster") or best.get("poster") or "") or None
    is_song = request["item"]["kind"] == "song"
    language = (meta.get("language") or "").strip().lower() or cfg["LIBRARY_NAME"]
    if language not in libraries(cfg):
        notes.append(f"{language.title()} has no library of its own (LANGUAGES); it goes with {cfg['LIBRARY_NAME'].title()}")
        language = cfg["LIBRARY_NAME"]
    tracks = match.get("tracks") or []
    total = int(meta.get("track_count") or len(tracks) or 0)

    # A whole album of background score ("Vishwaroopam II BGM") is filed as that album's score.
    score_album = not is_song and bool(SCORE_ALBUM.search(best.get("title") or ""))
    if score_album:
        album = score_base(album)
    review = {"album": album, "year": year, "composer": composer or None, "lyricists": lyricists or None,
              "coverUrl": cover_url, "source": base or None, "score": match.get("match_score"), "notes": notes}
    if covered_by:
        review.update(coveredBy=covered_by, destination="new", tracks=[])
        return review, None, None

    # Which files, and their track numbers.
    if is_song:
        song = match.get("matched_song") or {}
        if not song:
            raise RuntimeError("a song request without matched_song")
        position = next((i for i, t in enumerate(tracks) if key(t.get("name")) == key(song.get("name"))), None)
        picked = [(position + 1 if position is not None else 1, song)]
    else:
        picked = list(enumerate(tracks, 1))
        if total and len(tracks) != total:
            notes.append(f"The page lists {len(tracks)} songs, the album should have {total}")
    if not picked:
        raise RuntimeError("no tracks to download")

    say(f"      matched   {album}{f' ({year})' if year else ''}" + (f" · music {composer}" if composer else "")
        + (f" · score {round(match['match_score'])}" if match.get("match_score") is not None else "")
        + (f" · {len(picked)} of {len(tracks)} songs" if is_song else f" · {len(picked)} songs") + f" · {language.title()}")

    # Where it goes: the album's own folder if it's in the library (same title, and its year or composer).
    title_for_place = clean_title(picked[0][1].get("name"), album) if is_song else None
    place = server.place(rid, year, composer, title_for_place, movie=album if score_album else None)
    existing = None
    suggested = in_library(cfg, language, safe_name(f"{album} ({year})" if year else album))
    if place and place.get("folder") is None:
        place = None  # in the library, but its files can't be found: file it as a new album
    if place and place.get("library") != language:
        notes.append(f"Found {album} in the {place['library'].title()} library though the page says {language.title()}; it joins that album")
    if place:
        existing = place
        review.update(destination="existing", destinationAlbumId=place["albumId"], destinationFolder=place["folder"],
                      suggestedFolder=suggested, scoreFolder=place["folder"] + SCORE)
        # Already there: a song that's in the album, or every song of the album, needs no download.
        if is_song and place.get("songId"):
            return review, existing, "already"
        if not score_album:
            have = {loose(t) for t in place.get("songs") or []}
            kept = [(n, t) for n, t in picked if loose(clean_title(t.get("name"), album)) not in have]
            review["skipped"] = [clean_title(t.get("name"), album) for n, t in picked if (n, t) not in kept]
            picked = kept
            if not picked:
                return review, existing, "already"
    else:
        review.update(destination="new", destinationFolder=suggested, suggestedFolder=suggested, scoreFolder=suggested + SCORE)
        if music_exists(cfg, suggested):
            notes.append(f"A folder called {suggested} is already in the library; the songs will be added to it")
    say(f"      goes to   {'joins ' + review['destinationFolder'] + ' (already in the library)' if existing else 'new folder ' + review['destinationFolder']}"
        + (f", as its background score ({review['scoreFolder']})" if score_album else ""))
    if review.get("skipped"):
        say(f"      skipping  {len(review['skipped'])} already in the library: {', '.join(review['skipped'])}")
    if dry:
        review["tracks"] = [{"n": n, "file": "", "title": clean_title(t.get("name"), album)} for n, t in picked]
        return review, existing, None

    work = Path(cfg["WORK_FOLDER"]) / str(rid)
    shutil.rmtree(work, ignore_errors=True)
    raw = work / ".raw"
    raw.mkdir(parents=True)

    cover = None
    if cover_url:
        say("      cover     downloading…", end="")
        try:
            cover = raw / "cover.jpg"
            fetch(client, cover_url, cover)
            say(" ✓")
        except RuntimeError as e:
            cover = None
            notes.append(f"No cover: {e}")
            say(f" ✗ {e}")

    base_tags = {"album": album, "date": str(year) if year else None, "album_artist": composer, "composer": composer,
                 "genre": language.title()}
    if existing:
        say("      tags      reading the album's tags from the library…", end="")
        copied = {k: v for k, v in music_tags(cfg, existing["sampleFile"]).items() if v}
        base_tags.update(copied)
        say(f" ✓ {copied.get('album', album)} ({copied.get('date', year)})")

    out = []
    width = max(2, len(str(total or len(tracks))))
    for i, (n, t) in enumerate(picked, 1):
        title = clean_title(t.get("name"), album)
        head = f"      {i}/{len(picked)} {title[:34]:<34}"
        expected = millis(t.get("duration"))
        downloaded = None
        for quality in ("download320", "download128"):
            link = t.get(quality)
            if not link:
                continue
            dest = raw / f"{n:0{width}d}.mp3"
            kbps = quality[8:]
            say_progress = (lambda got, size, kbps=kbps: live(f"{head} downloading {kbps} kbps… {megabytes(got)}"
                                                               + (f" of {megabytes(size)}" if size else "")))
            if not TTY:
                say(f"{head} downloading {kbps} kbps…")
            try:
                fetch(client, urljoin(base, link), dest, progress=say_progress)
            except RuntimeError as e:
                notes.append(f"{title}: {kbps} kbps link failed ({e})")
                live(f"{head} {kbps} kbps failed ({e})")
                say() if TTY else say(f"{head} {kbps} kbps failed ({e})")
                continue
            info = probe(dest)
            problem = None
            if not looks_like_mp3(dest) or not info:
                problem = "not an mp3"
            elif expected and abs(info["durationMs"] - expected) > LENGTH_SLACK_MS:
                problem = f"{info['durationMs'] // 1000} s long, the page says {expected // 1000} s"
            if problem:
                notes.append(f"{title}: {quality[8:]} kbps file is {problem}")
                dest.unlink(missing_ok=True)
                continue
            if quality == "download320" and info["bitrate"] < 256:
                notes.append(f"{title}: the 320 kbps file is really {info['bitrate']} kbps")
            if quality == "download128":
                notes.append(f"{title}: only the 128 kbps file worked")
            downloaded = (dest, info)
            break
        if not downloaded:
            raise RuntimeError(f"couldn't get a good file for {title}")
        dest, info = downloaded
        name = safe_name(f"{n:0{width}d} - {title}")
        singers = people(t.get("artist") or "")
        is_score = score_album or bool(SCORE_TRACK.search(title))
        tags = dict(base_tags, title=title, artist=singers, lyricist=lyricists or None,
                    track=f"{n}/{total}" if total else str(n), disc="1/1")
        if is_score:
            tags.update(album=base_tags["album"] + SCORE, discsubtitle=None)
        live(f"{head} tagging…")
        tag(dest, work / f"{name}.mp3", tags, cover)
        live(f"{head} looking for lyrics…")
        kind, text = lyrics(client, title, singers, album, info["durationMs"])
        if text:
            (work / f"{name}.{'lrc' if kind == 'synced' else 'txt'}").write_text(text.rstrip() + "\n", encoding="utf-8")
        size = (work / f"{name}.mp3").stat().st_size
        done = (f"{head} ✓ {info['durationMs'] // 60000}:{info['durationMs'] // 1000 % 60:02d} · {info['bitrate']} kbps · "
                f"{megabytes(size)} · {dict(synced='synced lyrics', plain='plain lyrics').get(kind, 'no lyrics')}")
        live(done)
        say() if TTY else say(done)
        out.append({"n": n, "file": f"{name}.mp3", "title": title, "singers": singers or None,
                    "durationMs": info["durationMs"], "bitrate": info["bitrate"], "lyrics": kind, "score": is_score})
    if cover:
        shutil.copy(cover, work / "cover.jpg")
    shutil.rmtree(raw)
    review["tracks"] = out
    if any(t["score"] for t in out):
        say(f"      score     {sum(t['score'] for t in out)} background score track(s) go to {review['scoreFolder']}")
    return review, existing, None


def run(cfg, batch, dry):
    server = Server(cfg)
    matches = json.loads(Path(batch).read_text())
    if isinstance(matches, dict):
        matches = [matches]
    remote = cfg.get("REMOTE")
    steps = 4 if remote else 3
    say(f"Jukebox fulfil · {len(matches)} request(s) in {Path(batch).name}{' · dry run: nothing is downloaded or changed' if dry else ''}")
    say()

    say(f"Step 1/{steps}  Checking the requests in Jukebox…", end="")
    requests = server.requests()
    say(f" ✓ {sum(1 for r in requests.values() if r['status'] == 'open')} open")
    # Whole albums first: a song from an album in the same batch comes with it.
    matches.sort(key=lambda m: 0 if (requests.get(m.get("request_id")) or {}).get("item", {}).get("kind") == "movie" else 1)

    if dry:
        say(f"Step 2/{steps}  Checking the download site… skipped (dry run)")
    else:
        sites = {urlparse(urljoin((m.get("best_match") or {}).get("url") or "", l)).scheme + "://" + urlparse(urljoin((m.get("best_match") or {}).get("url") or "", l)).netloc
                 for m in matches if m.get("match_found")
                 for t in ([m["matched_song"]] if m.get("matched_song") else m.get("tracks") or [])
                 for l in [t.get("download320") or t.get("download128") or ""] if l}
        say(f"Step 2/{steps}  Checking the download site…", end="")
        for site in sorted(sites):
            why = reachable(site)
            if why:
                say(" ✗")
                sys.exit(f"Stopped before downloading anything: {why}.")
        say(f" ✓ {', '.join(urlparse(x).netloc for x in sorted(sites)) or 'nothing to download'}")

    if remote:
        say(f"Step 3/{steps}  Connecting to {remote} (if it asks for a password, this is the only time)…")
        if not connect(cfg):
            sys.exit(f"Couldn't connect to {remote} with ssh; check that `ssh {remote}` works.")
        say(f"           ✓ connected")
    say(f"Step {steps}/{steps}  Filling the requests")

    albums = {}
    handed, report = [], []
    with httpx.Client(timeout=httpx.Timeout(60, connect=15), headers={"User-Agent": AGENT}) as client:
        for i, match in enumerate(matches, 1):
            rid = match.get("request_id")
            request = requests.get(rid)
            label = f"#{rid} {match.get('title') or ''} ({match.get('movie') or ''})"
            kind = (request or {}).get("item", {}).get("kind")
            say()
            say(f"  [{i}/{len(matches)}] {label}" + (f" · {'album' if kind == 'movie' else 'song'} · asked by {', '.join(request['askedBy'])}" if request else ""))
            if not request or request["status"] != "open":
                report.append(f"skipped  {label}: not open any more")
                say("      skipped   not open any more")
                continue
            if request.get("stage") and not dry:
                report.append(f"skipped  {label}: already handed in ({request['stage']})")
                say(f"      skipped   already handed in ({request['stage']})")
                continue
            if not match.get("match_found"):
                report.append(f"skipped  {label}: the matcher found nothing")
                say("      skipped   the matcher found nothing")
                continue
            album_key = (key(clean_album((match.get("best_match") or {}).get("title") or "")), year_of(match))
            covered_by = albums.get(album_key) if kind == "song" else None
            if covered_by:
                say(f"      comes with #{covered_by} (its album is in this batch): nothing to download")
            notes = []
            try:
                review, existing, outcome = prepare(cfg, server, client, match, request, covered_by, notes, dry)
                if outcome == "already":
                    # Everything asked for is already in the library: close it with that album, nothing to download.
                    if dry:
                        report.append(f"would close {label}: already in the library ({review['destinationFolder']})")
                        say("      would close it: already in the library")
                        continue
                    place = existing
                    server.call("POST", f"/admin/requests/{rid}/done",
                                json={k: v for k, v in {"albumId": place["albumId"], "songId": place.get("songId")}.items() if v})
                    report.append(f"closed   {label}: already in the library ({review['destinationFolder']})")
                    say(f"      ✓ closed: already in the library ({review['destinationFolder']}); the people who asked were told")
                    continue
            except Exception as e:  # one bad request doesn't stop the batch
                shutil.rmtree(Path(cfg["WORK_FOLDER"]) / str(rid), ignore_errors=True)
                report.append(f"failed   {label}: {e}")
                say(f"      ✗ failed  {e}")
                continue
            if kind == "movie":
                albums[album_key] = rid
            where = (f"comes with #{covered_by}" if covered_by else
                     f"joins {review['destinationFolder']}" if existing else f"new folder {review['destinationFolder']}")
            lyr = sum(1 for t in review["tracks"] if t.get("lyrics") not in (None, "none"))
            line = f"{'would hand in' if dry else 'handed in'} {label}: {len(review['tracks'])} song(s), {where}"
            if review["tracks"] and not dry:
                line += f", lyrics for {lyr}"
            report.append(line + "".join(f"\n           ! {n}" for n in notes))
            for n in notes:
                say(f"      !         {n}")
            if dry:
                say("      would hand this in for your check")
                continue
            work = Path(cfg["WORK_FOLDER"]) / str(rid)
            work.mkdir(parents=True, exist_ok=True)
            (work / "review.json").write_text(json.dumps({"request": rid, "kind": request["item"]["kind"], "review": review,
                                                          "match": match}, indent=1, ensure_ascii=False))
            if remote:
                files = [f for f in work.iterdir() if f.is_file() and not f.name.startswith(".")]
                say(f"      sending   {len(files)} file{'' if len(files) == 1 else 's'} ({megabytes(sum(f.stat().st_size for f in files))}) to {remote}…", end="")
                try:
                    send_to_server(cfg, rid)
                    say(" ✓")
                except (RuntimeError, subprocess.CalledProcessError) as e:
                    say(" ✗")
                    report[-1] = f"failed   {label}: {e} (the download is kept in {work})"
                    say(f"      ✗ failed  {e} (the download is kept in {work})")
                    continue
            server.call("POST", f"/admin/requests/{rid}/review", json=review)
            handed.append(rid)
            say(f"      ✓ handed in · {'closes when #' + str(covered_by) + ' is approved' if covered_by else 'waiting for your check'}")
    if handed:
        server.call("POST", "/admin/requests/reviews-ready", json={"ids": handed})

    say()
    say("Summary")
    say("\n".join("  " + line for line in report) or "  Nothing in the file.")
    say()
    if handed:
        say(f"Next: open Jukebox → Requests → Everyone's → Ready to check, listen, and Approve or Reject ({len(handed)} handed in).")
        say("      Approved music is moved into the library, the library rescanned and the askers told, by the server.")
    elif dry:
        say("Next: run the same command without --dry-run to download and hand them in.")
    return handed


# ---------------------------------------------------------------- acting on decisions


def move_in(cfg, rid, review, existing_place, undo):
    """Moves request [rid]'s files into the library: songs to the album's folder, background score to its score
    folder. Returns the album folder (inside MUSIC_ROOT)."""
    work = Path(cfg["REQUESTS_FOLDER"]) / str(rid)
    music = Path(cfg["ROOT"])
    if existing_place:
        folder = existing_place["folder"]
        # The album's own tags win, so the songs join it (also when the admin picked it, or it arrived since).
        copied = {k: v for k, v in music_tags(cfg, existing_place["sampleFile"]).items() if v and k != "composer"}
        for t in review["tracks"]:
            f = work / t["file"]
            tags = dict(copied)
            if t.get("score"):
                tags["album"] = re.sub(r"\s*\(original background score\)\s*$", "", tags.get("album", review["album"]), flags=re.I) + SCORE
                tags.pop("discsubtitle", None)
            retagged = work / f".{t['file']}"
            subprocess.run(["ffmpeg", "-v", "error", "-nostdin", "-y", "-i", str(f), "-map", "0", "-c", "copy",
                            "-id3v2_version", "3"] + sum((["-metadata", f"{k}={v}"] for k, v in tags.items()), []) + [str(retagged)],
                           check=True, capture_output=True, stdin=subprocess.DEVNULL)
            retagged.replace(f)
    else:
        folder = review["destinationFolder"]
    score_folder = folder + SCORE
    where = {}  # file stem → folder
    for t in review["tracks"]:
        where[Path(t["file"]).stem] = score_folder if t.get("score") else folder
    used = set()
    for f in sorted(work.iterdir()):
        if f.name.startswith(".") or f.name == "review.json" or not f.is_file():
            continue
        if f.name == "cover.jpg":
            targets = [x for x in {folder, *where.values()} if not (music / x / "cover.jpg").exists()]
            for x in targets:
                (music / x).mkdir(parents=True, exist_ok=True)
                shutil.copy(f, music / x / "cover.jpg")
                undo.write(json.dumps({"request": rid, "file": f"{x}/cover.jpg"}) + "\n")
            continue
        dest = music / where.get(f.stem, folder)
        dest.mkdir(parents=True, exist_ok=True)
        used.add(dest)
        target = dest / f.name
        if target.exists():
            target = dest / f"{f.stem} (request {rid}){f.suffix}"
        shutil.move(str(f), str(target))
        undo.write(json.dumps({"request": rid, "file": str(target.relative_to(music))}) + "\n")
        undo.flush()
    return folder


def matched_title(cfg, rid):
    """The song's name as the matcher found it (from the hand-in), for finding it after the scan."""
    try:
        saved = json.loads((Path(cfg["REQUESTS_FOLDER"]) / str(rid) / "review.json").read_text())
    except (OSError, ValueError):
        return None
    song = (saved.get("match") or {}).get("matched_song") or {}
    return clean_title(song.get("name"), saved.get("review", {}).get("album", "")) or None


def act(cfg, server, waiting_for):
    """One look at the decisions for the requests in [waiting_for]."""
    requests = server.requests()
    approved = []
    for rid in sorted(waiting_for):
        r = requests.get(rid)
        work = Path(cfg["REQUESTS_FOLDER"]) / str(rid)
        if not r or r["status"] != "open" or not r.get("stage"):
            shutil.rmtree(work, ignore_errors=True)  # closed, declined or cleared meanwhile: nothing to file
            continue
        if r["stage"] == "rejected":
            shutil.rmtree(work, ignore_errors=True)
            server.call("DELETE", f"/admin/requests/{rid}/review")
            log(f"#{rid} rejected: download deleted, the request waits again")
        elif r["stage"] == "approved":
            approved.append(r)
    if not approved:
        return

    music = Path(cfg["ROOT"])
    logs = Path(cfg["REQUESTS_FOLDER"]) / "undo"
    logs.mkdir(exist_ok=True)
    undo_path = logs / f"{datetime.now():%Y%m%d-%H%M%S}.jsonl"
    moved_any = False
    with open(undo_path, "a") as undo:
        for r in approved:
            rid = r["id"]
            work = Path(cfg["REQUESTS_FOLDER"]) / str(rid)
            review = r.get("review") or {}
            if review.get("coveredBy") or (work / ".moved").exists():
                continue  # comes with its album, or already in the library and waiting to be closed
            first_title = review["tracks"][0]["title"] if review.get("tracks") and r["item"]["kind"] == "song" else None
            if review.get("destination") == "existing" and review.get("destinationAlbumId"):
                place = server.place(rid, album_id=review["destinationAlbumId"])  # the album it was checked against
            elif review.get("destinationChosen"):
                place = None  # the admin chose a new album
            else:
                place = server.place(rid, review.get("year"), review.get("composer"), first_title, movie=review.get("album"))
            if place and place.get("folder") is None:
                place = None
            try:
                folder = move_in(cfg, rid, review, place, undo)
            except Exception as e:
                log(f"#{rid} couldn't be moved into the library: {e}")
                (work / ".gave_up").write_text(str(e))
                continue
            work.mkdir(parents=True, exist_ok=True)
            (work / ".moved").write_text(folder)
            moved_any = True
            log(f"#{rid} approved: moved into {folder}")
    if undo_path.stat().st_size == 0:
        undo_path.unlink()
    else:
        log(f"Undo log: {undo_path}")

    if moved_any:
        log("Rescanning the library…")
        if not server.scan():
            log("The scan is taking over 30 minutes; trying to close the requests anyway")
    for r in approved:
        rid = r["id"]
        work = Path(cfg["REQUESTS_FOLDER"]) / str(rid)
        review = r.get("review") or {}
        if (work / ".gave_up").exists() or not ((work / ".moved").exists() or review.get("coveredBy")):
            continue
        song = r["item"]["kind"] == "song"
        title = None
        if song:
            title = (review["tracks"][0]["title"] if review.get("tracks") else matched_title(cfg, rid)) or r["item"]["title"]
        if review.get("destination") == "existing" and review.get("destinationAlbumId"):
            place = server.place(rid, title=title, album_id=review["destinationAlbumId"])
        else:
            place = server.place(rid, review.get("year"), review.get("composer"), title, movie=review.get("album"))
        if not place or (song and not place.get("songId")):
            tries = int((work / ".close_tries").read_text()) + 1 if (work / ".close_tries").exists() else 1
            (work / ".close_tries").write_text(str(tries))
            if tries >= 4:  # about two minutes after the scan: the library view has long caught up
                (work / ".gave_up").write_text("not found after the scan")
                log(f"#{rid} is in the library but couldn't be matched to the request; close it in the app (Requests → Everyone's → Match album)")
            continue
        server.call("POST", f"/admin/requests/{rid}/done",
                    json={k: v for k, v in {"albumId": place["albumId"], "songId": place.get("songId")}.items() if v})
        shutil.rmtree(work, ignore_errors=True)
        log(f"#{rid} closed: the people who asked have been told")
    if moved_any and cfg.get("ANALYZER"):
        subprocess.run(["docker", "kill", "-s", "USR1", cfg["ANALYZER"]], capture_output=True)
        log("Analyzer woken for the new songs")


def wait(cfg):
    """Acts on decisions until nothing handed in is left undecided (or a week has passed)."""
    folder = Path(cfg["REQUESTS_FOLDER"])
    folder.mkdir(parents=True, exist_ok=True)
    lock = open(folder / ".waiting.lock", "w")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        log("Another fulfil.py is already waiting for decisions; it will pick these up too")
        return
    server = Server(cfg)
    started = time.time()
    while time.time() - started < WAIT_DAYS * 86400:
        try:
            requests = server.requests()
            for p in folder.iterdir():  # given up on, then closed by hand in the app: nothing left to do
                if p.name.isdigit() and (p / ".gave_up").exists() and (requests.get(int(p.name)) or {}).get("status") != "open":
                    shutil.rmtree(p, ignore_errors=True)
            gave_up = {int(p.name) for p in folder.iterdir() if p.name.isdigit() and (p / ".gave_up").exists()}
            pending = {int(p.name) for p in folder.iterdir() if p.is_dir() and p.name.isdigit()}
            pending |= {rid for rid, r in requests.items() if r.get("stage") and r["status"] == "open"}
            pending -= gave_up
            if not pending:
                log("Every request is decided. Done." + (f" Needs a look: {sorted(gave_up)} (see above)" if gave_up else ""))
                return
            act(cfg, server, pending)
        except (httpx.HTTPError, ServerError) as e:
            log(f"Couldn't reach Jukebox, trying again: {e}")
        time.sleep(POLL_SECONDS)
    log(f"Stopped waiting after {WAIT_DAYS} days; run `fulfil.py resume` to carry on")


def wait_in_background(cfg):
    """Keeps waiting after the terminal (or ssh session) closes, logging to REQUESTS_FOLDER/fulfil.log."""
    out = open(Path(cfg["REQUESTS_FOLDER"]) / "fulfil.log", "a")
    subprocess.Popen([sys.executable, os.path.abspath(__file__), "wait"], stdout=out, stderr=out,
                     stdin=subprocess.DEVNULL, start_new_session=True, env=os.environ.copy())
    print(f"Waiting for your decisions in the background (log: {out.name}). Check them on the Requests page.", flush=True)


def start_remote_waiter(cfg):
    """Starts the waiting half on the server, which files approved music while the laptop is off."""
    say(f"Starting the filing half on {cfg['REMOTE']}…", end="")
    done = ssh(cfg, [cfg["REMOTE_FULFIL"], "resume"], capture_output=True, text=True)
    if done.returncode != 0:
        say(" ✗")
        say(f"Couldn't start it; run: ssh {cfg['REMOTE']} {cfg['REMOTE_FULFIL']} resume")
        return
    say(" ✓ it waits for your decisions there, so this laptop can sleep now.")
    say(f"      Its log: ssh {cfg['REMOTE']} tail -f {cfg['REQUESTS_FOLDER'].rstrip('/')}/fulfil.log")


def status(cfg):
    rows = [r for r in Server(cfg).requests().values() if r.get("stage")]
    for r in rows:
        review = r.get("review") or {}
        print(f"#{r['id']:<5} {r['stage']:<9} {review.get('album', r['item']['movie'])} ({review.get('year') or '?'})"
              f"  {len(review.get('tracks') or [])} song(s)  asked by {', '.join(r['askedBy'])}")
    print(f"{len(rows)} in progress" if rows else "Nothing waiting.")


def undo(cfg, path):
    music = Path(cfg["ROOT"])
    removed = 0
    for line in Path(path).read_text().splitlines():
        f = music / json.loads(line)["file"]
        if f.exists():
            f.unlink()
            removed += 1
            if f.parent != music and not any(f.parent.iterdir()):
                f.parent.rmdir()
    print(f"Removed {removed} file(s). Run a library scan (or wait for the hourly one) to update Jukebox.")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    r = sub.add_parser("run")
    r.add_argument("batch")
    r.add_argument("--dry-run", action="store_true")
    r.add_argument("--foreground", action="store_true", help="wait for the decisions here instead of in the background")
    sub.add_parser("resume")
    sub.add_parser("wait")
    sub.add_parser("status")
    u = sub.add_parser("undo")
    u.add_argument("log")
    args = p.parse_args()
    cfg = settings()
    remote = cfg.get("REMOTE")
    if args.command == "run":
        try:
            handed = run(cfg, args.batch, args.dry_run)
            if handed and not args.dry_run:
                if remote:
                    start_remote_waiter(cfg)
                else:
                    wait(cfg) if args.foreground else wait_in_background(cfg)
        finally:
            if remote:
                disconnect(cfg)
    elif args.command == "resume":
        if remote:
            if not connect(cfg):
                sys.exit(f"Couldn't connect to {remote} with ssh; check that `ssh {remote}` works.")
            try:
                start_remote_waiter(cfg)
            finally:
                disconnect(cfg)
        else:
            wait_in_background(cfg)
    elif args.command == "wait":
        if remote:
            sys.exit("The waiting half runs on the server: fulfil.py resume")
        wait(cfg)
    elif args.command == "status":
        status(cfg)
    elif args.command == "undo":
        if remote:
            sys.exit(f"Run undo on the server: ssh {remote} {cfg['REMOTE_FULFIL']} undo <log>")
        undo(cfg, args.log)


if __name__ == "__main__":
    main()
