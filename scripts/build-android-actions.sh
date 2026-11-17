#!/usr/bin/env bash
# Build on GitHub Actions. No arguments builds main without publishing a release.
set -euo pipefail
export PATH="$HOME/.local/bin:$PATH"
REPO="devasenan134/jukebox"
REF="main"
TAG=""
if [[ "${1:-}" == "--release" && $# == 2 ]]; then
  TAG="$2"
  REF="$TAG"
  gh release view "$TAG" --repo "$REPO" >/dev/null
elif [[ $# != 0 ]]; then
  echo "Usage: $0 [--release EXISTING_RELEASE_TAG]" >&2
  exit 2
fi
STARTED="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
ARGS=()
if [[ -n "$TAG" ]]; then ARGS=(-f "tag=$TAG"); fi
gh workflow run android-release.yml --repo "$REPO" --ref "$REF" "${ARGS[@]}"
RUN_ID=""
for attempt in {1..18}; do
  RUN_ID=$(gh run list --repo "$REPO" --workflow android-release.yml --event workflow_dispatch \
    --branch "$REF" --created ">=$STARTED" --json databaseId --jq 'last.databaseId // empty')
  [[ -n "$RUN_ID" ]] && break
  sleep 5
done
if [[ -z "$RUN_ID" ]]; then
  echo "Build dispatched, but its run ID is not visible yet. Check gh run list --repo $REPO." >&2
  exit 1
fi
echo "https://github.com/$REPO/actions/runs/$RUN_ID"
gh run watch "$RUN_ID" --repo "$REPO" --exit-status
ARTIFACT_DIR="${JUKEBOX_ARTIFACT_DIR:-$HOME/jukebox-artifacts}/$RUN_ID"
mkdir -p "$ARTIFACT_DIR"
gh run download "$RUN_ID" --repo "$REPO" --dir "$ARTIFACT_DIR"
echo "Build artifacts: $ARTIFACT_DIR"
