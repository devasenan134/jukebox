#!/usr/bin/env bash
# Deploys the current working directory to the live Jukebox server at craftingtable:/mnt/ugreen/jukebox
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REMOTE_HOST="${JUKEBOX_DEPLOY_HOST:-craftingtable}"
REMOTE_DIR="${JUKEBOX_DEPLOY_DIR:-/mnt/ugreen/jukebox}"

DEST="$REMOTE_HOST:$REMOTE_DIR/"
if [[ "$REMOTE_HOST" == "local" ]]; then
  if [[ "$ROOT" == "$(realpath "$REMOTE_DIR")" ]]; then
    echo "Run this from the development checkout, not the deployment directory." >&2
    exit 1
  fi
  DEST="$REMOTE_DIR/"
fi

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
  "$ROOT/" "$DEST"

# Reuses Docker's cached layers (the Gradle download alone takes about 9 minutes on the Mac mini and sometimes
# drops); ./scripts/deploy-server.sh --clean rebuilds everything from scratch.
BUILD_FLAGS=""
if [ "${1:-}" = "--clean" ]; then BUILD_FLAGS="--no-cache"; fi
echo "Rebuilding and restarting container on $REMOTE_HOST ${BUILD_FLAGS:+(with $BUILD_FLAGS)}..."
if [[ "$REMOTE_HOST" == "local" ]]; then
  cd "$REMOTE_DIR"
  docker compose build $BUILD_FLAGS
  docker compose up -d --force-recreate
  docker ps --filter name=jukebox
else
  ssh "$REMOTE_HOST" "cd $REMOTE_DIR && docker compose build $BUILD_FLAGS && docker compose up -d --force-recreate && docker ps --filter name=jukebox"
fi

echo "Done! Jukebox server redeployed successfully."
