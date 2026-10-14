#!/usr/bin/env python3
"""
One-off move to the per-source layout (run on the Mac mini, after the 0.19.2 server is deployed):

  MusicLibrary/tamil/jiosaavn_m4a     the JioSaavn library, untouched
  MusicLibrary/tamil/masstamilan      requests filled from masstamilan (adds to the Tamil albums)
  MusicLibrary/<language>/masstamilan the same for Malayalam, Telugu, Hindi

1. Moves what fulfil.py filed on 2026-10-13 (and the tidy-up moved) into those folders. Folders it made are moved
   whole; from albums JioSaavn already had, only its mp3s (and their lyrics) move, into a folder of the same name.
2. Marks two tracks as songs, not background score (data/score-overrides.txt): Hridayam's theme and Remo's
   Veshangalil Poiyillai.
3. Points Jukebox at the new folders (LIBRARIES in .env, with a backup) and restarts it, which rescans.
4. Adds the new settings to ~/.config/jukebox-fulfil.env.

Nothing is deleted; every move is logged in /mnt/ugreen/jukebox/backups/move-to-masstamilan-*.jsonl.
Stops at the first surprise (a folder already there, an m4a where only downloads should be).
"""

import datetime
import json
import os
import shutil
import subprocess

ROOT = "/mnt/ugreen/NAS/MusicLibrary"
T, TM = f"{ROOT}/tamil/jiosaavn_m4a", f"{ROOT}/tamil/masstamilan"
ML, MM = f"{ROOT}/malayalam", f"{ROOT}/malayalam/masstamilan"
JUKEBOX = "/mnt/ugreen/jukebox"
STAMP = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
log = open(f"{JUKEBOX}/backups/move-to-masstamilan-{STAMP}.jsonl", "a")


def note(**k):
    log.write(json.dumps(k) + "\n")
    log.flush()
    print(k)


def mv(a, b):
    if not os.path.exists(a):
        print(f"(already moved: {a})")
        return
    if os.path.exists(b):
        raise SystemExit(f"Stopped: {b} already exists")
    os.makedirs(os.path.dirname(b), exist_ok=True)
    shutil.move(a, b)
    note(moved=a, to=b)


for d in (TM, MM, f"{ROOT}/telugu/masstamilan", f"{ROOT}/hindi/masstamilan"):
    os.makedirs(d, exist_ok=True)

# 1a. Folders fulfil.py made: moved whole (they hold only its downloads).
whole = [(T, TM, d) for d in ["Anbe Aaruyire (2005)", "Annamalai (1992)", "Sarvam (2009)",
                              "Vishwaroopam II (2018) (Original Background Score)", "Remo (2016) (Original Background Score)",
                              "Pichaikkaran (2016) (Original Background Score)"]]
whole += [(ML, MM, d) for d in ["Hridayam (2022)", "Premam (2015)", "Sarvam Maya (2025)"]]
for src, dst, d in whole:
    if os.path.isdir(f"{src}/{d}") and any(f.endswith(".m4a") for f in os.listdir(f"{src}/{d}")):
        raise SystemExit(f"Stopped: {src}/{d} has m4a files, which fulfil.py doesn't make")
for src, dst, d in whole:
    mv(f"{src}/{d}", f"{dst}/{d}")

# 1b. Downloads added to albums JioSaavn already had: only those files move.
for album, stems in [("Pichaikkaran (2016)", ["02 - Nenjorathil (Female)"]), ("Remo (2016)", ["19 - Veshangalil Poiyillai"])]:
    for stem in stems:
        for ext in (".mp3", ".lrc", ".txt"):
            if os.path.exists(f"{T}/{album}/{stem}{ext}"):
                mv(f"{T}/{album}/{stem}{ext}", f"{TM}/{album}/{stem}{ext}")
    if os.path.exists(f"{T}/{album}/cover.jpg") and not os.path.exists(f"{TM}/{album}/cover.jpg"):
        shutil.copy(f"{T}/{album}/cover.jpg", f"{TM}/{album}/cover.jpg")

# 2. Songs, whatever the background score rules say (paths inside their library).
overrides = f"{JUKEBOX}/data/score-overrides.txt"
have = open(overrides).read() if os.path.exists(overrides) else ""
with open(overrides, "a") as f:
    for line in ["song: Hridayam (2022)/04 - Hridayam - Theme.mp3", "song: Remo (2016)/19 - Veshangalil Poiyillai.mp3"]:
        if line not in have:
            f.write(("" if not have or have.endswith("\n") else "\n") + line + "\n")
            have += line + "\n"
            note(override=line)

# 3. Jukebox's libraries: the masstamilan folders, Tamil's adding to the JioSaavn albums.
libraries = ("LIBRARIES=tamil=/music:film:tamil;tamil-masstamilan=/library/tamil/masstamilan:film:tamil:tamil;"
             "malayalam=/library/malayalam/masstamilan:film:malayalam;telugu=/library/telugu/masstamilan:film:telugu;"
             "hindi=/library/hindi/masstamilan:film:hindi")
env = f"{JUKEBOX}/.env"
shutil.copy(env, f"{JUKEBOX}/backups/env-before-masstamilan-{STAMP}")
lines = [libraries if line.startswith("LIBRARIES=") else line for line in open(env).read().splitlines()]
open(env, "w").write("\n".join(lines) + "\n")
note(env=libraries)
subprocess.run(["docker", "compose", "up", "-d"], cwd=JUKEBOX, check=True)

# 4. fulfil.py on this machine.
cfg = os.path.expanduser("~/.config/jukebox-fulfil.env")
text = open(cfg).read()
add = {"MUSIC_ROOT": ROOT, "LIBRARY_FOLDERS": "tamil-masstamilan=tamil/masstamilan,malayalam=malayalam/masstamilan,"
                                              "telugu=telugu/masstamilan,hindi=hindi/masstamilan"}
kept = [line for line in text.splitlines() if line.split("=", 1)[0] not in (*add, "LANGUAGES")]
open(cfg, "w").write("\n".join(kept + [f"{k}={v}" for k, v in add.items()]) + "\n")
note(fulfil_settings=add)

print("\nDone. Jukebox is restarting and rescans in a minute. Add the same two settings on the laptop.")
