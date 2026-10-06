// Jukebox for macOS: the Jukebox website in its own window.
//
// The window starts on start/index.html, which sends it to the saved server (or asks for one). After that
// it's the website itself, served by Jukebox, so the app always matches the site. This file only adds what
// a browser tab doesn't have: a Go menu (back, forward, reload, change server) and, on macOS, closing the
// window hides it so the music keeps playing; clicking the Dock icon brings it back.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use tauri::menu::{Menu, MenuItemBuilder, SubmenuBuilder};
use tauri::{AppHandle, Manager, RunEvent, Url, WindowEvent};

/// The start page with the server form showing, even when a server is saved.
fn change_server_url() -> Url {
    let origin = if cfg!(windows) { "http://tauri.localhost" } else { "tauri://localhost" };
    Url::parse(&format!("{origin}/index.html?change=1")).expect("valid start page address")
}

fn main() {
    let app = tauri::Builder::default()
        .menu(|handle| {
            let menu = Menu::default(handle)?;
            let back = MenuItemBuilder::with_id("back", "Back").accelerator("CmdOrCtrl+[").build(handle)?;
            let forward = MenuItemBuilder::with_id("forward", "Forward").accelerator("CmdOrCtrl+]").build(handle)?;
            let reload = MenuItemBuilder::with_id("reload", "Reload").accelerator("CmdOrCtrl+R").build(handle)?;
            let change = MenuItemBuilder::with_id("change-server", "Change Server…").build(handle)?;
            let go = SubmenuBuilder::new(handle, "Go")
                .item(&back)
                .item(&forward)
                .separator()
                .item(&reload)
                .separator()
                .item(&change)
                .build()?;
            // After App, File, Edit and View; before Window and Help.
            menu.insert(&go, 4)?;
            Ok(menu)
        })
        .on_menu_event(|app, event| {
            let Some(window) = app.get_webview_window("main") else { return };
            let _ = match event.id().as_ref() {
                "back" => window.eval("history.back()"),
                "forward" => window.eval("history.forward()"),
                "reload" => window.eval("location.reload()"),
                "change-server" => window.navigate(change_server_url()),
                _ => Ok(()),
            };
        })
        .on_window_event(|window, event| {
            if cfg!(target_os = "macos") {
                if let WindowEvent::CloseRequested { api, .. } = event {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
        .build(tauri::generate_context!())
        .expect("couldn't start Jukebox");

    app.run(on_run_event);
}

fn on_run_event(app: &AppHandle, event: RunEvent) {
    #[cfg(target_os = "macos")]
    if let RunEvent::Reopen { has_visible_windows: false, .. } = event {
        if let Some(window) = app.get_webview_window("main") {
            let _ = window.show();
            let _ = window.set_focus();
        }
    }
    #[cfg(not(target_os = "macos"))]
    let _ = (app, event);
}
