"""
The Jukebox audio analyzer.

It listens to every song in Jukebox's catalog once and saves what the song sounds like:
- an "embedding": 512 numbers from the CLAP music model. Songs that sound alike have similar numbers.
  Jukebox uses them for radio, "sounds like this" and to group your taste into Daily Mixes.
- the same kind of numbers for short descriptions like "a sad melancholic song". Comparing a song's
  numbers with a description's tells how well the description fits, which is how mood mixes are made
  without anyone tagging moods by hand.
- tempo, energy, brightness and rhythm, measured from the audio.

Songs are Jukebox's recordings (one file each: the best copy), keyed by the recording's id. It only reads
the music and Jukebox's database; it writes nothing but its own features.db, which Jukebox reads.

Commands:
    python analyze.py watch            analyze new and changed songs, then check again every few minutes
    python analyze.py once             analyze new and changed songs, then stop
    python analyze.py sample 60        analyze 60 random songs (for trying it out)
    python analyze.py report           show which songs fit each description best
"""

import os
import random
import signal
import sqlite3
import subprocess
import sys
import time

import numpy as np

JUKEBOX_DB = os.environ.get("JUKEBOX_DB", "/jukebox/jukebox.db")
MUSIC_DIR = os.environ.get("MUSIC_DIR", "/music")
FEATURES_DB = os.environ.get("FEATURES_DB", "/data/features.db")
# larger_clap_music would fit better, but its text half is broken in transformers (every description
# comes out the same), so the general model is used; it tells Tamil film moods apart well.
MODEL = os.environ.get("CLAP_MODEL", "laion/larger_clap_general")
THREADS = int(os.environ.get("THREADS", "4"))
CHECK_MINUTES = int(os.environ.get("CHECK_MINUTES", "10"))

SAMPLE_RATE = 48_000  # what CLAP expects
WINDOW_SECONDS = 10
# Three 10-second pieces from different parts of the song, skipping the intro and outro.
WINDOW_POSITIONS = (0.2, 0.45, 0.7)

# Descriptions the server can compare songs with. Each has a few wordings; their average is used,
# which makes the match steadier. Keys are stable names the server refers to.
PROMPTS = {
    "happy": ["a happy cheerful upbeat song", "joyful bright feel-good music"],
    "sad": ["a sad melancholic song with emotional vocals", "a sorrowful slow heartbreak song"],
    "romantic": ["a romantic love song", "a tender romantic melody duet"],
    "party": ["an energetic party dance song with heavy beats", "loud festive dance music"],
    "chill": ["calm relaxing soft music", "a soothing peaceful song"],
    "devotional": ["a devotional religious hymn", "a spiritual temple bhajan song"],
    "heroic": ["a powerful heroic motivational anthem", "an intense epic song with big drums and brass"],
    "kuthu": ["a fast Indian folk dance song with loud thappu drums", "tamil kuthu folk percussion dance beat"],
    "melody": ["a soft Indian film melody with strings and flute", "a slow melodious song with gentle vocals"],
    "classical": ["Indian carnatic classical music with veena and mridangam", "a classical raga vocal performance"],
    "instrumental": ["instrumental music with no vocals", "an orchestral film background score without singing"],
    "lullaby": ["a gentle lullaby"],
    "retro": ["an old vintage film song recording from the 1960s", "a retro 1970s film song with old analog sound"],
    "electronic": ["modern electronic music with synthesizers", "an EDM track with electronic beats and drops"],
    "hiphop": ["a hip hop rap song", "rap vocals over a hip hop beat"],
    "rock": ["a rock song with electric guitars and drums"],
    "acoustic": ["an acoustic guitar song", "unplugged acoustic music"],
    "male": ["a song sung by a male singer"],
    # Which music world a song is from: the server uses these to keep languages apart in mixes.
    "indianfilm": ["an Indian film song", "a Tamil movie song with Indian instruments and vocals"],
    "western": ["a Western English pop song", "an English language song from the US or UK charts"],
    "female": ["a song sung by a female singer"],
}

stopping = False


def on_stop(*_):
    global stopping
    stopping = True
    print("Stopping after the current song...", flush=True)


# ---------- storage ----------

def open_features():
    os.makedirs(os.path.dirname(os.path.abspath(FEATURES_DB)), exist_ok=True)
    db = sqlite3.connect(FEATURES_DB)
    db.execute("PRAGMA journal_mode = WAL")
    db.executescript("""
        CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS songs (
            id TEXT PRIMARY KEY,          -- Jukebox's recording id
            path TEXT NOT NULL,
            file_version TEXT NOT NULL,   -- the file's size and modification time, to notice changed files
            analyzed_at INTEGER NOT NULL, -- milliseconds since 1970
            tempo REAL, energy REAL, brightness REAL, rhythm REAL,
            embedding BLOB,               -- 512 float32 numbers, length 1
            error TEXT                    -- why it couldn't be analyzed (then embedding is NULL)
        );
        CREATE TABLE IF NOT EXISTS prompts (key TEXT PRIMARY KEY, embedding BLOB NOT NULL);
    """)
    # A different model gives numbers that can't be compared with the old ones: start over.
    row = db.execute("SELECT value FROM meta WHERE key = 'model'").fetchone()
    if row and row[0] != MODEL:
        print(f"Model changed from {row[0]} to {MODEL}; analyzing everything again")
        db.execute("DELETE FROM songs")
        db.execute("DELETE FROM prompts")
    db.execute("INSERT OR REPLACE INTO meta VALUES ('model', ?)", (MODEL,))
    # Tells Jukebox the ids are its recordings' (the analyzer this one replaced used Navidrome's).
    db.execute("INSERT OR REPLACE INTO meta VALUES ('ids', 'recording')")
    db.commit()
    return db


def catalog():
    """Jukebox's database, read-only (Jukebox keeps writing to it)."""
    return sqlite3.connect(f"file:{JUKEBOX_DB}?mode=ro", uri=True, timeout=30)


def jukebox_songs():
    """Every recording with a file on disk: {recording id: (path, file version, duration in seconds)}.

    A recording can be in several files (a film's soundtrack and a single); the best copy is listened to.
    """
    db = catalog()
    try:
        rows = db.execute(
            "SELECT f.recording_id, f.path, f.size, f.mtime, f.duration_ms FROM files f "
            "JOIN recordings r ON r.id = f.recording_id "
            "WHERE f.missing_since IS NULL AND r.merged_into IS NULL "
            "ORDER BY f.recording_id, coalesce(f.bitrate, 0) DESC, f.id"
        ).fetchall()
    finally:
        db.close()
    songs = {}
    for recording, path, size, mtime, duration_ms in rows:
        if recording in songs:
            continue
        # Jukebox keeps paths relative to its music folder, which is MUSIC_DIR here.
        songs[recording] = (os.path.join(MUSIC_DIR, path), f"{size}-{mtime}", (duration_ms or 0) / 1000)
    return songs


# ---------- the model ----------

class Clap:
    def __init__(self):
        import torch
        from transformers import ClapModel, ClapProcessor

        torch.set_num_threads(THREADS)
        print(f"Loading {MODEL} (downloaded once, about 800 MB)...", flush=True)
        self.torch = torch
        self.model = ClapModel.from_pretrained(MODEL).eval()
        self.processor = ClapProcessor.from_pretrained(MODEL)

    def audio(self, windows):
        """One embedding for a song from its windows: the average of each window's, length 1."""
        with self.torch.inference_mode():
            inputs = self.processor(audio=windows, sampling_rate=SAMPLE_RATE, return_tensors="pt")
            vectors = self.model.get_audio_features(**inputs).numpy()
        return normalize(normalize(vectors).mean(axis=0))

    def text(self, phrases):
        with self.torch.inference_mode():
            inputs = self.processor(text=phrases, return_tensors="pt", padding=True)
            vectors = self.model.get_text_features(**inputs).numpy()
        return normalize(normalize(vectors).mean(axis=0))


def normalize(v):
    return v / np.linalg.norm(v, axis=-1, keepdims=True).clip(min=1e-9)


def prompts_current(db):
    known = {key for (key,) in db.execute("SELECT key FROM prompts")}
    stored_version = db.execute("SELECT value FROM meta WHERE key = 'prompts'").fetchone()
    return known == set(PROMPTS) and stored_version and stored_version[0] == str(sorted((k, v) for k, v in PROMPTS.items()))


def save_prompts(db, clap):
    """Recomputes the description embeddings when the list above changed."""
    if prompts_current(db):
        return
    wanted_version = str(sorted((k, v) for k, v in PROMPTS.items()))
    db.execute("DELETE FROM prompts")
    for key, phrases in PROMPTS.items():
        db.execute("INSERT INTO prompts VALUES (?, ?)", (key, clap.text(phrases).astype(np.float32).tobytes()))
    db.execute("INSERT OR REPLACE INTO meta VALUES ('prompts', ?)", (wanted_version,))
    db.commit()
    print(f"Saved {len(PROMPTS)} descriptions", flush=True)


# ---------- listening ----------

def decode(path, start, seconds, rate):
    """Mono float audio from [start, start + seconds), decoded by ffmpeg."""
    out = subprocess.run(
        ["ffmpeg", "-v", "error", "-nostdin", "-ss", f"{start:.2f}", "-t", str(seconds), "-i", path,
         "-ac", "1", "-ar", str(rate), "-f", "f32le", "-"],
        capture_output=True, timeout=120,
    )
    if out.returncode != 0:
        raise RuntimeError(out.stderr.decode(errors="replace").strip()[:300] or "ffmpeg failed")
    return np.frombuffer(out.stdout, dtype=np.float32)


def measure(y, sr):
    """Tempo (BPM), energy (loudness in dB), brightness (Hz) and rhythm (how punchy the beats are)."""
    import librosa

    tempo, _ = librosa.beat.beat_track(y=y, sr=sr)
    rms = librosa.feature.rms(y=y)[0]
    energy = float(20 * np.log10(max(float(np.mean(rms)), 1e-6)))
    brightness = float(np.mean(librosa.feature.spectral_centroid(y=y, sr=sr)))
    rhythm = float(np.mean(librosa.onset.onset_strength(y=y, sr=sr)))
    return float(np.atleast_1d(tempo)[0]), energy, brightness, rhythm


def analyze(clap, path, duration):
    if duration <= 0:
        duration = 240
    windows = []
    for pos in WINDOW_POSITIONS:
        start = max(0.0, min(duration * pos, duration - WINDOW_SECONDS))
        audio = decode(path, start, WINDOW_SECONDS, SAMPLE_RATE)
        if len(audio) >= SAMPLE_RATE * 2:  # at least 2 seconds
            windows.append(audio)
    if not windows:
        raise RuntimeError("no audio")
    embedding = clap.audio(windows)
    # 30 seconds from the middle for tempo and energy (at a lower rate, which is plenty for that).
    middle = decode(path, max(0.0, duration / 2 - 15), 30, 22_050)
    return embedding, measure(middle, 22_050)


def run(db, clap, songs):
    total = len(songs)
    started = time.time()
    for n, (song_id, (path, updated, duration)) in enumerate(songs.items(), start=1):
        if stopping:
            break
        try:
            embedding, (tempo, energy, brightness, rhythm) = analyze(clap, path, duration)
            db.execute(
                "INSERT OR REPLACE INTO songs VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)",
                (song_id, path, updated, int(time.time() * 1000), tempo, energy, brightness, rhythm,
                 embedding.astype(np.float32).tobytes()),
            )
        except Exception as e:  # a broken file shouldn't stop the rest
            db.execute(
                "INSERT OR REPLACE INTO songs VALUES (?, ?, ?, ?, NULL, NULL, NULL, NULL, NULL, ?)",
                (song_id, path, updated, int(time.time() * 1000), str(e)[:300]),
            )
            print(f"  couldn't analyze {path}: {e}", flush=True)
        if n % 20 == 0 or n == total:
            db.commit()
            per_song = (time.time() - started) / n
            left = (total - n) * per_song / 60
            print(f"  {n}/{total} songs, {per_song:.1f}s each, about {left:.0f} min left", flush=True)
    db.commit()


def pending(db, songs):
    """Songs that are new or whose file changed since they were analyzed."""
    done = dict(db.execute("SELECT id, file_version FROM songs"))
    return {sid: s for sid, s in songs.items() if done.get(sid) != s[1]}


def forget_removed(db, songs):
    gone = [sid for (sid,) in db.execute("SELECT id FROM songs") if sid not in songs]
    db.executemany("DELETE FROM songs WHERE id = ?", [(sid,) for sid in gone])
    db.commit()
    if gone:
        print(f"Forgot {len(gone)} songs that left the library", flush=True)


class LazyClap:
    """The model is big (about 2 GB in memory): it's loaded only when there's something to listen to, and let
    go after, so the analyzer takes little memory while it waits for new songs."""

    def __init__(self):
        self.clap = None

    def get(self):
        if self.clap is None:
            self.clap = Clap()
        return self.clap

    def release(self):
        if self.clap is not None:
            self.clap = None
            import gc
            gc.collect()


def sync(db, lazy):
    if not prompts_current(db):
        save_prompts(db, lazy.get())
    songs = jukebox_songs()
    forget_removed(db, songs)
    todo = pending(db, songs)
    if todo:
        print(f"Analyzing {len(todo)} new or changed songs", flush=True)
        run(db, lazy.get(), todo)
        db.execute("INSERT OR REPLACE INTO meta VALUES ('updated_at', ?)", (str(int(time.time() * 1000)),))
        db.commit()
    return len(todo)


# ---------- trying it out ----------

def report(db):
    """For each description, the songs that fit it best (compared with how well it fits songs in general)."""
    jb = catalog()
    names = {rid: f"{title} — {album} ({year or '?'})"
             for rid, title, album, year in jb.execute(
                 "SELECT r.id, r.title, a.title, a.year FROM recordings r JOIN tracks t ON t.recording_id = r.id "
                 "JOIN releases rl ON rl.id = t.release_id JOIN albums a ON a.id = rl.album_id")}
    rows = db.execute("SELECT id, tempo, energy, embedding FROM songs WHERE embedding IS NOT NULL").fetchall()
    ids = [r[0] for r in rows]
    songs = np.stack([np.frombuffer(r[3], dtype=np.float32) for r in rows])
    prompts = {k: np.frombuffer(e, dtype=np.float32) for k, e in db.execute("SELECT key, embedding FROM prompts")}
    scores = {k: songs @ v for k, v in prompts.items()}
    # How much more a description fits this song than the others (z-score), so descriptions compare fairly.
    z = {k: (s - s.mean()) / (s.std() + 1e-9) for k, s in scores.items()}
    for key in prompts:
        print(f"\n== {key} ==")
        for i in np.argsort(-z[key])[:6]:
            print(f"  {z[key][i]:+.1f}  {names.get(ids[i], ids[i])}")
    print("\n== sounds like (nearest neighbours) ==")
    for i in random.Random(1).sample(range(len(ids)), min(5, len(ids))):
        sims = songs @ songs[i]
        near = [j for j in np.argsort(-sims) if j != i][:3]
        print(f"  {names.get(ids[i], ids[i])}")
        for j in near:
            print(f"      {sims[j]:.2f}  {names.get(ids[j], ids[j])}")
    tempos = np.array([r[1] for r in rows])
    print(f"\ntempo: median {np.median(tempos):.0f} BPM, range {tempos.min():.0f}–{tempos.max():.0f}")


def main():
    signal.signal(signal.SIGTERM, on_stop)
    signal.signal(signal.SIGINT, on_stop)
    command = sys.argv[1] if len(sys.argv) > 1 else "watch"
    db = open_features()
    if command == "report":
        report(db)
        return
    lazy = LazyClap()
    if command == "sample":
        clap = lazy.get()
        save_prompts(db, clap)
        count = int(sys.argv[2]) if len(sys.argv) > 2 else 60
        songs = jukebox_songs()
        picked = random.Random(42).sample(sorted(songs), min(count, len(songs)))
        run(db, clap, {sid: songs[sid] for sid in picked})
        report(db)
    elif command == "once":
        sync(db, lazy)
    else:
        print(f"Watching for new songs every {CHECK_MINUTES} minutes", flush=True)
        while not stopping:
            try:
                sync(db, lazy)
                lazy.release()
            except Exception as e:
                print(f"Check failed, trying again later: {e}", flush=True)
            for _ in range(CHECK_MINUTES * 60):
                if stopping:
                    break
                time.sleep(1)


if __name__ == "__main__":
    main()
