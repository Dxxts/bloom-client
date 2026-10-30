//! Official Mojang version manifest and version metadata resolution.
//!
//! Sources (official only):
//! * `https://piston-meta.mojang.com/mc/game/version_manifest_v2.json`
//! * the per-version URLs it lists, verified against their SHA1.

use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};

use super::version::VersionJson;
use crate::database::now_ts;
use crate::downloads::sha1_file;
use crate::errors::{BloomError, BloomResult};
use crate::services::state::AppState;
use crate::system::paths::write_atomic;

pub const MANIFEST_URL: &str = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
const CACHE_KEY: &str = "version_manifest_v2";
const MEMORY_TTL: Duration = Duration::from_secs(10 * 60);
const DISK_TTL_SECS: i64 = 10 * 60;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VersionManifest {
    pub latest: Latest,
    pub versions: Vec<ManifestEntry>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Latest {
    pub release: Option<String>,
    pub snapshot: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ManifestEntry {
    pub id: String,
    #[serde(rename = "type")]
    pub release_type: String,
    pub url: String,
    pub time: Option<String>,
    pub release_time: String,
    pub sha1: Option<String>,
}

impl VersionManifest {
    pub fn find(&self, id: &str) -> Option<&ManifestEntry> {
        self.versions.iter().find(|v| v.id == id)
    }
}

fn manifest_path(state: &AppState) -> PathBuf {
    state.paths().meta().join("version_manifest_v2.json")
}

/// Returns the manifest and whether it was served offline from disk.
///
/// Resolution order: in-memory (10 min) → disk (10 min, unless `force`) →
/// network with ETag revalidation → stale disk copy when offline.
pub async fn get_manifest(state: &AppState, force: bool) -> BloomResult<(Arc<VersionManifest>, bool)> {
    let mut cache = state.manifest_cache.lock().await;
    if !force {
        if let Some((at, m, offline)) = cache.as_ref() {
            if at.elapsed() < MEMORY_TTL && !*offline {
                return Ok((m.clone(), false));
            }
        }
    }

    let path = manifest_path(state);
    let meta = state.db.http_cache_get(CACHE_KEY)?;
    let disk = tokio::fs::read(&path).await.ok().and_then(|b| serde_json::from_slice::<VersionManifest>(&b).ok());

    if !force {
        if let (Some(m), Some((_, fetched_at))) = (disk.as_ref(), meta.as_ref()) {
            if now_ts() - fetched_at < DISK_TTL_SECS {
                let m = Arc::new(m.clone());
                *cache = Some((Instant::now(), m.clone(), false));
                return Ok((m, false));
            }
        }
    }

    let etag = meta.as_ref().and_then(|(e, _)| e.clone()).filter(|_| disk.is_some());
    match fetch_remote(state, etag.as_deref()).await {
        Ok(Some((bytes, new_etag))) => {
            let manifest: VersionManifest = serde_json::from_slice(&bytes)?;
            write_atomic(&path, &bytes)?;
            state.db.http_cache_put(CACHE_KEY, new_etag.as_deref(), now_ts())?;
            let m = Arc::new(manifest);
            *cache = Some((Instant::now(), m.clone(), false));
            Ok((m, false))
        }
        Ok(None) => {
            // 304 Not Modified: the disk copy is current.
            let m = Arc::new(disk.ok_or_else(|| BloomError::Internal("manifest cache missing".into()))?);
            state.db.http_cache_put(CACHE_KEY, etag.as_deref(), now_ts())?;
            *cache = Some((Instant::now(), m.clone(), false));
            Ok((m, false))
        }
        Err(e) => match disk {
            Some(m) => {
                tracing::warn!(error = %e, "using cached version manifest (offline)");
                let m = Arc::new(m);
                *cache = Some((Instant::now(), m.clone(), true));
                Ok((m, true))
            }
            None => Err(e),
        },
    }
}

async fn fetch_remote(state: &AppState, etag: Option<&str>) -> BloomResult<Option<(Vec<u8>, Option<String>)>> {
    let mut req = state.downloader.client().get(MANIFEST_URL).timeout(Duration::from_secs(20));
    if let Some(etag) = etag {
        req = req.header(reqwest::header::IF_NONE_MATCH, etag);
    }
    let resp = req.send().await?;
    if resp.status() == reqwest::StatusCode::NOT_MODIFIED {
        return Ok(None);
    }
    if !resp.status().is_success() {
        return Err(BloomError::Network(format!("server responded with HTTP {}", resp.status().as_u16())));
    }
    let new_etag = resp
        .headers()
        .get(reqwest::header::ETAG)
        .and_then(|v| v.to_str().ok())
        .map(String::from);
    Ok(Some((resp.bytes().await?.to_vec(), new_etag)))
}

/// Loads a version JSON, downloading it from the official manifest when it
/// is missing or does not match the published SHA1. Profiles that use
/// `inheritsFrom` are merged with their parent.
pub async fn resolve_version(state: &AppState, id: &str) -> BloomResult<VersionJson> {
    let mut chain = Vec::new();
    let mut current = id.to_string();
    for _ in 0..5 {
        let v = load_single(state, &current).await?;
        let parent = v.inherits_from.clone();
        chain.push(v);
        match parent {
            Some(p) => current = p,
            None => break,
        }
    }
    if chain.last().is_some_and(|v| v.inherits_from.is_some()) {
        return Err(BloomError::Parse(format!("version {id} has an inheritance chain that is too deep")));
    }
    let mut merged = chain.pop().ok_or_else(|| BloomError::NotFound(format!("version {id} not found")))?;
    while let Some(child) = chain.pop() {
        merged = child.merge_parent(merged);
    }
    Ok(merged)
}

async fn load_single(state: &AppState, id: &str) -> BloomResult<VersionJson> {
    let paths = state.paths();
    let file = paths.version_dir(id)?.join(format!("{id}.json"));
    let manifest = get_manifest(state, false).await.ok().map(|(m, _)| m);
    let entry = manifest.as_ref().and_then(|m| m.find(id).cloned());

    if let Some(entry) = entry {
        if file.exists() {
            let ok = match &entry.sha1 {
                Some(expected) => {
                    let f = file.clone();
                    let actual = tokio::task::spawn_blocking(move || sha1_file(&f)).await??;
                    actual.eq_ignore_ascii_case(expected)
                }
                None => true,
            };
            if ok {
                return parse_file(&file).await;
            }
            tracing::info!(version = id, "version metadata outdated or corrupted, re-downloading");
        }
        let bytes = state.downloader.fetch_verified(&entry.url, entry.sha1.as_deref()).await?;
        let parsed: VersionJson = serde_json::from_slice(&bytes)?;
        write_atomic(&file, &bytes)?;
        return Ok(parsed);
    }

    if file.exists() {
        return parse_file(&file).await;
    }
    Err(BloomError::NotFound(format!(
        "version {id} is not available (check your internet connection)"
    )))
}

async fn parse_file(path: &std::path::Path) -> BloomResult<VersionJson> {
    let bytes = tokio::fs::read(path).await?;
    serde_json::from_slice(&bytes).map_err(|e| BloomError::Parse(format!("{}: {e}", path.display())))
}
