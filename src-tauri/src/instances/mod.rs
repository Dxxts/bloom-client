//! Instance manager. Every instance has its own game directory (saves,
//! options, mods, resource packs, logs); only immutable, content-addressed
//! data (libraries, assets, client jars, runtimes) is shared.

pub mod content;
pub mod loaders;

use std::collections::HashSet;
use std::path::PathBuf;

use crate::database::now_ts;
use crate::database::repos::InstanceRow;
use crate::errors::{BloomError, BloomResult};
use crate::installation::quick_install_state;
use crate::models::{EngineKind, Instance, InstanceInput, LoaderKind};
use crate::services::settings::{validate_jvm_args, validate_memory, validate_resolution};
use crate::services::state::AppState;
use crate::system::paths::{copy_dir_all, validate_id, GamePaths};

pub const ICONS: &[&str] = &[
    "sprout", "flower", "leaf", "trees", "mountain", "pickaxe", "sword", "shield", "gem", "flame", "snowflake",
    "zap", "compass", "castle", "star", "moon",
];

/// Bloom-internal directory inside each instance (natives, launch logs).
pub const INTERNAL_DIR: &str = ".bloom";

fn to_model(paths: &GamePaths, installed: &HashSet<String>, row: InstanceRow) -> Instance {
    let directory = paths.instance_dir(&row.id).map(|p| p.to_string_lossy().to_string()).unwrap_or_default();
    Instance {
        install_state: quick_install_state(paths, installed, &row.version_id).to_string(),
        directory,
        id: row.id,
        name: row.name,
        version_id: row.version_id,
        loader: row.loader,
        loader_version: row.loader_version,
        engine: row.engine,
        java_path: row.java_path,
        min_ram_mb: row.min_ram_mb,
        max_ram_mb: row.max_ram_mb,
        resolution_width: row.resolution_width,
        resolution_height: row.resolution_height,
        fullscreen: row.fullscreen,
        jvm_args: row.jvm_args,
        icon: row.icon,
        created_at: row.created_at,
        last_played_at: row.last_played_at,
        total_play_seconds: row.total_play_seconds,
    }
}

fn installed_set(state: &AppState) -> BloomResult<HashSet<String>> {
    Ok(state.db.list_installed_versions()?.into_iter().map(|v| v.version_id).collect())
}

pub fn list(state: &AppState) -> BloomResult<Vec<Instance>> {
    let paths = state.paths();
    let installed = installed_set(state)?;
    Ok(state.db.list_instances()?.into_iter().map(|r| to_model(&paths, &installed, r)).collect())
}

pub fn get(state: &AppState, id: &str) -> BloomResult<Instance> {
    let row = state.db.get_instance(id)?.ok_or_else(|| BloomError::NotFound("instance not found".into()))?;
    Ok(to_model(&state.paths(), &installed_set(state)?, row))
}

pub fn instance_dir(state: &AppState, id: &str) -> BloomResult<PathBuf> {
    state.db.get_instance(id)?.ok_or_else(|| BloomError::NotFound("instance not found".into()))?;
    state.paths().instance_dir(id)
}

/// Validates an instance payload and normalizes optional overrides.
fn validate_input(state: &AppState, mut input: InstanceInput) -> BloomResult<InstanceInput> {
    input.name = input.name.trim().to_string();
    if input.name.is_empty() || input.name.chars().count() > 48 {
        return Err(BloomError::InvalidInput("the instance name must be between 1 and 48 characters".into()));
    }
    validate_id(&input.version_id)?;
    if !loaders::adapter(input.loader).is_available() {
        return Err(BloomError::Unsupported(format!(
            "{} instances are not supported yet",
            loaders::adapter(input.loader).display_name()
        )));
    }
    let settings = state.settings();
    let total = crate::system::info::total_memory_mb();
    if input.min_ram_mb.is_some() || input.max_ram_mb.is_some() {
        validate_memory(
            input.min_ram_mb.unwrap_or(settings.min_ram_mb),
            input.max_ram_mb.unwrap_or(settings.max_ram_mb),
            total,
        )?;
    }
    if input.resolution_width.is_some() || input.resolution_height.is_some() {
        validate_resolution(
            input.resolution_width.unwrap_or(settings.resolution_width),
            input.resolution_height.unwrap_or(settings.resolution_height),
        )?;
    }
    input.jvm_args = match input.jvm_args.as_deref().map(str::trim) {
        Some(a) if !a.is_empty() => Some(validate_jvm_args(a)?),
        _ => None,
    };
    input.java_path = match input.java_path.as_deref().map(str::trim) {
        Some(p) if !p.is_empty() => {
            if !std::path::Path::new(p).is_file() {
                return Err(BloomError::InvalidInput("the selected Java executable does not exist".into()));
            }
            Some(p.to_string())
        }
        _ => None,
    };
    if let Some(icon) = &input.icon {
        if !ICONS.contains(&icon.as_str()) {
            return Err(BloomError::InvalidInput("unknown icon".into()));
        }
    }
    if input.engine == EngineKind::Rust {
        if !crate::engine::supports(&input.version_id) {
            return Err(BloomError::InvalidInput(format!(
                "the Bloom Native engine (Rust) is not available for {}",
                input.version_id
            )));
        }
        // The native engine is a Fabric mod: the loader follows the engine.
        input.loader = LoaderKind::Fabric;
        input.loader_version = None;
    }
    Ok(input)
}

fn create_layout(dir: &std::path::Path) -> BloomResult<()> {
    for sub in ["mods", "resourcepacks", "saves", "screenshots", INTERNAL_DIR] {
        std::fs::create_dir_all(dir.join(sub))?;
    }
    Ok(())
}

pub fn create(state: &AppState, input: InstanceInput) -> BloomResult<Instance> {
    let input = validate_input(state, input)?;
    let id = uuid::Uuid::new_v4().to_string();
    let dir = state.paths().instance_dir(&id)?;
    create_layout(&dir)?;
    let row = InstanceRow {
        id: id.clone(),
        name: input.name,
        version_id: input.version_id,
        loader: input.loader,
        loader_version: input.loader_version,
        engine: input.engine,
        java_path: input.java_path,
        min_ram_mb: input.min_ram_mb,
        max_ram_mb: input.max_ram_mb,
        resolution_width: input.resolution_width,
        resolution_height: input.resolution_height,
        fullscreen: input.fullscreen,
        jvm_args: input.jvm_args,
        icon: input.icon.unwrap_or_else(|| "sprout".into()),
        created_at: now_ts(),
        last_played_at: None,
        total_play_seconds: 0,
    };
    if let Err(e) = state.db.insert_instance(&row) {
        let _ = std::fs::remove_dir_all(&dir);
        return Err(e);
    }
    get(state, &id)
}

pub fn update(state: &AppState, id: &str, input: InstanceInput) -> BloomResult<Instance> {
    let existing = state.db.get_instance(id)?.ok_or_else(|| BloomError::NotFound("instance not found".into()))?;
    if state.sessions.is_busy(id) && existing.version_id != input.version_id {
        return Err(BloomError::AlreadyRunning("close the game before changing the version".into()));
    }
    let input = validate_input(state, input)?;
    let row = InstanceRow {
        name: input.name,
        version_id: input.version_id,
        loader: input.loader,
        loader_version: input.loader_version,
        engine: input.engine,
        java_path: input.java_path,
        min_ram_mb: input.min_ram_mb,
        max_ram_mb: input.max_ram_mb,
        resolution_width: input.resolution_width,
        resolution_height: input.resolution_height,
        fullscreen: input.fullscreen,
        jvm_args: input.jvm_args,
        icon: input.icon.unwrap_or(existing.icon.clone()),
        ..existing
    };
    state.db.update_instance(&row)?;
    get(state, id)
}

/// Copies an instance, including its worlds and configuration, into a new,
/// fully independent instance.
pub async fn duplicate(state: &AppState, id: &str) -> BloomResult<Instance> {
    let source = state.db.get_instance(id)?.ok_or_else(|| BloomError::NotFound("instance not found".into()))?;
    if state.sessions.is_busy(id) {
        return Err(BloomError::AlreadyRunning("close the game before duplicating this instance".into()));
    }
    let paths = state.paths();
    let src_dir = paths.instance_dir(id)?;
    let new_id = uuid::Uuid::new_v4().to_string();
    let dst_dir = paths.instance_dir(&new_id)?;
    let (s, d) = (src_dir.clone(), dst_dir.clone());
    tokio::task::spawn_blocking(move || -> BloomResult<()> {
        std::fs::create_dir_all(&d)?;
        if s.exists() {
            for entry in std::fs::read_dir(&s)? {
                let entry = entry?;
                // Launcher internals (natives, launch logs) are not copied.
                if entry.file_name() == INTERNAL_DIR {
                    continue;
                }
                let target = d.join(entry.file_name());
                if entry.file_type()?.is_dir() {
                    copy_dir_all(&entry.path(), &target)?;
                } else {
                    std::fs::copy(entry.path(), target)?;
                }
            }
        }
        create_layout(&d)
    })
    .await??;

    let mut name = format!("{} (copy)", source.name);
    if name.chars().count() > 48 {
        name = name.chars().take(48).collect();
    }
    let row = InstanceRow {
        id: new_id.clone(),
        name,
        created_at: now_ts(),
        last_played_at: None,
        total_play_seconds: 0,
        ..source
    };
    if let Err(e) = state.db.insert_instance(&row) {
        let _ = std::fs::remove_dir_all(&dst_dir);
        return Err(e);
    }
    get(state, &new_id)
}

pub async fn delete(state: &AppState, id: &str) -> BloomResult<()> {
    if state.sessions.is_busy(id) {
        return Err(BloomError::AlreadyRunning("close the game before deleting this instance".into()));
    }
    let dir = instance_dir(state, id)?;
    if dir.exists() {
        tokio::fs::remove_dir_all(&dir).await?;
    }
    state.db.delete_instance(id)?;
    state.sessions.remove(id);
    let mut settings = state.settings();
    if settings.selected_instance_id.as_deref() == Some(id) {
        settings.selected_instance_id = None;
        state.db.save_settings(&settings)?;
        state.replace_settings(settings);
    }
    Ok(())
}
