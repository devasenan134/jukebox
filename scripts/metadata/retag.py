"""
Writes the credits feed (credits_feed.py) into the music files' tags, so the files carry the cleaned-up people
on their own: singers in artist, the song's composer in composer, the album's music directors in album artist
(the same on every file of an album) and lyricists in a LYRICIST tag. Jukebox reads these on its next scan.

Only these four tags change, only when the feed has something for them, and every change is logged first so
it can be undone:

    python retag.py --feed credits.jsonl --db jukebox.db --root /music/folder            # dry run: what would change
    python retag.py --feed credits.jsonl --db jukebox.db --root /music/folder --apply    # write, log in retag-log.jsonl
    python retag.py --root /music/folder --undo retag-log.jsonl                          # put the old tags back

--db is a copy of Jukebox's database: it says which JioSaavn song each file is and which album it belongs to.
Needs mutagen (uv run --with mutagen).
"""

import argparse
import collections
import json
import os
import random
import re
import sqlite3
import sys

from mutagen.mp4 import MP4, MP4FreeForm

LYRICIST = "----:com.apple.iTunes:LYRICIST"
TAGS = {"artist": "\xa9ART", "composer": "\xa9wrt", "album_artist": "aART", "lyricist": LYRICIST}
# What Jukebox splits a tag on (Names.people): a name containing one of these would come apart.
SPLITS = re.compile(r"\s*(,|;|/|&|\band\b|\bfeat\.?|\bft\.|\bwith\b|\s\|\s)\s*", re.I)


def read(f):
    out = {}
    for k, atom in TAGS.items():
        v = f.tags.get(atom) if f.tags else None
        if v:
            out[k] = ", ".join(x.decode() if isinstance(x, (bytes, MP4FreeForm)) else str(x) for x in v)
    return out


def write(f, values):
    if f.tags is None:
        f.add_tags()
    for k, atom in TAGS.items():
        if k not in values:
            continue
        v = values[k]
        if v is None:
            f.tags.pop(atom, None)
        elif k == "lyricist":
            f.tags[atom] = [MP4FreeForm(v.encode())]
        else:
            f.tags[atom] = [v]
    f.save()


def plan(args):
    persons, recs = {}, {}
    with open(args.feed) as fh:
        for line in fh:
            d = json.loads(line)
            if d["type"] == "person":
                persons[d["id"]] = d
            else:
                recs[d["saavn_id"]] = d

    def names(ids):
        # Joined with ", " for Jukebox to split again, so a name can't contain what it splits on.
        out = [" ".join(SPLITS.sub(" ", persons[i]["name"]).split()) for i in ids if persons.get(i, {}).get("name")]
        return ", ".join(dict.fromkeys(n for n in out if n)) or None

    db = sqlite3.connect(args.db)
    files = db.execute("""SELECT f.path, r.saavn_id, rl.album_id FROM files f JOIN recordings r ON r.id = f.recording_id
                          JOIN tracks t ON t.id = f.track_id JOIN releases rl ON rl.id = t.release_id
                          WHERE f.missing_since IS NULL""").fetchall()
    # An album's music directors: those the feed gives the album of at least 30% of its songs.
    by_album = collections.defaultdict(list)
    for _, sid, album in files:
        if sid in recs:
            by_album[album].append(recs[sid]["album_composers"])
    album_composers = {}
    for album, lists in by_album.items():
        c = collections.Counter(p for l in lists for p in dict.fromkeys(l))
        album_composers[album] = names([p for p, n in c.most_common() if n >= 0.3 * len(lists)])

    changes = []
    for path, sid, album in files:
        r = recs.get(sid)
        if not r:
            continue
        want = {"artist": names(r.get("singer", [])), "composer": names(r.get("composer", [])),
                "album_artist": album_composers.get(album), "lyricist": names(r.get("lyricist", []))}
        changes.append((path, {k: v for k, v in want.items() if v}))
    return changes


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--feed")
    ap.add_argument("--db")
    ap.add_argument("--root", required=True)
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--log", default="retag-log.jsonl")
    ap.add_argument("--undo", help="a log written by --apply: put those files' old tags back")
    args = ap.parse_args()

    if args.undo:
        n = 0
        with open(args.undo) as fh:
            for line in fh:
                d = json.loads(line)
                f = MP4(os.path.join(args.root, d["path"]))
                write(f, {k: d["before"].get(k) for k in d["after"]})
                n += 1
        print(f"put back the old tags of {n} files")
        return

    changes = plan(args)
    stats = collections.Counter()
    samples = []
    log = open(args.log, "a") if args.apply else None
    for i, (path, want) in enumerate(changes):
        full = os.path.join(args.root, path)
        if not path.lower().endswith(".m4a") or not os.path.isfile(full):
            stats["skipped (not an m4a file here)"] += 1
            continue
        f = MP4(full)
        before = read(f)
        diff = {k: v for k, v in want.items() if before.get(k) != v}
        if not diff:
            stats["already right"] += 1
            continue
        stats["files to change"] += 1
        for k in diff:
            stats[f"changes {k}"] += 1
        if len(samples) < 400:
            samples.append((path, {k: (before.get(k), v) for k, v in diff.items()}))
        if args.apply:
            log.write(json.dumps({"path": path, "before": {k: before.get(k) for k in diff}, "after": diff}, ensure_ascii=False) + "\n")
            log.flush()
            write(f, diff)
        if (i + 1) % 2000 == 0:
            print(f"  {i + 1}/{len(changes)}", file=sys.stderr)
    print(dict(stats))
    random.seed(7)
    for path, d in random.sample(samples, min(12, len(samples))):
        print(path)
        for k, (a, b) in d.items():
            print(f"   {k}: {a!r} → {b!r}")
    if not args.apply:
        print("dry run: nothing written (add --apply)")


if __name__ == "__main__":
    main()
