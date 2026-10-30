//! Mod loader adapters.
//!
//! A loader adapter turns `(minecraft version, loader version)` into the id
//! of a launchable version profile that the installation engine can resolve
//! (vanilla: the Minecraft version itself; Fabric/Quilt: a generated profile
//! with `inheritsFrom`; Forge/NeoForge: an installer-produced profile).
//!
//! Vanilla and Fabric are implemented (Fabric is required by the Bloom
//! Native World Engine). Forge and NeoForge report themselves as unavailable
//! and refuse to prepare, so the UI can show them as "coming soon" without
//! pretending they work.

use std::future::Future;
use std::pin::Pin;

use serde::{Deserialize, Serialize};

use crate::errors::{BloomError, BloomResult};
use crate::models::LoaderKind;
use crate::services::state::AppState;

pub type PrepareFuture<'a> = Pin<Box<dyn Future<Output = BloomResult<String>> + Send + 'a>>;

pub trait LoaderAdapter: Send + Sync {
    fn kind(&self) -> LoaderKind;
    fn display_name(&self) -> &'static str;
    fn is_available(&self) -> bool;
    /// Makes sure the loader profile exists locally and returns the version
    /// id to install and launch.
    fn prepare<'a>(
        &'a self,
        state: &'a AppState,
        minecraft_version: &'a str,
        loader_version: Option<&'a str>,
    ) -> PrepareFuture<'a>;
}

pub struct Vanilla;

impl LoaderAdapter for Vanilla {
    fn kind(&self) -> LoaderKind {
        LoaderKind::Vanilla
    }
    fn display_name(&self) -> &'static str {
        "Vanilla"
    }
    fn is_available(&self) -> bool {
        true
    }
    fn prepare<'a>(&'a self, _state: &'a AppState, mc: &'a str, _lv: Option<&'a str>) -> PrepareFuture<'a> {
        Box::pin(async move { Ok(mc.to_string()) })
    }
}

const FABRIC_META: &str = "https://meta.fabricmc.net/v2";

/// Fabric: the profile JSON from Fabric Meta (`inheritsFrom` the vanilla
/// version, libraries as maven coordinates) is stored as a local version, so
/// the installation engine resolves it like any other version. Without a
/// network connection, a profile downloaded earlier is reused.
pub struct Fabric;

#[derive(Deserialize)]
struct FabricLoaderEntry {
    loader: FabricLoaderVersion,
}

#[derive(Deserialize)]
struct FabricLoaderVersion {
    version: String,
    stable: bool,
}

impl Fabric {
    async fn latest_stable_loader(state: &AppState, mc: &str) -> BloomResult<String> {
        let url = format!("{FABRIC_META}/versions/loader/{}", urlencode(mc));
        let entries: Vec<FabricLoaderEntry> = state.downloader.fetch_json(&url).await?;
        entries
            .into_iter()
            .find(|e| e.loader.stable)
            .map(|e| e.loader.version)
            .ok_or_else(|| BloomError::Unsupported(format!("Fabric does not support Minecraft {mc} yet")))
    }

    fn profile_id(mc: &str, loader: &str) -> String {
        format!("fabric-loader-{loader}-{mc}")
    }

    /// A profile already on disk for `mc`, newest first by name.
    fn cached_profile(state: &AppState, mc: &str) -> Option<String> {
        let dir = state.paths().version_dir(mc).ok()?.parent()?.to_path_buf();
        let suffix = format!("-{mc}");
        let mut ids: Vec<String> = std::fs::read_dir(dir)
            .ok()?
            .filter_map(|e| e.ok()?.file_name().into_string().ok())
            .filter(|n| n.starts_with("fabric-loader-") && n.ends_with(&suffix))
            .collect();
        ids.sort();
        ids.pop()
    }
}

fn urlencode(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => (b as char).to_string(),
            _ => format!("%{b:02X}"),
        })
        .collect()
}

impl LoaderAdapter for Fabric {
    fn kind(&self) -> LoaderKind {
        LoaderKind::Fabric
    }
    fn display_name(&self) -> &'static str {
        "Fabric"
    }
    fn is_available(&self) -> bool {
        true
    }
    fn prepare<'a>(&'a self, state: &'a AppState, mc: &'a str, lv: Option<&'a str>) -> PrepareFuture<'a> {
        Box::pin(async move {
            let loader = match lv {
                Some(v) => v.to_string(),
                None => match Self::latest_stable_loader(state, mc).await {
                    Ok(v) => v,
                    Err(e) => {
                        if let Some(id) = Self::cached_profile(state, mc) {
                            tracing::warn!(error = %e, profile = %id, "Fabric Meta unreachable, using the cached profile");
                            return Ok(id);
                        }
                        return Err(e);
                    }
                },
            };
            let id = Self::profile_id(mc, &loader);
            let file = state.paths().version_dir(&id)?.join(format!("{id}.json"));
            if !file.exists() {
                let url = format!("{FABRIC_META}/versions/loader/{}/{}/profile/json", urlencode(mc), urlencode(&loader));
                let bytes = state.downloader.fetch_verified(&url, None).await?;
                let profile: serde_json::Value = serde_json::from_slice(&bytes)?;
                if profile.get("id").and_then(|v| v.as_str()) != Some(id.as_str())
                    || profile.get("inheritsFrom").and_then(|v| v.as_str()) != Some(mc)
                {
                    return Err(BloomError::Parse(format!("unexpected Fabric profile for {mc} / {loader}")));
                }
                crate::system::paths::write_atomic(&file, &bytes)?;
            }
            Ok(id)
        })
    }
}

/// Placeholder adapter for loaders scheduled for a later milestone. It is
/// explicitly unavailable and every attempt to use it fails.
pub struct Planned {
    kind: LoaderKind,
    name: &'static str,
}

impl LoaderAdapter for Planned {
    fn kind(&self) -> LoaderKind {
        self.kind
    }
    fn display_name(&self) -> &'static str {
        self.name
    }
    fn is_available(&self) -> bool {
        false
    }
    fn prepare<'a>(&'a self, _state: &'a AppState, _mc: &'a str, _lv: Option<&'a str>) -> PrepareFuture<'a> {
        Box::pin(async move {
            Err(BloomError::Unsupported(format!("{} support is not available yet", self.name)))
        })
    }
}

static VANILLA: Vanilla = Vanilla;
static FABRIC: Fabric = Fabric;
static FORGE: Planned = Planned { kind: LoaderKind::Forge, name: "Forge" };
static NEOFORGE: Planned = Planned { kind: LoaderKind::NeoForge, name: "NeoForge" };

pub fn adapter(kind: LoaderKind) -> &'static dyn LoaderAdapter {
    match kind {
        LoaderKind::Vanilla => &VANILLA,
        LoaderKind::Fabric => &FABRIC,
        LoaderKind::Forge => &FORGE,
        LoaderKind::NeoForge => &NEOFORGE,
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LoaderInfo {
    pub kind: LoaderKind,
    pub name: &'static str,
    pub available: bool,
}

pub fn list() -> Vec<LoaderInfo> {
    [LoaderKind::Vanilla, LoaderKind::Fabric, LoaderKind::Forge, LoaderKind::NeoForge]
        .into_iter()
        .map(|k| {
            let a = adapter(k);
            LoaderInfo { kind: a.kind(), name: a.display_name(), available: a.is_available() }
        })
        .collect()
}
