//! Minecraft installation engine.
//!
//! The pipeline is idempotent: running it twice only re-verifies files. It
//! is built from three steps:
//!
//! 1. [`plan_version`] turns version metadata into the exact list of files
//!    (client jar, libraries for this OS/arch, asset index, assets, logging
//!    configuration) and the launch inputs (classpath, natives).
//! 2. [`ensure_files`] verifies every file (size + SHA1, accelerated by a
//!    persistent size/mtime cache) and downloads only what is missing or
//!    corrupted.
//! 3. [`install_version`] runs both and finalizes legacy asset layouts.

use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::Arc;

use futures::StreamExt;
use serde::Deserialize;
use tokio_util::sync::CancellationToken;

use crate::database::repos::InstalledVersionRow;
use crate::database::{now_ts, Db};
use crate::downloads::{sha1_file, DownloadItem, ProgressTracker};
use crate::errors::{BloomError, BloomResult};
use crate::minecraft::manifest;
use crate::minecraft::rules::Features;
use crate::minecraft::version::{MavenCoord, VersionJson};
use crate::models::Stage;
use crate::services::state::AppState;
use crate::system::paths::{safe_join, write_atomic, GamePaths};

const RESOURCES_URL: &str = "https://resources.download.minecraft.net";

#[derive(Debug, Clone)]
pub struct NativeJar {
    pub path: PathBuf,
    pub exclude: Vec<String>,
}

#[derive(Debug, Clone)]
pub struct VersionPlan {
    pub version: VersionJson,
    /// Libraries in manifest order followed by the client jar.
    pub classpath: Vec<PathBuf>,
    /// Legacy native archives that must be extracted before launch.
    pub natives: Vec<NativeJar>,
    pub files: Vec<DownloadItem>,
    pub asset_index_id: String,
    pub assets_virtual: bool,
    pub map_to_resources: bool,
    pub asset_objects: Vec<(String, String)>,
    /// Logging argument template and the config file it points to.
    pub logging: Option<(String, PathBuf)>,
}

#[derive(Deserialize)]
struct AssetIndexFile {
    objects: HashMap<String, AssetObject>,
    #[serde(default, rename = "virtual")]
    is_virtual: bool,
    #[serde(default)]
    map_to_resources: bool,
}

#[derive(Deserialize)]
struct AssetObject {
    hash: String,
    size: u64,
}

fn is_sha1(s: &str) -> bool {
    s.len() == 40 && s.chars().all(|c| c.is_ascii_hexdigit())
}

/// Builds the installation plan. Downloads (and verifies) the asset index if
/// needed because the list of asset objects lives inside it.
pub async fn plan_version(state: &AppState, version: VersionJson) -> BloomResult<VersionPlan> {
    let paths = state.paths();
    let features = Features::default();
    let mut files: Vec<DownloadItem> = Vec::new();
    let mut classpath: Vec<PathBuf> = Vec::new();
    let mut seen_cp: HashSet<PathBuf> = HashSet::new();
    let mut natives = Vec::new();

    // -- client jar ---------------------------------------------------------
    let version_dir = paths.version_dir(&version.id)?;
    let client_jar = version_dir.join(format!("{}.jar", version.id));
    let client = version
        .downloads
        .as_ref()
        .and_then(|d| d.client.clone())
        .ok_or_else(|| BloomError::Parse(format!("version {} has no client download", version.id)))?;
    files.push(DownloadItem {
        url: client.url,
        path: client_jar.clone(),
        sha1: client.sha1,
        size: client.size,
        label: format!("{}.jar", version.id),
    });

    // -- libraries ----------------------------------------------------------
    let libs_root = paths.libraries();
    for lib in &version.libraries {
        if !lib.applies(&features) || lib.is_foreign_arch_native() {
            continue;
        }
        let coord = MavenCoord::parse(&lib.name)?;

        // Main artifact
        let artifact = lib.downloads.as_ref().and_then(|d| d.artifact.clone());
        let main = match artifact {
            Some(a) => {
                let rel = a.path.clone().unwrap_or_else(|| coord.path());
                Some((a.url, rel, a.sha1, a.size))
            }
            None if lib.downloads.is_none() => {
                // Third-party profile entry: maven repository + coordinate.
                let base = lib.url.clone().unwrap_or_else(|| "https://libraries.minecraft.net/".into());
                let rel = coord.path();
                Some((format!("{}/{}", base.trim_end_matches('/'), rel), rel, lib.sha1.clone(), lib.size))
            }
            None => None,
        };
        if let Some((url, rel, sha1, size)) = main {
            if !url.is_empty() {
                let path = safe_join(&libs_root, &rel)?;
                if seen_cp.insert(path.clone()) {
                    classpath.push(path.clone());
                    files.push(DownloadItem { url, path, sha1, size, label: file_label(&rel) });
                }
            }
        }

        // Legacy natives (pre-1.19): separate classifier archive to extract.
        if let Some(classifier) = lib.native_classifier() {
            let info = lib
                .downloads
                .as_ref()
                .and_then(|d| d.classifiers.as_ref())
                .and_then(|c| c.get(&classifier).cloned());
            let (url, rel, sha1, size) = match info {
                Some(i) => {
                    let rel = i.path.clone().unwrap_or_else(|| coord.path_with_classifier(Some(&classifier)));
                    (i.url, rel, i.sha1, i.size)
                }
                None => {
                    let base = lib.url.clone().unwrap_or_else(|| "https://libraries.minecraft.net/".into());
                    let rel = coord.path_with_classifier(Some(&classifier));
                    (format!("{}/{}", base.trim_end_matches('/'), rel), rel, None, None)
                }
            };
            let path = safe_join(&libs_root, &rel)?;
            if !files.iter().any(|f| f.path == path) {
                files.push(DownloadItem { url, path: path.clone(), sha1, size, label: file_label(&rel) });
            }
            natives.push(NativeJar {
                path,
                exclude: lib.extract.as_ref().map(|e| e.exclude.clone()).unwrap_or_default(),
            });
        }
    }
    classpath.push(client_jar.clone());

    // -- asset index + objects ----------------------------------------------
    let asset_index_id = version.asset_index_id();
    let mut asset_objects = Vec::new();
    let mut assets_virtual = false;
    let mut map_to_resources = false;
    if let Some(index) = &version.asset_index {
        let index_path = safe_join(&paths.assets().join("indexes"), &format!("{}.json", index.id))?;
        let index_bytes = load_or_fetch_verified(state, &index.url, &index_path, &index.sha1).await?;
        let parsed: AssetIndexFile = serde_json::from_slice(&index_bytes)?;
        assets_virtual = parsed.is_virtual;
        map_to_resources = parsed.map_to_resources;
        files.push(DownloadItem {
            url: index.url.clone(),
            path: index_path,
            sha1: Some(index.sha1.clone()),
            size: Some(index.size),
            label: format!("assets index {}", index.id),
        });
        let objects_root = paths.assets().join("objects");
        let mut seen_hashes = HashSet::new();
        let mut entries: Vec<_> = parsed.objects.into_iter().collect();
        entries.sort_by(|a, b| a.0.cmp(&b.0));
        for (name, obj) in entries {
            if !is_sha1(&obj.hash) {
                return Err(BloomError::Parse(format!("invalid asset hash for {name}")));
            }
            let prefix = &obj.hash[..2];
            if seen_hashes.insert(obj.hash.clone()) {
                files.push(DownloadItem {
                    url: format!("{RESOURCES_URL}/{prefix}/{}", obj.hash),
                    path: objects_root.join(prefix).join(&obj.hash),
                    sha1: Some(obj.hash.clone()),
                    size: Some(obj.size),
                    label: name.rsplit('/').next().unwrap_or(&name).to_string(),
                });
            }
            asset_objects.push((name, obj.hash));
        }
    }

    // -- logging configuration ----------------------------------------------
    let logging = match version.logging.as_ref().and_then(|l| l.client.clone()) {
        Some(entry) => {
            let path = safe_join(&paths.assets().join("log_configs"), &entry.file.id)?;
            files.push(DownloadItem {
                url: entry.file.url.clone(),
                path: path.clone(),
                sha1: Some(entry.file.sha1.clone()),
                size: Some(entry.file.size),
                label: entry.file.id.clone(),
            });
            Some((entry.argument, path))
        }
        None => None,
    };

    Ok(VersionPlan {
        version,
        classpath,
        natives,
        files,
        asset_index_id,
        assets_virtual,
        map_to_resources,
        asset_objects,
        logging,
    })
}

fn file_label(rel: &str) -> String {
    rel.rsplit('/').next().unwrap_or(rel).to_string()
}

async fn load_or_fetch_verified(state: &AppState, url: &str, path: &Path, sha1: &str) -> BloomResult<Vec<u8>> {
    if let Ok(bytes) = tokio::fs::read(path).await {
        use sha1::Digest;
        if hex::encode(sha1::Sha1::digest(&bytes)).eq_ignore_ascii_case(sha1) {
            return Ok(bytes);
        }
    }
    let bytes = state.downloader.fetch_verified(url, Some(sha1)).await?;
    write_atomic(path, &bytes)?;
    Ok(bytes)
}

fn mtime_secs(meta: &std::fs::Metadata) -> i64 {
    meta.modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

/// Checks whether a file is present and intact. Returns `(ok, cache_entry)`
/// where `cache_entry` is set when a fresh hash was computed.
fn verify_file(db: &Db, item: &DownloadItem) -> (bool, Option<(String, u64, i64, String)>) {
    let Ok(meta) = std::fs::metadata(&item.path) else { return (false, None) };
    if !meta.is_file() {
        return (false, None);
    }
    if let Some(size) = item.size {
        if meta.len() != size {
            return (false, None);
        }
    }
    let Some(expected) = &item.sha1 else { return (true, None) };
    let key = item.path.to_string_lossy().to_string();
    let mtime = mtime_secs(&meta);
    if let Ok(Some(cached)) = db.file_cache_lookup(&key, meta.len(), mtime) {
        if cached.eq_ignore_ascii_case(expected) {
            return (true, None);
        }
    }
    match sha1_file(&item.path) {
        Ok(actual) if actual.eq_ignore_ascii_case(expected) => (true, Some((key, meta.len(), mtime, actual))),
        _ => (false, None),
    }
}

/// Verifies all files and downloads missing or corrupted ones.
/// Returns the number of files downloaded.
pub async fn ensure_files(
    state: &AppState,
    items: Vec<DownloadItem>,
    tracker: &Arc<ProgressTracker>,
    cancel: &CancellationToken,
    check_message: &str,
    download_message: &str,
) -> BloomResult<usize> {
    // Deduplicate by destination.
    let mut seen = HashSet::new();
    let items: Vec<DownloadItem> = items.into_iter().filter(|i| seen.insert(i.path.clone())).collect();

    tracker.stage(Stage::CheckingFiles, check_message);
    tracker.set_totals(items.len() as u64, items.iter().filter_map(|i| i.size).sum());

    let stop = CancellationToken::new();
    let reporter = tracker.spawn_reporter(stop.clone());

    let workers = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(4).clamp(2, 8);
    let chunk_size = 48;
    let chunks: Vec<Vec<DownloadItem>> = items.chunks(chunk_size).map(|c| c.to_vec()).collect();
    let mut missing = Vec::new();
    let mut cache_entries = Vec::new();
    let mut results = futures::stream::iter(chunks.into_iter().map(|chunk| {
        let db = state.db.clone();
        let tracker = tracker.clone();
        tokio::task::spawn_blocking(move || {
            let mut missing = Vec::new();
            let mut entries = Vec::new();
            for item in chunk {
                let (ok, entry) = verify_file(&db, &item);
                if let Some(size) = item.size {
                    tracker.add_bytes(size);
                }
                tracker.file_done();
                if let Some(e) = entry {
                    entries.push(e);
                }
                if !ok {
                    missing.push(item);
                }
            }
            (missing, entries)
        })
    }))
    .buffer_unordered(workers);

    while let Some(res) = results.next().await {
        if cancel.is_cancelled() {
            stop.cancel();
            let _ = reporter.await;
            return Err(BloomError::Cancelled);
        }
        let (m, e) = res?;
        missing.extend(m);
        cache_entries.extend(e);
    }
    drop(results);
    state.db.file_cache_put_many(&cache_entries)?;
    stop.cancel();
    let _ = reporter.await;

    if missing.is_empty() {
        return Ok(0);
    }

    let count = missing.len();
    tracker.stage(Stage::Downloading, download_message);
    tracker.set_totals(count as u64, missing.iter().filter_map(|i| i.size).sum());
    let stop = CancellationToken::new();
    let reporter = tracker.spawn_reporter(stop.clone());
    let concurrency = state.settings().download_concurrency as usize;
    let result = state
        .downloader
        .download_all(missing.clone(), concurrency, tracker.clone(), cancel.clone())
        .await;
    stop.cancel();
    let _ = reporter.await;
    result?;

    // Record freshly downloaded files in the verification cache. They were
    // hashed while streaming, so only metadata needs to be read here.
    let entries: Vec<_> = missing
        .iter()
        .filter_map(|i| {
            let sha1 = i.sha1.clone()?;
            let meta = std::fs::metadata(&i.path).ok()?;
            Some((i.path.to_string_lossy().to_string(), meta.len(), mtime_secs(&meta), sha1))
        })
        .collect();
    state.db.file_cache_put_many(&entries)?;
    Ok(count)
}

/// Full installation of a vanilla version. Safe to call repeatedly.
pub async fn install_version(
    state: &AppState,
    version_id: &str,
    tracker: &Arc<ProgressTracker>,
    cancel: &CancellationToken,
) -> BloomResult<VersionPlan> {
    let _guard = state.install_lock.lock().await;
    let paths = state.paths();
    paths.ensure()?;

    tracker.stage(Stage::Preparing, format!("Resolving Minecraft {version_id}"));
    let version = manifest::resolve_version(state, version_id).await?;
    if cancel.is_cancelled() {
        return Err(BloomError::Cancelled);
    }
    tracker.message("Reading asset index");
    let plan = plan_version(state, version).await?;

    ensure_files(state, plan.files.clone(), tracker, cancel, "Checking game files", "Downloading game files").await?;

    tracker.stage(Stage::Installing, "Finalizing installation");
    if plan.assets_virtual {
        let target = paths.assets().join("virtual").join(&plan.asset_index_id);
        let objects = plan.asset_objects.clone();
        let objects_root = paths.assets().join("objects");
        tokio::task::spawn_blocking(move || copy_assets_to(&objects_root, &objects, &target)).await??;
    }

    let (java_major, java_component) = plan.version.java_requirement();
    state.db.mark_version_installed(&InstalledVersionRow {
        version_id: plan.version.id.clone(),
        release_type: plan.version.release_type.clone(),
        release_time: plan.version.release_time.clone(),
        java_component: Some(java_component),
        java_major: Some(java_major),
        installed_at: now_ts(),
    })?;
    Ok(plan)
}

/// Copies asset objects to a legacy name-based layout (virtual assets or
/// `resources/` for very old versions). Existing files with the right size
/// are left untouched.
pub fn copy_assets_to(objects_root: &Path, objects: &[(String, String)], target: &Path) -> BloomResult<()> {
    for (name, hash) in objects {
        let src = objects_root.join(&hash[..2]).join(hash);
        let dst = safe_join(target, name)?;
        let src_len = std::fs::metadata(&src)?.len();
        if std::fs::metadata(&dst).map(|m| m.len() == src_len).unwrap_or(false) {
            continue;
        }
        if let Some(parent) = dst.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::copy(&src, &dst)?;
    }
    Ok(())
}

/// Extracts legacy native archives into `target`, honoring exclusions and
/// rejecting archive entries that would escape the target directory.
pub fn extract_natives(natives: &[NativeJar], target: &Path) -> BloomResult<()> {
    std::fs::create_dir_all(target)?;
    for native in natives {
        let file = std::fs::File::open(&native.path)?;
        let mut archive = zip::ZipArchive::new(std::io::BufReader::new(file))?;
        for i in 0..archive.len() {
            let mut entry = archive.by_index(i)?;
            let name = entry.name().to_string();
            if entry.is_dir() || native.exclude.iter().any(|ex| name.starts_with(ex.as_str())) {
                continue;
            }
            let Some(enclosed) = entry.enclosed_name() else {
                tracing::warn!(entry = name, "skipping unsafe archive entry");
                continue;
            };
            let dst = target.join(enclosed);
            if let Some(parent) = dst.parent() {
                std::fs::create_dir_all(parent)?;
            }
            let mut out = std::fs::File::create(&dst)?;
            std::io::copy(&mut entry, &mut out)?;
        }
    }
    Ok(())
}

/// Quick, offline install check used for list views: metadata and client jar
/// present and recorded as installed. Full verification happens at launch.
pub fn quick_install_state(paths: &GamePaths, installed: &HashSet<String>, version_id: &str) -> &'static str {
    let Ok(dir) = paths.version_dir(version_id) else { return "not_installed" };
    let json = dir.join(format!("{version_id}.json"));
    let jar = dir.join(format!("{version_id}.jar"));
    match (installed.contains(version_id), json.exists() && jar.exists()) {
        (true, true) => "installed",
        (true, false) => "incomplete",
        (false, true) => "incomplete",
        (false, false) => "not_installed",
    }
}

/// Deletes a version's own files (metadata, client jar). Shared libraries and
/// assets are kept because other versions may use them; use
/// [`prune_unused_files`] to reclaim that space.
pub async fn delete_version(state: &AppState, version_id: &str) -> BloomResult<()> {
    let _guard = state.install_lock.lock().await;
    let users = state.db.instances_using_version(version_id)?;
    if !users.is_empty() {
        return Err(BloomError::InvalidInput(format!(
            "version {version_id} is used by: {}",
            users.join(", ")
        )));
    }
    let dir = state.paths().version_dir(version_id)?;
    if dir.exists() {
        tokio::fs::remove_dir_all(&dir).await?;
    }
    state.db.file_cache_remove_prefix(&dir.to_string_lossy())?;
    state.db.remove_installed_version(version_id)?;
    Ok(())
}

/// Removes libraries and asset objects not referenced by any installed
/// version. Aborts without deleting anything if any installed version cannot
/// be resolved, so no file in use can be removed by mistake.
pub async fn prune_unused_files(state: &AppState) -> BloomResult<u64> {
    let _guard = state.install_lock.lock().await;
    let paths = state.paths();
    let mut referenced: HashSet<PathBuf> = HashSet::new();
    for row in state.db.list_installed_versions()? {
        let version = manifest::resolve_version(state, &row.version_id).await?;
        let plan = plan_version(state, version).await?;
        referenced.extend(plan.files.into_iter().map(|f| f.path));
    }
    let roots = vec![paths.libraries(), paths.assets().join("objects")];
    tokio::task::spawn_blocking(move || {
        let mut freed = 0u64;
        for root in roots {
            let mut stack = vec![root];
            while let Some(dir) = stack.pop() {
                let Ok(entries) = std::fs::read_dir(&dir) else { continue };
                for entry in entries.flatten() {
                    let p = entry.path();
                    let Ok(meta) = entry.metadata() else { continue };
                    if meta.is_dir() {
                        stack.push(p);
                    } else if !referenced.contains(&p) && std::fs::remove_file(&p).is_ok() {
                        freed += meta.len();
                    }
                }
            }
        }
        Ok(freed)
    })
    .await?
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn extract_natives_skips_excluded_and_unsafe_entries() {
        let dir = tempfile::tempdir().unwrap();
        let jar = dir.path().join("n.jar");
        {
            let f = std::fs::File::create(&jar).unwrap();
            let mut z = zip::ZipWriter::new(f);
            let opts = zip::write::SimpleFileOptions::default();
            z.start_file("lwjgl.dll", opts).unwrap();
            z.write_all(b"dll").unwrap();
            z.start_file("META-INF/MANIFEST.MF", opts).unwrap();
            z.write_all(b"m").unwrap();
            z.start_file("../escape.dll", opts).unwrap();
            z.write_all(b"x").unwrap();
            z.finish().unwrap();
        }
        let out = dir.path().join("natives");
        extract_natives(&[NativeJar { path: jar, exclude: vec!["META-INF/".into()] }], &out).unwrap();
        assert!(out.join("lwjgl.dll").exists());
        assert!(!out.join("META-INF").exists());
        assert!(!dir.path().join("escape.dll").exists());
    }

    #[test]
    fn verify_file_uses_size_and_hash() {
        let db = Db::open_in_memory().unwrap();
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("a.bin");
        std::fs::write(&p, b"hello").unwrap();
        let good = DownloadItem {
            url: String::new(),
            path: p.clone(),
            sha1: Some("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d".into()),
            size: Some(5),
            label: "a".into(),
        };
        let (ok, entry) = verify_file(&db, &good);
        assert!(ok);
        db.file_cache_put_many(&[entry.unwrap()]).unwrap();
        // Second check hits the cache.
        assert_eq!(verify_file(&db, &good), (true, None));
        let wrong_size = DownloadItem { size: Some(6), ..good.clone() };
        assert!(!verify_file(&db, &wrong_size).0);
        let wrong_hash = DownloadItem { sha1: Some("0".repeat(40)), ..good };
        assert!(!verify_file(&db, &wrong_hash).0);
    }
}
