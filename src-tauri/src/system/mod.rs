//! Operating system integration: paths, host information and platform
//! detection used by the Minecraft rule engine.

pub mod info;
pub mod paths;

/// Operating system name as used by Mojang manifests (`windows`, `osx`, `linux`).
pub fn mojang_os_name() -> &'static str {
    match std::env::consts::OS {
        "windows" => "windows",
        "macos" => "osx",
        _ => "linux",
    }
}

/// Architecture string as used by Mojang library rules.
pub fn mojang_arch() -> &'static str {
    match std::env::consts::ARCH {
        "x86" => "x86",
        "aarch64" => "arm64",
        "x86_64" => "x86_64",
        other => other,
    }
}

/// Bitness used for the `${arch}` placeholder in legacy native classifiers.
pub fn arch_bits() -> &'static str {
    if cfg!(target_pointer_width = "64") {
        "64"
    } else {
        "32"
    }
}

/// OS version string used by `os.version` regex rules.
/// On Windows this mirrors Java's `os.version` (`10.0.<build>`, also for
/// Windows 11), which is what Mojang's `^10\.` rules are written against.
pub fn os_version() -> String {
    if cfg!(windows) {
        let build = sysinfo::System::kernel_version().unwrap_or_default();
        let build = build.split('.').next_back().unwrap_or_default().to_string();
        format!("10.0.{build}")
    } else {
        sysinfo::System::os_version().unwrap_or_default()
    }
}
