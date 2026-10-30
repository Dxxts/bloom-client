//! Launch, session and log commands.

use tauri::{AppHandle, State};

use crate::errors::{BloomError, BloomResult};
use crate::launcher;
use crate::models::{GameSession, LaunchRecord, LogLine};
use crate::services::state::AppState;

#[tauri::command]
pub fn launch_instance(app: AppHandle, instance_id: String) -> BloomResult<()> {
    launcher::start(&app, &instance_id)
}

/// Cancels preparation or terminates the running game.
#[tauri::command]
pub fn stop_instance(state: State<'_, AppState>, instance_id: String) -> bool {
    state.sessions.cancel(&instance_id)
}

#[tauri::command]
pub fn get_sessions(state: State<'_, AppState>) -> Vec<GameSession> {
    state.sessions.list()
}

#[tauri::command]
pub fn get_session_logs(state: State<'_, AppState>, instance_id: String) -> Vec<LogLine> {
    state.sessions.logs(&instance_id)
}

#[tauri::command]
pub fn list_launch_history(state: State<'_, AppState>, limit: Option<u32>) -> BloomResult<Vec<LaunchRecord>> {
    state.db.recent_launches(limit.unwrap_or(20).min(200))
}

/// Returns the last `max_lines` lines of a past launch log.
#[tauri::command]
pub async fn read_launch_log(state: State<'_, AppState>, launch_id: i64, max_lines: Option<usize>) -> BloomResult<Vec<String>> {
    let record = state.db.get_launch(launch_id)?.ok_or_else(|| BloomError::NotFound("launch not found".into()))?;
    let path = record.log_file.ok_or_else(|| BloomError::NotFound("no log file for this launch".into()))?;
    // Only files inside the instance's own log directory are readable.
    let expected_dir = state.paths().instance_dir(&record.instance_id)?.join(crate::instances::INTERNAL_DIR).join("logs");
    let path = std::path::PathBuf::from(path);
    if path.parent() != Some(expected_dir.as_path()) {
        return Err(BloomError::InvalidInput("invalid log file".into()));
    }
    let bytes = tokio::fs::read(&path).await.map_err(|_| BloomError::NotFound("the log file no longer exists".into()))?;
    let text = String::from_utf8_lossy(&bytes);
    let lines: Vec<&str> = text.lines().collect();
    let max = max_lines.unwrap_or(2000).min(20000);
    Ok(lines[lines.len().saturating_sub(max)..].iter().map(|s| s.to_string()).collect())
}
