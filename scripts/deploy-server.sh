#!/usr/bin/env bash
# Deploys the current working directory to the live Jukebox server at craftingtable:/mnt/ugreen/jukebox
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REMOTE_HOST="craftingtable"
REMOTE_DIR="/mnt/ugreen/jukebox"

echo "Syncing $ROOT to $REMOTE_HOST:$REMOTE_DIR..."
rsync -avz \
  --exclude='.git' \
  --exclude='android' \
  --exclude='node_modules' \
  --exclude='.gradle' \
  --exclude='server/build' \
  --exclude='data' \
  "$ROOT/" "$REMOTE_HOST:$REMOTE_DIR/"

echo "Rebuilding and restarting container on $REMOTE_HOST..."
ssh "$REMOTE_HOST" "cd $REMOTE_DIR && docker compose build && docker compose up -d --force-recreate"

echo "Done! Jukebox server redeployed successfully."
