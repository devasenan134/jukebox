# Milestone 6: Packaging & Self-Hosting

> Completed in release 0.17.0. See [DESIGN.md](DESIGN.md) for architectural goals and [ROADMAP.md](../ROADMAP.md) for milestone order.

Milestone 6 transforms Jukebox from an internal, single-host project into an easily deployable, production-ready product for the broader self-hosting community.

---

## 1. Architectural Goals

1. **Multi-Architecture Container Images on GHCR**:
   - Provide automated builds supporting both `linux/amd64` (standard servers, PCs, NAS boxes) and `linux/arm64` (Apple Silicon, Raspberry Pi 4/5, Oracle Cloud ARM instances).
   - Images published to GitHub Container Registry (`ghcr.io/devasenan134/jukebox`).
   - Single unified container bundling the Kotlin Ktor server and the pre-built React/TypeScript web app.
   - Separate, companion image for the CLAP audio analyzer (`ghcr.io/devasenan134/jukebox-analyzer`).

2. **Modular Deployment Flavors (`deploy/`)**:
   - Give self-hosters copy-pasteable Compose setups tailored to their needs:
     - **Standalone**: Clean, private music server without friends or chat (`JUKEBOX_SOCIAL=off`).
     - **Music + Social**: Full collaborative server with friends, realtime gateway, presence, and listening parties ("Jams").
     - **Full Stack**: Server + CLAP audio analyzer for neural mood tagging and acoustic radio.

3. **Release Pipeline Automation**:
   - **Android APK**: Automated compilation and publishing of signed release APKs (`isaipetti-<version>.apk`) to GitHub Releases via GitHub Actions and [`scripts/release-apk.sh`](../scripts/release-apk.sh).
   - **macOS Desktop**: Universal DMG packaging and auto-update manifests (`latest-macos.json`) via GitHub Actions.
   - **Docker Images**: Automated multi-arch builds triggered on git release publications and commits to `main`.

4. **Production-Grade Documentation**:
   - A complete [Self-Hosting Guide](SELF_HOSTING.md) detailing prerequisites, reverse proxy configurations (Nginx, Caddy, Cloudflare Tunnel), account management CLI, audio transcoding, and backup/restore workflows.

---

## 2. Container Architecture

### A. Jukebox Server (`Dockerfile`)
Multi-stage Dockerfile:
1. **Web build**: Node 22 slim environment builds the static assets in `web/` using Vite.
2. **Server build**: Eclipse Temurin 21 JDK compiles the server and outputs the distribution using Gradle.
3. **Runtime**: Eclipse Temurin 21 JRE with system `ffmpeg` for audio transcoding, Chromaprint fingerprinting, and cover art processing. Exposes port 8095 and mounts `/data` for SQLite and transcodes.

### B. Jukebox Analyzer (`analyzer/Dockerfile`)
1. Python 3.12 slim with system `ffmpeg`.
2. PyTorch CPU-only runtime with native ARM and x86 support.
3. Hugging Face model cache mounted at `/data/models` for zero repeated downloads.

---

## 3. GitHub Actions CI/CD Matrix

| Workflow | Trigger | Target Artifacts |
|---|---|---|
| [`.github/workflows/docker.yml`](../.github/workflows/docker.yml) | Release published, Push to `main` | `ghcr.io/devasenan134/jukebox`, `ghcr.io/devasenan134/jukebox-analyzer` (`amd64`, `arm64`) |
| [`.github/workflows/android-release.yml`](../.github/workflows/android-release.yml) | Release published | `isaipetti-<version>.apk`, `app-release.apk` |
| [`.github/workflows/desktop-macos.yml`](../.github/workflows/desktop-macos.yml) | Release published, Push to `desktop/` | Universal macOS DMG (`jukebox-<version>-macos.dmg`), `latest-macos.json` |

---

## 4. Delivery Summary

- Ready-to-use Docker compose stacks placed under [`deploy/`](../deploy/).
- Multi-arch Docker workflow committed under `.github/workflows/docker.yml`.
- Android APK release workflow committed under `.github/workflows/android-release.yml`.
- End-to-end self-hosting manual published under [`docs/SELF_HOSTING.md`](SELF_HOSTING.md).
