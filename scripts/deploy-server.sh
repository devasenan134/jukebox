#!/usr/bin/env bash
# Deploys the current working directory to the live Jukebox server at craftingtable:/mnt/ugreen/jukebox
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REMOTE_HOST="craftingtable"
REMOTE_DIR="/mnt/ugreen/jukebox"

echo "Syncing $ROOT to $REMOTE_HOST:$REMOTE_DIR..."
# --delete keeps the server copy identical to the repo, but the files below exist only on the
# server (settings, secrets, backups); the P (protect) rules stop rsync from ever deleting them.
rsync -avz --delete \
  --filter='P /.env' \
  --filter='P /docker-compose.yml' \
  --filter='P /docker-compose.override.yml' \
  --filter='P /secrets/' \
  --filter='P /backups/' \
  --filter='P /requests/' \
  --filter='P /analyzer/.env' \
  --filter='P /analyzer/docker-compose.override.yml' \
  --exclude='.git' \
  --exclude='android' \
  --exclude='node_modules' \
  --exclude='.gradle' \
  --exclude='server/build' \
  --exclude='data' \
  --exclude='desktop/src-tauri/target' \
  "$ROOT/" "$REMOTE_HOST:$REMOTE_DIR/"

# Reuses Docker's cached layers (the Gradle download alone takes about 9 minutes on the Mac mini and sometimes
# drops); ./scripts/deploy-server.sh --clean rebuilds everything from scratch.
BUILD_FLAGS=""
if [ "${1:-}" = "--clean" ]; then BUILD_FLAGS="--no-cache"; fi
echo "Rebuilding and restarting container on $REMOTE_HOST ${BUILD_FLAGS:+(with $BUILD_FLAGS)}..."
ssh "$REMOTE_HOST" "cd $REMOTE_DIR && docker compose build $BUILD_FLAGS && docker compose up -d --force-recreate && docker ps --filter name=jukebox"

echo "Done! Jukebox server redeployed successfully."
