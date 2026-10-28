#!/usr/bin/env python3
"""
Labels songs with moods, energy, positivity and themes using Gemini (the "teacher" in docs/song-labels.md).

For each song it sends the title, movie, year, composer, singers and the start of its lyrics, 25 songs a request,
and writes one JSON line per song to the output file: {"id", "moods", "energy", "valence", "themes",
"instrumental", "confidence"}. Songs already in the output are skipped, so it can be stopped and run again;
it waits when the free tier says to slow down.

    python3 label_songs.py --db jukebox.db --out labels.jsonl [--model gemini-3.5-flash] [--limit 30] [--ids a,b,c]

The API key is read from $GEMINI_API_KEY, else ~/.config/jukebox/gemini.key (never from this repository).
"""
import argparse
import json
import os
import re
import sqlite3
import sys
import time
import urllib.error
import urllib.request

MOODS = ["chill", "romance", "happy", "party", "kuthu", "mass", "sad", "melody", "devotional", "classical", "sleep", "workout"]
THEMES = [
    "love", "heartbreak", "longing", "mother", "father", "family", "friendship", "devotion", "hero", "philosophy",
    "celebration", "village", "motivation", "lullaby", "patriotism", "comedy", "wedding", "festival", "rain", "nature",
    "dance", "travel", "college", "night",
]

PROMPT = """You label songs from Indian films (mostly Tamil) for a music app's mixes.
For each song below, from what you know of it and from its details and lyrics, give:
- moods: 1 to 3 of %(moods)s that clearly fit how it sounds and feels. kuthu = Tamil folk dance beats (thappu, gaana);
  mass = hero intro / elevation; melody = a soft tuneful film song; classical = Carnatic/Hindustani feel; sleep = lullaby or very quiet;
  workout = driving high energy; chill = calm and easy (never a dance number).
- energy: 1 (very calm) to 5 (very intense).
- valence: 1 (very sad) to 5 (very happy).
- themes: 0 to 3 of %(themes)s, only ones the song is really about or made for.
- instrumental: true if it has no singing (a theme or background score).
- confidence: "high" if you know this song or the lyrics make it clear, else "low".
Return one entry per song, with its id, in the same order.

Songs:
%(songs)s"""

SCHEMA = {
    "type": "ARRAY",
    "items": {
        "type": "OBJECT",
        "properties": {
            "id": {"type": "STRING"},
            "moods": {"type": "ARRAY", "items": {"type": "STRING", "enum": MOODS}},
            "energy": {"type": "INTEGER"},
            "valence": {"type": "INTEGER"},
            "themes": {"type": "ARRAY", "items": {"type": "STRING", "enum": THEMES}},
            "instrumental": {"type": "BOOLEAN"},
            "confidence": {"type": "STRING", "enum": ["high", "low"]},
        },
        "required": ["id", "moods", "energy", "valence", "themes", "instrumental", "confidence"],
    },
}


def api_key():
    key = os.environ.get("GEMINI_API_KEY")
    if not key:
        path = os.path.expanduser("~/.config/jukebox/gemini.key")
        if os.path.exists(path):
            key = open(path).read().strip()
    if not key:
        sys.exit("No Gemini API key: set GEMINI_API_KEY or put it in ~/.config/jukebox/gemini.key")
    return key


def songs(db_path, only_ids=None):
    """Every song in the library (not merged away), with what Gemini needs to know about it."""
    c = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    rows = c.execute(
        """SELECT r.id, r.title,
                  (SELECT a.title FROM tracks t JOIN releases rel ON rel.id = t.release_id JOIN albums a ON a.id = rel.album_id WHERE t.recording_id = r.id LIMIT 1),
                  (SELECT a.year FROM tracks t JOIN releases rel ON rel.id = t.release_id JOIN albums a ON a.id = rel.album_id WHERE t.recording_id = r.id LIMIT 1)
             FROM recordings r
            WHERE r.merged_into IS NULL AND EXISTS (SELECT 1 FROM files f WHERE f.recording_id = r.id AND f.missing_since IS NULL)"""
    ).fetchall()
    people = {}
    for rid, role, name in c.execute(
        "SELECT rc.recording_id, rc.role, p.name FROM recording_credits rc JOIN people p ON p.id = rc.person_id ORDER BY rc.position"
    ):
        people.setdefault(rid, {}).setdefault(role, []).append(name)
    lyrics = {}
    for rid, text in c.execute("SELECT recording_id, text FROM lyrics"):
        if rid not in lyrics and text:
            lines = [re.sub(r"\[[^\]]*\]", "", line).strip() for line in text.splitlines()]
            lyrics[rid] = " / ".join(line for line in lines if line)[:450]
    out = []
    for rid, title, album, year in rows:
        if only_ids and rid not in only_ids:
            continue
        p = people.get(rid, {})
        out.append({
            "id": rid, "title": title, "movie": album, "year": year,
            "composer": ", ".join(p.get("composer", [])[:2]), "singers": ", ".join(p.get("singer", [])[:3]),
            "lyrics": lyrics.get(rid, ""),
        })
    return out


def describe(song):
    parts = [f'id={song["id"]}', f'"{song["title"]}"']
    if song["movie"]:
        parts.append(f'from {song["movie"]}' + (f' ({song["year"]})' if song["year"] else ""))
    if song["composer"]:
        parts.append(f'music: {song["composer"]}')
    if song["singers"]:
        parts.append(f'singers: {song["singers"]}')
    if song["lyrics"]:
        parts.append(f'lyrics: {song["lyrics"]}')
    return "- " + "; ".join(parts)


def ask(key, model, batch):
    body = {
        "contents": [{"parts": [{"text": PROMPT % {"moods": ", ".join(MOODS), "themes": ", ".join(THEMES), "songs": "\n".join(map(describe, batch))}}]}],
        "generationConfig": {
            "responseMimeType": "application/json", "responseSchema": SCHEMA, "temperature": 0.2,
            # Labels need knowledge, not long reasoning (and every model takes this setting).
            "thinkingConfig": {"thinkingLevel": "minimal"},
        },
    }
    request = urllib.request.Request(
        f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
        data=json.dumps(body).encode(), headers={"Content-Type": "application/json", "x-goog-api-key": key},
    )
    while True:
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                data = json.load(response)
            text = data["candidates"][0]["content"]["parts"][0]["text"]
            return json.loads(text), data.get("usageMetadata", {})
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")
            if e.code in (429, 500, 503):
                # The free tier says how long to wait; otherwise a minute.
                wait = re.search(r'"retryDelay":\s*"(\d+)s"', detail)
                seconds = int(wait.group(1)) + 2 if wait else 60
                if "PerDay" in detail or "per day" in detail.lower():
                    sys.exit(f"Daily limit reached ({e.code}); run again tomorrow: it carries on where it stopped.")
                print(f"  {e.code}: waiting {seconds} s", flush=True)
                time.sleep(seconds)
                continue
            sys.exit(f"Gemini error {e.code}: {detail[:400]}")
        except (TimeoutError, urllib.error.URLError, ConnectionError, json.JSONDecodeError, KeyError) as e:
            # A busy server that never answers, a dropped connection or a cut-off answer: try again shortly.
            print(f"  {type(e).__name__}: trying again in 30 s", flush=True)
            time.sleep(30)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--model", default="gemini-3.5-flash")
    ap.add_argument("--batch", type=int, default=25)
    ap.add_argument("--limit", type=int, default=0, help="label at most this many songs (0: all)")
    ap.add_argument("--ids", default="", help="only these recording ids, comma separated")
    args = ap.parse_args()

    done = set()
    if os.path.exists(args.out):
        with open(args.out) as f:
            done = {json.loads(line)["id"] for line in f if line.strip()}
    todo = [s for s in songs(args.db, set(filter(None, args.ids.split(",")))) if s["id"] not in done]
    if args.limit:
        todo = todo[: args.limit]
    print(f"{len(done)} songs labelled already, {len(todo)} to go with {args.model}", flush=True)
    key = api_key()
    started = time.time()
    labelled = 0
    with open(args.out, "a") as out:
        for i in range(0, len(todo), args.batch):
            batch = todo[i : i + args.batch]
            answers, usage = ask(key, args.model, batch)
            known = {s["id"] for s in batch}
            for a in answers:
                if a.get("id") in known:
                    a["energy"] = max(1, min(5, int(a.get("energy", 3))))
                    a["valence"] = max(1, min(5, int(a.get("valence", 3))))
                    out.write(json.dumps(a, ensure_ascii=False) + "\n")
                    labelled += 1
            out.flush()
            rate = labelled / max(1, time.time() - started) * 60
            print(f"  {labelled}/{len(todo)} ({rate:.0f} songs/min, {usage.get('totalTokenCount', '?')} tokens last request)", flush=True)


if __name__ == "__main__":
    main()
