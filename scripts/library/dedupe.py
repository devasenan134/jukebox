# /// script
# requires-python = ">=3.11"
# dependencies = ["httpx>=0.27"]
# ///
"""
Songs filed twice, and names that say a language: what was left in the library from before downloads were named
after JioSaavn (docs/request-fulfilment.md, "Named as JioSaavn names them").

    dedupe.py plan [--root /mnt/ugreen/NAS/MusicLibrary] [--db /mnt/ugreen/jukebox/data/jukebox.db]
    dedupe.py run  [--root …] [--db …]       moves and retags; an undo log in <root>/_duplicates/
    dedupe.py undo <log>                     puts everything back

Then rescan (Jukebox's own scan finds the changes; the analyzer only looks at the retagged files again).

- A file of the same recording as one from JioSaavn (the scanner matched them by sound or JioSaavn id), downloaded
  again from masstamilan into the same film's album (its songs or its background score, any year), is moved to
  <root>/_duplicates/, the same path below it, with its lyrics. A copy on a compilation (a devotional album of songs
  from many films) stays: it's part of that album. The song keeps its id, so playlists and likes don't change.
- A title that ends in its library's language ("Hukum Reloaded - Tamil", "Yolo (Tamil)" in the Tamil library) gets the
  name without it, as fulfil.py's song_name gives it: the single and the album's copy of a song become one song.
  Another language's stays ("Theeranae (English Version)" on a Tamil album is another song).
"""
import argparse
import importlib.util
import json
import re
import shutil
import sqlite3
import sys
from collections import defaultdict
from datetime import datetime
from pathlib import Path

here = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("fulfil", here.parent / "requests" / "fulfil.py")
fulfil = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fulfil)

SCORE = re.compile(r"\s*\(original background score\)\s*$", re.I)


def film_of(folder):
    """"Arasan (2026) (Original Background Score)" and "Arasan (2025)" are both Arasan's."""
    name = SCORE.sub("", folder.rsplit("/", 1)[-1])
    return fulfil.key(re.sub(r"\s*\((?:19|20)\d\d\)\s*$", "", name))


def plan(root, db):
    c = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    folders = {lib_id: root / name for lib_id, name in c.execute("SELECT id, name FROM libraries")}
    rows = c.execute("""SELECT f.library_id, f.path, coalesce(r.merged_into, r.id), r.title FROM files f
                        JOIN recordings r ON r.id = f.recording_id WHERE f.missing_since IS NULL""").fetchall()
    languages = dict(c.execute("SELECT id, name FROM libraries"))
    moves, retitles = [], []
    by_recording = defaultdict(list)
    for lib, path, recording, title in rows:
        by_recording[(lib, recording)].append(path)
        if fulfil.LANGUAGE_TAG.search(title or ""):
            retitles.append((folders[lib] / path, languages[lib]))
    for (lib, _), paths in by_recording.items():
        kept = [p for p in paths if not p.endswith(".mp3")]
        if not kept:
            continue
        films = {film_of(p.rsplit("/", 1)[0]) for p in kept}
        moves += [folders[lib] / p for p in paths if p.endswith(".mp3") and film_of(p.rsplit("/", 1)[0]) in films]
    steps = [("move", f, root / "_duplicates" / f.relative_to(root)) for f in sorted(moves) if f.exists()]
    for f, language in sorted(set(retitles)):
        if f in moves or not f.exists():
            continue
        old = (fulfil.probe(f) or {"tags": {}})["tags"].get("title")
        new = fulfil.song_name(old, language)[0]
        if old and new != old:
            steps.append(("retitle", f, (old, new)))
    return steps


def show(root, steps):
    for kind, f, what in steps:
        if kind == "move":
            print(f"  move     {f.relative_to(root)}  (the same recording is in the library from JioSaavn)")
        else:
            print(f"  retitle  {f.relative_to(root)}: {what[0]} → {what[1]}")
    print(f"{sum(k == 'move' for k, *_ in steps)} file(s) to move to _duplicates, {sum(k == 'retitle' for k, *_ in steps)} to retitle")


def run(root, steps):
    logs = root / "_duplicates"
    logs.mkdir(exist_ok=True)
    log_path = logs / f"undo-{datetime.now():%Y%m%d-%H%M%S}.jsonl"
    with open(log_path, "a") as log:
        for kind, f, what in steps:
            if kind == "move":
                for side in [f] + [f.with_suffix(s) for s in (".lrc", ".txt") if f.with_suffix(s).exists()]:
                    dest = what.with_name(side.name)
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    shutil.move(side, dest)
                    log.write(json.dumps({"move": str(side), "to": str(dest)}) + "\n")
            else:
                fulfil.retag_in_place(f, {"title": what[1]})
                log.write(json.dumps({"retag": str(f), "tags": {"title": what[0]}}) + "\n")
            log.flush()
    print(f"Done. Undo: dedupe.py undo {log_path}\nRescan the library so Jukebox sees the changes.")


def undo(log_path):
    for line in reversed(Path(log_path).read_text().splitlines()):
        e = json.loads(line)
        if "move" in e and Path(e["to"]).exists() and not Path(e["move"]).exists():
            shutil.move(e["to"], e["move"])
        elif "retag" in e and Path(e["retag"]).exists():
            fulfil.retag_in_place(Path(e["retag"]), e["tags"])
    print("Put back. Rescan the library so Jukebox sees it.")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("command", choices=["plan", "run", "undo"])
    p.add_argument("log", nargs="?")
    p.add_argument("--root", default="/mnt/ugreen/NAS/MusicLibrary")
    p.add_argument("--db", default="/mnt/ugreen/jukebox/data/jukebox.db")
    a = p.parse_args()
    root = Path(a.root)
    if a.command == "undo":
        if not a.log:
            sys.exit("undo needs the log: dedupe.py undo <root>/_duplicates/undo-….jsonl")
        undo(a.log)
        return
    steps = plan(root, a.db)
    show(root, steps)
    if a.command == "run":
        run(root, steps)


if __name__ == "__main__":
    main()
