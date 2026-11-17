// Jukebox for macOS: the Jukebox website in its own window.
//
// The window starts on start/index.html, which sends it to the saved server (or asks for one). After that
// it's the website itself, served by Jukebox, so the app always matches the site. This file only adds what
// a browser tab doesn't have: a Go menu (back, forward, reload, change server); on macOS, closing the
// window hides it so the music keeps playing (clicking the Dock icon brings it back); and updates: at start
// and from Jukebox › Check for Updates…, it reads latest-macos.json on the newest GitHub release, asks, then
// installs the signed update and restarts.
//
// And what the Mac's web view lacks next to a browser: notifications (the page's `Notification` is filled in
// to call the `notify` command), downloads (saved to Downloads), and links that open a new tab (they open in
// the default browser). Microphone use, for voice messages, is declared in Info.plist and Entitlements.plist.
//
// What's playing goes to macOS Now Playing (Control Center, the media keys, AirPods, apps like Boring Notch):
// the page calls `media_update` / `media_clear` (web/src/player/player.ts), and Now Playing's buttons come back
// to it as `window.__jukeboxMedia(action, value)`. And like Music, it pauses when the headphones or speaker it
// was playing through go away (Bluetooth out of range, unplugged), by watching the Mac's audio outputs.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use tauri::menu::{Menu, MenuItemBuilder, SubmenuBuilder};
use tauri::webview::{DownloadEvent, NewWindowResponse};
use tauri::{AppHandle, Manager, RunEvent, Url, WebviewWindowBuilder, WindowEvent};
use tauri_plugin_notification::NotificationExt;
use tauri_plugin_opener::OpenerExt;
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons, MessageDialogKind};
use tauri_plugin_updater::UpdaterExt;
use souvlaki::{MediaControlEvent, MediaControls, MediaMetadata, MediaPlayback, MediaPosition, PlatformConfig, SeekDirection};
use std::sync::Mutex;
use std::time::Duration;

/// Runs in every page before its own scripts: a `Notification` for pages to use (the web view has none) that
/// shows a macOS notification through the `notify` command. The website asks for permission and gets it here;
/// macOS asks you once, the first time one is shown.
const NOTIFICATION_SHIM: &str = r#"
(() => {
  if (window.Notification) return;
  class AppNotification {
    constructor(title, options = {}) {
      this.title = title;
      this.onclick = null;
      try { window.__TAURI_INTERNALS__.invoke('notify', { title: String(title), body: String(options.body || '') }); } catch (_) {}
    }
    close() {}
  }
  AppNotification.permission = 'granted';
  AppNotification.requestPermission = async () => 'granted';
  window.Notification = AppNotification;
})();
"#;

/// Shows a notification for the website (see NOTIFICATION_SHIM).
#[tauri::command]
fn notify(app: AppHandle, title: String, body: String) {
    let _ = app.notification().builder().title(title).body(body).show();
}

/// macOS Now Playing for this app; None if it couldn't be set up.
struct NowPlaying(Mutex<Option<MediaControls>>);

// Now Playing is only touched from the main thread (commands that aren't async run there in Tauri), and its
// buttons call back on the main thread too.
unsafe impl Send for NowPlaying {}
unsafe impl Sync for NowPlaying {}

/// What's playing, from the page: the song, its cover (an address), length and place, whether it plays, and
/// repeat ("off", "all" or "one"; pages from before it was sent leave it out).
#[tauri::command]
#[allow(clippy::too_many_arguments)]
fn media_update(
    now: tauri::State<NowPlaying>, title: String, artist: String, album: String, cover: Option<String>,
    duration_ms: u64, position_ms: u64, playing: bool, repeat: Option<String>,
) {
    #[cfg(target_os = "macos")]
    if let Some(mode) = &repeat {
        repeat::show(mode);
    }
    #[cfg(not(target_os = "macos"))]
    let _ = repeat;
    let Ok(mut guard) = now.0.lock() else { return };
    let Some(controls) = guard.as_mut() else { return };
    let _ = controls.set_metadata(MediaMetadata {
        title: Some(&title),
        artist: Some(&artist),
        album: Some(&album),
        cover_url: cover.as_deref(),
        duration: Some(Duration::from_millis(duration_ms)),
    });
    let progress = Some(MediaPosition(Duration::from_millis(position_ms)));
    let _ = controls.set_playback(if playing { MediaPlayback::Playing { progress } } else { MediaPlayback::Paused { progress } });
}

/// Nothing is playing any more.
#[tauri::command]
fn media_clear(now: tauri::State<NowPlaying>) {
    if let Ok(mut guard) = now.0.lock() {
        if let Some(controls) = guard.as_mut() {
            let _ = controls.set_playback(MediaPlayback::Stopped);
        }
    }
}

/// Tells the page what Now Playing (or the audio output) asked for: `window.__jukeboxMedia(action, value)`.
fn tell_page(app: &AppHandle, action: &str, value: Option<f64>) {
    if let Some(window) = app.get_webview_window("main") {
        let value = value.map(|v| v.to_string()).unwrap_or_else(|| "undefined".into());
        let _ = window.eval(&format!("window.__jukeboxMedia && window.__jukeboxMedia('{action}', {value})"));
    }
}

/// Sets up Now Playing and passes its buttons (play, pause, next, previous, seeking) to the page.
fn now_playing(app: &AppHandle) -> Option<MediaControls> {
    let mut controls = MediaControls::new(PlatformConfig { display_name: "Jukebox", dbus_name: "jukebox", hwnd: None }).ok()?;
    let handle = app.clone();
    controls
        .attach(move |event| match event {
            MediaControlEvent::Play => tell_page(&handle, "play", None),
            MediaControlEvent::Pause | MediaControlEvent::Stop => tell_page(&handle, "pause", None),
            MediaControlEvent::Toggle => tell_page(&handle, "toggle", None),
            MediaControlEvent::Next => tell_page(&handle, "next", None),
            MediaControlEvent::Previous => tell_page(&handle, "previous", None),
            MediaControlEvent::SetPosition(MediaPosition(at)) => tell_page(&handle, "seekto", Some(at.as_secs_f64())),
            MediaControlEvent::Seek(SeekDirection::Forward) => tell_page(&handle, "seekby", Some(10.0)),
            MediaControlEvent::Seek(SeekDirection::Backward) => tell_page(&handle, "seekby", Some(-10.0)),
            MediaControlEvent::SeekBy(SeekDirection::Forward, by) => tell_page(&handle, "seekby", Some(by.as_secs_f64())),
            MediaControlEvent::SeekBy(SeekDirection::Backward, by) => tell_page(&handle, "seekby", Some(-by.as_secs_f64())),
            _ => {}
        })
        .ok()?;
    Some(controls)
}

/// Repeat in Now Playing (souvlaki has none): the system's repeat button (Control Center, Boring Notch) sets the
/// page's repeat as `window.__jukeboxMedia('repeat', 0 | 1 | 2)` (off, one, all), and the page's repeat shows on it.
#[cfg(target_os = "macos")]
mod repeat {
    use super::{tell_page, AppHandle};
    use block2::RcBlock;
    use objc2_media_player::{
        MPChangeRepeatModeCommandEvent, MPRemoteCommandCenter, MPRemoteCommandEvent, MPRemoteCommandHandlerStatus, MPRepeatType,
    };
    use std::ptr::NonNull;

    pub fn listen(app: AppHandle) {
        unsafe {
            let command = MPRemoteCommandCenter::sharedCommandCenter().changeRepeatModeCommand();
            command.setEnabled(true);
            let handler = RcBlock::new(move |event: NonNull<MPRemoteCommandEvent>| -> MPRemoteCommandHandlerStatus {
                // This command only sends repeat events.
                let event = &*(event.as_ptr() as *const MPChangeRepeatModeCommandEvent);
                let mode = event.repeatType();
                let value = if mode == MPRepeatType::One { 1.0 } else if mode == MPRepeatType::All { 2.0 } else { 0.0 };
                tell_page(&app, "repeat", Some(value));
                MPRemoteCommandHandlerStatus::Success
            });
            let _ = command.addTargetWithHandler(&handler);
        }
    }

    pub fn show(mode: &str) {
        let kind = match mode {
            "one" => MPRepeatType::One,
            "all" => MPRepeatType::All,
            _ => MPRepeatType::Off,
        };
        unsafe { MPRemoteCommandCenter::sharedCommandCenter().changeRepeatModeCommand().setCurrentRepeatType(kind) };
    }
}

/// Pauses when the output the music was playing through disappears (Bluetooth headphones out of range,
/// headphones unplugged): the default output changes and the old one is no longer there. Switching to another
/// output that's still connected doesn't pause, as in Music.
fn watch_outputs(app: AppHandle) {
    std::thread::spawn(move || {
        use cpal::traits::{DeviceTrait, HostTrait};
        let host = cpal::default_host();
        let current = |host: &cpal::Host| host.default_output_device().and_then(|d| d.name().ok());
        let mut last = current(&host);
        loop {
            std::thread::sleep(Duration::from_millis(700));
            let now = current(&host);
            if now == last {
                continue;
            }
            if let Some(old) = &last {
                let still_there = host
                    .output_devices()
                    .map(|mut devices| devices.any(|d| d.name().ok().as_deref() == Some(old.as_str())))
                    .unwrap_or(true);
                if !still_there {
                    tell_page(&app, "outputgone", None);
                }
            }
            last = now;
        }
    });
}

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
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![notify, media_update, media_clear])
        .setup(|app| {
            app.manage(NowPlaying(Mutex::new(now_playing(app.handle()))));
            #[cfg(target_os = "macos")]
            repeat::listen(app.handle().clone());
            watch_outputs(app.handle().clone());
            // The window from tauri.conf.json, with what the web view needs from the app.
            let config = app.config().app.windows.first().cloned().expect("a window in tauri.conf.json");
            let links = app.handle().clone();
            let downloads = app.handle().clone();
            WebviewWindowBuilder::from_config(app.handle(), &config)?
                .initialization_script(NOTIFICATION_SHIM)
                // A link that opens a new tab (GitHub, a feedback issue) opens in the default browser.
                .on_new_window(move |url, _| {
                    let _ = links.opener().open_url(url.as_str(), None::<&str>);
                    NewWindowResponse::Deny
                })
                // Saving a picture from a chat: it goes to Downloads, and a notification says so.
                .on_download(move |_, event| {
                    if let DownloadEvent::Finished { success, .. } = event {
                        let text = if success { "Saved to your Downloads folder" } else { "Couldn't save it" };
                        let _ = downloads.notification().builder().title("Jukebox").body(text).show();
                    }
                    true
                })
                .build()?;
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
