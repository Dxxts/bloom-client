//! Launcher self-update through the official Tauri updater plugin.
//!
//! Update packages are signed with the minisign key whose public half is in
//! `tauri.conf.json` (`plugins.updater.pubkey`); the plugin refuses any
//! package whose signature does not verify. The update feed URL is provided
//! at build time through `BLOOM_UPDATE_ENDPOINT` (see docs/RELEASING.md);
//! builds without it report the updater as not configured.

use serde::Serialize;
use tauri::{AppHandle, Emitter};
use tauri_plugin_updater::UpdaterExt;

use crate::errors::{BloomError, BloomResult};
use crate::services::events;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateInfo {
    pub configured: bool,
    pub available: bool,
    pub current_version: String,
    pub version: Option<String>,
    pub notes: Option<String>,
    pub date: Option<String>,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct UpdateProgress {
    downloaded: u64,
    total: Option<u64>,
    finished: bool,
}

pub fn endpoint() -> Option<&'static str> {
    option_env!("BLOOM_UPDATE_ENDPOINT").filter(|s| !s.trim().is_empty())
}

fn updater(app: &AppHandle) -> BloomResult<Option<tauri_plugin_updater::Updater>> {
    let Some(endpoint) = endpoint() else { return Ok(None) };
    let url = url::Url::parse(endpoint).map_err(|e| BloomError::Updater(format!("invalid update endpoint: {e}")))?;
    if url.scheme() != "https" {
        return Err(BloomError::Updater("the update endpoint must use HTTPS".into()));
    }
    let updater = app
        .updater_builder()
        .endpoints(vec![url])
        .map_err(|e| BloomError::Updater(e.to_string()))?
        .build()
        .map_err(|e| BloomError::Updater(e.to_string()))?;
    Ok(Some(updater))
}

pub async fn check(app: &AppHandle) -> BloomResult<UpdateInfo> {
    let current_version = app.package_info().version.to_string();
    let Some(updater) = updater(app)? else {
        return Ok(UpdateInfo {
            configured: false,
            available: false,
            current_version,
            version: None,
            notes: None,
            date: None,
        });
    };
    let update = updater.check().await.map_err(|e| BloomError::Updater(e.to_string()))?;
    Ok(match update {
        Some(u) => UpdateInfo {
            configured: true,
            available: true,
            current_version,
            version: Some(u.version.clone()),
            notes: u.body.clone(),
            date: u.date.map(|d| d.to_string()),
        },
        None => UpdateInfo { configured: true, available: false, current_version, version: None, notes: None, date: None },
    })
}

/// Downloads, verifies and installs the update, then restarts the launcher.
pub async fn install(app: &AppHandle) -> BloomResult<()> {
    let updater = updater(app)?.ok_or_else(|| BloomError::Updater("the updater is not configured".into()))?;
    let update = updater
        .check()
        .await
        .map_err(|e| BloomError::Updater(e.to_string()))?
        .ok_or_else(|| BloomError::Updater("no update is available".into()))?;
    let mut downloaded = 0u64;
    let progress_app = app.clone();
    let finished_app = app.clone();
    update
        .download_and_install(
            move |chunk, total| {
                downloaded += chunk as u64;
                let _ = progress_app.emit(events::UPDATE_PROGRESS, UpdateProgress { downloaded, total, finished: false });
            },
            move || {
                let _ = finished_app.emit(events::UPDATE_PROGRESS, UpdateProgress { downloaded: 0, total: None, finished: true });
            },
        )
        .await
        .map_err(|e| BloomError::Updater(e.to_string()))?;
    app.restart();
}
