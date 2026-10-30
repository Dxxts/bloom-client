//! Bloom Client launcher backend.
//!
//! Module map:
//! * `commands`        – Tauri command layer (the only frontend-facing API)
//! * `services`        – app state, settings, logging, events, tray
//! * `database`        – SQLite persistence and migrations
//! * `downloads`       – parallel, resumable, verified download engine
//! * `minecraft`       – manifests, version model, rules, arguments, news
//! * `installation`    – installation planning, verification and repair
//! * `java`            – Java detection and managed runtimes
//! * `authentication`  – Microsoft / Xbox / Minecraft Services auth
//! * `instances`       – instance manager, loader adapters, mods & packs
//! * `launcher`        – launch pipeline, process monitoring, sessions
//! * `updater`         – signed self-updates
//! * `system`          – OS integration, paths and host information
//! * `models`, `errors` – shared types

mod authentication;
mod commands;
mod database;
mod downloads;
mod engine;
mod errors;
mod installation;
mod instances;
mod java;
mod launcher;
mod minecraft;
mod models;
mod services;
mod system;
mod updater;

use std::sync::Arc;

use tauri::{Manager, WindowEvent};

use crate::authentication::secret_store::KeyringStore;
use crate::database::Db;
use crate::services::state::AppState;

const KEYRING_SERVICE: &str = "com.bloomclient.launcher";

fn setup(app: &mut tauri::App) -> Result<(), Box<dyn std::error::Error>> {
    let handle = app.handle().clone();
    let config_dir = app.path().app_data_dir()?;
    let log_dir = app.path().app_log_dir()?;
    std::fs::create_dir_all(&config_dir)?;

    let db = Arc::new(Db::open(&config_dir.join("bloom.db"))?);
    let settings = db.load_settings()?;
    let log_handle = services::logging::init(&log_dir, &settings.log_level)?;
    tracing::info!(version = env!("CARGO_PKG_VERSION"), "Bloom Client starting");
    db.close_dangling_launches()?;

    let state = AppState::new(
        handle.clone(),
        db,
        settings.clone(),
        config_dir,
        log_dir,
        Arc::new(KeyringStore::new(KEYRING_SERVICE)),
        log_handle,
    )?;
    state.paths().ensure()?;
    app.manage(state);

    services::tray::init(&handle)?;

    // Keep the OS autostart entry consistent with the saved preference.
    {
        use tauri_plugin_autostart::ManagerExt;
        let enabled = handle.autolaunch().is_enabled().unwrap_or(false);
        if enabled != settings.launch_on_startup {
            let st = handle.state::<AppState>();
            if let Err(e) = services::settings::set_autostart(&st, settings.launch_on_startup) {
                tracing::warn!(error = %e, "could not sync autostart");
            }
        }
    }

    // Background session renewal; never blocks startup.
    let bg = handle.clone();
    tauri::async_runtime::spawn(async move {
        let state = bg.state::<AppState>();
        authentication::refresh_expiring(&state).await;
    });

    Ok(())
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            services::tray::show_main(app);
        }))
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_process::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            Some(vec!["--autostart"]),
        ))
        .setup(setup)
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                if window.label() != "main" {
                    return;
                }
                let app = window.app_handle();
                let Some(state) = app.try_state::<AppState>() else { return };
                if state.settings().minimize_to_tray {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            commands::app::get_app_info,
            commands::app::get_settings,
            commands::app::update_settings,
            commands::app::reset_settings,
            commands::app::set_selected_instance,
            commands::accounts::list_accounts,
            commands::accounts::login_microsoft,
            commands::accounts::cancel_login,
            commands::accounts::logout_account,
            commands::accounts::set_active_account,
            commands::accounts::refresh_account,
            commands::accounts::get_account_skin,
            commands::versions::list_versions,
            commands::versions::get_version_details,
            commands::versions::install_version,
            commands::versions::cancel_task,
            commands::versions::delete_version,
            commands::instances::list_instances,
            commands::instances::get_instance,
            commands::instances::create_instance,
            commands::instances::update_instance,
            commands::instances::duplicate_instance,
            commands::instances::delete_instance,
            commands::instances::list_loaders,
            commands::instances::list_render_engines,
            commands::instances::open_instance_folder,
            commands::instances::list_mods,
            commands::instances::set_mod_enabled,
            commands::instances::delete_mod,
            commands::instances::import_mods,
            commands::instances::list_resource_packs,
            commands::instances::set_resource_pack_enabled,
            commands::instances::delete_resource_pack,
            commands::instances::import_resource_packs,
            commands::java::list_java_runtimes,
            commands::java::detect_java_runtimes,
            commands::java::add_java_runtime,
            commands::java::test_java_runtime,
            commands::java::remove_java_runtime,
            commands::java::install_java_runtime,
            commands::java::uninstall_java_runtime,
            commands::launch::launch_instance,
            commands::launch::stop_instance,
            commands::launch::get_sessions,
            commands::launch::get_session_logs,
            commands::launch::list_launch_history,
            commands::launch::read_launch_log,
            commands::system::get_system_info,
            commands::system::open_bloom_folder,
            commands::system::pick_directory,
            commands::system::get_storage_usage,
            commands::system::clear_metadata_cache,
            commands::system::clear_verification_cache,
            commands::system::prune_unused_files,
            commands::system::run_minecraft_diagnostics,
            commands::system::export_diagnostics,
            commands::system::get_news,
            commands::system::get_news_article,
            commands::system::check_for_updates,
            commands::system::install_update,
        ])
        .build(tauri::generate_context!())
        .expect("error while building Bloom Client")
        .run(|app, event| {
            if let tauri::RunEvent::Exit = event {
                // Abort in-flight installs; running games keep running as
                // independent processes.
                if let Some(state) = app.try_state::<AppState>() {
                    for token in state.tasks.lock().unwrap_or_else(|e| e.into_inner()).values() {
                        token.cancel();
                    }
                }
            }
        });
}
