# Song labels: moods and themes

The analyzer (CLAP) hears how a song sounds, but it guesses moods by comparing each song with a few sentences
("a sad melancholic song..."), and it gets kinds of music wrong: a kuthu could land in Chill. So every song also
gets labels from a language model, which knows most Tamil film songs and reads their lyrics.

## What a song gets

`scripts/labels/label_songs.py` sends Gemini each song's title, movie, year, composer, singers and the start of
its lyrics, 50 songs a request, and writes one JSON line per song:

- **moods**: 1–3 of the mix moods (chill, romance, happy, party, kuthu, mass, sad, melody, devotional,
  classical, sleep, workout);
- **energy** and **valence**: 1 (calm, sad) to 5 (intense, happy);
- **themes**: 0–3 of love, heartbreak, longing, mother, father, family, friendship, devotion, hero, philosophy,
  celebration, village, motivation, lullaby, patriotism, comedy, wedding, festival, rain, nature, dance, travel,
  college, night;
- **instrumental**, and **confidence** ("high" if it knows the song or the lyrics make it clear).

## How the mixes use them

- A mood mix takes a song only if its labels allow it (`MixMaker.labelFit`): kinds of music (Kuthu, Mass,
  Devotional, Carnatic, Sleep, Party, Workout) need the label; broad feelings (Chill, Happy, Sad, Melody,
  Romance) take an unlabelled-for-it song only if it sounds very much like it; calm moods never take energy 4–5,
  loud ones never energy 1–2. Then how it sounds sorts the songs that fit. Songs without labels go by sound.
- Seasons count matching themes like words in the title (rain → Rain Songs, festival → Deepavali...).

## Running it

```bash
# Labels for every song not labelled yet (resumable; it waits when the free tier says so, and stops at the daily limit)
python3 scripts/labels/label_songs.py --db <copy of jukebox.db> --out labels.jsonl --model gemini-3.5-flash-lite --batch 50
# Into the server's database
docker exec jukebox /app/bin/jukebox import-labels /app/data/labels.jsonl
```

The key is read from `$GEMINI_API_KEY` or `~/.config/jukebox/gemini.key`, never from this repository.

Free tier, October 2026: `gemini-3.5-flash` allows 20 requests a day; `gemini-3.5-flash-lite` agreed with it on
29 of 30 pilot songs and labels about 370 songs a minute. Older models (2.5) aren't open to new keys.

Known weak spots: songs from the last few months (the model doesn't know them yet) and less famous ones without
lyrics come back with confidence "low", and count less.
