# Milestone 5: The Data Plane

> Completed in release 0.16.0. See [DESIGN.md](DESIGN.md) for architectural goals and [ROADMAP.md](../ROADMAP.md) for milestone order.

Milestone 5 builds the **Data Plane** of Jukebox. While the Control Plane (Milestones 1–4) handles catalog metadata, playlists, social interactions, and search, the Data Plane is responsible for the actual audio bytes: serving audio efficiently, preserving bandwidth and mobile battery, eliminating playback latency, and enabling listening on the go without an internet connection.

---

## 1. Architectural Goals

1. **Ahead-of-Time Mobile Audio Copies (No On-the-Fly CPU Spikes)**:
   - On low-power reference servers (e.g. Mac mini M1, ARM SBCs, or home NAS boxes), transcoding audio on-the-fly when multiple listeners connect causes CPU spikes and thermal throttling.
   - Lighter mobile-quality copies (Opus 128 kbps / AAC) are generated in the background when the server is idle and stored in `data/transcoded/`.
   - The primary music library remains strictly read-only.
2. **Quality Negotiation & Native Streaming Endpoint**:
   - Native streaming endpoint: `GET /api/v2/songs/{id}/stream` with quality selection (`original`, `mobile`, `auto`).
   - Strict HTTP Range request support (`206 Partial Content`) for instant seeking, scrubbing, and pause-resume.
   - Backward-compatible Subsonic streaming (`/rest/stream`, `/rest/download`) automatically serves the mobile copy when `maxBitRate` or mobile quality is requested.
3. **Prefetch Strategy**:
   - Clients prefetch the next track in the playback queue so transitions between songs are instantaneous and gapless, even over slow mobile connections.
4. **Offline Downloads**:
   - Android client download manager: download songs, albums, and playlists to local storage for offline playback.
   - Web client offline playback using standard Web Cache / IndexedDB storage where supported.

---

## 2. Audio Format & Transcoding Strategy

### Audio Codec Selection: Opus
- **Codec**: Opus inside Ogg container (`audio/ogg; codecs=opus`) or AAC inside MP4 (`audio/mp4`).
- **Target Bitrate**: VBR 128 kbps (transparent quality for mobile listening, ~1 MB per minute).
- **Tooling**: Utilizes `ffmpeg` already bundled in the Docker container (`AudioTools.kt`).

### Background Transcoding Job
- A background worker (`Transcoder.kt`) checks the catalog for recordings without a mobile copy.
- Transcodes incrementally using low process priority (`nice` or thread pool limits).
- Preserves disk space with a configurable max cache quota.

---

## 3. Native Streaming API

### `GET /api/v2/songs/{id}/stream`
Query Parameters:
- `quality`: `auto` (default), `mobile`, `original`
- Supports standard `Range: bytes=start-end` HTTP header.

Response:
- `206 Partial Content` (for range requests) or `200 OK`
- `Content-Type`: `audio/ogg`, `audio/mp4`, `audio/flac`, etc.
- `Content-Range: bytes start-end/total`
- `Accept-Ranges: bytes`

---

## 4. Client Enhancements

### A. Web App (`web/`)
- Stream from `/api/v2/songs/{id}/stream` with quality preference setting in Settings (Auto / High / Data Saver).
- Enhanced standby prefetch of the next queue item.

### B. Android App (`android/`)
- Media3 `CacheDataSource.Factory` for seamless caching of prefetched and playing audio.
- Offline Download Manager: Download albums and playlists for offline playback with local media source switching.
