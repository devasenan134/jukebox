# Jukebox web app

The web player Jukebox serves at `/`: sign in, Home, albums, music directors, search, and a full player with
synced lyrics. React + TypeScript + Vite. It talks to the same server it's served from: the Subsonic API at
`/rest` and Jukebox's own API at the root (`/auth`, `/mixes`, `/ws`, …).

```bash
npm ci
npm run dev      # http://localhost:5173, with /rest and the API sent on to a Jukebox server (see vite.config.ts)
npm run build    # dist/, which the Docker image copies to /app/web
```
