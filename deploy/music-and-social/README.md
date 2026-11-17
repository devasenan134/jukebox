# Jukebox Music + Social Server

Deployment with full social features enabled:
- Realtime Gateway with per-device presence and cross-device remote control.
- Friend connections and one-time invite codes.
- Group chats and shared listening parties ("Jams").
- Optional push notifications through Firebase Cloud Messaging.

## Quick Start

1. Copy `.env.example` to `.env`:
   ```bash
   cp .env.example .env
   ```
2. Edit `.env` to set `MUSIC_FOLDER` and your `LIBRARIES` mapping.
3. Create folders:
   ```bash
   mkdir -p data secrets
   ```
4. (Optional) For push notifications, place `google-services.json` and your Firebase admin key `firebase-key.json` into `./secrets/` and restrict permissions:
   ```bash
   chmod 700 secrets && chmod 600 secrets/*
   ```
5. Start Jukebox:
   ```bash
   docker compose up -d
   ```
6. Create an admin user:
   ```bash
   docker compose exec -it jukebox /app/bin/jukebox user add admin --admin
   ```
7. Invite friends from the app or generate invite codes directly:
   ```bash
   # Invited users connect their accounts and can immediately start chats and jams
   ```
