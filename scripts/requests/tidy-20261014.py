#!/usr/bin/env python3
"""
One-off tidy-up of the requests filled on 2026-10-13, before fulfil.py knew about languages, background score
and albums already in the library. Run on the Mac mini, then rescan (Jukebox → Settings → Rescan, or wait an hour).
Nothing is deleted: duplicates go to /mnt/ugreen/jukebox/requests/trash/20261014/, and every move is logged there.

  1. Hridayam, Sarvam Maya and Premam move to MusicLibrary/malayalam/ (Premam's language tag becomes Malayalam).
  2. Pichaikkaran: the five downloaded songs it already had go to trash; the theme music goes to
     "Pichaikkaran (2016) (Original Background Score)".
  3. "Vishwaroopam II BGM (2018)" becomes "Vishwaroopam II (2018) (Original Background Score)".
  4. "Remo Special BGM Additional (2016)" becomes "Remo (2016) (Original Background Score)"; its one song,
     Veshangalil Poiyillai, joins "Remo (2016)".
"""

import json
import os
import shutil
import subprocess

T = "/mnt/ugreen/NAS/MusicLibrary/tamil/jiosaavn_m4a"
M = "/mnt/ugreen/NAS/MusicLibrary/malayalam"
TRASH = "/mnt/ugreen/jukebox/requests/trash/20261014"
ADDED = "/mnt/ugreen/jukebox/requests/undo/20261013-215438.jsonl"  # what fulfil.py filed that night

os.makedirs(TRASH, exist_ok=True)
log = open(f"{TRASH}/tidy-log.jsonl", "a")


def note(**k):
    log.write(json.dumps(k) + "\n")
    log.flush()
    print(k)


def mv(a, b):
    os.makedirs(os.path.dirname(b), exist_ok=True)
    if os.path.exists(b):
        raise SystemExit(f"Stopped: {b} already exists")
    shutil.move(a, b)
    note(moved=a, to=b)


def tags(f):
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format_tags", "-of", "json", f], capture_output=True, text=True)
    return {k.lower(): v for k, v in json.loads(out.stdout)["format"].get("tags", {}).items()}


def retag(f, **new):
    tmp = os.path.join(os.path.dirname(f), "." + os.path.basename(f))
    cmd = ["ffmpeg", "-v", "error", "-nostdin", "-y", "-i", f, "-map", "0", "-c", "copy", "-id3v2_version", "3"]
    for k, v in new.items():
        cmd += ["-metadata", f"{k}={v}"]
    subprocess.run(cmd + [tmp], check=True, stdin=subprocess.DEVNULL)
    os.replace(tmp, f)
    note(retagged=f, tags=new)


# 1. Malayalam albums
for d in ["Hridayam (2022)", "Sarvam Maya (2025)", "Premam (2015)"]:
    mv(f"{T}/{d}", f"{M}/{d}")
for f in sorted(os.listdir(f"{M}/Premam (2015)")):
    if f.endswith(".mp3"):
        retag(f"{M}/Premam (2015)/{f}", genre="Malayalam")

# 2. Pichaikkaran: only files that night's run added (the m4a copies stay)
duplicates = {"01 - Glamour Song", "03 - Nenjorathil (Male)", "04 - Nooru Samigal", "05 - Oru Vellai Sotrukkaga", "07 - Unakkaga Varuven"}
for rel in [json.loads(line)["file"] for line in open(ADDED)]:
    if rel.startswith("Pichaikkaran (2016)/") and os.path.splitext(os.path.basename(rel))[0] in duplicates and os.path.exists(f"{T}/{rel}"):
        mv(f"{T}/{rel}", f"{TRASH}/{rel}")
P, PS = f"{T}/Pichaikkaran (2016)", f"{T}/Pichaikkaran (2016) (Original Background Score)"
for ext in (".mp3", ".txt", ".lrc"):
    if os.path.exists(f"{P}/06 - Pichaikkaran (Theme Music){ext}"):
        mv(f"{P}/06 - Pichaikkaran (Theme Music){ext}", f"{PS}/06 - Pichaikkaran (Theme Music){ext}")
retag(f"{PS}/06 - Pichaikkaran (Theme Music).mp3", album="Pichaikkaran (Original Background Score)", discsubtitle="")
shutil.copy(f"{P}/cover.jpg", f"{PS}/cover.jpg")

# 3. Vishwaroopam II
V = f"{T}/Vishwaroopam II (2018) (Original Background Score)"
mv(f"{T}/Vishwaroopam II BGM (2018)", V)
for f in sorted(os.listdir(V)):
    if f.endswith(".mp3"):
        retag(f"{V}/{f}", album="Vishwaroopam II (Original Background Score)")

# 4. Remo
R = f"{T}/Remo (2016) (Original Background Score)"
mv(f"{T}/Remo Special BGM Additional (2016)", R)
remo = tags(f"{T}/Remo (2016)/01 - Remo Nee Kadhalan.m4a")
for f in sorted(os.listdir(R)):
    if f.startswith("19 - Veshangalil Poiyillai"):
        mv(f"{R}/{f}", f"{T}/Remo (2016)/{f}")
for f in sorted(os.listdir(R)):
    if f.endswith(".mp3"):
        retag(f"{R}/{f}", album="Remo (Original Background Score)", date=remo.get("date", "2016"))
retag(f"{T}/Remo (2016)/19 - Veshangalil Poiyillai.mp3", album=remo.get("album", "Remo"), date=remo.get("date", "2016"),
      album_artist=remo.get("album_artist", ""), discsubtitle=remo.get("discsubtitle", ""))

print("Done. Now rescan the library.")
