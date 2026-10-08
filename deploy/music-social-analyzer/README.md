# Jukebox Full Stack (Server + Analyzer)

Runs the full Jukebox stack with acoustic intelligence:
- **Jukebox Server**: Catalog, streaming, web interface, and realtime gateway.
- **Jukebox Analyzer**: CPU-efficient background worker using the CLAP neural network model to extract sound embeddings, musical properties (tempo, loudness, brightness), and automated mood scoring (21 mood vectors) into `features.db`.

This setup gives you mood mixes ("Late Night", "Energetic", "Melancholy"), sound-alike radio stations, and rich acoustic recommendations without manual mood tagging.

## Quick Start

1. Copy `.env.example` to `.env`:
   ```bash
   cp .env.example .env
   ```
2. Configure `MUSIC_FOLDER` and your `LIBRARIES` mapping.
3. Ensure directories exist:
   ```bash
   mkdir -p data analyzer-data secrets
   ```
4. Start both containers:
   ```bash
   docker compose up -d
   ```
5. Follow analyzer progress:
   ```bash
   docker compose logs -f jukebox-analyzer
   ```
   The analyzer listens to each new track once (~1.5s per song on modern CPUs), saves features to `features.db`, and sleeps until new songs are detected.
