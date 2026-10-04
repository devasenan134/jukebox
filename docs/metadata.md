# People in the library: music directors, singers, lyricists

The files came from JioSaavn with their people in the tags, and those tags were often wrong: singers listed
as a film's composers, lyricists missing everywhere, one person under five spellings, "Chorus" and "Various
Artiste" as people. This is how the library gets them right, and how to do it again.

## Sources

- **JioSaavn's album replies** (cached by the tamil-catalog crawler in `cache.db`): each song lists its people
  with a role (`music`, `singer`, `lyricist`, `starring`) and a JioSaavn artist id.
- **Wikidata**: the composer of each Tamil film, with an id, English and Tamil names, aliases and a photo.
- **The catalog** (`catalog.db`): which film each JioSaavn album is.

What the real data showed (2026-10-04):

- JioSaavn's artist ids are **not one per person** (Ilaiyaraaja has 17, S. P. Balasubrahmanyam 27), and an id
  sometimes carries a wrong name ("Revathy" and "Shamili" on one id). Ids only join spellings that look alike.
- JioSaavn often lists a song's **singers or lyricists as its "music"**, mostly on old films. Wikidata and
  JioSaavn agreed on 2,107 films, JioSaavn added extra names on 1,008, and they disagreed on 671, mostly over
  spelling ("Thaman S" / "S. Thaman") and the rest JioSaavn's mistakes.
- JioSaavn calls Srikanth Deva "Sri".

## Rules (`scripts/metadata/credits_feed.py`)

1. **One person, many spellings.** Spellings are joined when Wikidata lists them as one person's names, when
   JioSaavn puts alike spellings on one artist id, when a film's JioSaavn composer is a spelling of its
   Wikidata composer, or when a JioSaavn name is the composer of three or more films that Wikidata gives to one
   other person (and nearly all its films). Two Wikidata people are never joined. A person's name is their
   usual spelling, unless it doesn't look like their Wikidata name (then Wikidata's).
2. **Names are cleaned**: "K J Yesudas Chorus" → K J Yesudas, "(ADK)" tails dropped, "Anahita & Apoorva"
   split in two, "Chorus", "Various Artiste", "N.A" dropped.
3. **A film's music directors** are decided by vote:
   - Wikidata's composers that JioSaavn also names, plus anyone JioSaavn names on half the songs who isn't a
     suspect (see below);
   - else JioSaavn's composer of at least half the songs;
   - else Wikidata's, when JioSaavn names nobody clearly;
   - else (anthologies) everyone JioSaavn names on two or more songs.

   Suspects: someone named as composer only on songs they also sing or write, or someone who across all films
   is named as "music" mostly where Wikidata confirms someone else (T. M. Soundararajan on 1960s films).
4. **A song's composer** is its own JioSaavn composer when that's one of the film's music directors, else the
   film's music directors. **Singers** are JioSaavn's singers; when a song has none, its primary artists minus
   its composer, lyricists and actors. **Lyricists** and **actors** as JioSaavn lists them.

Albums the sources disagree on go to `review.csv` (776 on 2026-10-04) for checking by hand later.

## Into the files and Jukebox (`scripts/metadata/retag.py`)

The feed is written into the files' own tags, so the files are right without Jukebox too: singers in artist,
the song's composer in composer, the album's music directors in album artist (the same on every file of an
album: those the feed gives at least 30% of its songs) and lyricists in `LYRICIST`. Only those four tags
change, and every old value is logged first (`--undo` puts them back). Jukebox reads them on its next scan; a
retagged album starts its composers over, so the wrong ones go.

To run it again (on the Mac mini, in `~/jukebox-metadata`):

```sh
python credits_feed.py --cache ~/tamil-catalog/cache.db --catalog ~/tamil-catalog/catalog.db \
    --wikidata wd_film_composers.json --out credits.jsonl
# a copy of the live database: which JioSaavn song each file is, and its album
docker run --rm --user $(id -u):$(id -g) -v /mnt/ugreen/jukebox:/j:ro -v $PWD:/o keinos/sqlite3 \
    sqlite3 "file:/j/data/jukebox.db?mode=ro" ".backup /o/jukebox.db"
uv run --no-project --with mutagen python retag.py --feed credits.jsonl --db jukebox.db --root <music folder>          # dry run
# stop Jukebox, back up data/jukebox.db*, then:
uv run --no-project --with mutagen python retag.py --feed credits.jsonl --db jukebox.db --root <music folder> --apply
# start Jukebox: its startup scan reads the changed files
```

## Later

- A review page for admins (merge, split, set a film's music directors), replacing `people-overrides.txt`.
- Person ids (Wikidata, JioSaavn) and photos in Jukebox itself; actors per song in search.
- New downloads: run the two scripts again (they only change files whose tags differ).
