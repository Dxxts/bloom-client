//! Application info and settings commands.

use serde::Serialize;
use tauri::State;

use crate::errors::BloomResult;
use crate::models::Settings;
use crate::services::{settings, state::AppState};

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppInfo {
    pub name: String,
    pub version: String,
    pub os: &'static str,
    pub arch: &'static str,
    pub data_dir: String,
    pub instances_dir: String,
    pub default_data_dir: String,
    pub config_dir: String,
    pub log_dir: String,
    pub microsoft_auth_configured: bool,
    pub microsoft_client_id_source: &'static str,
    pub updater_configured: bool,
    pub schema_version: u32,
    pub total_memory_mb: u64,
}

#[tauri::command]
pub fn get_app_info(app: tauri::AppHandle, state: State<'_, AppState>) -> BloomResult<AppInfo> {
    let paths = state.paths();
    let s = state.settings();
    Ok(AppInfo {
        name: app.package_info().name.clone(),
        version: app.package_info().version.to_string(),
        os: std::env::consts::OS,
        arch: std::env::consts::ARCH,
        data_dir: paths.root.to_string_lossy().to_string(),
        instances_dir: paths.instances.to_string_lossy().to_string(),
        default_data_dir: state.default_data_dir().to_string_lossy().to_string(),
        config_dir: state.config_dir.to_string_lossy().to_string(),
        log_dir: state.log_dir.to_string_lossy().to_string(),
        microsoft_auth_configured: crate::authentication::is_configured(&state),
        microsoft_client_id_source: if s.microsoft_client_id.is_some() {
            "settings"
        } else if option_env!("BLOOM_MS_CLIENT_ID").is_some() {
            "build"
        } else {
            "none"
        },
        updater_configured: crate::updater::endpoint().is_some(),
        schema_version: state.db.schema_version()?,
        total_memory_mb: crate::system::info::total_memory_mb(),
    })
}

#[tauri::command]
pub fn get_settings(state: State<'_, AppState>) -> Settings {
    state.settings()
}

#[tauri::command]
pub fn update_settings(state: State<'_, AppState>, settings: Settings) -> BloomResult<Settings> {
    settings::apply(&state, settings)
}

#[tauri::command]
pub fn reset_settings(state: State<'_, AppState>) -> BloomResult<Settings> {
    let current = state.settings();
    // Keep identity/navigation state; reset preferences only.
    let defaults = Settings { selected_instance_id: current.selected_instance_id, ..Settings::default() };
    settings::apply(&state, defaults)
}

#[tauri::command]
pub fn set_selected_instance(state: State<'_, AppState>, instance_id: Option<String>) -> BloomResult<()> {
    if let Some(id) = &instance_id {
        state
            .db
            .get_instance(id)?
            .ok_or_else(|| crate::errors::BloomError::NotFound("instance not found".into()))?;
    }
    let mut s = state.settings();
    s.selected_instance_id = instance_id;
    state.db.save_settings(&s)?;
    state.replace_settings(s);
    Ok(())
}
