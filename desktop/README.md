# Jukebox for macOS

The Jukebox website in its own Mac window, built with [Tauri](https://tauri.app). The app is a wrapper, so it
always matches the website and has no features of its own except these:

- **First start:** it asks for the server address (`https://jukebox.craftingtable.cc` is filled in) and remembers it.
  **Go › Change Server…** asks again.
- **Go menu:** Back (⌘[), Forward (⌘]), Reload (⌘R).
- **Closing the window** hides it and the music keeps playing. Click the Dock icon to bring it back; ⌘Q quits.
- Media keys and Now Playing come from the website's Media Session code (`web/src/player/player.ts`).

## Getting the DMG

It's on each app release next to the APK (`jukebox-<version>-macos.dmg`), built by GitHub Actions on a Mac
(`.github/workflows/desktop-macos.yml`): publishing a release `vX.Y.Z` builds the DMG with that version and attaches
it. To add one to an older release, run the workflow by hand with its tag
(`gh workflow run desktop-macos.yml -f tag=v0.12.1`). Pushes that change `desktop/` also build one, kept under the
run's **Artifacts**, for trying changes before a release.

The DMG is one universal app for Apple silicon and Intel Macs, macOS 12 or newer. It isn't signed with an Apple
Developer ID, so the first time macOS says it can't check it: open **System Settings › Privacy & Security** and
click **Open Anyway** (or run `xattr -cr /Applications/Jukebox.app`).

## Building on a Mac

```bash
cd desktop
npm ci
rustup target add aarch64-apple-darwin x86_64-apple-darwin
npm run build:mac     # src-tauri/target/universal-apple-darwin/release/bundle/dmg/
npx tauri dev         # run it without packaging
```

The icon is `icon.svg` (the Android launcher mark); after changing it run `npm run icons` and delete the
`android/`, `ios/` and `Square*` files it also makes.
