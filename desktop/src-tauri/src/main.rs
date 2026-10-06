// Jukebox for macOS: the Jukebox website in its own window.
//
// The window starts on start/index.html, which sends it to the saved server (or asks for one). After that
// it's the website itself, served by Jukebox, so the app always matches the site. This file only adds what
// a browser tab doesn't have: a Go menu (back, forward, reload, change server); on macOS, closing the
// window hides it so the music keeps playing (clicking the Dock icon brings it back); and updates: at start
// and from Jukebox › Check for Updates…, it reads latest-macos.json on the newest GitHub release, asks, then
// installs the signed update and restarts.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use tauri::menu::{Menu, MenuItemBuilder, SubmenuBuilder};
use tauri::{AppHandle, Manager, RunEvent, Url, WindowEvent};
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons, MessageDialogKind};
use tauri_plugin_updater::UpdaterExt;

/// The start page with the server form showing, even when a server is saved.
fn change_server_url() -> Url {
    let origin = if cfg!(windows) { "http://tauri.localhost" } else { "tauri://localhost" };
    Url::parse(&format!("{origin}/index.html?change=1")).expect("valid start page address")
}

/// Looks for a newer release. At start (`asked` false) it stays quiet unless there is one; from the menu it
/// also says when you're up to date or the check failed.
fn check_for_update(app: AppHandle, asked: bool) {
    tauri::async_runtime::spawn(async move {
        let found = match app.updater() {
            Ok(updater) => updater.check().await,
            Err(e) => Err(e),
        };
        let update = match found {
            Ok(Some(update)) => update,
            Ok(None) => {
                if asked {
                    let version = app.package_info().version.to_string();
                    tell(&app, "You have the newest Jukebox", &format!("Version {version}."));
                }
                return;
            }
            Err(e) => {
                if asked {
                    tell(&app, "Couldn't check for updates", &e.to_string());
                }
                return;
            }
        };
        let app2 = app.clone();
        app.dialog()
            .message(format!("Jukebox {} is out (you have {}). Update and restart now?", update.version, update.current_version))
            .title("Update Jukebox")
            .buttons(MessageDialogButtons::OkCancelCustom("Update".into(), "Later".into()))
            .show(move |yes| {
                if !yes {
                    return;
                }
                tauri::async_runtime::spawn(async move {
                    match update.download_and_install(|_, _| {}, || {}).await {
                        Ok(()) => app2.restart(),
                        Err(e) => tell(&app2, "The update didn't install", &e.to_string()),
                    }
                });
            });
    });
}

fn tell(app: &AppHandle, title: &str, text: &str) {
    app.dialog().message(text).title(title).kind(MessageDialogKind::Info).show(|_| {});
}

fn main() {
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .setup(|app| {
            check_for_update(app.handle().clone(), false);
            Ok(())
        })
        .menu(|handle| {
            let menu = Menu::default(handle)?;
            // Jukebox › Check for Updates…, under About like other Mac apps.
            let updates = MenuItemBuilder::with_id("check-updates", "Check for Updates…").build(handle)?;
            if let Some(app_menu) = menu.items()?.first().and_then(|item| item.as_submenu().cloned()) {
                app_menu.insert(&updates, 1)?;
            }
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
            if event.id().as_ref() == "check-updates" {
                return check_for_update(app.clone(), true);
            }
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
