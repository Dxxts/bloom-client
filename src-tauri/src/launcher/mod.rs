//! Game launcher engine.
//!
//! [`start`] registers a session and runs the whole pipeline in the
//! background: authentication → installation/verification → Java runtime →
//! command construction → process spawn → monitoring. Progress and state are
//! pushed to the frontend through events; nothing is polled.

pub mod hooks;
pub mod process;
pub mod sessions;

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use tauri::{AppHandle, Emitter, Manager};
use tokio_util::sync::CancellationToken;

use crate::authentication::{self, LaunchAuth};
use crate::database::repos::InstanceRow;
use crate::downloads::ProgressTracker;
use crate::errors::{BloomError, BloomResult};
use crate::installation::{self, VersionPlan};
use crate::instances::{loaders, INTERNAL_DIR};
use crate::java;
use crate::minecraft::arguments::{self, LaunchVars};
use crate::minecraft::rules::Features;
use crate::models::{ProgressEvent, Settings, Stage};
use crate::services::events;
use crate::services::state::AppState;
use hooks::LaunchCommand;

pub const LAUNCHER_NAME: &str = "bloom-client";

/// Starts a launch in the background. Fails immediately (without spawning
/// anything) if the instance is already being prepared or running.
pub fn start(app: &AppHandle, instance_id: &str) -> BloomResult<()> {
    let state = app.state::<AppState>();
    state.db.get_instance(instance_id)?.ok_or_else(|| BloomError::NotFound("instance not found".into()))?;
    let cancel = state.sessions.begin(app, instance_id)?;
    let app = app.clone();
    let instance_id = instance_id.to_string();
    tauri::async_runtime::spawn(async move {
        let state = app.state::<AppState>();
        if let Err(e) = run(&app, &state, &instance_id, cancel).await {
            let (stage, message) = match &e {
                BloomError::Cancelled => (Stage::Cancelled, "Launch cancelled".to_string()),
                other => (Stage::Failed, other.to_string()),
            };
            tracing::error!(instance = instance_id, error = %e, "launch failed");
            state.sessions.finish(&app, &instance_id, stage, message, None);
        }
    });
    Ok(())
}

fn progress_sink(app: &AppHandle, instance_id: &str) -> crate::downloads::ProgressSink {
    let app = app.clone();
    let instance_id = instance_id.to_string();
    Arc::new(move |p: ProgressEvent| {
        if let Some(state) = app.try_state::<AppState>() {
            state.sessions.set_progress(&instance_id, &p);
        }
        let _ = app.emit(events::PROGRESS, &p);
    })
}

async fn run(app: &AppHandle, state: &AppState, instance_id: &str, cancel: CancellationToken) -> BloomResult<()> {
    let settings = state.settings();
    let instance = state
        .db
        .get_instance(instance_id)?
        .ok_or_else(|| BloomError::NotFound("instance not found".into()))?;

    // 1. Account
    state.sessions.set_stage(app, instance_id, Stage::Preparing, "Preparing...");
    let auth = match state.db.active_account()? {
        Some(account) => authentication::launch_session(state, &account.uuid).await?,
        // Development builds only: test the game without a Microsoft account.
        None if cfg!(debug_assertions) => authentication::offline_dev_session(),
        None => return Err(BloomError::NoAccount),
    };
    if cancel.is_cancelled() {
        return Err(BloomError::Cancelled);
    }

    // 2. Loader profile + installation (verifies every file, downloads only
    // what is missing or corrupted).
    let tracker = ProgressTracker::new(instance_id, progress_sink(app, instance_id));
    let adapter = loaders::adapter(instance.loader);
    let profile_id = adapter
        .prepare(state, &instance.version_id, instance.loader_version.as_deref())
        .await?;
    let plan = installation::install_version(state, &profile_id, &tracker, &cancel).await?;
    // The install state shown in instance lists may have changed.
    let _ = app.emit(events::INSTANCES_CHANGED, ());

    // 3. Java
    let (java_path, java_major) = resolve_java(state, &settings, &instance, &plan, &tracker, &cancel).await?;
    if cancel.is_cancelled() {
        return Err(BloomError::Cancelled);
    }

    // 4. Build the command
    state.sessions.set_stage(app, instance_id, Stage::Launching, "Launching...");
    let game_dir = state.paths().instance_dir(instance_id)?;
    let mut command = build_command(state, &settings, &instance, &plan, &auth, java_path, java_major, &game_dir).await?;
    hooks::apply_all(&mut command)?;
    crate::engine::apply(app, instance.engine, &instance.engine_options, &mut command)?;

    // 5. Spawn and monitor
    process::spawn_and_monitor(app, state, command, &instance, &auth, cancel).await
}

async fn resolve_java(
    state: &AppState,
    settings: &Settings,
    instance: &InstanceRow,
    plan: &VersionPlan,
    tracker: &Arc<ProgressTracker>,
    cancel: &CancellationToken,
) -> BloomResult<(PathBuf, u32)> {
    let (required_major, component) = plan.version.java_requirement();
    let explicit = instance
        .java_path
        .clone()
        .filter(|p| !p.trim().is_empty())
        .or_else(|| settings.java_path.clone().filter(|p| !p.trim().is_empty()));

    if let Some(path) = explicit {
        let path = java::normalize_java_path(Path::new(&path));
        let probe = java::probe(&path).await?;
        if probe.major < required_major {
            return Err(BloomError::Java(format!(
                "Minecraft {} requires Java {required_major} or newer, but the selected runtime is Java {} ({}). \
                 Choose another runtime or use the automatic one.",
                plan.version.id, probe.major, path.display()
            )));
        }
        if probe.major != required_major {
            tracing::warn!(required_major, selected = probe.major, "using a Java version different from the recommended one");
        }
        return Ok((path, probe.major));
    }

    let path = java::ensure_managed_runtime(state, &component, tracker, cancel).await?;
    let probe = java::probe(&path).await?;
    Ok((path, probe.major))
}

fn classpath_separator() -> &'static str {
    if cfg!(windows) {
        ";"
    } else {
        ":"
    }
}

#[allow(clippy::too_many_arguments)]
async fn build_command(
    state: &AppState,
    settings: &Settings,
    instance: &InstanceRow,
    plan: &VersionPlan,
    auth: &LaunchAuth,
    java: PathBuf,
    java_major: u32,
    game_dir: &Path,
) -> BloomResult<LaunchCommand> {
    let paths = state.paths();
    let internal = game_dir.join(INTERNAL_DIR);
    let natives_dir = internal.join("natives");

    // Fresh natives directory per launch (the session registry guarantees the
    // instance is not running).
    if natives_dir.exists() {
        let _ = tokio::fs::remove_dir_all(&natives_dir).await;
    }
    tokio::fs::create_dir_all(&natives_dir).await?;
    for sub in ["mods", "resourcepacks"] {
        tokio::fs::create_dir_all(game_dir.join(sub)).await?;
    }
    let natives = plan.natives.clone();
    let nd = natives_dir.clone();
    tokio::task::spawn_blocking(move || installation::extract_natives(&natives, &nd)).await??;

    // Pre-1.6 versions read assets from <game_dir>/resources.
    let assets_root = paths.assets();
    let game_assets = if plan.map_to_resources {
        let target = game_dir.join("resources");
        let objects = plan.asset_objects.clone();
        let objects_root = assets_root.join("objects");
        let t = target.clone();
        tokio::task::spawn_blocking(move || installation::copy_assets_to(&objects_root, &objects, &t)).await??;
        target
    } else if plan.assets_virtual {
        assets_root.join("virtual").join(&plan.asset_index_id)
    } else {
        assets_root.clone()
    };

    let width = instance.resolution_width.unwrap_or(settings.resolution_width);
    let height = instance.resolution_height.unwrap_or(settings.resolution_height);
    let fullscreen = instance.fullscreen.unwrap_or(settings.fullscreen);
    let classpath = plan
        .classpath
        .iter()
        .map(|p| p.to_string_lossy().to_string())
        .collect::<Vec<_>>()
        .join(classpath_separator());

    let path_str = |p: &Path| p.to_string_lossy().to_string();
    let mut vars = LaunchVars::new();
    vars.insert("auth_player_name", auth.username.clone());
    vars.insert("version_name", plan.version.id.clone());
    vars.insert("game_directory", path_str(game_dir));
    vars.insert("assets_root", path_str(&assets_root));
    vars.insert("game_assets", path_str(&game_assets));
    vars.insert("assets_index_name", plan.asset_index_id.clone());
    vars.insert("auth_uuid", auth.uuid.replace('-', ""));
    vars.insert("auth_access_token", auth.access_token.clone());
    vars.insert("auth_session", format!("token:{}:{}", auth.access_token, auth.uuid.replace('-', "")));
    vars.insert("auth_xuid", auth.xuid.clone());
    vars.insert("clientid", authentication::client_id(state).unwrap_or_default());
    vars.insert("user_type", "msa".into());
    vars.insert("user_properties", "{}".into());
    vars.insert("version_type", plan.version.release_type.clone().unwrap_or_else(|| "release".into()));
    vars.insert("natives_directory", path_str(&natives_dir));
    vars.insert("launcher_name", LAUNCHER_NAME.into());
    vars.insert("launcher_version", env!("CARGO_PKG_VERSION").into());
    vars.insert("classpath", classpath);
    vars.insert("classpath_separator", classpath_separator().into());
    vars.insert("library_directory", path_str(&paths.libraries()));
    vars.insert("resolution_width", width.to_string());
    vars.insert("resolution_height", height.to_string());

    let features = Features { demo_user: false, custom_resolution: !fullscreen };
    let user_jvm = instance
        .jvm_args
        .clone()
        .filter(|a| !a.trim().is_empty())
        .unwrap_or_else(|| settings.jvm_args.clone());
    let built = arguments::build(&plan.version, &vars, &features, user_jvm.trim().is_empty());

    let min_ram = instance.min_ram_mb.unwrap_or(settings.min_ram_mb);
    let max_ram = instance.max_ram_mb.unwrap_or(settings.max_ram_mb).max(min_ram);
    let mut jvm_args = vec![format!("-Xms{min_ram}M"), format!("-Xmx{max_ram}M")];
    jvm_args.extend(built.jvm);
    if let Some((argument, file)) = &plan.logging {
        jvm_args.push(argument.replace("${path}", &path_str(file)));
    }
    // Defense in depth against Log4Shell on older versions.
    jvm_args.push("-Dlog4j2.formatMsgNoLookups=true".into());
    jvm_args.extend(user_jvm.split_whitespace().map(String::from));

    let mut game_args = built.game;
    if fullscreen {
        game_args.push("--fullscreen".into());
    }

    Ok(LaunchCommand {
        instance_id: instance.id.clone(),
        version_id: plan.version.id.clone(),
        java,
        java_major,
        game_dir: game_dir.to_path_buf(),
        jvm_args,
        main_class: plan.version.main_class.clone(),
        game_args,
        env: BTreeMap::new(),
    })
}
