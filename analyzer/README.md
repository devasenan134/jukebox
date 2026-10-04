# The audio analyzer (optional)

Jukebox makes mixes from your listening and the catalog's tags. The analyzer adds what tags can't say:
**how songs sound**. It listens to every song once with the [CLAP](https://github.com/LAION-AI/CLAP) model
and saves, for each recording:

- a *sound fingerprint* (512 numbers): songs that sound alike get similar ones. Stations, Daily Mixes and
  "sounds like this" use them;
- how well descriptions like "a sad melancholic song" or "a fast folk dance song with thappu drums" fit it,
  which is where the **mood mixes** come from (nobody tags moods by hand);
- tempo, energy, brightness and rhythm.

It only **reads** the music and Jukebox's database, and writes just its own `features.db`, which Jukebox reads
(`FEATURES_FOLDER` in Jukebox's `.env`). Songs are keyed by Jukebox's recording ids.

## Run it

```bash
cd analyzer
cp .env.example .env && nano .env      # Jukebox's data folder, the music folder, where features go
mkdir -p <ANALYZER_DATA>
docker compose up -d --build
docker logs -f jukebox-analyzer        # "Analyzing 3061 new or changed songs", then progress every 20 songs
```

The first run takes about 1.5 seconds per song on 4 cores (an Apple M1: about 80 minutes for 3,000 songs).
After that, only new or changed songs are analyzed, a few minutes after Jukebox's scan finds them.

## Coming from Isaipetti

The Isaipetti analyzer keyed songs by Navidrome's ids. Instead of listening to everything again, take its
results over (files are matched by path, so Navidrome and Jukebox must read the same music folder):

```bash
docker compose run --rm -v /path/to/isaipetti-analyzer/data:/old:ro -v /path/to/navidrome/data:/navidrome:ro \
  jukebox-analyzer adopt /old/features.db /navidrome/navidrome.db
```

Copy its `models` folder into `ANALYZER_DATA` too, to skip downloading the model again.
