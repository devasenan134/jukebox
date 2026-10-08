# Jukebox Self-Hosting Guide

This guide walks you through deploying, configuring, and maintaining your own instance of **Jukebox**.

Jukebox is a self-hosted music server designed around film music: soundtracks, background scores, composers, singers, lyricists, and actors. It serves a responsive web app directly, streams natively to the Jukebox Android and macOS desktop apps, supports third-party Subsonic players, and optionally offers realtime listening parties (Jams), cross-device remote control, and friend chat.

---

## 1. System Requirements

- **Operating System**: Linux (Ubuntu, Debian, Alpine, Fedora, Arch), macOS, or Windows (WSL2).
- **Architecture**: `linux/amd64` (x86_64) or `linux/arm64` (Apple Silicon, Raspberry Pi 4/5, ARM VPS).
- **Docker**: Docker Engine 24+ and Docker Compose v2.
- **Hardware**:
  - **Minimal (Standalone)**: 1 vCPU, 512 MB RAM, ~1 GB storage for metadata and artwork cache.
  - **Standard (Music + Social + Transcoding)**: 2 vCPUs, 1 GB RAM.
  - **With Audio Analyzer (CLAP model)**: 2–4 vCPUs, 3 GB RAM (CPU-only PyTorch).
- **Network**: Port `8095` exposed to localhost, behind an HTTPS reverse proxy (Nginx, Caddy, Cloudflare Tunnel).

---

## 2. Choosing a Deployment Flavor

Ready-to-use Docker Compose setups are located in the [`deploy/`](../deploy/) folder:

| Deployment | Path | Best For | Included Services |
|---|---|---|---|
| **Standalone** | [`deploy/standalone/`](../deploy/standalone/) | Personal or private family server | Jukebox server (Social off) |
| **Music + Social** | [`deploy/music-and-social/`](../deploy/music-and-social/) | Sharing with friends & real-time sessions | Jukebox server (Social on, realtime gateway) |
| **Full Stack** | [`deploy/music-social-analyzer/`](../deploy/music-social-analyzer/) | Full intelligence: automated mood mixes & acoustic radio | Jukebox server + CLAP audio analyzer |

---

## 3. Quick Start (Standalone Server)

### Step 1: Copy Deployment Files
```bash
mkdir -p ~/jukebox && cd ~/jukebox
curl -fsSL https://raw.githubusercontent.com/devasenan134/jukebox/main/deploy/standalone/docker-compose.yml -o docker-compose.yml
curl -fsSL https://raw.githubusercontent.com/devasenan134/jukebox/main/deploy/standalone/.env.example -o .env
```

### Step 2: Configure Environment
Edit `.env` to match your environment:
```bash
nano .env
```
Key variables to adjust:
- `MUSIC_FOLDER`: Host directory containing your music files (mounted read-only).
- `LIBRARIES`: Library configuration string (e.g. `tamil=/music/tamil:film:tamil;english=/music/english:album:english`).
- `PUID` / `PGID`: User ID and Group ID on the host (`id -u` and `id -g`).

### Step 3: Start Jukebox
```bash
mkdir -p data
docker compose up -d
```
Monitor startup and library scanning:
```bash
docker compose logs -f jukebox
```

### Step 4: Create the Admin Account
A brand new server has no users. Create the administrator account:
```bash
docker compose exec -it jukebox /app/bin/jukebox user add admin --admin
```
Enter your password when prompted.

---

## 4. Library Configuration (`LIBRARIES`)

Jukebox organizes music by libraries defined via the `LIBRARIES` environment variable:
```text
LIBRARIES=name=path:kind:language;name=path:kind:language
```

- **`name`**: Friendly identifier for the library section.
- **`path`**: Directory inside the container (usually `/music` or a subfolder).
- **`kind`**:
  - `film`: For movie soundtracks and original background scores. Jukebox will group albums as films, track directors/cast, and separate soundtrack releases from score releases.
  - `album`: For non-film studio albums, independent releases, and singles.
- **`language`**: Primary language of the collection (e.g. `tamil`, `telugu`, `hindi`, `malayalam`, `english`).

### Tagging and Artwork
- **Metadata**: Jukebox reads ID3v2, Vorbis, FLAC, MP4/AAC tags: Title, Artist, Album, Year, Track, Composer, Lyricist, Performer.
- **Lyrics**: Embedded synchronized lyrics (SYLT/USLT) or sidecar `.lrc` and `.txt` files next to audio files are automatically indexed for playback and full-text lyrics search.
- **Covers**: Embedded cover art or folder images (`cover.jpg`, `folder.jpg`) are extracted, cached, and served in multiple resolutions (150px, 300px, 600px).

---

## 5. User & Account Management

All user operations are performed via the bundled `jukebox user` CLI command:

```bash
# Add a new standard user
docker compose exec -it jukebox /app/bin/jukebox user add <username>

# Add an administrator
docker compose exec -it jukebox /app/bin/jukebox user add <username> --admin

# Change or reset a user's password (signs out all active sessions)
docker compose exec -it jukebox /app/bin/jukebox user password <username>

# Grant or revoke administrator privileges
docker compose exec jukebox /app/bin/jukebox user admin <username> on
docker compose exec jukebox /app/bin/jukebox user admin <username> off

# List all accounts
docker compose exec jukebox /app/bin/jukebox user list

# Remove an account (messages and shared playlists are preserved as "former member")
docker compose exec jukebox /app/bin/jukebox user remove <username>
```

> [!NOTE]
> When social features are enabled, existing users can generate 7-day single-use invite codes directly from the app (*Friends* tab or Web Settings) to invite friends without CLI intervention.

---

## 6. Reverse Proxy & HTTPS Setup

Jukebox binds to `127.0.0.1:8095` by default. Because Web Audio, Service Workers, and WebSocket sync require a secure origin, place Jukebox behind an HTTPS reverse proxy.

### Nginx
```nginx
server {
    listen 443 ssl http2;
    server_name music.example.com;

    ssl_certificate /etc/letsencrypt/live/music.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/music.example.com/privkey.pem;

    client_max_body_size 64M;

    location / {
        proxy_pass http://127.0.0.1:8095;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # WebSocket support (Realtime gateway)
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 86400s;
        proxy_send_timeout 86400s;

        # Enable byte range streaming
        proxy_buffering off;
    }
}
```

### Caddy
```caddyfile
music.example.com {
    reverse_proxy 127.0.0.1:8095
}
```

### Cloudflare Tunnel
In your `config.yml`:
```yaml
ingress:
  - hostname: music.example.com
    service: http://localhost:8095
  - service: http_status:404
```

---

## 7. Connecting Clients

### 1. Web App
Open your server's HTTPS URL (e.g., `https://music.example.com`) in any modern browser. The web app is served directly from the root path `/`.

### 2. Android App
1. Download the latest `isaipetti-<version>.apk` from the [GitHub Releases](https://github.com/devasenan134/jukebox/releases) page.
2. Install the APK on your device.
3. Open the app, enter your server address (`https://music.example.com`), and log in with your credentials.
4. Enjoy offline downloads, playback transfer, and Android Auto support.

### 3. macOS Desktop App
1. Download `jukebox-<version>-macos.dmg` from GitHub Releases.
2. Drag Jukebox to your Applications folder.
3. Enjoy native desktop media keys, menu bar status, and cross-device remote control.

### 4. Third-Party Subsonic Apps
Jukebox implements the Subsonic API subset under `/rest/*`. You can connect clients like **Symfonium**, **DSub**, or **Tempo**:
- **Server URL**: `https://music.example.com`
- **Username & Password**: Your Jukebox credentials.

---

## 8. Backup & Maintenance

### What to Back Up
Your Jukebox installation contains critical state in the `data/` directory:
1. `data/jukebox.db`: The SQLite database containing user accounts, play history, event logs, playlists, and catalog indexes.
2. `data/secret.key`: The AES-GCM encryption key used to encrypt passwords for Subsonic compatibility. **Without this file, saved client passwords cannot be verified.**
3. `data/artwork/`: Cached album artwork thumbnails.

### Backup Command
```bash
# Safely snapshot the database while running
docker compose exec jukebox sqlite3 /data/jukebox.db ".backup '/data/jukebox-backup.db'"
tar -czvf jukebox-backup-$(date +%F).tar.gz data/jukebox-backup.db data/secret.key
rm data/jukebox-backup.db
```

### Upgrading Jukebox
```bash
docker compose pull
docker compose up -d
```
Database schema migrations apply automatically on container startup.
