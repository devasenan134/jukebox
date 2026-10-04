"""
Builds Jukebox's credits feed: who composed, sang, wrote and starred in each song, with one identity per
person however JioSaavn spells them. Jukebox reads the feed over the files' tags (docs/metadata.md).

Sources (all read offline):
  - JioSaavn album replies cached by the tamil-catalog crawler (cache.db): every song lists its people with
    a role (music, singer, lyricist, starring) and a JioSaavn artist id. Those ids are not one per person
    (Ilaiyaraaja has 17), and an id sometimes carries a wrong name, so they only help join spellings.
  - Wikidata's composer of each film (people with ids, English and Tamil names, aliases and a photo).
  - The catalog (catalog.db), which says which film each JioSaavn album is.

    python credits_feed.py --cache cache.db --catalog catalog.db --out credits.jsonl [--fetch-wikidata]

Writes credits.jsonl and review.csv (albums whose music directors the sources disagree on).
"""

import argparse
import collections
import csv
import html
import json
import os
import re
import unicodedata
import urllib.parse
import urllib.request

UA = "Mozilla/5.0 (X11; Linux x86_64) jukebox-metadata/0.1 (personal music library)"

WIKIDATA_QUERY = """
SELECT ?film ?c ?en ?ta (GROUP_CONCAT(DISTINCT ?alias; separator="|") AS ?aliases) (SAMPLE(?img) AS ?image) WHERE {
  ?film wdt:P364 wd:Q5885; wdt:P31 wd:Q11424; wdt:P86 ?c.
  OPTIONAL { ?c rdfs:label ?en. FILTER(LANG(?en) = "en") }
  OPTIONAL { ?c rdfs:label ?ta. FILTER(LANG(?ta) = "ta") }
  OPTIONAL { ?c skos:altLabel ?alias. FILTER(LANG(?alias) IN ("en", "ta")) }
  OPTIONAL { ?c wdt:P18 ?img }
} GROUP BY ?film ?c ?en ?ta
"""


# ---------------------------------------------------------------- names (same rules as the server's Fuzzy)

_SPELLINGS = [("zh", "l"), ("ae", "e"), ("ai", "e"), ("ay", "e"), ("ee", "i"), ("oo", "u"), ("ou", "u"),
              ("w", "v"), ("y", "i"), ("q", "k"), ("x", "ks"), ("z", "s")]
_SOFT_H = re.compile(r"(?<=[bcdgjklmnprstv])h")
_REPEATS = re.compile(r"(.)\1+")
_BRACKETS = re.compile(r"\(.*?\)|\[.*?\]")
_NON_WORD = re.compile(r"[^\w]+|_")


def sound(word):
    if any(not ("a" <= ch <= "z" or "0" <= ch <= "9") for ch in word):
        return word
    for a, b in _SPELLINGS:
        word = word.replace(a, b)
    word = _SOFT_H.sub("", word)
    word = word.translate(str.maketrans("gdbj", "ktps"))
    return _REPEATS.sub(r"\1", word)


def words(text):
    text = _BRACKETS.sub(" ", text.lower().replace(".", " "))
    return [w for w in (sound(x) for x in _NON_WORD.split(text) if x) if w]


def key(name):
    """The server's Names.personKey: "M.S. Viswanathan" and "M. S. Viswanathan" give the same."""
    return "".join(words(name))


def similarity(a, b):
    if a == b:
        return 1.0
    prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        cur = [i] + [0] * len(b)
        for j in range(1, len(b) + 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] != b[j - 1]))
        prev = cur
    return 1.0 - prev[len(b)] / max(len(a), len(b))


def vowel_typo(a, b):
    if a == b:
        return True
    if abs(len(a) - len(b)) > 1:
        return False
    if len(a) == len(b):
        diff = [i for i in range(len(a)) if a[i] != b[i]]
        return len(diff) == 1 and a[diff[0]] in "aeiou" and b[diff[0]] in "aeiou"
    long, short = (a, b) if len(a) > len(b) else (b, a)
    return any(long[i] in "aeiou" and long[:i] + long[i + 1:] == short for i in range(len(long)))


def parts(name):
    ws = words(name)
    return "".join(w for w in ws if len(w) == 1), [w for w in ws if len(w) > 1]


def alike(a, b):
    """Two spellings JioSaavn put on one artist id, or Wikidata and JioSaavn put on one film, that really are
    one person: the same full words in any order (initials may be missing on one side), or initials standing for
    the other's words ("S.P.B." = "S.P. Balasubrahmanyam")."""
    if key(a) == key(b):
        return True
    ia, wa = parts(a)
    ib, wb = parts(b)
    if not wa or not wb:
        letters = (ia or "".join(wa)), (ib or "".join(wb))
        if not wa and len(ia) >= 2:
            return ia == ib + "".join(w[0] for w in wb) or ia == "".join(w[0] for w in wb) + ib
        if not wb and len(ib) >= 2:
            return ib == ia + "".join(w[0] for w in wa) or ib == "".join(w[0] for w in wa) + ia
        return letters[0] == letters[1]
    if ia and ib and ia != ib and not (set(ia) <= set(ib) or set(ib) <= set(ia)):
        return False
    if len(wa) == len(wb) == 1 and not ia and not ib:
        # One bare word: one vowel changed, added or dropped at most (Ilaiyaraaja / Ilayaraja), never a consonant
        # (Hariharan / Haricharan, Mano / Manoj).
        return vowel_typo(wa[0], wb[0])
    if len(wa) == len(wb):
        sa, sb = sorted(wa), sorted(wb)
        if all(similarity(x, y) >= 0.75 for x, y in zip(sa, sb)):
            return True
        return similarity("".join(wa), "".join(wb)) >= 0.85
    # One name has more full words ("Ghibran" / "Mohamaad Ghibran"): the shorter one's words must all be in the
    # longer one, and be the longest word there (a surname or the name people use, not a stray initial).
    # Nearly exact words only ("Thaman S" is not "Anthony Daasan"), and the shorter one's initials must be the
    # longer one's initials or the first letters of its other words.
    (si, short), (li, long) = ((ia, wa), (ib, wb)) if len(wa) < len(wb) else ((ib, wb), (ia, wa))
    if not set(si) <= set(li) | {w[0] for w in long}:
        return False
    return all(any(similarity(x, y) >= 0.9 for y in long) for x in short) and max(len(x) for x in short) >= 5


# Names that aren't a person (or not one we can tell): never credited.
NOT_PEOPLE = {"various artists", "various artiste", "various", "unknown", "unknown artist", "n a", "na", "chorus",
              "kids chorus", "group", "traditional", "others", "all", "lrc", "vbr"}
_CHORUS = re.compile(r"\s*(&\s*)?(and\s+)?(chorus|group|party)\s*$", re.I)
_TAIL = re.compile(r"\s*\((?:traditional|[A-Z0-9]{2,6})\)\s*$")


def clean(name):
    """A person's name without what isn't their name: "K J Yesudas Chorus" → "K J Yesudas",
    "Aaryan Dinesh Kanagaratnam (ADK)" → "Aaryan Dinesh Kanagaratnam"; None when there's no person left."""
    name = html.unescape(unicodedata.normalize("NFC", name or ""))
    name = re.sub(r"\s+", " ", name).strip(" ,-")
    name = _TAIL.sub("", name)
    name = _CHORUS.sub("", name).strip(" ,-&")
    if len(name) < 2 or name.lower().replace(".", " ").replace("/", " ").strip() in NOT_PEOPLE or not key(name):
        return None
    return name


# ---------------------------------------------------------------- reading the sources

ROLES = {"music": "composer", "singer": "singer", "lyricist": "lyricist", "starring": "actor"}


def read_saavn(cache):
    """{JioSaavn album id: [(song id, [(role, name, artist id)])]} from the cached album replies."""
    import sqlite3
    albums = {}
    for _, body in sqlite3.connect(cache).execute("SELECT url, body FROM http WHERE url LIKE '%getAlbumDetails%'"):
        try:
            d = json.loads(body)
        except ValueError:
            continue
        if not isinstance(d, dict) or not d.get("id"):
            continue
        songs = []
        for s in d.get("list") or []:
            artist_map = (s.get("more_info") or {}).get("artistMap") or {}
            artists = [a for a in artist_map.get("artists") or [] if a.get("role") in ROLES]
            # Some songs have no singer role: their primary artists are the singers mixed with the composer and
            # lyricists. The singers are the rest (or, when nobody's left, the composer singing their own song).
            if not any(a["role"] == "singer" for a in artists):
                others = {a.get("name") for a in artists if a["role"] in ("music", "lyricist", "starring")}
                writers = {a.get("name") for a in artists if a["role"] in ("lyricist", "starring")}
                primary = artist_map.get("primary_artists") or []
                sang = [a for a in primary if a.get("name") not in others] or [a for a in primary if a.get("name") not in writers]
                artists += [dict(a, role="singer") for a in sang]
            people = []
            for a in artists:
                # One entry naming two people ("Anahita & Apoorva"): each on their own, without the shared id.
                names = [n for n in re.split(r"\s*(?:&|,)\s*", html.unescape(a.get("name") or "")) if len(n.strip()) > 1]
                for n in names:
                    people.append((ROLES[a["role"]], n, (a.get("id") or "") if len(names) == 1 else ""))
            songs.append((s["id"], people))
        albums[str(d["id"])] = songs
    return albums


def read_catalog(path):
    """{JioSaavn album id: film id}, and {film id: (title, year)}."""
    import sqlite3
    db = sqlite3.connect(path)
    films = {q: (t, y) for q, t, y in db.execute("SELECT qid, title, year FROM films")}
    album_film = {i: q for i, q in db.execute("SELECT id, qid FROM albums WHERE source = 'saavn' AND qid IS NOT NULL")}
    return album_film, films


def read_wikidata(path, fetch):
    if fetch or not os.path.exists(path):
        url = "https://query.wikidata.org/sparql?" + urllib.parse.urlencode({"query": WIKIDATA_QUERY, "format": "json"})
        d = json.load(urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=300))
        rows = []
        for b in d["results"]["bindings"]:
            v = lambda k: b.get(k, {}).get("value")
            rows.append(dict(film=v("film").rsplit("/", 1)[1], qid=v("c").rsplit("/", 1)[1], en=v("en"), ta=v("ta"),
                             aliases=[a for a in (v("aliases") or "").split("|") if a], image=v("image")))
        with open(path, "w") as f:
            json.dump(rows, f, ensure_ascii=False)
    with open(path) as f:
        rows = json.load(f)
    people = {}
    film_composers = collections.defaultdict(list)
    for r in rows:
        people[r["qid"]] = r
        if r["qid"] not in film_composers[r["film"]]:
            film_composers[r["film"]].append(r["qid"])
    return people, film_composers


# ---------------------------------------------------------------- one identity per person

class People:
    """Spellings (by key) joined into people with a union-find: Wikidata's names and aliases of one person,
    alike spellings on one JioSaavn id, and a film's JioSaavn composer matched to its Wikidata composer."""

    def __init__(self):
        self.parent = {}
        self.spellings = collections.defaultdict(collections.Counter)  # key -> original spellings
        self.saavn = collections.defaultdict(set)                      # key -> JioSaavn ids
        self.wikidata = {}                                             # key -> qid
        self.why = collections.Counter()

    def find(self, k):
        self.parent.setdefault(k, k)
        while self.parent[k] != k:
            self.parent[k] = self.parent[self.parent[k]]
            k = self.parent[k]
        return k

    def union(self, a, b, why):
        ra, rb = self.find(a), self.find(b)
        if ra == rb:
            return
        # Never join two Wikidata people.
        qa, qb = self.qid(ra), self.qid(rb)
        if qa and qb and qa != qb:
            self.why["refused: two wikidata people"] += 1
            return
        self.parent[rb] = ra
        self.why[why] += 1

    def qid(self, root):
        return self.wikidata.get(root)

    def add(self, name, saavn_id="", n=1):
        k = key(name)
        self.find(k)
        self.spellings[k][name] += n
        if saavn_id:
            self.saavn[k].add(saavn_id)
        return k

    def add_wikidata(self, p):
        names = [n for n in [p.get("en")] + p.get("aliases", []) if n and clean(n)]
        if not names:
            return
        main = self.add(clean(names[0]), n=0)
        root = self.find(main)
        if self.qid(root) and self.qid(root) != p["qid"]:
            return  # two Wikidata people with the same spelling: leave the second out
        self.wikidata[root] = p["qid"]
        for n in names[1:]:
            if re.search(r"[a-zA-Z]", n) and alike(names[0], n):
                k = self.add(clean(n), n=0)
                if self.qid(self.find(k)) in (None, p["qid"]):
                    self.union(main, k, "wikidata alias")

    def fix_roots(self):
        """After joining, the qid lives on the root."""
        by_root = {}
        for k, q in list(self.wikidata.items()):
            by_root[self.find(k)] = q
        self.wikidata = by_root

    def groups(self):
        g = collections.defaultdict(list)
        for k in self.parent:
            g[self.find(k)].append(k)
        return g


def build(args):
    saavn = read_saavn(args.cache)
    album_film, films = read_catalog(args.catalog)
    wd_people, wd_films = read_wikidata(args.wikidata, args.fetch_wikidata)
    print(f"JioSaavn: {len(saavn)} albums, {sum(len(s) for s in saavn.values())} songs; Wikidata: {len(wd_people)} composers of {len(wd_films)} films")

    people = People()
    for p in wd_people.values():
        people.add_wikidata(p)
    people.fix_roots()

    # Every spelling, and which JioSaavn ids it was seen on.
    by_id = collections.defaultdict(collections.Counter)
    for songs in saavn.values():
        for _, credits in songs:
            for role, name, aid in credits:
                n = clean(name)
                if n:
                    people.add(n, aid)
                    if aid:
                        by_id[aid][n] += 1
    # Spellings on one JioSaavn id: alike ones are one person (an id also carries the odd wrong name).
    for aid, names in by_id.items():
        main = names.most_common(1)[0][0]
        for n, _ in names.most_common()[1:]:
            if alike(main, n):
                people.union(key(main), key(n), "same jiosaavn id")
    people.fix_roots()

    # A film's JioSaavn composer that is one of its Wikidata composers by name.
    for album_id, songs in saavn.items():
        film = album_film.get(album_id)
        wd = wd_films.get(film or "")
        if not wd:
            continue
        music = {clean(name) for _, credits in songs for role, name, _ in credits if role == "composer" and clean(name)}
        for n in music:
            if people.qid(people.find(key(n))):
                continue
            hits = [q for q in wd if any(alike(n, w) for w in [wd_people[q].get("en")] + wd_people[q].get("aliases", []) if w)]
            if len(hits) == 1:
                q = hits[0]
                qroot = next((r for r, x in people.wikidata.items() if x == q), None)
                if qroot:
                    people.union(qroot, key(n), "film composer on wikidata")
                    people.fix_roots()
    # A JioSaavn name that is the composer of film after film whose Wikidata composer is one other person, is that
    # person under another name (JioSaavn calls Srikanth Deva "Sri"). Needs three films and nearly all of its films.
    pairs, tops = collections.Counter(), collections.Counter()
    for album_id, songs in saavn.items():
        wd = wd_films.get(album_film.get(album_id) or "")
        if not wd or len(wd) != 1:
            continue
        music = collections.Counter()
        for _, credits in songs:
            music.update({people.find(key(clean(n))) for r, n, _ in credits if r == "composer" and clean(n)})
        if not music:
            continue
        top, c = music.most_common(1)[0]
        if c < len(songs) * 0.5 or people.qid(top):
            continue
        tops[top] += 1
        if not any(people.qid(r) == wd[0] for r in music):
            pairs[(top, wd[0])] += 1
    for (top, q), c in pairs.items():
        qroot = next((r for r, x in people.wikidata.items() if x == q), None)
        if qroot and c >= 3 and c >= 0.8 * tops[top] and not people.qid(people.find(top)):
            people.union(qroot, top, "same films as a wikidata composer")
            people.fix_roots()
    print("joined:", dict(people.why))

    # The people: id, name, aliases, ids elsewhere.
    groups = people.groups()
    person_of = {}
    persons = {}
    for root, keys in groups.items():
        spellings = collections.Counter()
        ids = set()
        for k in keys:
            spellings.update(people.spellings[k])
            ids |= people.saavn[k]
        q = people.qid(root)
        wp = wd_people.get(q) if q else None
        # Their usual spelling (the one JioSaavn uses most) when it's a spelling of Wikidata's name, else Wikidata's.
        usual = next((s for s, c in spellings.most_common(1) if c > 0), None)
        wd_name = clean(wp.get("en") or "") if wp else None
        name = usual if usual and (not wd_name or alike(usual, wd_name)) else wd_name or usual
        if not name:
            continue
        # The id Jukebox knows them by: Wikidata's, else their oldest JioSaavn id, else the spelling.
        pid = f"wd:{q}" if q else f"sv:{min(ids, key=lambda i: (len(i), i))}" if ids else f"nm:{root}"
        for k in keys:
            person_of[k] = pid
        aliases = sorted({s for s in spellings if s != name} | ({a for a in wp.get("aliases", []) if a != name} if wp else set()))
        persons[pid] = {"type": "person", "id": pid, "name": name, "aliases": aliases, "saavn": sorted(ids),
                        **({"wikidata": q, "name_ta": wp.get("ta"), "image": wp.get("image")} if wp else {})}

    def pid(name):
        n = clean(name)
        return person_of.get(key(n)) if n else None

    # Music directors of each JioSaavn album, then each song's credits.
    review = []
    recordings = []
    used = set()
    decided = collections.Counter()

    def album_music(songs):
        music, doubled = collections.Counter(), collections.Counter()
        for sid, credits in songs:
            ms = {pid(n) for r, n, _ in credits if r == "composer"} - {None}
            music.update(ms)
            doubled.update(ms & {pid(n) for r, n, _ in credits if r in ("singer", "lyricist")})
        return music, doubled

    # Across all films: who JioSaavn names as "music" mostly on films Wikidata gives to someone else (T. M.
    # Soundararajan on 1960s films) rather than leading films themselves. They're never an extra music director.
    led, leaked = collections.Counter(), collections.Counter()
    for album_id, songs in saavn.items():
        wd = [f"wd:{q}" for q in wd_films.get(album_film.get(album_id) or "", [])]
        music, _ = album_music(songs)
        confirmed = [p for p in wd if p in music]
        for p, c in music.items():
            if p in wd:
                continue
            if confirmed:
                leaked[p] += 1
            elif c >= len(songs) * 0.5:
                led[p] += 1
    leakers = {p for p in leaked if leaked[p] > led[p]}

    for album_id, songs in saavn.items():
        film = album_film.get(album_id)
        wd = [f"wd:{q}" for q in wd_films.get(film or "", [])]
        music, doubled = album_music(songs)
        n_songs = max(1, len(songs))
        # JioSaavn sometimes lists a song's singers or lyricists as its "music" too (often on old films): someone
        # named as composer only on songs they also sing or write is suspect when there's a better candidate.
        def mostly_sings(p):
            return p not in wd and (doubled[p] >= music[p] * 0.8 or p in leakers)
        # Someone JioSaavn calls the composer of at least half the songs.
        coherent = [p for p, c in music.most_common() if c >= n_songs * 0.5]
        coherent = [p for p in coherent if not mostly_sings(p)] or coherent[:1]
        confirmed = [p for p in wd if p in music]
        if confirmed:
            directors, how = confirmed + [p for p in coherent if p not in confirmed and not mostly_sings(p)], "wikidata + jiosaavn"
        elif wd and not coherent:
            directors, how = wd, "wikidata (jiosaavn unclear)"
        elif coherent:
            directors, how = coherent, "jiosaavn" if not wd else "jiosaavn (wikidata disagrees)"
        else:
            # Each song by someone else (an anthology), or nobody clear: every composer JioSaavn names on two or more
            # songs, else on any.
            candidates = [p for p, _ in music.most_common() if not mostly_sings(p)] or [p for p, _ in music.most_common()]
            directors = [p for p in candidates if music[p] >= 2] or candidates
            how = "jiosaavn, no clear composer"
        decided[how] += 1
        if how in ("jiosaavn (wikidata disagrees)", "jiosaavn, no clear composer", "wikidata (jiosaavn unclear)") or len(directors) > 2:
            title, year = films.get(film, ("", ""))
            review.append(dict(saavn_album=album_id, film=film or "", title=title, year=year, decided=how,
                               music_directors=" | ".join(persons[p]["name"] for p in directors if p in persons),
                               wikidata=" | ".join(persons[p]["name"] for p in wd if p in persons),
                               jiosaavn_music=" | ".join(f"{persons[p]['name']} ({c})" for p, c in music.most_common() if p in persons)))

        for sid, credits in songs:
            out = collections.defaultdict(list)
            for r, n, _ in credits:
                p = pid(n)
                if p and p not in out[r]:
                    out[r].append(p)
            # A song's composer: its JioSaavn composer when that's one of the album's music directors (an album with
            # several has each song by one of them), else the album's music directors.
            own = [p for p in out.get("composer", []) if p in directors]
            out["composer"] = own or list(directors)
            line = {"type": "recording", "saavn_id": sid, "album_composers": directors,
                    **{r: out[r] for r in ("composer", "singer", "lyricist", "actor") if out.get(r)}}
            recordings.append(line)
            for r in ("composer", "singer", "lyricist", "actor"):
                used.update(out.get(r, []))
            used.update(directors)

    with open(args.out, "w") as f:
        for p in sorted(used):
            if p in persons:
                f.write(json.dumps(persons[p], ensure_ascii=False) + "\n")
        for r in recordings:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    review_path = os.path.join(os.path.dirname(os.path.abspath(args.out)), "review.csv")
    with open(review_path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(review[0].keys()) if review else ["saavn_album"])
        w.writeheader()
        w.writerows(review)
    print(f"{len(used)} people, {len(recordings)} songs → {args.out}; music directors decided by: {dict(decided)}; {len(review)} albums to review → {review_path}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--cache", required=True, help="tamil-catalog cache.db (JioSaavn replies)")
    ap.add_argument("--catalog", required=True, help="tamil-catalog catalog.db (which film each album is)")
    ap.add_argument("--wikidata", default="wikidata_composers.json", help="Wikidata film composers (fetched once)")
    ap.add_argument("--fetch-wikidata", action="store_true", help="fetch Wikidata again")
    ap.add_argument("--out", default="credits.jsonl")
    build(ap.parse_args())
