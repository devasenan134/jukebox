fn main() {
    // The commands pages may call (see capabilities/default.json): notifications, and what's playing for macOS.
    tauri_build::try_build(tauri_build::Attributes::new().app_manifest(tauri_build::AppManifest::new().commands(&["notify", "media_update", "media_clear"])))
        .expect("failed to run tauri-build")
}
