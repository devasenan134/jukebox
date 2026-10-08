# Jukebox Standalone Music Server

A lightweight, private deployment for individuals and families. Runs purely as a personal music streaming server with the web app included, with social and chat modules disabled (`JUKEBOX_SOCIAL=off`).

## Quick Start

1. Copy `.env.example` to `.env`:
   ```bash
   cp .env.example .env
   ```
2. Edit `.env` and set `MUSIC_FOLDER` to the path containing your music library.
3. Ensure directory permissions:
   ```bash
   mkdir -p data
   ```
4. Start the server:
   ```bash
   docker compose up -d
   ```
5. Create your initial admin account:
   ```bash
   docker compose exec -it jukebox /app/bin/jukebox user add admin --admin
   ```
6. Access the web interface at `http://localhost:8095` or configure your reverse proxy for external HTTPS access.
