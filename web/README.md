# Jukebox web app

The web player Jukebox serves at `/`: sign in, Home, albums, music directors, search, and a full player with
synced lyrics. React + TypeScript + Vite. It talks to the same server it's served from: the Subsonic API at
`/rest` and Jukebox's own API at the root (`/auth`, `/mixes`, `/ws`, …).

```bash
npm ci
npm run dev      # http://localhost:5173, with /rest and the API sent on to a Jukebox server (see vite.config.ts)
npm run build    # dist/, which the Docker image copies to /app/web
```

## Fonts and icons

Fonts are served by Jukebox itself, not Google Fonts: Figtree and Noto Sans Tamil come from npm
(`@fontsource`), and the icons are a subset of Material Symbols Rounded with only the icons the app uses,
in `public/fonts/material-symbols-rounded.woff2`. After using a new icon, make the file again:

```bash
python3 web/scripts/icons.py
```
