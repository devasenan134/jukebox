# Milestone 4: Jukebox API v2

> In progress. See [DESIGN.md](DESIGN.md) for architectural goals and [ROADMAP.md](../ROADMAP.md) for milestone order.

Milestone 4 moves Jukebox to its own first-class native API. Instead of forcing film music into the legacy Subsonic model (which treats films as two split albums, lumps music directors as generic "artists", and has no native concept of lyricists, cast, or releases), Jukebox API v2 exposes the true catalog schema:

1. **Films and Albums with full details**: A film is an album with releases (Soundtrack, Background Score, Singles) rather than split duplicate albums. Film details (cast, directors) and album music directors are native credits.
2. **People as first-class citizens**: Composers, singers, lyricists, and actors with distinct profiles, filmographies, discographies, and roles.
3. **Lyrics search**: Search inside synced and plain lyrics text across the entire library to find songs by lyrics lines.
4. **App and Web migration**: Web and Android clients transition off Subsonic API to Jukebox API v2 for catalog browsing, lyrics, and search.
5. **App dark theme & rename**: Redesign the Android app with the dark theme to match the web app and officially rename it to Jukebox (keeping package id `io.github.devasenan134.isaipetti`).

---

## 1. Native API Endpoints (Control Plane)

All native v2 endpoints authenticate with the standard Jukebox Bearer session token (`Authorization: Bearer <token>`).

### A. Albums & Films

#### `GET /api/v2/albums`
Query parameters:
- `sort`: `name`, `newest`, `recent`, `frequent`, `byYear`, `starred`, `random` (default: `name`)
- `kind`: `film`, `album`, `compilation` (optional filter)
- `fromYear`, `toYear`: optional year filters
- `offset`: integer (default 0)
- `limit`: integer (default 50, max 500)

Returns:
```json
{
  "total": 4200,
  "albums": [
    {
      "id": "alb-123",
      "title": "Roja",
      "year": 1992,
      "kind": "film",
      "coverArt": "al-alb-123",
      "composers": [{ "id": "p-arr", "name": "A.R. Rahman" }],
      "cast": ["Arvind Swami", "Madhoo"],
      "songCount": 6,
      "durationMs": 1642000,
      "starred": "2026-10-04T12:00:00Z"
    }
  ]
}
```

#### `GET /api/v2/albums/{id}`
Returns the complete film/album details with its releases (Soundtrack, Score, Singles) and tracks:
```json
{
  "id": "alb-123",
  "title": "Roja",
  "year": 1992,
  "kind": "film",
  "coverArt": "al-alb-123",
  "composers": [{ "id": "p-arr", "name": "A.R. Rahman" }],
  "directors": ["Mani Ratnam"],
  "cast": [
    { "id": "actor-arvindswami", "name": "Arvind Swami" },
    { "id": "actor-madhoo", "name": "Madhoo" }
  ],
  "starred": "2026-10-04T12:00:00Z",
  "releases": [
    {
      "id": "rel-ost-1",
      "title": "Roja (Original Motion Picture Soundtrack)",
      "kind": "soundtrack",
      "year": 1992,
      "tracks": [
        {
          "id": "trk-1",
          "disc": 1,
          "number": 1,
          "title": "Chinna Chinna Aasai",
          "recording": {
            "id": "rec-101",
            "title": "Chinna Chinna Aasai",
            "version": "original",
            "durationMs": 295000,
            "singers": [{ "id": "p-minmini", "name": "Minmini" }],
            "composers": [{ "id": "p-arr", "name": "A.R. Rahman" }],
            "lyricists": [{ "id": "p-vairamuthu", "name": "Vairamuthu" }],
            "hasSyncedLyrics": true,
            "playCount": 42,
            "starred": "2026-10-05T08:00:00Z"
          }
        }
      ]
    },
    {
      "id": "rel-score-2",
      "title": "Roja (Original Background Score)",
      "kind": "score",
      "year": 1992,
      "tracks": [...]
    }
  ]
}
```

---

### B. People (Composers, Singers, Lyricists, Actors)

#### `GET /api/v2/people`
Query parameters:
- `role`: `composer`, `singer`, `lyricist`, `actor`, `all` (default: `all`)
- `offset`: integer (default 0)
- `limit`: integer (default 100, max 500)

Returns paginated list of people with their primary roles and counts.

#### `GET /api/v2/people/{id}`
Returns person biography, roles, discography (albums and recordings where credited), and filmography (films where cast/director/composer):
```json
{
  "id": "p-arr",
  "name": "A.R. Rahman",
  "roles": ["composer", "singer"],
  "coverArt": "ar-p-arr",
  "albums": [...],
  "songs": [...],
  "movieCount": 160,
  "songCount": 850
}
```

---

### C. Songs & Detailed Lyrics

#### `GET /api/v2/songs/{id}`
Returns song recording details, other versions (karaoke, remixes, covers), credits, audio metadata, and lyrics:
```json
{
  "id": "rec-101",
  "title": "Chinna Chinna Aasai",
  "version": "original",
  "durationMs": 295000,
  "albumId": "alb-123",
  "albumTitle": "Roja",
  "composers": [...],
  "singers": [...],
  "lyricists": [...],
  "versions": [
    { "id": "rec-101-inst", "version": "instrumental", "title": "Chinna Chinna Aasai (Instrumental)" }
  ],
  "lyrics": [
    {
      "script": "ta",
      "synced": true,
      "lines": [
        { "startMs": 14200, "text": "சின்ன சின்ன ஆசை" }
      ]
    }
  ]
}
```

---

### D. Lyrics Search & Unified Search

#### `GET /api/v2/search/lyrics?q=`
Full-text search across lyrics database.
Returns matched snippets with timestamp offsets:
```json
{
  "query": "chinna chinna aasai",
  "matches": [
    {
      "recordingId": "rec-101",
      "songTitle": "Chinna Chinna Aasai",
      "albumId": "alb-123",
      "albumTitle": "Roja",
      "coverArt": "al-alb-123",
      "matchedLine": "சின்ன சின்ன ஆசை சிறகடிக்கும் ஆசை",
      "startMs": 14200
    }
  ]
}
```

#### `GET /api/v2/search?q=`
Enhanced search returning `albums`, `people`, `songs`, and `lyricsMatches` in a single query.

---

## 2. Implementation Steps

1. **Server (`server/`)**:
   - `CatalogApi.kt`: Implement `/api/v2/albums`, `/api/v2/people`, `/api/v2/songs`, and lyrics search.
   - Extend `Db.kt` queries for release hierarchy and lyrics full-text querying.
   - Comprehensive unit and integration tests (`CatalogApiTest.kt`).
2. **Web App (`web/`)**:
   - Update `api/` clients to use `/api/v2/*` endpoints.
   - Show unified film releases (Soundtrack + Score) and film cast on Album pages.
   - Add lyrics search toggle/section to search page.
3. **Android App (`android/`)**:
   - Migrate `SubsonicApi.kt` catalog calls to native endpoints.
   - Update dark theme colors and UI components to align with web dark styling.
   - Rebrand application to Jukebox while keeping package `io.github.devasenan134.isaipetti`.
