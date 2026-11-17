# Jukebox Development Guidelines & Operational Knowledge

## Server Deployment
- The live production server runs at `craftingtable` under `/mnt/ugreen/jukebox`.
- The server container builds directly from source files using Docker Compose (`build: .`), not by pulling GHCR registry images.
- **Redeploy Procedure**:
  1. Sync current working directory to `craftingtable:/mnt/ugreen/jukebox/`:
     ```bash
     rsync -avz \
       --exclude='.git' \
       --exclude='android' \
       --exclude='node_modules' \
       --exclude='.gradle' \
       --exclude='server/build' \
       --exclude='data' \
       ./ craftingtable:/mnt/ugreen/jukebox/
     ```
  2. Rebuild and recreate the container:
     ```bash
     ssh craftingtable "cd /mnt/ugreen/jukebox && docker compose build && docker compose up -d --force-recreate"
     ```
  - Or simply run the helper script: `./scripts/deploy-server.sh`.

## Domain Terminology Consistency
- **Album / Albums**: Movie soundtrack or independent album (formerly called Movie/Film).
- **Composer / Composers**: Music Director (formerly called Music Director).
- **Artist / Artists**: Singers, Lyricists, and featured artists (formerly called Singers/Lyricists/Peoples).

## Mixes & Recommendations Segregation
- **Pure Song Mixes**: Vocal tracks only (no theme music or Original Background Scores).
- **Dedicated Score Mixes**: Instrumentals/BGMs/OBS only (e.g., *"Smell the Romantic rose"*, *"Feel the Mass elevation"*).
- **Stations & Recommendations**: Preserves song vs score separation based on the seed track.
