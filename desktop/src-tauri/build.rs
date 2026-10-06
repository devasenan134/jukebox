fn main() {
    // `notify` is the one command pages may call (see capabilities/default.json).
    tauri_build::try_build(tauri_build::Attributes::new().app_manifest(tauri_build::AppManifest::new().commands(&["notify"])))
        .expect("failed to run tauri-build")
}
