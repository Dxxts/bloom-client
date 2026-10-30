//! Settings validation and application of side effects (log level,
//! autostart).

use crate::errors::{BloomError, BloomResult};
use crate::models::Settings;
use crate::services::logging;
use crate::services::state::AppState;
use crate::system::paths::validate_user_dir;

pub const MIN_RAM_MB: u32 = 512;

/// Validates and normalizes user-provided settings.
pub fn validate(mut s: Settings, total_memory_mb: u64) -> BloomResult<Settings> {
    if !matches!(s.language.as_str(), "en" | "it") {
        return Err(BloomError::InvalidInput("unsupported language".into()));
    }
    if !matches!(s.theme.as_str(), "dark" | "midnight") {
        return Err(BloomError::InvalidInput("unsupported theme".into()));
    }
    if !matches!(s.reduced_motion.as_str(), "system" | "on" | "off") {
        return Err(BloomError::InvalidInput("invalid reduced motion option".into()));
    }
    if !matches!(s.launcher_visibility_on_launch.as_str(), "keep" | "minimize" | "hide") {
        return Err(BloomError::InvalidInput("invalid launcher visibility option".into()));
    }
    if !logging::valid_level(&s.log_level) {
        return Err(BloomError::InvalidInput("invalid log level".into()));
    }
    validate_memory(s.min_ram_mb, s.max_ram_mb, total_memory_mb)?;
    validate_resolution(s.resolution_width, s.resolution_height)?;
    s.download_concurrency = s.download_concurrency.clamp(1, 32);
    s.jvm_args = validate_jvm_args(&s.jvm_args)?;
    for dir in [&mut s.data_directory, &mut s.instances_directory] {
        if let Some(d) = dir.as_ref() {
            if d.trim().is_empty() {
                *dir = None;
            } else {
                *dir = Some(validate_user_dir(d)?.to_string_lossy().to_string());
            }
        }
    }
    if let Some(path) = s.java_path.as_ref() {
        if path.trim().is_empty() {
            s.java_path = None;
        }
    }
    if let Some(id) = s.microsoft_client_id.as_ref() {
        let id = id.trim().to_string();
        if id.is_empty() {
            s.microsoft_client_id = None;
        } else if !is_guid(&id) {
            return Err(BloomError::InvalidInput("the Microsoft client ID must be a GUID".into()));
        } else {
            s.microsoft_client_id = Some(id);
        }
    }
    Ok(s)
}

pub fn validate_memory(min: u32, max: u32, total_memory_mb: u64) -> BloomResult<()> {
    if min < MIN_RAM_MB {
        return Err(BloomError::InvalidInput(format!("minimum memory must be at least {MIN_RAM_MB} MB")));
    }
    if max < min {
        return Err(BloomError::InvalidInput("maximum memory must be greater than or equal to minimum memory".into()));
    }
    if total_memory_mb > 0 && u64::from(max) > total_memory_mb {
        return Err(BloomError::InvalidInput(format!(
            "maximum memory cannot exceed the installed memory ({total_memory_mb} MB)"
        )));
    }
    Ok(())
}

pub fn validate_resolution(w: u32, h: u32) -> BloomResult<()> {
    if !(320..=15360).contains(&w) || !(240..=8640).contains(&h) {
        return Err(BloomError::InvalidInput("invalid game resolution".into()));
    }
    Ok(())
}

/// JVM arguments are split on whitespace. Memory flags are rejected because
/// they are controlled by the RAM settings, and options that would let a
/// value smuggle in a different main class or agent are refused.
pub fn validate_jvm_args(raw: &str) -> BloomResult<String> {
    let args: Vec<&str> = raw.split_whitespace().collect();
    for a in &args {
        let lower = a.to_ascii_lowercase();
        if lower.starts_with("-xmx") || lower.starts_with("-xms") {
            return Err(BloomError::InvalidInput(
                "set memory with the RAM options instead of -Xmx/-Xms".into(),
            ));
        }
        if lower == "-cp" || lower == "-classpath" || lower == "--class-path" || lower == "-jar" {
            return Err(BloomError::InvalidInput(format!("the JVM argument {a} is managed by Bloom")));
        }
        if !a.starts_with('-') {
            return Err(BloomError::InvalidInput(format!("invalid JVM argument: {a}")));
        }
    }
    Ok(args.join(" "))
}

fn is_guid(s: &str) -> bool {
    let parts: Vec<&str> = s.split('-').collect();
    let lens = [8, 4, 4, 4, 12];
    parts.len() == 5
        && parts.iter().zip(lens).all(|(p, l)| p.len() == l && p.chars().all(|c| c.is_ascii_hexdigit()))
}

/// Persists settings and applies runtime side effects.
pub fn apply(state: &AppState, new: Settings) -> BloomResult<Settings> {
    let old = state.settings();
    let new = validate(new, crate::system::info::total_memory_mb())?;
    state.db.save_settings(&new)?;
    state.replace_settings(new.clone());

    if old.log_level != new.log_level {
        state.log_handle.set_level(&new.log_level)?;
    }
    if old.launch_on_startup != new.launch_on_startup {
        set_autostart(state, new.launch_on_startup)?;
    }
    if old.data_directory != new.data_directory || old.instances_directory != new.instances_directory {
        state.paths().ensure()?;
    }
    Ok(new)
}

pub fn set_autostart(state: &AppState, enabled: bool) -> BloomResult<()> {
    use tauri_plugin_autostart::ManagerExt;
    let manager = state.app.autolaunch();
    let result = if enabled { manager.enable() } else { manager.disable() };
    result.map_err(|e| BloomError::Internal(format!("could not update launch on startup: {e}")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn jvm_arg_validation() {
        assert_eq!(validate_jvm_args("  -XX:+UseG1GC   -Dfoo=bar ").unwrap(), "-XX:+UseG1GC -Dfoo=bar");
        assert!(validate_jvm_args("-Xmx4G").is_err());
        assert!(validate_jvm_args("-cp evil.jar").is_err());
        assert!(validate_jvm_args("notaflag").is_err());
        assert_eq!(validate_jvm_args("").unwrap(), "");
    }

    #[test]
    fn memory_validation() {
        assert!(validate_memory(1024, 4096, 16000).is_ok());
        assert!(validate_memory(256, 4096, 16000).is_err());
        assert!(validate_memory(4096, 1024, 16000).is_err());
        assert!(validate_memory(1024, 32000, 16000).is_err());
    }

    #[test]
    fn settings_validation() {
        let s = validate(Settings::default(), 16000).unwrap();
        assert_eq!(s, Settings::default());
        let bad = Settings { language: "xx".into(), ..Settings::default() };
        assert!(validate(bad, 16000).is_err());
        let rel = Settings { data_directory: Some("relative/dir".into()), ..Settings::default() };
        assert!(validate(rel, 16000).is_err());
        let guid = Settings { microsoft_client_id: Some("not-a-guid".into()), ..Settings::default() };
        assert!(validate(guid, 16000).is_err());
        assert!(is_guid("00000000-0000-0000-0000-000000000000"));
    }
}
