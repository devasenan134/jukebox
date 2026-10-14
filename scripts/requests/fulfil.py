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
    MUSIC_FOLDER     the library folder on the server (the one mounted at /music)
    LIBRARY_NAME     its name in LIBRARIES (tamil)
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


def log(*parts):
    print(datetime.now().strftime("%H:%M:%S"), *parts, flush=True)


# ---------------------------------------------------------------- settings and the server


def settings():
    path = Path(os.environ.get("FULFIL_ENV", Path.home() / ".config/jukebox-fulfil.env"))
    values = {}
    if path.is_file():
        for line in path.read_text().splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.split("=", 1)
                values[k.strip()] = v.strip().strip('"')
    values.update({k: v for k, v in os.environ.items() if k in KEYS})
    optional = {"ANALYZER", "REMOTE", "REMOTE_FULFIL", "STAGING_FOLDER"}
    missing = [k for k in KEYS if k not in optional and not values.get(k)]
    if values.get("REMOTE") and not values.get("REMOTE_FULFIL"):
        missing.append("REMOTE_FULFIL")
    if missing:
        sys.exit(f"Missing settings in {path}: {', '.join(missing)}")
    # Where downloads are put together: the waiting folder itself on the server, a staging folder on the laptop.
    values["WORK_FOLDER"] = (os.path.expanduser(values.get("STAGING_FOLDER") or "~/.cache/jukebox-fulfil")
                             if values.get("REMOTE") else values["REQUESTS_FOLDER"])
    return values


KEYS = ["JUKEBOX_URL", "JUKEBOX_TOKEN", "MUSIC_FOLDER", "LIBRARY_NAME", "REQUESTS_FOLDER", "ANALYZER",
        "REMOTE", "REMOTE_FULFIL", "STAGING_FOLDER"]


# ---------------------------------------------------------------- the server's files, from either machine


def ssh(cfg, command, **kw):
    """Runs [command] (a list of words) on the server, quoted for its shell."""
    return subprocess.run(["ssh", cfg["REMOTE"], " ".join(shlex.quote(c) for c in command)], stdin=subprocess.DEVNULL, **kw)


def music_exists(cfg, relative):
    path = str(Path(cfg["MUSIC_FOLDER"]) / relative)
    if cfg.get("REMOTE"):
        return ssh(cfg, ["test", "-e", path], capture_output=True).returncode == 0
    return Path(path).exists()


def music_tags(cfg, relative):
    """The album tags of a file in the library (on the server)."""
    path = str(Path(cfg["MUSIC_FOLDER"]) / relative)
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
    done = subprocess.run(["rsync", "-a", "--delete", "--exclude", ".*", f"{local}/", f"{cfg['REMOTE']}:{shlex.quote(remote)}/"],
                          stdin=subprocess.DEVNULL, capture_output=True, text=True)
    if done.returncode != 0:
        raise RuntimeError(f"rsync to {cfg['REMOTE']} failed: {done.stderr.strip()[:300]}")
    shutil.rmtree(local, ignore_errors=True)


class Server:
    def __init__(self, cfg):
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

    def place(self, rid, year=None, composer=None, title=None):
        params = {k: v for k, v in {"year": year, "composer": composer, "title": title}.items() if v}
        try:
            return self.call("GET", f"/admin/requests/{rid}/place", params=params)
        except ServerError as e:
            if e.status == 404:
                return None
            raise

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


def fetch(client, url, dest, tries=3):
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
                    part = dest.with_name(dest.name + ".part")
                    with open(part, "wb") as f:
                        for chunk in r.iter_bytes(64 * 1024):
                            size += len(chunk)
                            if size > MAX_FILE_BYTES:
                                raise ValueError("bigger than 60 MB")
                            f.write(chunk)
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
    tracks = match.get("tracks") or []
    total = int(meta.get("track_count") or len(tracks) or 0)

    review = {"album": album, "year": year, "composer": composer or None, "lyricists": lyricists or None,
              "coverUrl": cover_url, "source": base or None, "score": match.get("match_score"), "notes": notes}
    if covered_by:
        review.update(coveredBy=covered_by, destination="new", tracks=[])
        return review, None

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

    # Where it goes: the album's own folder if it's in the library (same title, and its year or composer).
    title_for_place = clean_title(picked[0][1].get("name"), album) if is_song else None
    place = server.place(rid, year, composer, title_for_place)
    existing = None
    if place and place.get("library") == cfg["LIBRARY_NAME"] and place.get("folder") is not None:
        existing = place
        review.update(destination="existing", destinationAlbumId=place["albumId"], destinationFolder=place["folder"])
    else:
        folder = safe_name(f"{album} ({year})" if year else album)
        review.update(destination="new", destinationFolder=folder)
        if music_exists(cfg, folder):
            notes.append(f"A folder called {folder} is already in the library; the songs will be added to it")
    if dry:
        review["tracks"] = [{"n": n, "file": "", "title": clean_title(t.get("name"), album)} for n, t in picked]
        return review, existing

    work = Path(cfg["WORK_FOLDER"]) / str(rid)
    shutil.rmtree(work, ignore_errors=True)
    raw = work / ".raw"
    raw.mkdir(parents=True)

    cover = None
    if cover_url:
        try:
            cover = raw / "cover.jpg"
            fetch(client, cover_url, cover)
        except RuntimeError as e:
            cover = None
            notes.append(f"No cover: {e}")

    base_tags = {"album": album, "date": str(year) if year else None, "album_artist": composer, "composer": composer,
                 "genre": meta.get("language") or "Tamil"}
    if existing:
        copied = {k: v for k, v in music_tags(cfg, existing["sampleFile"]).items() if v}
        base_tags.update(copied)

    out = []
    width = max(2, len(str(total or len(tracks))))
    for i, (n, t) in enumerate(picked, 1):
        title = clean_title(t.get("name"), album)
        log(f"  {i}/{len(picked)} {title}")
        expected = millis(t.get("duration"))
        downloaded = None
        for quality in ("download320", "download128"):
            link = t.get(quality)
            if not link:
                continue
            dest = raw / f"{n:0{width}d}.mp3"
            try:
                fetch(client, urljoin(base, link), dest)
            except RuntimeError as e:
                notes.append(f"{title}: {quality[8:]} kbps link failed ({e})")
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
        tags = dict(base_tags, title=title, artist=singers, lyricist=lyricists or None,
                    track=f"{n}/{total}" if total else str(n), disc="1/1")
        tag(dest, work / f"{name}.mp3", tags, cover)
        kind, text = lyrics(client, title, singers, album, info["durationMs"])
        if text:
            (work / f"{name}.{'lrc' if kind == 'synced' else 'txt'}").write_text(text.rstrip() + "\n", encoding="utf-8")
        out.append({"n": n, "file": f"{name}.mp3", "title": title, "singers": singers or None,
                    "durationMs": info["durationMs"], "bitrate": info["bitrate"], "lyrics": kind})
    if cover and not existing:
        shutil.copy(cover, work / "cover.jpg")
    shutil.rmtree(raw)
    review["tracks"] = out
    return review, existing


def run(cfg, batch, dry):
    server = Server(cfg)
    matches = json.loads(Path(batch).read_text())
    if isinstance(matches, dict):
        matches = [matches]
    requests = server.requests()
    # Whole albums first: a song from an album in the same batch comes with it.
    matches.sort(key=lambda m: 0 if (requests.get(m.get("request_id")) or {}).get("item", {}).get("kind") == "movie" else 1)
    albums = {}
    handed, report = [], []
    # Before anything: can this machine reach the download site at all?
    if not dry:
        sites = {urlparse(urljoin((m.get("best_match") or {}).get("url") or "", l)).scheme + "://" + urlparse(urljoin((m.get("best_match") or {}).get("url") or "", l)).netloc
                 for m in matches if m.get("match_found")
                 for t in ([m["matched_song"]] if m.get("matched_song") else m.get("tracks") or [])
                 for l in [t.get("download320") or t.get("download128") or ""] if l}
        for site in sorted(sites):
            why = reachable(site)
            if why:
                sys.exit(f"Stopped before downloading anything: {why}.")
    with httpx.Client(timeout=httpx.Timeout(60, connect=15), headers={"User-Agent": AGENT}) as client:
        for match in matches:
            rid = match.get("request_id")
            request = requests.get(rid)
            label = f"#{rid} {match.get('title') or ''} ({match.get('movie') or ''})"
            if not request or request["status"] != "open":
                report.append(f"skipped  {label}: not open any more")
                continue
            if request.get("stage") and not dry:
                report.append(f"skipped  {label}: already handed in ({request['stage']})")
                continue
            if not match.get("match_found"):
                report.append(f"skipped  {label}: the matcher found nothing")
                continue
            log(f"{label}…")
            album_key = (key(clean_album((match.get("best_match") or {}).get("title") or "")), year_of(match))
            covered_by = albums.get(album_key) if request["item"]["kind"] == "song" else None
            notes = []
            try:
                review, existing = prepare(cfg, server, client, match, request, covered_by, notes, dry)
            except Exception as e:  # one bad request doesn't stop the batch
                shutil.rmtree(Path(cfg["WORK_FOLDER"]) / str(rid), ignore_errors=True)
                report.append(f"failed   {label}: {e}")
                continue
            if request["item"]["kind"] == "movie":
                albums[album_key] = rid
            where = (f"comes with #{covered_by}" if covered_by else
                     f"joins {review['destinationFolder']}" if existing else f"new folder {review['destinationFolder']}")
            lyr = sum(1 for t in review["tracks"] if t.get("lyrics") not in (None, "none"))
            line = f"{'would hand in' if dry else 'handed in'} {label}: {len(review['tracks'])} song(s), {where}"
            if review["tracks"] and not dry:
                line += f", lyrics for {lyr}"
            report.append(line + "".join(f"\n           ! {n}" for n in notes))
            if dry:
                continue
            work = Path(cfg["WORK_FOLDER"]) / str(rid)
            work.mkdir(parents=True, exist_ok=True)
            (work / "review.json").write_text(json.dumps({"request": rid, "kind": request["item"]["kind"], "review": review,
                                                          "match": match}, indent=1, ensure_ascii=False))
            if cfg.get("REMOTE"):
                log(f"  sending to {cfg['REMOTE']}")
                try:
                    send_to_server(cfg, rid)
                except (RuntimeError, subprocess.CalledProcessError) as e:
                    report[-1] = f"failed   {label}: {e} (the download is kept in {work})"
                    continue
            server.call("POST", f"/admin/requests/{rid}/review", json=review)
            handed.append(rid)
    if handed:
        server.call("POST", "/admin/requests/reviews-ready", json={"ids": handed})
    print("\n".join(report) or "Nothing in the file.", flush=True)
    return handed


# ---------------------------------------------------------------- acting on decisions


def move_in(cfg, rid, review, existing_place, undo):
    """Moves request [rid]'s files into the library. Returns the folder (inside MUSIC_FOLDER) they went to."""
    work = Path(cfg["REQUESTS_FOLDER"]) / str(rid)
    music = Path(cfg["MUSIC_FOLDER"])
    if existing_place:
        folder = existing_place["folder"]
        # The album may have arrived since the download: its tags win, so the songs join it.
        sample = music / existing_place["sampleFile"]
        copied = {k: v for k, v in album_tags_from(sample).items() if v and k != "composer"}
        if review.get("destination") != "existing" and copied:
            for t in review["tracks"]:
                f = work / t["file"]
                retagged = work / f".{t['file']}"
                subprocess.run(["ffmpeg", "-v", "error", "-nostdin", "-y", "-i", str(f), "-map", "0", "-c", "copy",
                                "-id3v2_version", "3"] + sum((["-metadata", f"{k}={v}"] for k, v in copied.items()), []) + [str(retagged)],
                               check=True, capture_output=True, stdin=subprocess.DEVNULL)
                retagged.replace(f)
    else:
        folder = review["destinationFolder"]
    dest = music / folder
    dest.mkdir(parents=True, exist_ok=True)
    for f in sorted(work.iterdir()):
        if f.name.startswith(".") or f.name == "review.json" or not f.is_file():
            continue
        if f.name == "cover.jpg" and (dest / "cover.jpg").exists():
            continue
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

    music = Path(cfg["MUSIC_FOLDER"])
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
            place = server.place(rid, review.get("year"), review.get("composer"), first_title)
            if place and (place.get("library") != cfg["LIBRARY_NAME"] or place.get("folder") is None):
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
        place = server.place(rid, review.get("year"), review.get("composer"), title)
        if not place or (song and not place.get("songId")):
            tries = int((work / ".close_tries").read_text()) + 1 if (work / ".close_tries").exists() else 1
            (work / ".close_tries").write_text(str(tries))
            if tries >= 4:  # about two minutes after the scan: the library view has long caught up
                (work / ".gave_up").write_text("not found after the scan")
                log(f"#{rid} is in the library but couldn't be matched to the request; close it with \"Added\" in the app")
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
            gave_up = {int(p.name) for p in folder.iterdir() if p.name.isdigit() and (p / ".gave_up").exists()}
            pending = {int(p.name) for p in folder.iterdir() if p.is_dir() and p.name.isdigit()}
            pending |= {rid for rid, r in server.requests().items() if r.get("stage") and r["status"] == "open"}
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
    done = ssh(cfg, [cfg["REMOTE_FULFIL"], "resume"], capture_output=True, text=True)
    print((done.stdout or done.stderr).strip(), flush=True)
    if done.returncode != 0:
        print(f"Couldn't start the waiting half on {cfg['REMOTE']}; run: ssh {cfg['REMOTE']} {cfg['REMOTE_FULFIL']} resume")


def status(cfg):
    rows = [r for r in Server(cfg).requests().values() if r.get("stage")]
    for r in rows:
        review = r.get("review") or {}
        print(f"#{r['id']:<5} {r['stage']:<9} {review.get('album', r['item']['movie'])} ({review.get('year') or '?'})"
              f"  {len(review.get('tracks') or [])} song(s)  asked by {', '.join(r['askedBy'])}")
    print(f"{len(rows)} in progress" if rows else "Nothing waiting.")


def undo(cfg, path):
    music = Path(cfg["MUSIC_FOLDER"])
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
        handed = run(cfg, args.batch, args.dry_run)
        if handed and not args.dry_run:
            if remote:
                start_remote_waiter(cfg)
            else:
                wait(cfg) if args.foreground else wait_in_background(cfg)
    elif args.command == "resume":
        start_remote_waiter(cfg) if remote else wait_in_background(cfg)
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
