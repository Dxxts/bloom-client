//! Launcher logging: rolling daily file in the app log directory plus stderr
//! in debug builds. The level can be changed at runtime from Settings.

use std::path::Path;

use tracing_subscriber::{fmt, layer::SubscriberExt, reload, util::SubscriberInitExt, EnvFilter, Registry};

use crate::errors::{BloomError, BloomResult};

pub struct LogHandle {
    reload: reload::Handle<EnvFilter, Registry>,
    _guard: tracing_appender::non_blocking::WorkerGuard,
}

impl LogHandle {
    pub fn set_level(&self, level: &str) -> BloomResult<()> {
        let filter = build_filter(level);
        self.reload
            .reload(filter)
            .map_err(|e| BloomError::Internal(format!("failed to change log level: {e}")))
    }
}

pub fn valid_level(level: &str) -> bool {
    matches!(level, "error" | "warn" | "info" | "debug" | "trace")
}

fn build_filter(level: &str) -> EnvFilter {
    let level = if valid_level(level) { level } else { "info" };
    // Keep noisy dependencies quiet unless explicitly debugging them.
    EnvFilter::new(format!("warn,bloom_lib={level},bloom_client={level}"))
}

pub fn init(log_dir: &Path, level: &str) -> BloomResult<LogHandle> {
    std::fs::create_dir_all(log_dir)?;
    let appender = tracing_appender::rolling::Builder::new()
        .rotation(tracing_appender::rolling::Rotation::DAILY)
        .filename_prefix("bloom")
        .filename_suffix("log")
        .max_log_files(7)
        .build(log_dir)
        .map_err(|e| BloomError::Internal(format!("log file: {e}")))?;
    let (writer, guard) = tracing_appender::non_blocking(appender);
    let (filter, reload) = reload::Layer::new(build_filter(level));

    let file_layer = fmt::layer().with_ansi(false).with_target(true).with_writer(writer);
    let stderr_layer = cfg!(debug_assertions).then(|| fmt::layer().with_target(true));

    tracing_subscriber::registry()
        .with(filter)
        .with(file_layer)
        .with(stderr_layer)
        .try_init()
        .map_err(|e| BloomError::Internal(format!("logger init: {e}")))?;
    Ok(LogHandle { reload, _guard: guard })
}
