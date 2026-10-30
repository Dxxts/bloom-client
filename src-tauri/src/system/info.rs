//! Host system information used by the Advanced settings and diagnostics.

use serde::Serialize;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SystemInfo {
    pub os_name: String,
    pub os_version: String,
    pub kernel_version: String,
    pub arch: String,
    pub hostname: String,
    pub cpu_brand: String,
    pub cpu_cores_logical: usize,
    pub cpu_cores_physical: Option<usize>,
    pub total_memory_mb: u64,
    pub available_memory_mb: u64,
    pub gpus: Vec<GpuInfo>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GpuInfo {
    pub name: String,
    pub vendor: Option<String>,
    pub driver_version: Option<String>,
    pub memory_mb: Option<u64>,
}

/// Collects system information. This is relatively expensive (CPU refresh),
/// so it is only called on demand from the Advanced settings page.
pub fn collect() -> SystemInfo {
    use sysinfo::{CpuRefreshKind, MemoryRefreshKind, RefreshKind, System};

    let sys = System::new_with_specifics(
        RefreshKind::nothing()
            .with_memory(MemoryRefreshKind::everything())
            .with_cpu(CpuRefreshKind::nothing()),
    );
    let cpu_brand = sys
        .cpus()
        .first()
        .map(|c| c.brand().trim().to_string())
        .unwrap_or_default();

    SystemInfo {
        os_name: System::name().unwrap_or_else(|| std::env::consts::OS.to_string()),
        os_version: System::long_os_version().unwrap_or_default(),
        kernel_version: System::kernel_version().unwrap_or_default(),
        arch: std::env::consts::ARCH.to_string(),
        hostname: System::host_name().unwrap_or_default(),
        cpu_brand,
        cpu_cores_logical: sys.cpus().len(),
        cpu_cores_physical: sys.physical_core_count(),
        total_memory_mb: sys.total_memory() / 1024 / 1024,
        available_memory_mb: sys.available_memory() / 1024 / 1024,
        gpus: gpus(),
    }
}

/// Total physical memory in MB (cheap, used to clamp RAM sliders).
pub fn total_memory_mb() -> u64 {
    use sysinfo::{MemoryRefreshKind, RefreshKind, System};
    let sys = System::new_with_specifics(RefreshKind::nothing().with_memory(MemoryRefreshKind::nothing().with_ram()));
    sys.total_memory() / 1024 / 1024
}

#[cfg(windows)]
fn gpus() -> Vec<GpuInfo> {
    use winreg::enums::{HKEY_LOCAL_MACHINE, KEY_READ};
    use winreg::RegKey;

    // Display adapter device class. Reading it requires no elevation and
    // avoids spawning WMI/PowerShell processes.
    const CLASS: &str =
        r"SYSTEM\CurrentControlSet\Control\Class\{4d36e968-e325-11ce-bfc1-08002be10318}";
    let mut out = Vec::new();
    let Ok(class) = RegKey::predef(HKEY_LOCAL_MACHINE).open_subkey_with_flags(CLASS, KEY_READ) else {
        return out;
    };
    for name in class.enum_keys().flatten() {
        if !name.chars().all(|c| c.is_ascii_digit()) {
            continue;
        }
        let Ok(key) = class.open_subkey_with_flags(&name, KEY_READ) else { continue };
        let Ok(desc) = key.get_value::<String, _>("DriverDesc") else { continue };
        let memory = key
            .get_value::<u64, _>("HardwareInformation.qwMemorySize")
            .ok()
            .or_else(|| key.get_value::<u32, _>("HardwareInformation.MemorySize").ok().map(u64::from))
            .or_else(|| {
                key.get_raw_value("HardwareInformation.MemorySize").ok().and_then(|v| {
                    (v.bytes.len() >= 4).then(|| {
                        u64::from(u32::from_le_bytes([v.bytes[0], v.bytes[1], v.bytes[2], v.bytes[3]]))
                    })
                })
            })
            .filter(|m| *m > 0)
            .map(|m| m / 1024 / 1024);
        out.push(GpuInfo {
            name: desc,
            vendor: key.get_value("ProviderName").ok(),
            driver_version: key.get_value("DriverVersion").ok(),
            memory_mb: memory,
        });
    }
    out
}

#[cfg(not(windows))]
fn gpus() -> Vec<GpuInfo> {
    // GPU enumeration for Linux/macOS is not implemented in this milestone.
    Vec::new()
}
