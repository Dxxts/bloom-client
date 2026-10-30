//! System, storage, diagnostics, news and updater commands.

use std::path::{Path, PathBuf};
use std::time::Duration;

use serde::Serialize;
use tauri::{AppHandle, State};

use crate::errors::{BloomError, BloomResult};
use crate::models::NewsItem;
use crate::services::state::AppState;
use crate::system::info::{self, SystemInfo};
use crate::system::paths::dir_size;

pub fn open_path(state: &AppState, path: &Path) -> BloomResult<()> {
    use tauri_plugin_opener::OpenerExt;
    state
        .app
        .opener()
        .open_path(path.to_string_lossy(), None::<&str>)
        .map_err(|e| BloomError::Internal(format!("could not open {}: {e}", path.display())))
}

#[tauri::command]
pub async fn get_system_info() -> BloomResult<SystemInfo> {
    Ok(tokio::task::spawn_blocking(info::collect).await?)
}

#[tauri::command]
pub fn open_bloom_folder(state: State<'_, AppState>, kind: String) -> BloomResult<()> {
    let paths = state.paths();
    let target: PathBuf = match kind.as_str() {
        "data" => paths.root.clone(),
        "instances" => paths.instances.clone(),
        "config" => state.config_dir.clone(),
        "logs" => state.log_dir.clone(),
        "runtimes" => paths.runtimes(),
        _ => return Err(BloomError::InvalidInput("unknown folder".into())),
    };
    std::fs::create_dir_all(&target)?;
    open_path(&state, &target)
}

/// Opens a native folder picker. The returned path is only a suggestion: it
/// is validated again when settings are saved.
#[tauri::command]
pub async fn pick_directory(state: State<'_, AppState>) -> BloomResult<Option<String>> {
    use tauri_plugin_dialog::DialogExt;
    let app = state.app.clone();
    let picked = tokio::task::spawn_blocking(move || app.dialog().file().blocking_pick_folder()).await?;
    Ok(picked.and_then(|p| p.into_path().ok()).map(|p| p.to_string_lossy().to_string()))
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StorageUsage {
    pub versions: u64,
    pub libraries: u64,
    pub assets: u64,
    pub runtimes: u64,
    pub metadata: u64,
    pub instances: u64,
    pub verification_entries: i64,
}

#[tauri::command]
pub async fn get_storage_usage(state: State<'_, AppState>) -> BloomResult<StorageUsage> {
    let paths = state.paths();
    let entries: i64 = state.db.conn().query_row("SELECT COUNT(*) FROM file_cache", [], |r| r.get(0))?;
    Ok(tokio::task::spawn_blocking(move || StorageUsage {
        versions: dir_size(&paths.versions()),
        libraries: dir_size(&paths.libraries()),
        assets: dir_size(&paths.assets()),
        runtimes: dir_size(&paths.runtimes()),
        metadata: dir_size(&paths.meta()),
        instances: dir_size(&paths.instances),
        verification_entries: entries,
    })
    .await?)
}

/// Deletes cached manifests and news. They are re-downloaded on demand.
#[tauri::command]
pub async fn clear_metadata_cache(state: State<'_, AppState>) -> BloomResult<()> {
    let meta = state.paths().meta();
    if meta.exists() {
        tokio::fs::remove_dir_all(&meta).await?;
    }
    tokio::fs::create_dir_all(&meta).await?;
    state.db.conn().execute("DELETE FROM http_cache", [])?;
    *state.manifest_cache.lock().await = None;
    Ok(())
}

/// Forgets verified-file records so the next launch re-hashes every file.
#[tauri::command]
pub fn clear_verification_cache(state: State<'_, AppState>) -> BloomResult<usize> {
    state.db.file_cache_clear()
}

#[tauri::command]
pub async fn prune_unused_files(state: State<'_, AppState>) -> BloomResult<u64> {
    if state.sessions.any_active() {
        return Err(BloomError::AlreadyRunning("close running games before cleaning up files".into()));
    }
    crate::installation::prune_unused_files(&state).await
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Reachability {
    pub name: &'static str,
    pub url: &'static str,
    pub ok: bool,
    pub latency_ms: Option<u128>,
    pub error: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MinecraftDiagnostics {
    pub services: Vec<Reachability>,
    pub installed_versions: usize,
    pub instances: usize,
    pub data_dir_writable: bool,
    pub data_dir: String,
    pub free_space_mb: Option<u64>,
}

/// Free space on the volume that holds `path` (longest matching mount point).
fn free_space_mb(path: &Path) -> Option<u64> {
    let disks = sysinfo::Disks::new_with_refreshed_list();
    disks
        .list()
        .iter()
        .filter(|d| path.starts_with(d.mount_point()))
        .max_by_key(|d| d.mount_point().as_os_str().len())
        .map(|d| d.available_space() / 1024 / 1024)
}

#[tauri::command]
pub async fn run_minecraft_diagnostics(state: State<'_, AppState>) -> BloomResult<MinecraftDiagnostics> {
    const TARGETS: [(&str, &str); 5] = [
        ("Mojang metadata", crate::minecraft::manifest::MANIFEST_URL),
        ("Mojang assets", "https://resources.download.minecraft.net/"),
        ("Mojang libraries", "https://libraries.minecraft.net/"),
        ("Microsoft login", "https://login.microsoftonline.com/"),
        ("Minecraft Services", "https://api.minecraftservices.com/"),
    ];
    let client = state.downloader.client().clone();
    let services = futures::future::join_all(TARGETS.iter().map(|(name, url)| {
        let client = client.clone();
        async move {
            let start = std::time::Instant::now();
            // Any HTTP response (even 403/404) proves the host is reachable.
            match client.head(*url).timeout(Duration::from_secs(10)).send().await {
                Ok(_) => Reachability { name, url, ok: true, latency_ms: Some(start.elapsed().as_millis()), error: None },
                Err(e) => Reachability { name, url, ok: false, latency_ms: None, error: Some(BloomError::from(e).to_string()) },
            }
        }
    }))
    .await;
    let paths = state.paths();
    let probe = paths.root.join(".bloom-write-test");
    let writable = std::fs::create_dir_all(&paths.root).is_ok() && std::fs::write(&probe, b"ok").is_ok();
    let _ = std::fs::remove_file(&probe);
    Ok(MinecraftDiagnostics {
        services,
        installed_versions: state.db.list_installed_versions()?.len(),
        instances: state.db.list_instances()?.len(),
        data_dir_writable: writable,
        free_space_mb: free_space_mb(&paths.root),
        data_dir: paths.root.to_string_lossy().to_string(),
    })
}

/// Writes a diagnostics report (no credentials) to a user-chosen file.
#[tauri::command]
pub async fn export_diagnostics(app: AppHandle, state: State<'_, AppState>) -> BloomResult<Option<String>> {
    use tauri_plugin_dialog::DialogExt;
    let system = tokio::task::spawn_blocking(info::collect).await?;
    let instances = crate::instances::list(&state)?;
    let accounts: Vec<_> = crate::authentication::list_accounts(&state)?
        .into_iter()
        .map(|a| serde_json::json!({ "username": a.username, "status": a.status, "active": a.active }))
        .collect();
    let launcher_log_tail = latest_log_tail(&state.log_dir, 300);
    let report = serde_json::json!({
        "generatedAt": chrono::Utc::now().to_rfc3339(),
        "launcher": {
            "version": app.package_info().version.to_string(),
            "schemaVersion": state.db.schema_version()?,
            "databasePath": state.db.path().to_string_lossy(),
            "microsoftAuthConfigured": crate::authentication::is_configured(&state),
            "updaterConfigured": crate::updater::endpoint().is_some(),
        },
        "system": system,
        "settings": state.settings(),
        "javaRuntimes": state.db.list_java_runtimes()?,
        "installedVersions": state.db.list_installed_versions()?.into_iter().map(|v| v.version_id).collect::<Vec<_>>(),
        "instances": instances,
        "accounts": accounts,
        "recentLaunches": state.db.recent_launches(20)?,
        "launcherLogTail": launcher_log_tail,
    });
    let default_name = format!("bloom-diagnostics-{}.json", chrono::Local::now().format("%Y%m%d-%H%M%S"));
    let dialog_app = app.clone();
    let picked = tokio::task::spawn_blocking(move || {
        dialog_app
            .dialog()
            .file()
            .set_file_name(default_name)
            .add_filter("JSON", &["json"])
            .blocking_save_file()
    })
    .await?;
    let Some(path) = picked.and_then(|p| p.into_path().ok()) else { return Ok(None) };
    tokio::fs::write(&path, serde_json::to_vec_pretty(&report)?).await?;
    Ok(Some(path.to_string_lossy().to_string()))
}

fn latest_log_tail(dir: &Path, lines: usize) -> Vec<String> {
    let Ok(entries) = std::fs::read_dir(dir) else { return Vec::new() };
    let latest = entries
        .flatten()
        .filter(|e| e.file_name().to_string_lossy().starts_with("bloom"))
        .max_by_key(|e| e.metadata().and_then(|m| m.modified()).ok());
    let Some(latest) = latest else { return Vec::new() };
    let text = std::fs::read_to_string(latest.path()).unwrap_or_default();
    let all: Vec<&str> = text.lines().collect();
    all[all.len().saturating_sub(lines)..].iter().map(|s| s.to_string()).collect()
}

// -- news --------------------------------------------------------------------

#[tauri::command]
pub async fn get_news(state: State<'_, AppState>) -> BloomResult<Vec<NewsItem>> {
    crate::minecraft::news::latest_news(&state, 12).await
}

#[tauri::command]
pub async fn get_news_article(state: State<'_, AppState>, id: String) -> BloomResult<String> {
    crate::minecraft::news::news_article(&state, &id).await
}

// -- updater -----------------------------------------------------------------

#[tauri::command]
pub async fn check_for_updates(app: AppHandle) -> BloomResult<crate::updater::UpdateInfo> {
    crate::updater::check(&app).await
}

#[tauri::command]
pub async fn install_update(app: AppHandle, state: State<'_, AppState>) -> BloomResult<()> {
    if state.sessions.any_active() {
        return Err(BloomError::AlreadyRunning("wait for games and installs to finish before updating".into()));
    }
    crate::updater::install(&app).await
}
