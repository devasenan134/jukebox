# Deploying Jukebox

Ready-to-use Docker Compose configurations for running Jukebox on your home server, VPS, or NAS.

Images are available on GitHub Container Registry:
- `ghcr.io/devasenan134/jukebox:latest` (Multi-arch: `linux/amd64`, `linux/arm64`)
- `ghcr.io/devasenan134/jukebox-analyzer:latest` (Multi-arch: `linux/amd64`, `linux/arm64`)

## Choose a Deployment Flavor

| Deployment | Ideal For | Features |
|---|---|---|
| [`deploy/standalone/`](standalone/) | Personal or family library | Music streaming, web player, mobile app sync, offline downloads. Social/chat disabled. |
| [`deploy/music-and-social/`](music-and-social/) | Sharing with friends | All standalone features plus friends, live chat, shared listening parties (Jams), cross-device sync, and optional push notifications. |
| [`deploy/music-social-analyzer/`](music-social-analyzer/) | Full intelligence | Everything above plus the local CLAP audio model for automatic mood mixes ("Melancholic", "Energetic") and acoustic similarity radio. |

## Documentation

For full setup instructions, reverse proxy configs (Nginx, Caddy, Traefik, Cloudflare Tunnel), mobile/desktop app pairing, and backup routines, refer to the [Self-Hosting Guide](../docs/SELF_HOSTING.md).
