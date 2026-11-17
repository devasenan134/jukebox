# Jukebox on craftingtable

Development lives at `/mnt/ugreen/jukebox-development`; the live Docker deployment
remains `/mnt/ugreen/jukebox`, with its existing settings, secrets, data and backups.
The feature worktrees are `/mnt/ugreen/jukebox-mac` and `/mnt/ugreen/jukebox-sugg`.
The original machine's copies are retained for rollback.

## Android

GitHub Actions builds and signs Android APKs. Craftingtable has GitHub CLI installed
at `~/.local/bin/gh` and authenticated for the repository. The existing app signing
key and password are stored as repository Actions secrets; no Android SDK is needed
on craftingtable.

Build the pushed `main` branch without publishing a release:

```sh
cd /mnt/ugreen/jukebox-development
./scripts/build-android-actions.sh
```

The command prints the run URL, waits for completion and downloads artifacts to
`~/jukebox-artifacts/<run-id>/`. It returns failure if the workflow fails. Local
uncommitted or unpushed changes are not included in GitHub builds.

Build an existing release tag and attach its signed APKs:

```sh
./scripts/release-apk.sh v2.7.0
```

This builds the tag's source and replaces APK assets on that release. Creating or
publishing a release is separate. Published releases also trigger the existing
Android release workflow automatically. Avoid triggering both for the same tag.

## Server and analyzer

Both already run on craftingtable. Deploy source from the development checkout:

```sh
JUKEBOX_DEPLOY_HOST=local ./scripts/deploy-server.sh
```

This synchronizes source, rebuilds and restarts the live server, retaining its
existing deployment protection rules. Do not run it from the live directory.

## macOS and supporting data

macOS builds continue on GitHub Actions macOS runners. Labeling data belongs in
`/mnt/ugreen/jukebox-labels`. Do not remove the original data or signing-key backup
until migration verification is complete.
