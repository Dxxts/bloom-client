//! Global application state shared by every command.

use std::path::PathBuf;
use std::sync::{Arc, RwLock};
use std::time::Instant;

use tauri::AppHandle;

use crate::authentication::secret_store::SecretStore;
use crate::authentication::AuthService;
use crate::database::Db;
use crate::downloads::Downloader;
use crate::errors::BloomResult;
use crate::launcher::sessions::SessionRegistry;
use crate::minecraft::manifest::VersionManifest;
use crate::models::Settings;
use crate::services::logging::LogHandle;
use crate::system::paths::GamePaths;

pub struct AppState {
    pub app: AppHandle,
    pub db: Arc<Db>,
    pub downloader: Downloader,
    settings: RwLock<Settings>,
    /// Bloom's own configuration directory (database, logs).
    pub config_dir: PathBuf,
    pub log_dir: PathBuf,
    /// In-memory manifest cache; the async mutex also deduplicates
    /// concurrent fetches.
    pub manifest_cache: tokio::sync::Mutex<Option<(Instant, Arc<VersionManifest>, bool)>>,
    /// Serializes installation pipelines so two installs never write the
    /// same shared file (libraries, assets) concurrently.
    pub install_lock: tokio::sync::Mutex<()>,
    /// Cancellation tokens of standalone background tasks (version or Java
    /// installs started from the UI), keyed by task id.
    pub tasks: std::sync::Mutex<std::collections::HashMap<String, tokio_util::sync::CancellationToken>>,
    pub sessions: Arc<SessionRegistry>,
    pub auth: AuthService,
    pub secrets: Arc<dyn SecretStore>,
    pub log_handle: LogHandle,
}

impl AppState {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        app: AppHandle,
        db: Arc<Db>,
        settings: Settings,
        config_dir: PathBuf,
        log_dir: PathBuf,
        secrets: Arc<dyn SecretStore>,
        log_handle: LogHandle,
    ) -> BloomResult<Self> {
        Ok(Self {
            sessions: Arc::new(SessionRegistry::default()),
            app,
            db,
            downloader: Downloader::new()?,
            settings: RwLock::new(settings),
            config_dir,
            log_dir,
            manifest_cache: tokio::sync::Mutex::new(None),
            install_lock: tokio::sync::Mutex::new(()),
            tasks: std::sync::Mutex::new(std::collections::HashMap::new()),
            auth: AuthService::default(),
            secrets,
            log_handle,
        })
    }

    pub fn settings(&self) -> Settings {
        self.settings.read().unwrap_or_else(|e| e.into_inner()).clone()
    }

    pub fn replace_settings(&self, s: Settings) {
        *self.settings.write().unwrap_or_else(|e| e.into_inner()) = s;
    }

    /// Effective game data layout derived from the current settings.
    pub fn paths(&self) -> GamePaths {
        let s = self.settings();
        let root = s
            .data_directory
            .as_ref()
            .filter(|d| !d.trim().is_empty())
            .map(PathBuf::from)
            .unwrap_or_else(|| self.default_data_dir());
        let instances = s
            .instances_directory
            .as_ref()
            .filter(|d| !d.trim().is_empty())
            .map(PathBuf::from)
            .unwrap_or_else(|| root.join("instances"));
        GamePaths::new(root, instances)
    }

    pub fn default_data_dir(&self) -> PathBuf {
        self.config_dir.join("game")
    }
}
