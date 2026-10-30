//! Java runtime commands.

use serde::Serialize;
use tauri::State;

use super::versions::{finish_task, register_task, task_tracker};
use crate::errors::BloomResult;
use crate::java;
use crate::models::JavaRuntime;
use crate::services::state::AppState;

#[tauri::command]
pub fn list_java_runtimes(state: State<'_, AppState>) -> BloomResult<Vec<JavaRuntime>> {
    state.db.list_java_runtimes()
}

#[tauri::command]
pub async fn detect_java_runtimes(state: State<'_, AppState>) -> BloomResult<Vec<JavaRuntime>> {
    java::detect(&state).await
}

/// Opens a file picker and registers the selected Java executable after
/// verifying it actually runs. Returns `None` if the dialog was cancelled.
#[tauri::command]
pub async fn add_java_runtime(state: State<'_, AppState>) -> BloomResult<Option<JavaRuntime>> {
    use tauri_plugin_dialog::DialogExt;
    let app = state.app.clone();
    let picked = tokio::task::spawn_blocking(move || {
        let dialog = app.dialog().file().set_title("Select a Java executable");
        #[cfg(windows)]
        let dialog = dialog.add_filter("Java", &["exe"]);
        dialog.blocking_pick_file()
    })
    .await?;
    let Some(path) = picked.and_then(|p| p.into_path().ok()) else { return Ok(None) };
    Ok(Some(java::add_custom(&state, &path.to_string_lossy()).await?))
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct JavaTestResult {
    pub version: String,
    pub major: u32,
    pub vendor: String,
    pub arch: String,
}

#[tauri::command]
pub async fn test_java_runtime(path: String) -> BloomResult<JavaTestResult> {
    let p = java::probe(&java::normalize_java_path(std::path::Path::new(&path))).await?;
    Ok(JavaTestResult { version: p.version, major: p.major, vendor: p.vendor, arch: p.arch })
}

#[tauri::command]
pub fn remove_java_runtime(state: State<'_, AppState>, path: String) -> BloomResult<()> {
    // Only the registry entry is removed for system/custom runtimes; Bloom
    // never deletes Java installations it did not create.
    state.db.remove_java_runtime(&path)
}

/// Downloads (or repairs) a managed runtime. Progress events use
/// `taskId = "java:<component>"`.
#[tauri::command]
pub async fn install_java_runtime(state: State<'_, AppState>, component: String) -> BloomResult<JavaRuntime> {
    let task_id = format!("java:{component}");
    let cancel = register_task(&state, &task_id)?;
    let tracker = task_tracker(&state, &task_id);
    let result = java::ensure_managed_runtime(&state, &component, &tracker, &cancel).await;
    finish_task(&state, &tracker, &task_id, &result);
    let path = result?;
    let path = path.to_string_lossy().to_string();
    state
        .db
        .list_java_runtimes()?
        .into_iter()
        .find(|r| r.path == path)
        .ok_or_else(|| crate::errors::BloomError::Java("runtime not registered".into()))
}

#[tauri::command]
pub async fn uninstall_java_runtime(state: State<'_, AppState>, component: String) -> BloomResult<()> {
    if state.sessions.any_active() {
        return Err(crate::errors::BloomError::AlreadyRunning(
            "close running games before removing a Java runtime".into(),
        ));
    }
    java::remove_managed_runtime(&state, &component).await
}
