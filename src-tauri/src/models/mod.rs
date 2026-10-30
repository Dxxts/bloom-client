//! Data transfer objects shared between services and the frontend.
//!
//! All structs serialize as camelCase to match the TypeScript types in
//! `src/types`.

use serde::{Deserialize, Serialize};

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct Settings {
    // General
    pub language: String,
    pub theme: String,
    pub reduced_motion: String,
    pub launch_on_startup: bool,
    pub minimize_to_tray: bool,
    pub notifications: bool,
    pub launcher_visibility_on_launch: String,

    // Minecraft defaults (instances may override)
    pub min_ram_mb: u32,
    pub max_ram_mb: u32,
    pub java_path: Option<String>,
    pub jvm_args: String,
    pub resolution_width: u32,
    pub resolution_height: u32,
    pub fullscreen: bool,
    pub instances_directory: Option<String>,

    // Launcher
    pub data_directory: Option<String>,
    pub download_concurrency: u32,
    pub auto_update: bool,
    pub log_level: String,
    pub microsoft_client_id: Option<String>,

    // UI state that must survive restarts
    pub selected_instance_id: Option<String>,
    pub show_snapshots: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            language: "en".into(),
            theme: "dark".into(),
            reduced_motion: "system".into(),
            launch_on_startup: false,
            minimize_to_tray: false,
            notifications: true,
            launcher_visibility_on_launch: "keep".into(),
            min_ram_mb: 1024,
            max_ram_mb: 4096,
            java_path: None,
            jvm_args: String::new(),
            resolution_width: 1280,
            resolution_height: 720,
            fullscreen: false,
            instances_directory: None,
            data_directory: None,
            download_concurrency: 12,
            auto_update: true,
            log_level: "info".into(),
            microsoft_client_id: None,
            selected_instance_id: None,
            show_snapshots: false,
        }
    }
}

// ---------------------------------------------------------------------------
// Accounts
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Account {
    pub uuid: String,
    pub username: String,
    pub skin_url: Option<String>,
    pub skin_variant: Option<String>,
    pub active: bool,
    /// `valid` when a non-expired Minecraft token is stored, `refreshable`
    /// when only a refresh token is available, `expired` when re-login is needed.
    pub status: String,
    pub minecraft_token_expires_at: Option<i64>,
    pub added_at: i64,
    pub last_used_at: Option<i64>,
}

// ---------------------------------------------------------------------------
// Instances
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum LoaderKind {
    Vanilla,
    Fabric,
    Forge,
    NeoForge,
}

impl LoaderKind {
    pub fn as_str(&self) -> &'static str {
        match self {
            LoaderKind::Vanilla => "vanilla",
            LoaderKind::Fabric => "fabric",
            LoaderKind::Forge => "forge",
            LoaderKind::NeoForge => "neoforge",
        }
    }

    pub fn parse(s: &str) -> Option<Self> {
        match s {
            "vanilla" => Some(LoaderKind::Vanilla),
            "fabric" => Some(LoaderKind::Fabric),
            "forge" => Some(LoaderKind::Forge),
            "neoforge" => Some(LoaderKind::NeoForge),
            _ => None,
        }
    }
}

/// Which world renderer an instance uses.
///
/// `Java` is Minecraft's own renderer. `Rust` adds the Bloom Native World
/// Engine (native chunk mesher) on top of Fabric; it exists only for the
/// versions listed in `engine::SUPPORTED_VERSIONS`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum EngineKind {
    #[default]
    Java,
    Rust,
}

impl EngineKind {
    pub fn as_str(&self) -> &'static str {
        match self {
            EngineKind::Java => "java",
            EngineKind::Rust => "rust",
        }
    }

    pub fn parse(s: &str) -> Option<Self> {
        match s {
            "java" => Some(EngineKind::Java),
            "rust" => Some(EngineKind::Rust),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Instance {
    pub id: String,
    pub name: String,
    pub version_id: String,
    pub loader: LoaderKind,
    pub loader_version: Option<String>,
    pub engine: EngineKind,
    pub java_path: Option<String>,
    pub min_ram_mb: Option<u32>,
    pub max_ram_mb: Option<u32>,
    pub resolution_width: Option<u32>,
    pub resolution_height: Option<u32>,
    pub fullscreen: Option<bool>,
    pub jvm_args: Option<String>,
    pub icon: String,
    pub created_at: i64,
    pub last_played_at: Option<i64>,
    pub total_play_seconds: i64,
    /// `installed`, `not_installed` or `incomplete` (computed, not stored).
    pub install_state: String,
    pub directory: String,
}

/// Payload used to create or update an instance.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InstanceInput {
    pub name: String,
    pub version_id: String,
    #[serde(default = "default_loader")]
    pub loader: LoaderKind,
    pub loader_version: Option<String>,
    #[serde(default)]
    pub engine: EngineKind,
    pub java_path: Option<String>,
    pub min_ram_mb: Option<u32>,
    pub max_ram_mb: Option<u32>,
    pub resolution_width: Option<u32>,
    pub resolution_height: Option<u32>,
    pub fullscreen: Option<bool>,
    pub jvm_args: Option<String>,
    pub icon: Option<String>,
}

fn default_loader() -> LoaderKind {
    LoaderKind::Vanilla
}

// ---------------------------------------------------------------------------
// Versions
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VersionSummary {
    pub id: String,
    /// `release`, `snapshot`, `old_beta`, `old_alpha`.
    pub release_type: String,
    pub release_time: String,
    pub metadata_url: String,
    pub metadata_sha1: Option<String>,
    pub installed: bool,
    pub java_major: Option<u32>,
    pub java_component: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VersionList {
    pub latest_release: Option<String>,
    pub latest_snapshot: Option<String>,
    pub versions: Vec<VersionSummary>,
    pub fetched_at: i64,
    /// True when the list was served from the on-disk cache because the
    /// network was unavailable.
    pub offline: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VersionDetails {
    pub id: String,
    pub release_type: String,
    pub release_time: String,
    pub main_class: String,
    pub java_major: u32,
    pub java_component: String,
    pub asset_index: String,
    pub library_count: usize,
    pub client_size: u64,
    pub installed: bool,
    pub installed_at: Option<i64>,
    pub disk_size: Option<u64>,
}

// ---------------------------------------------------------------------------
// Progress & game state events
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Stage {
    Preparing,
    CheckingFiles,
    Downloading,
    Installing,
    Launching,
    Running,
    Exited,
    Crashed,
    Failed,
    Cancelled,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProgressEvent {
    /// Identifier of the operation (instance id for launches, version id or
    /// runtime component for standalone installs).
    pub task_id: String,
    pub stage: Stage,
    pub message: String,
    pub files_done: u64,
    pub files_total: u64,
    pub bytes_done: u64,
    pub bytes_total: u64,
    pub speed_bps: u64,
    pub current_file: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GameSession {
    pub instance_id: String,
    pub stage: Stage,
    pub message: String,
    pub pid: Option<u32>,
    pub started_at: Option<i64>,
    pub exit_code: Option<i32>,
    pub last_progress: Option<ProgressEvent>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LogLine {
    pub stream: &'static str,
    pub line: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LaunchRecord {
    pub id: i64,
    pub instance_id: String,
    pub instance_name: Option<String>,
    pub version_id: String,
    pub account_name: Option<String>,
    pub started_at: i64,
    pub ended_at: Option<i64>,
    pub exit_code: Option<i32>,
    pub crashed: bool,
    pub log_file: Option<String>,
}

// ---------------------------------------------------------------------------
// Java
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct JavaRuntime {
    pub path: String,
    pub version: String,
    pub major: u32,
    pub vendor: String,
    pub arch: String,
    /// `managed` (downloaded by Bloom), `system` (detected) or `custom` (added by the user).
    pub source: String,
    pub component: Option<String>,
}

// ---------------------------------------------------------------------------
// Instance content (mods / resource packs)
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModEntry {
    pub file_name: String,
    pub enabled: bool,
    pub size: u64,
    pub modified_at: i64,
    pub mod_id: Option<String>,
    pub name: Option<String>,
    pub version: Option<String>,
    pub description: Option<String>,
    pub authors: Vec<String>,
    /// Loader declared by the metadata file found in the jar.
    pub loader: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResourcePackEntry {
    pub file_name: String,
    pub is_directory: bool,
    pub enabled: bool,
    pub size: u64,
    pub modified_at: i64,
    pub description: Option<String>,
    pub pack_format: Option<i64>,
    pub icon_data_url: Option<String>,
}

// ---------------------------------------------------------------------------
// News
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewsItem {
    pub id: String,
    pub title: String,
    pub version: Option<String>,
    pub category: String,
    pub date: Option<String>,
    pub image_url: Option<String>,
    pub body_html: String,
    pub source: String,
}
