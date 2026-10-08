"""People's photos for Jukebox, from JioSaavn's artist pictures, then Wikidata's.

Reads the JioSaavn album replies the catalog crawl cached (tamil-catalog's cache.db: every song lists its
artists with a picture), matches the artists to Jukebox's people by name (or any spelling merged into them),
and downloads each one's picture at 500x500 into the data folder's people-photos/, named after the person.
People JioSaavn has no real picture of (mostly older composers) get the Wikimedia Commons photo Wikidata gives
the composers of Tamil films. Jukebox links them up after its next scan (server/.../library/PeoplePhotos.kt).

- JioSaavn's stand-in pictures ("default" artists) and film posters given to actors are skipped.
- A file already in the folder is never replaced, so a photo picked by hand stays. Delete a wrong one, or put
  the right one in its place under the same name.
- When one name has several JioSaavn pictures, the one used on the most songs wins.

    python3 people_photos.py --cache ~/tamil-catalog/cache.db --db /mnt/ugreen/jukebox/data/jukebox.db \
        --out /mnt/ugreen/jukebox/data/people-photos [--dry-run]
"""

import argparse
import collections
import json
import os
import re
import sqlite3
import sys
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor


def key(name):
    """One key however the name is written: "A.R. Rahman" = "A R Rahman" = "a. r. rahman"."""
    return re.sub(r"[^\w]+", "", name.lower().replace(".", " "), flags=re.UNICODE)


def pictures(cache):
    """name key -> Counter of picture URLs, from every cached album reply."""
    found = collections.defaultdict(collections.Counter)
    db = sqlite3.connect(f"file:{cache}?mode=ro", uri=True)
    for (body,) in db.execute("SELECT body FROM http WHERE url LIKE '%getAlbumDetails%'"):
        try:
            album = json.loads(body)
        except (TypeError, ValueError):
            continue
        for song in album.get("songs") or album.get("list") or []:
            info = song.get("more_info") if isinstance(song.get("more_info"), dict) else song
            artists = ((info or {}).get("artistMap") or {}).get("artists") or []
            for a in artists:
                url = a.get("image") or ""
                if a.get("role") == "starring" or "/artists/" not in url or re.search(r"default", url, re.I):
                    continue
                found[key(a.get("name", ""))][url] += 1
    return found


WIKIDATA = """
SELECT DISTINCT ?c ?en (GROUP_CONCAT(DISTINCT ?alias; separator="|") AS ?aliases) (SAMPLE(?img) AS ?image) WHERE {
  ?film wdt:P364 wd:Q5885; wdt:P31 wd:Q11424; wdt:P86 ?c.
  ?c wdt:P18 ?img.
  OPTIONAL { ?c rdfs:label ?en. FILTER(LANG(?en) = "en") }
  OPTIONAL { ?c skos:altLabel ?alias. FILTER(LANG(?alias) = "en") }
} GROUP BY ?c ?en
"""


def wikidata():
    """name key -> Commons photo of a composer of Tamil films (Wikidata P86 on a Tamil film, with P18)."""
    url = "https://query.wikidata.org/sparql?" + urllib.parse.urlencode({"query": WIKIDATA, "format": "json"})
    req = urllib.request.Request(url, headers={"User-Agent": "jukebox-people-photos/0.1 (personal music library)"})
    rows = json.load(urllib.request.urlopen(req, timeout=180))["results"]["bindings"]
    found = {}
    for b in rows:
        image = b["image"]["value"].replace("http://", "https://") + "?width=500"
        names = [b.get("en", {}).get("value", "")] + (b.get("aliases", {}).get("value") or "").split("|")
        for n in names:
            if n.strip():
                found.setdefault(key(n), image)
    return found


def people(jukebox_db):
    """(name, [spellings]) of everyone credited on something."""
    db = sqlite3.connect(f"file:{jukebox_db}?mode=ro", uri=True)
    return db.execute(
        """SELECT p.name, coalesce(p.aliases, '') FROM people p
            WHERE p.merged_into IS NULL
              AND (EXISTS (SELECT 1 FROM recording_credits c WHERE c.person_id = p.id)
                   OR EXISTS (SELECT 1 FROM album_credits c WHERE c.person_id = p.id))"""
    ).fetchall()


def download(url, path):
    big = re.sub(r"\d+x\d+(?=\.\w+$)", "500x500", url) if "saavncdn" in url else url
    for attempt in (big, url):
        try:
            with urllib.request.urlopen(urllib.request.Request(attempt, headers={"User-Agent": "jukebox-people-photos/0.1 (personal music library)"}), timeout=20) as r:
                data = r.read()
            if len(data) > 2000:
                tmp = path + ".part"
                with open(tmp, "wb") as f:
                    f.write(data)
                os.replace(tmp, path)
                return True
        except Exception:
            time.sleep(1)
    return False


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--cache", required=True)
    ap.add_argument("--db", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    found = pictures(args.cache)
    try:
        commons = wikidata()
    except Exception as e:
        print(f"Wikidata didn't answer ({e}); JioSaavn pictures only", file=sys.stderr)
        commons = {}
    os.makedirs(args.out, exist_ok=True)
    have = {key(os.path.splitext(f)[0]) for f in os.listdir(args.out)}
    jobs, matched, missing = [], 0, 0
    for name, aliases in people(args.db):
        urls = collections.Counter()
        spellings = [name] + [a for a in aliases.split("\n") if a.strip()]
        for spelling in spellings:
            urls.update(found.get(key(spelling), {}))
        if not urls:
            photo = next((commons[key(n)] for n in spellings if key(n) in commons), None)
            if photo is None:
                missing += 1
                continue
            urls[photo] = 1
        matched += 1
        if key(name) in have:
            continue
        safe = re.sub(r'[/\\:*?"<>|]+', " ", name).strip()
        jobs.append((urls.most_common(1)[0][0], os.path.join(args.out, safe + ".jpg")))

    print(f"{matched} people have a JioSaavn picture, {missing} don't; {len(jobs)} to download", file=sys.stderr)
    if args.dry_run:
        for url, path in jobs[:20]:
            print(os.path.basename(path), url)
        return
    with ThreadPoolExecutor(4) as pool:
        done = sum(pool.map(lambda j: download(*j), jobs))
    print(f"downloaded {done} of {len(jobs)}", file=sys.stderr)


if __name__ == "__main__":
    main()
