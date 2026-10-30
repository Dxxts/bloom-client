//! Instance and instance-content commands.

use std::path::PathBuf;

use tauri::{Emitter, State};

use crate::errors::{BloomError, BloomResult};
use crate::instances::{self, content, loaders};
use crate::models::{Instance, InstanceInput, ModEntry, ResourcePackEntry};
use crate::services::{events, state::AppState};

fn changed(state: &AppState) {
    let _ = state.app.emit(events::INSTANCES_CHANGED, ());
}

#[tauri::command]
pub fn list_instances(state: State<'_, AppState>) -> BloomResult<Vec<Instance>> {
    instances::list(&state)
}

#[tauri::command]
pub fn get_instance(state: State<'_, AppState>, id: String) -> BloomResult<Instance> {
    instances::get(&state, &id)
}

#[tauri::command]
pub fn create_instance(state: State<'_, AppState>, input: InstanceInput) -> BloomResult<Instance> {
    let i = instances::create(&state, input)?;
    changed(&state);
    Ok(i)
}

#[tauri::command]
pub fn update_instance(state: State<'_, AppState>, id: String, input: InstanceInput) -> BloomResult<Instance> {
    let i = instances::update(&state, &id, input)?;
    changed(&state);
    Ok(i)
}

#[tauri::command]
pub async fn duplicate_instance(state: State<'_, AppState>, id: String) -> BloomResult<Instance> {
    let i = instances::duplicate(&state, &id).await?;
    changed(&state);
    Ok(i)
}

#[tauri::command]
pub async fn delete_instance(state: State<'_, AppState>, id: String) -> BloomResult<()> {
    instances::delete(&state, &id).await?;
    changed(&state);
    Ok(())
}

#[tauri::command]
pub fn list_render_engines(app: tauri::AppHandle) -> Vec<crate::engine::RenderEngineInfo> {
    crate::engine::list(&app)
}

#[tauri::command]
pub fn list_loaders() -> Vec<loaders::LoaderInfo> {
    loaders::list()
}

/// Opens one of the instance's folders in the system file manager.
#[tauri::command]
pub fn open_instance_folder(state: State<'_, AppState>, id: String, sub: Option<String>) -> BloomResult<()> {
    let dir = instances::instance_dir(&state, &id)?;
    let target = match sub.as_deref() {
        None | Some("") => dir,
        Some(s @ ("mods" | "resourcepacks" | "saves" | "screenshots" | "logs" | "crash-reports")) => dir.join(s),
        Some(_) => return Err(BloomError::InvalidInput("unknown folder".into())),
    };
    std::fs::create_dir_all(&target)?;
    super::system::open_path(&state, &target)
}

// -- mods -------------------------------------------------------------------

#[tauri::command]
pub async fn list_mods(state: State<'_, AppState>, instance_id: String) -> BloomResult<Vec<ModEntry>> {
    content::list_mods(&state, &instance_id).await
}

#[tauri::command]
pub fn set_mod_enabled(
    state: State<'_, AppState>,
    instance_id: String,
    file_name: String,
    enabled: bool,
) -> BloomResult<String> {
    content::set_mod_enabled(&state, &instance_id, &file_name, enabled)
}

#[tauri::command]
pub fn delete_mod(state: State<'_, AppState>, instance_id: String, file_name: String) -> BloomResult<()> {
    content::delete_mod(&state, &instance_id, &file_name)
}

async fn pick_files(state: &AppState, title: &str, filter_name: &str, ext: &'static [&'static str]) -> BloomResult<Vec<PathBuf>> {
    use tauri_plugin_dialog::DialogExt;
    let app = state.app.clone();
    let title = title.to_string();
    let filter_name = filter_name.to_string();
    let picked = tokio::task::spawn_blocking(move || {
        app.dialog().file().set_title(title).add_filter(filter_name, ext).blocking_pick_files()
    })
    .await?;
    Ok(picked
        .unwrap_or_default()
        .into_iter()
        .filter_map(|f| f.into_path().ok())
        .collect())
}

#[tauri::command]
pub async fn import_mods(state: State<'_, AppState>, instance_id: String) -> BloomResult<usize> {
    instances::instance_dir(&state, &instance_id)?;
    let files = pick_files(&state, "Import mods", "Java mods", &["jar"]).await?;
    content::import_mods(&state, &instance_id, &files)
}

// -- resource packs ---------------------------------------------------------

#[tauri::command]
pub async fn list_resource_packs(state: State<'_, AppState>, instance_id: String) -> BloomResult<Vec<ResourcePackEntry>> {
    content::list_resource_packs(&state, &instance_id).await
}

#[tauri::command]
pub fn set_resource_pack_enabled(
    state: State<'_, AppState>,
    instance_id: String,
    file_name: String,
    enabled: bool,
) -> BloomResult<()> {
    content::set_pack_enabled(&state, &instance_id, &file_name, enabled)
}

#[tauri::command]
pub fn delete_resource_pack(state: State<'_, AppState>, instance_id: String, file_name: String) -> BloomResult<()> {
    content::delete_pack(&state, &instance_id, &file_name)
}

#[tauri::command]
pub async fn import_resource_packs(state: State<'_, AppState>, instance_id: String) -> BloomResult<usize> {
    instances::instance_dir(&state, &instance_id)?;
    let files = pick_files(&state, "Import resource packs", "Resource packs", &["zip"]).await?;
    content::import_packs(&state, &instance_id, &files)
}
