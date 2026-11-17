#!/usr/bin/env bash
# Build and attach a signed APK to an existing release using GitHub Actions.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="${1:-$(git -C "$ROOT" describe --tags --abbrev=0)}"
exec "$ROOT/scripts/build-android-actions.sh" --release "$TAG"
