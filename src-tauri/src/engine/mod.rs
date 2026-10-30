//! World renderer selection: Minecraft's Java renderer or the Bloom Native
//! World Engine (Rust chunk mesher, shipped as a Fabric mod).
//!
//! The engine jar is never copied into the instance's `mods` folder: it is
//! added with `-Dfabric.addMods`, so the user's mods stay untouched and a
//! Java instance never sees it.
//!
//! Rust instances default to Minecraft's own Vulkan backend: the engine's
//! meshes go through `renderpearl`, so they work on Vulkan and OpenGL alike.

use std::path::{Path, PathBuf};

use serde::Serialize;
use tauri::{AppHandle, Manager};

use crate::errors::{BloomError, BloomResult};
use crate::launcher::hooks::LaunchCommand;
use crate::models::EngineKind;

/// Minecraft versions the native engine is built and verified for.
pub const SUPPORTED_VERSIONS: &[&str] = &["26.3"];

const JAR_NAME: &str = "bloom-engine.jar";

pub fn supports(version_id: &str) -> bool {
    SUPPORTED_VERSIONS.contains(&version_id)
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RenderEngineInfo {
    pub kind: EngineKind,
    pub name: &'static str,
    /// `None`: every version.
    pub supported_versions: Option<Vec<&'static str>>,
    pub available: bool,
    pub detail: Option<String>,
}

pub fn list(app: &AppHandle) -> Vec<RenderEngineInfo> {
    let jar = locate_jar(app);
    vec![
        RenderEngineInfo {
            kind: EngineKind::Rust,
            name: "Bloom Native",
            supported_versions: Some(SUPPORTED_VERSIONS.to_vec()),
            available: jar.is_some(),
            detail: if jar.is_some() { None } else { Some("bloom-engine.jar is not bundled with this build".into()) },
        },
        RenderEngineInfo { kind: EngineKind::Java, name: "Java", supported_versions: None, available: true, detail: None },
    ]
}

/// Finds the engine jar: `BLOOM_ENGINE_JAR`, then the bundled resource, then
/// (development builds) the Gradle output next to the source tree.
pub fn locate_jar(app: &AppHandle) -> Option<PathBuf> {
    if let Ok(p) = std::env::var("BLOOM_ENGINE_JAR") {
        let p = PathBuf::from(p);
        if p.is_file() {
            return Some(p);
        }
    }
    if let Ok(dir) = app.path().resource_dir() {
        let p = dir.join("engine").join(JAR_NAME);
        if p.is_file() {
            return Some(p);
        }
    }
    if cfg!(debug_assertions) {
        let exe = std::env::current_exe().ok()?;
        for dir in exe.ancestors() {
            if let Some(p) = dev_jar(dir) {
                return Some(p);
            }
        }
    }
    None
}

fn dev_jar(root: &Path) -> Option<PathBuf> {
    let libs = root.join("bloom-performance-engine").join("java").join("build").join("libs");
    std::fs::read_dir(libs)
        .ok()?
        .filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| {
            let n = p.file_name().and_then(|n| n.to_str()).unwrap_or("");
            n.starts_with("bloom-engine-") && n.ends_with(".jar") && !n.contains("sources")
        })
        .max()
}

/// Adds the engine to a Rust instance's launch command. Java instances are
/// left untouched.
pub fn apply(app: &AppHandle, engine: EngineKind, command: &mut LaunchCommand) -> BloomResult<()> {
    if engine != EngineKind::Rust {
        return Ok(());
    }
    let jar = locate_jar(app).ok_or_else(|| {
        BloomError::Unsupported("the Bloom Native engine is not bundled with this build of Bloom".into())
    })?;
    // JVM options must precede the main class, which `argv` appends last.
    command.jvm_args.push(format!("-Dfabric.addMods={}", jar.display()));
    prefer_vulkan(&command.game_dir.join("options.txt"))?;
    Ok(())
}

const BACKEND_KEY: &str = "preferredGraphicsBackend:";

/// Sets `preferredGraphicsBackend` to Vulkan unless the player picked a
/// backend: a missing key or `"default"` becomes `"vulkan"`, an explicit
/// `"opengl"` is kept. Minecraft falls back to OpenGL by itself when the
/// driver has no usable Vulkan.
fn prefer_vulkan(options: &Path) -> BloomResult<()> {
    let current = match std::fs::read_to_string(options) {
        Ok(text) => text,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => String::new(),
        Err(e) => return Err(e.into()),
    };
    if let Some(updated) = with_vulkan_default(&current) {
        crate::system::paths::write_atomic(options, updated.as_bytes())?;
    }
    Ok(())
}

/// The new file contents, or `None` if nothing has to change.
fn with_vulkan_default(options: &str) -> Option<String> {
    let line = format!("{BACKEND_KEY}\"vulkan\"");
    match options.lines().find(|l| l.starts_with(BACKEND_KEY)) {
        Some(existing) if existing.trim_end() == format!("{BACKEND_KEY}\"default\"") => {
            Some(options.replacen(existing, &line, 1))
        }
        Some(_) => None,
        None if options.is_empty() || options.ends_with('\n') => Some(format!("{options}{line}\n")),
        None => Some(format!("{options}\n{line}\n")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn vulkan_becomes_the_default_but_explicit_choices_stay() {
        assert_eq!(with_vulkan_default("").as_deref(), Some("preferredGraphicsBackend:\"vulkan\"\n"));
        assert_eq!(
            with_vulkan_default("version:4785\npreferredGraphicsBackend:\"default\"\nfov:0.0\n").as_deref(),
            Some("version:4785\npreferredGraphicsBackend:\"vulkan\"\nfov:0.0\n")
        );
        assert_eq!(with_vulkan_default("fov:0.0").as_deref(), Some("fov:0.0\npreferredGraphicsBackend:\"vulkan\"\n"));
        assert_eq!(with_vulkan_default("preferredGraphicsBackend:\"opengl\"\n"), None);
        assert_eq!(with_vulkan_default("preferredGraphicsBackend:\"vulkan\"\n"), None);
    }

    #[test]
    fn only_verified_versions_are_supported() {
        assert!(supports("26.3"));
        assert!(!supports("1.21.11"));
        assert!(!supports("26.3-snapshot-3"));
    }
}
