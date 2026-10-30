//! Minecraft version manager commands.

use std::collections::HashMap;
use std::sync::Arc;

use tauri::{Emitter, State};
use tokio_util::sync::CancellationToken;

use crate::downloads::ProgressTracker;
use crate::errors::{BloomError, BloomResult};
use crate::installation;
use crate::minecraft::manifest;
use crate::models::{ProgressEvent, Stage, VersionDetails, VersionList, VersionSummary};
use crate::services::{events, state::AppState};
use crate::system::paths::{dir_size, validate_id};

#[tauri::command]
pub async fn list_versions(state: State<'_, AppState>, force: bool) -> BloomResult<VersionList> {
    let (m, offline) = manifest::get_manifest(&state, force).await?;
    let installed: HashMap<String, _> = state
        .db
        .list_installed_versions()?
        .into_iter()
        .map(|v| (v.version_id.clone(), v))
        .collect();
    let paths = state.paths();
    let mut versions: Vec<VersionSummary> = m
        .versions
        .iter()
        .map(|v| {
            let row = installed.get(&v.id);
            VersionSummary {
                id: v.id.clone(),
                release_type: v.release_type.clone(),
                release_time: v.release_time.clone(),
                metadata_url: v.url.clone(),
                metadata_sha1: v.sha1.clone(),
                installed: row.is_some()
                    && paths.version_dir(&v.id).map(|d| d.join(format!("{}.jar", v.id)).exists()).unwrap_or(false),
                java_major: row.and_then(|r| r.java_major),
                java_component: row.and_then(|r| r.java_component.clone()),
            }
        })
        .collect();
    // Installed versions no longer listed by Mojang stay visible.
    for (id, row) in &installed {
        if m.find(id).is_none() {
            versions.push(VersionSummary {
                id: id.clone(),
                release_type: row.release_type.clone().unwrap_or_else(|| "release".into()),
                release_time: row.release_time.clone().unwrap_or_default(),
                metadata_url: String::new(),
                metadata_sha1: None,
                installed: true,
                java_major: row.java_major,
                java_component: row.java_component.clone(),
            });
        }
    }
    let fetched_at = state.db.http_cache_get("version_manifest_v2")?.map(|(_, t)| t).unwrap_or_default();
    Ok(VersionList {
        latest_release: m.latest.release.clone(),
        latest_snapshot: m.latest.snapshot.clone(),
        versions,
        fetched_at,
        offline,
    })
}

#[tauri::command]
pub async fn get_version_details(state: State<'_, AppState>, id: String) -> BloomResult<VersionDetails> {
    validate_id(&id)?;
    let v = manifest::resolve_version(&state, &id).await?;
    let (java_major, java_component) = v.java_requirement();
    let installed_row = state.db.list_installed_versions()?.into_iter().find(|r| r.version_id == id);
    let dir = state.paths().version_dir(&id)?;
    let installed = installed_row.is_some() && dir.join(format!("{id}.jar")).exists();
    let disk_size = if installed {
        let d = dir.clone();
        Some(tokio::task::spawn_blocking(move || dir_size(&d)).await?)
    } else {
        None
    };
    Ok(VersionDetails {
        id: v.id.clone(),
        release_type: v.release_type.clone().unwrap_or_else(|| "release".into()),
        release_time: v.release_time.clone().unwrap_or_default(),
        main_class: v.main_class.clone(),
        java_major,
        java_component,
        asset_index: v.asset_index_id(),
        library_count: v.libraries.len(),
        client_size: v.downloads.as_ref().and_then(|d| d.client.as_ref()).and_then(|c| c.size).unwrap_or(0),
        installed,
        installed_at: installed_row.map(|r| r.installed_at),
        disk_size,
    })
}

pub(crate) fn register_task(state: &AppState, task_id: &str) -> BloomResult<CancellationToken> {
    let mut tasks = state.tasks.lock().unwrap_or_else(|e| e.into_inner());
    if tasks.contains_key(task_id) {
        return Err(BloomError::AlreadyRunning("this operation is already in progress".into()));
    }
    let token = CancellationToken::new();
    tasks.insert(task_id.to_string(), token.clone());
    Ok(token)
}

pub(crate) fn unregister_task(state: &AppState, task_id: &str) {
    state.tasks.lock().unwrap_or_else(|e| e.into_inner()).remove(task_id);
}

/// Emits the terminal progress event of a standalone task so the UI can
/// close its progress view even if it was not mounted when the task ended.
pub(crate) fn finish_task<T>(state: &AppState, tracker: &ProgressTracker, task_id: &str, result: &BloomResult<T>) {
    unregister_task(state, task_id);
    match result {
        Ok(_) => tracker.stage(Stage::Exited, "Completed"),
        Err(BloomError::Cancelled) => tracker.stage(Stage::Cancelled, "Cancelled"),
        Err(e) => tracker.stage(Stage::Failed, e.to_string()),
    }
}

pub(crate) fn task_tracker(state: &AppState, task_id: &str) -> Arc<ProgressTracker> {
    let app = state.app.clone();
    ProgressTracker::new(task_id, Arc::new(move |p: ProgressEvent| {
        let _ = app.emit(events::PROGRESS, &p);
    }))
}

/// Installs (or repairs) a version. Progress is reported through
/// `bloom://progress` events with `taskId = "version:<id>"`.
#[tauri::command]
pub async fn install_version(state: State<'_, AppState>, id: String) -> BloomResult<()> {
    validate_id(&id)?;
    let task_id = format!("version:{id}");
    let cancel = register_task(&state, &task_id)?;
    let tracker = task_tracker(&state, &task_id);
    let result = installation::install_version(&state, &id, &tracker, &cancel).await;
    finish_task(&state, &tracker, &task_id, &result);
    let _ = state.app.emit(events::INSTANCES_CHANGED, ());
    result.map(|_| ())
}

#[tauri::command]
pub fn cancel_task(state: State<'_, AppState>, task_id: String) -> bool {
    match state.tasks.lock().unwrap_or_else(|e| e.into_inner()).get(&task_id) {
        Some(t) => {
            t.cancel();
            true
        }
        None => false,
    }
}

#[tauri::command]
pub async fn delete_version(state: State<'_, AppState>, id: String) -> BloomResult<()> {
    validate_id(&id)?;
    installation::delete_version(&state, &id).await?;
    let _ = state.app.emit(events::INSTANCES_CHANGED, ());
    Ok(())
}
