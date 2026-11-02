//! World renderer selection: Minecraft's Java renderer or the Bloom Native
//! World Engine (Rust chunk mesher, shipped as a Fabric mod).
//!
//! The engine jar is never copied into the instance's `mods` folder: it is
//! added with `-Dfabric.addMods`, so the user's mods stay untouched and a
//! Java instance never sees it.
//!
//! Rust instances default to Minecraft's own Vulkan backend: the engine's
//! meshes go through `renderpearl`, so they work on Vulkan and OpenGL alike.
//! Exception: with Iris installed every instance runs on OpenGL, because on
//! Minecraft's Vulkan backend Iris loads but does not apply shaderpacks
//! (tested with Iris 1.11.7 on 26.3).

pub mod compat_db;

use std::io::Read;
use std::path::{Path, PathBuf};

use serde::Serialize;
use tauri::{AppHandle, Manager};

use crate::errors::{BloomError, BloomResult};
use crate::launcher::hooks::LaunchCommand;
use crate::models::{EngineKind, EngineOptions};

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

/// Adds the engine to a Rust instance's launch command, and applies the
/// graphics backend policy to every instance.
pub fn apply(app: &AppHandle, engine: EngineKind, options: &EngineOptions, command: &mut LaunchCommand) -> BloomResult<()> {
    let options_txt = command.game_dir.join("options.txt");
    let iris = has_mod(&command.game_dir.join("mods"), "iris");
    if iris {
        opengl_for_iris(&options_txt)?;
    }
    if engine != EngineKind::Rust {
        return Ok(());
    }
    let jar = locate_jar(app).ok_or_else(|| {
        BloomError::Unsupported("the Bloom Native engine is not bundled with this build of Bloom".into())
    })?;
    // JVM options must precede the main class, which `argv` appends last.
    command.jvm_args.push(format!("-Dfabric.addMods={}", jar.display()));
    // The instance editor is the source of truth: these override config/bloom-engine.json.
    command.jvm_args.extend(engine_properties(options));
    // A verified download newer than the copy in the jar, if any (the engine checks it again).
    if let Some(state) = app.try_state::<crate::services::state::AppState>() {
        if let Some(db) = compat_db::cached(&state.paths().meta()) {
            command.jvm_args.push(format!("-Dbloom.compatDb={}", db.display()));
        }
    }
    if !iris {
        prefer_vulkan(&options_txt)?;
    }
    Ok(())
}

/// `-D` properties for the engine settings (see `EngineConfig` in the mod).
fn engine_properties(o: &EngineOptions) -> Vec<String> {
    let mut v = vec![
        format!("-Dbloom.engine.enabled={}", o.enabled),
        format!("-Dbloom.engine.nativeFluids={}", o.native_fluids),
        format!("-Dbloom.engine.validateEvery={}", o.validate_every),
        format!("-Dbloom.engine.allowUnverified={}", o.allow_unverified),
    ];
    let device = o.vulkan_device.trim();
    if !device.is_empty() {
        v.push(format!("-Dbloom.vulkan.device={device}"));
    }
    v
}

/// Refreshes the compatibility database in the background (startup).
/// Failures only mean the bundled or previously verified copy stays in use.
pub fn refresh_compat_db_in_background(app: &AppHandle) {
    let Some(state) = app.try_state::<crate::services::state::AppState>() else { return };
    let client = state.downloader.client().clone();
    let meta = state.paths().meta();
    let url = std::env::var("BLOOM_COMPAT_DB_URL").unwrap_or_else(|_| compat_db::DEFAULT_INDEX_URL.to_string());
    tauri::async_runtime::spawn(async move {
        match compat_db::refresh(&client, &meta, &url).await {
            Ok(outcome) => tracing::info!(?outcome, "compatibility database"),
            Err(e) => tracing::warn!(error = %e, "compatibility database not updated; the verified copy in use stays"),
        }
    });
}

/// Whether a jar in `mods` declares `id` in its `fabric.mod.json`.
fn has_mod(mods: &Path, id: &str) -> bool {
    let Ok(entries) = std::fs::read_dir(mods) else { return false };
    entries.filter_map(|e| e.ok().map(|e| e.path())).filter(|p| p.extension().is_some_and(|x| x.eq_ignore_ascii_case("jar"))).any(|jar| {
        (|| -> Option<bool> {
            let mut zip = zip::ZipArchive::new(std::fs::File::open(&jar).ok()?).ok()?;
            let file = zip.by_name("fabric.mod.json").ok()?;
            let mut text = String::new();
            file.take(1 << 20).read_to_string(&mut text).ok()?;
            let meta: serde_json::Value = serde_json::from_str(&text).ok()?;
            Some(meta.get("id")?.as_str()? == id)
        })()
        .unwrap_or(false)
    })
}

/// With Iris, Vulkan (explicit or written by Bloom) becomes OpenGL.
fn opengl_for_iris(options: &Path) -> BloomResult<()> {
    let current = match std::fs::read_to_string(options) {
        Ok(text) => text,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => String::new(),
        Err(e) => return Err(e.into()),
    };
    if let Some(updated) = with_opengl_for_iris(&current) {
        tracing::info!("Iris is installed: using the OpenGL backend (Iris does not apply shaderpacks on Vulkan)");
        crate::system::paths::write_atomic(options, updated.as_bytes())?;
    }
    Ok(())
}

fn with_opengl_for_iris(options: &str) -> Option<String> {
    let line = format!("{BACKEND_KEY}\"opengl\"");
    match options.lines().find(|l| l.starts_with(BACKEND_KEY)) {
        Some(existing) if existing.trim_end() == format!("{BACKEND_KEY}\"vulkan\"") => Some(options.replacen(existing, &line, 1)),
        // "default" already tries OpenGL first; "opengl" is fine.
        Some(_) => None,
        None if options.is_empty() || options.ends_with('\n') => Some(format!("{options}{line}\n")),
        None => Some(format!("{options}\n{line}\n")),
    }
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
    fn iris_moves_vulkan_to_opengl_and_leaves_other_choices() {
        assert_eq!(
            with_opengl_for_iris("fov:0.0\npreferredGraphicsBackend:\"vulkan\"\n").as_deref(),
            Some("fov:0.0\npreferredGraphicsBackend:\"opengl\"\n")
        );
        assert_eq!(with_opengl_for_iris("").as_deref(), Some("preferredGraphicsBackend:\"opengl\"\n"));
        assert_eq!(with_opengl_for_iris("preferredGraphicsBackend:\"default\"\n"), None);
        assert_eq!(with_opengl_for_iris("preferredGraphicsBackend:\"opengl\"\n"), None);
    }

    #[test]
    fn mods_are_recognised_by_their_metadata_not_their_file_name() {
        let dir = std::env::temp_dir().join(format!("bloom-mods-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let write_jar = |name: &str, id: &str| {
            let file = std::fs::File::create(dir.join(name)).unwrap();
            let mut zip = zip::ZipWriter::new(file);
            zip.start_file("fabric.mod.json", zip::write::SimpleFileOptions::default()).unwrap();
            std::io::Write::write_all(&mut zip, format!("{{\"id\": \"{id}\"}}").as_bytes()).unwrap();
            zip.finish().unwrap();
        };
        write_jar("shaders-renamed.jar", "iris");
        write_jar("iris-lookalike.jar", "sodium");
        std::fs::write(dir.join("broken.jar"), b"not a zip").unwrap();
        assert!(has_mod(&dir, "iris"));
        assert!(!has_mod(&dir, "lithium"));
        assert!(!has_mod(&dir.join("missing"), "iris"));
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn instance_options_become_engine_properties() {
        let defaults = engine_properties(&EngineOptions::default());
        assert!(defaults.contains(&"-Dbloom.engine.enabled=true".to_string()));
        assert!(defaults.contains(&"-Dbloom.engine.validateEvery=0".to_string()));
        assert!(!defaults.iter().any(|a| a.starts_with("-Dbloom.vulkan.device")));
        let custom = EngineOptions { enabled: false, native_fluids: false, vulkan_device: " GeForce ".into(), ..Default::default() };
        let args = engine_properties(&custom);
        assert!(args.contains(&"-Dbloom.engine.enabled=false".to_string()));
        assert!(args.contains(&"-Dbloom.engine.nativeFluids=false".to_string()));
        assert!(args.contains(&"-Dbloom.vulkan.device=GeForce".to_string()));
        // Old rows and partial payloads keep the defaults for missing fields.
        assert_eq!(serde_json::from_str::<EngineOptions>(r#"{"enabled": false}"#).unwrap(), EngineOptions { enabled: false, ..Default::default() });
    }

    #[test]
    fn only_verified_versions_are_supported() {
        assert!(supports("26.3"));
        assert!(!supports("1.21.11"));
        assert!(!supports("26.3-snapshot-3"));
    }
}
