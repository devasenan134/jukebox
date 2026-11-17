#!/usr/bin/env python3
"""
One folder per album: moves every album out of its source's folder into its language's folder, so the same album
from JioSaavn and from masstamilan is one folder (docs/request-fulfilment.md, "One folder per album").

    MusicLibrary/tamil/jiosaavn_m4a/Jeans (1998)/   ┐
    MusicLibrary/tamil/masstamilan/Jeans (1998)/    ┴→  MusicLibrary/tamil/Jeans (1998)/

Where a file came from is a tag instead: files from masstamilan get source=masstamilan; a file without one is from
JioSaavn (those aren't rewritten: a changed file is analysed again).

    flatten.py plan [--root /mnt/ugreen/NAS/MusicLibrary]     what would move, and what clashes
    flatten.py run  [--root …]                                moves, tags; an undo log in <root>/_flatten/
    flatten.py undo <log>                                     puts everything back

Run it with Jukebox and the analyzer stopped, then point the libraries at the language folders (LIBRARIES,
MUSIC_FOLDER) and repoint the masstamilan library's files in the database (the doc has the steps).

- An album folder that isn't in the language folder yet is moved whole (a rename: nothing is copied).
- Into one that is (from another source), its files are moved one by one. A file of the same name already there
  (cover.jpg) isn't overwritten: it goes to <root>/_flatten/leftovers/, the same path below it.
- The repeats folder (jiosaavn_m4a_duplicate_singles, never scanned) goes to <root>/_duplicate_singles/<language>/.
- Needs ffmpeg (the tag).
"""
import argparse
import json
import os
import subprocess
import sys
from datetime import datetime
from pathlib import Path

# A language folder's source folders, in the order they're moved (the first one keeps a clashing file), and their tag.
SOURCES = {"jiosaavn_m4a": None, "masstamilan": "masstamilan"}
REPEATS = "jiosaavn_m4a_duplicate_singles"
AUDIO = {".mp3", ".m4a", ".flac", ".ogg", ".opus", ".aac", ".wav"}


def languages(root):
    return sorted(p for p in root.iterdir() if p.is_dir() and not p.name.startswith((".", "_")))


def plan(root):
    """[(kind, source, destination, tag)]: "dir" moves a whole folder, "file" one file, "leftover" a clashing file."""
    steps = []
    for lang in languages(root):
        # Album name → the files that will be in its folder once the steps before have run.
        taken = {p.name: {f.name for f in p.iterdir()} if p.is_dir() else set()
                 for p in lang.iterdir() if p.name not in SOURCES and p.name != REPEATS}
        if (lang / REPEATS).is_dir():
            steps.append(("dir", lang / REPEATS, root / "_duplicate_singles" / lang.name, None))
        for source, tag in SOURCES.items():
            if not (lang / source).is_dir():
                continue
            for album in sorted((lang / source).iterdir()):
                dest = lang / album.name
                if album.name not in taken:
                    steps.append(("dir", album, dest, tag))
                    taken[album.name] = {f.name for f in album.iterdir()} if album.is_dir() else set()
                    continue
                for f in sorted(album.iterdir()):
                    if f.name in taken[album.name]:
                        steps.append(("leftover", f, root / "_flatten" / "leftovers" / f.relative_to(root), None))
                    else:
                        steps.append(("file", f, dest / f.name, tag))
                        taken[album.name].add(f.name)
    return steps


def tag_source(path, value):
    """Sets (or with None, removes) the source tag on mp3 [path], keeping everything else."""
    tmp = path.with_name(f".flatten-{path.name}")
    subprocess.run(["ffmpeg", "-v", "error", "-nostdin", "-y", "-i", str(path), "-map", "0", "-c", "copy",
                    "-id3v2_version", "3", "-metadata", f"source={value or ''}", str(tmp)],
                   check=True, capture_output=True, stdin=subprocess.DEVNULL)
    tmp.replace(path)


def audio_in(path):
    """The files a source tag goes on: masstamilan's are all mp3 (an m4a would need its tags stored another way)."""
    return [path] if path.is_file() and path.suffix.lower() == ".mp3" else \
        sorted(f for f in path.rglob("*.mp3") if f.is_file()) if path.is_dir() else []


def show(root, steps):
    dirs = [s for s in steps if s[0] == "dir"]
    files = [s for s in steps if s[0] == "file"]
    leftovers = [s for s in steps if s[0] == "leftover"]
    tagged = sum(len(audio_in(s[1])) for s in steps if s[3])
    print(f"{len(dirs)} folders move whole, {len(files)} files join an album already moved, "
          f"{len(leftovers)} clashing files go to _flatten/leftovers, {tagged} audio files get a source tag")
    for kind, src, dest, tag in steps:
        if kind != "dir" or src.parent.name not in SOURCES:
            print(f"  {kind:8} {src.relative_to(root)}  →  {dest.relative_to(root)}" + (f"  [source={tag}]" if tag else ""))


def run(root):
    steps = plan(root)
    show(root, steps)
    logs = root / "_flatten"
    logs.mkdir(exist_ok=True)
    log_path = logs / f"undo-{datetime.now():%Y%m%d-%H%M%S}.jsonl"
    sources = set()
    with open(log_path, "w") as log:
        def note(**entry):
            log.write(json.dumps(entry, ensure_ascii=False) + "\n")
            log.flush()
        for kind, src, dest, tag in steps:
            if dest.exists():
                sys.exit(f"{dest} appeared since the plan; stopped (undo with: flatten.py undo {log_path})")
            dest.parent.mkdir(parents=True, exist_ok=True)
            src.rename(dest)  # one disk: a rename, nothing copied
            note(move=str(dest.relative_to(root)), back=str(src.relative_to(root)))
            sources.add(src.parent if kind == "dir" else src.parent.parent)
            if tag:
                for f in audio_in(dest):
                    tag_source(f, tag)
                    note(tag=str(f.relative_to(root)), value=tag)
        # The emptied source folders (an album folder whose files all moved, then the source folder itself).
        for folder in sorted({p for s in steps if s[0] != "dir" for p in [s[1].parent]} | sources, key=lambda p: -len(p.parts)):
            if folder.is_dir() and not any(folder.iterdir()):
                folder.rmdir()
                note(rmdir=str(folder.relative_to(root)))
    print(f"Done. Undo log: {log_path}")


def undo(root, log_path):
    for line in reversed(Path(log_path).read_text().splitlines()):
        e = json.loads(line)
        if "rmdir" in e:
            (root / e["rmdir"]).mkdir(parents=True, exist_ok=True)
        elif "tag" in e:
            if (root / e["tag"]).exists():
                tag_source(root / e["tag"], None)
        elif "move" in e:
            src, dest = root / e["move"], root / e["back"]
            if src.exists() and not dest.exists():
                dest.parent.mkdir(parents=True, exist_ok=True)
                src.rename(dest)
            else:
                print(f"  couldn't put back {e['move']}")
    for top in (root / "_flatten" / "leftovers", root / "_duplicate_singles"):
        if top.is_dir():
            for d in sorted((p for p in top.rglob("*") if p.is_dir()), key=lambda p: -len(p.parts)) + [top]:
                if not any(d.iterdir()):
                    d.rmdir()
    print("Put back. Point the libraries at the source folders again before starting Jukebox.")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("command", choices=["plan", "run", "undo"])
    p.add_argument("log", nargs="?")
    p.add_argument("--root", default=os.environ.get("MUSIC_ROOT", "/mnt/ugreen/NAS/MusicLibrary"))
    a = p.parse_args()
    root = Path(a.root)
    if a.command == "plan":
        show(root, plan(root))
    elif a.command == "run":
        run(root)
    else:
        if not a.log:
            sys.exit("undo needs the log: flatten.py undo <root>/_flatten/undo-….jsonl")
        undo(root, a.log)


if __name__ == "__main__":
    main()
