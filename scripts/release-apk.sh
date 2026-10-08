#!/usr/bin/env bash
# Builds the signed Android release APK and uploads it to GitHub Release.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="${1:-$(git describe --tags --abbrev=0)}"
VERSION="${TAG#v}"

echo "Building release APK for version $VERSION ($TAG)..."
cd "$ROOT/android"
./gradlew assembleRelease -Dorg.gradle.java.home=/home/devs/.jdks/jdk-21.0.12.1+1

APK_DIR="$ROOT/android/app/build/outputs/apk/release"
cp "$APK_DIR/app-release.apk" "$APK_DIR/isaipetti-$VERSION.apk"

echo "Uploading APKs to release $TAG..."
gh release upload "$TAG" "$APK_DIR/app-release.apk" "$APK_DIR/isaipetti-$VERSION.apk" --clobber

echo "Done! APK attached to release $TAG."
