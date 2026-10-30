//! Java runtime management.
//!
//! * Detection of installed runtimes (JAVA_HOME, PATH, Windows registry,
//!   common vendor directories, runtimes managed by Bloom).
//! * Verification by actually executing the binary and reading its
//!   properties (never trusting folder names).
//! * Managed runtimes downloaded from Mojang's official Java runtime
//!   manifest, which publishes the exact OpenJDK build each Minecraft
//!   version's `javaVersion.component` requires, with per-file SHA1.

use std::collections::{BTreeSet, HashMap};
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::Arc;
use std::time::Duration;

use serde::Deserialize;
use tokio_util::sync::CancellationToken;

use crate::downloads::{DownloadItem, ProgressTracker};
use crate::errors::{BloomError, BloomResult};
use crate::installation::ensure_files;
use crate::models::{JavaRuntime, Stage};
use crate::services::state::AppState;
use crate::system::paths::{safe_join, validate_id, write_atomic};

const RUNTIME_INDEX_URL: &str =
    "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

#[cfg(windows)]
const JAVA_BIN: &str = "java.exe";
#[cfg(not(windows))]
const JAVA_BIN: &str = "java";

#[cfg(windows)]
pub const CREATE_NO_WINDOW: u32 = 0x0800_0000;

// ---------------------------------------------------------------------------
// Probing
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
pub struct JavaProbe {
    pub version: String,
    pub major: u32,
    pub vendor: String,
    pub arch: String,
}

/// Parses the major version from `java.version` (`1.8.0_51` → 8, `17.0.2` → 17).
pub fn parse_major(version: &str) -> Option<u32> {
    let mut parts = version.split(['.', '_', '-', '+']);
    let first: u32 = parts.next()?.parse().ok()?;
    if first == 1 {
        parts.next()?.parse().ok()
    } else {
        Some(first)
    }
}

fn parse_properties(output: &str) -> HashMap<String, String> {
    output
        .lines()
        .filter_map(|l| {
            let (k, v) = l.trim().split_once(" = ")?;
            Some((k.trim().to_string(), v.trim().to_string()))
        })
        .collect()
}

/// Runs the Java binary and reads its system properties.
pub async fn probe(java: &Path) -> BloomResult<JavaProbe> {
    let meta = tokio::fs::metadata(java)
        .await
        .map_err(|_| BloomError::Java(format!("{} does not exist", java.display())))?;
    if !meta.is_file() {
        return Err(BloomError::Java(format!("{} is not a file", java.display())));
    }
    let name = java.file_name().and_then(|n| n.to_str()).unwrap_or_default().to_ascii_lowercase();
    if !matches!(name.as_str(), "java" | "java.exe" | "javaw.exe" | "javaw") {
        return Err(BloomError::Java("select a java or javaw executable".into()));
    }
    let mut cmd = tokio::process::Command::new(java);
    cmd.arg("-XshowSettings:properties")
        .arg("-version")
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);
    #[cfg(windows)]
    cmd.creation_flags(CREATE_NO_WINDOW);
    let output = tokio::time::timeout(Duration::from_secs(15), cmd.output())
        .await
        .map_err(|_| BloomError::Java(format!("{} did not respond", java.display())))?
        .map_err(|e| BloomError::Java(format!("could not run {}: {e}", java.display())))?;
    let text = format!("{}\n{}", String::from_utf8_lossy(&output.stderr), String::from_utf8_lossy(&output.stdout));
    let props = parse_properties(&text);
    let version = props
        .get("java.version")
        .cloned()
        .ok_or_else(|| BloomError::Java(format!("{} is not a working Java runtime", java.display())))?;
    let major = parse_major(&version).ok_or_else(|| BloomError::Java(format!("unrecognized Java version {version}")))?;
    Ok(JavaProbe {
        version,
        major,
        vendor: props.get("java.vendor").cloned().unwrap_or_default(),
        arch: props.get("os.arch").cloned().unwrap_or_default(),
    })
}

/// Prefer `java.exe` over `javaw.exe` for launching: Bloom hides the console
/// itself and needs stdout/stderr for the log viewer.
pub fn normalize_java_path(p: &Path) -> PathBuf {
    if p.file_name().and_then(|n| n.to_str()).is_some_and(|n| n.eq_ignore_ascii_case("javaw.exe")) {
        let java = p.with_file_name("java.exe");
        if java.exists() {
            return java;
        }
    }
    p.to_path_buf()
}

// ---------------------------------------------------------------------------
// Detection
// ---------------------------------------------------------------------------

/// `canonicalize` returns `\\?\C:\...` verbatim paths on Windows; convert them
/// back to regular paths so the same runtime is not listed twice.
fn strip_verbatim(p: PathBuf) -> PathBuf {
    let s = p.to_string_lossy();
    match s.strip_prefix(r"\\?\") {
        Some(rest) if !rest.starts_with("UNC\\") => PathBuf::from(rest),
        _ => p,
    }
}

fn candidates(state: &AppState) -> BTreeSet<PathBuf> {
    let mut out = BTreeSet::new();
    let mut add_home = |home: PathBuf| {
        let bin = home.join("bin").join(JAVA_BIN);
        if bin.is_file() {
            out.insert(bin);
        }
    };

    if let Ok(home) = std::env::var("JAVA_HOME") {
        add_home(PathBuf::from(home));
    }
    if let Some(path) = std::env::var_os("PATH") {
        for dir in std::env::split_paths(&path) {
            let bin = dir.join(JAVA_BIN);
            if bin.is_file() {
                // Resolve Oracle's javapath shim directory to the real install.
                let resolved = std::fs::canonicalize(&bin).map(strip_verbatim).unwrap_or(bin);
                add_home(resolved.parent().and_then(|p| p.parent()).map(Path::to_path_buf).unwrap_or_default());
            }
        }
    }

    let mut roots: Vec<PathBuf> = Vec::new();
    #[cfg(windows)]
    {
        for var in ["ProgramFiles", "ProgramFiles(x86)", "ProgramW6432"] {
            if let Ok(pf) = std::env::var(var) {
                let pf = PathBuf::from(pf);
                for vendor in [
                    "Java",
                    "Eclipse Adoptium",
                    "Eclipse Foundation",
                    "Microsoft",
                    "Zulu",
                    "Amazon Corretto",
                    "BellSoft",
                    "Semeru",
                    "OpenJDK",
                    "AdoptOpenJDK",
                ] {
                    roots.push(pf.join(vendor));
                }
            }
        }
        if let Ok(local) = std::env::var("LOCALAPPDATA") {
            // Official Minecraft Launcher (Microsoft Store and standalone) runtimes.
            roots.push(
                PathBuf::from(&local)
                    .join("Packages")
                    .join("Microsoft.4297127D64EC6_8wekyb3d8bbwe")
                    .join("LocalCache")
                    .join("Local")
                    .join("runtime"),
            );
        }
        for home in registry_java_homes() {
            add_home(home);
        }
    }
    #[cfg(target_os = "linux")]
    roots.push(PathBuf::from("/usr/lib/jvm"));
    #[cfg(target_os = "macos")]
    roots.push(PathBuf::from("/Library/Java/JavaVirtualMachines"));

    roots.push(state.paths().runtimes());

    // Scan up to four levels deep for bin/java (covers vendor/jdk-x/bin and
    // Mojang runtime/component/platform/component/bin layouts).
    for root in roots {
        scan_for_java(&root, 0, &mut out);
    }
    out
}

fn scan_for_java(dir: &Path, depth: u32, out: &mut BTreeSet<PathBuf>) {
    if depth > 4 {
        return;
    }
    let bin = dir.join("bin").join(JAVA_BIN);
    if bin.is_file() {
        out.insert(bin);
        return;
    }
    #[cfg(target_os = "macos")]
    {
        let home = dir.join("Contents").join("Home").join("bin").join(JAVA_BIN);
        if home.is_file() {
            out.insert(home);
            return;
        }
    }
    let Ok(entries) = std::fs::read_dir(dir) else { return };
    for e in entries.flatten() {
        if e.file_type().map(|t| t.is_dir()).unwrap_or(false) {
            scan_for_java(&e.path(), depth + 1, out);
        }
    }
}

#[cfg(windows)]
fn registry_java_homes() -> Vec<PathBuf> {
    use winreg::enums::{HKEY_LOCAL_MACHINE, KEY_READ, KEY_WOW64_32KEY, KEY_WOW64_64KEY};
    use winreg::RegKey;

    let mut homes = Vec::new();
    let bases = [
        r"SOFTWARE\JavaSoft\Java Runtime Environment",
        r"SOFTWARE\JavaSoft\Java Development Kit",
        r"SOFTWARE\JavaSoft\JRE",
        r"SOFTWARE\JavaSoft\JDK",
        r"SOFTWARE\Eclipse Adoptium\JRE",
        r"SOFTWARE\Eclipse Adoptium\JDK",
        r"SOFTWARE\Microsoft\JDK",
        r"SOFTWARE\Azul Systems\Zulu",
    ];
    for view in [KEY_WOW64_64KEY, KEY_WOW64_32KEY] {
        let hklm = RegKey::predef(HKEY_LOCAL_MACHINE);
        for base in bases {
            let Ok(key) = hklm.open_subkey_with_flags(base, KEY_READ | view) else { continue };
            for ver in key.enum_keys().flatten() {
                let Ok(sub) = key.open_subkey_with_flags(&ver, KEY_READ | view) else { continue };
                if let Ok(home) = sub.get_value::<String, _>("JavaHome") {
                    homes.push(PathBuf::from(home));
                }
                // Adoptium/Microsoft layout: <ver>\hotspot\MSI\Path
                for nested in ["hotspot\\MSI", "MSI"] {
                    if let Ok(n) = sub.open_subkey_with_flags(nested, KEY_READ | view) {
                        if let Ok(p) = n.get_value::<String, _>("Path") {
                            homes.push(PathBuf::from(p));
                        }
                    }
                }
            }
        }
    }
    homes
}

/// Scans the system, probes every candidate and refreshes the database.
pub async fn detect(state: &AppState) -> BloomResult<Vec<JavaRuntime>> {
    let found = candidates(state);
    let runtimes_root = state.paths().runtimes();
    let probes = futures::future::join_all(found.into_iter().map(|path| async move {
        let r = probe(&path).await;
        (path, r)
    }))
    .await;

    state.db.remove_system_java_runtimes()?;
    for (path, result) in probes {
        match result {
            Ok(p) => {
                let managed = path.starts_with(&runtimes_root);
                let component = if managed {
                    path.strip_prefix(&runtimes_root)
                        .ok()
                        .and_then(|r| r.components().next())
                        .map(|c| c.as_os_str().to_string_lossy().to_string())
                } else {
                    None
                };
                state.db.upsert_java_runtime(&JavaRuntime {
                    path: path.to_string_lossy().to_string(),
                    version: p.version,
                    major: p.major,
                    vendor: p.vendor,
                    arch: p.arch,
                    source: if managed { "managed".into() } else { "system".into() },
                    component,
                })?;
            }
            Err(e) => tracing::debug!(path = %path.display(), error = %e, "ignoring Java candidate"),
        }
    }
    // Drop entries whose binary disappeared (e.g. uninstalled custom runtimes).
    for rt in state.db.list_java_runtimes()? {
        if !Path::new(&rt.path).is_file() {
            state.db.remove_java_runtime(&rt.path)?;
        }
    }
    state.db.list_java_runtimes()
}

/// Validates and registers a user-selected Java binary.
pub async fn add_custom(state: &AppState, path: &str) -> BloomResult<JavaRuntime> {
    let path = normalize_java_path(Path::new(path));
    let p = probe(&path).await?;
    let rt = JavaRuntime {
        path: path.to_string_lossy().to_string(),
        version: p.version,
        major: p.major,
        vendor: p.vendor,
        arch: p.arch,
        source: "custom".into(),
        component: None,
    };
    state.db.upsert_java_runtime(&rt)?;
    Ok(rt)
}

// ---------------------------------------------------------------------------
// Managed runtimes (Mojang Java runtime manifest)
// ---------------------------------------------------------------------------

fn platform_key() -> Option<&'static str> {
    match (std::env::consts::OS, std::env::consts::ARCH) {
        ("windows", "x86_64") => Some("windows-x64"),
        ("windows", "x86") => Some("windows-x86"),
        ("windows", "aarch64") => Some("windows-arm64"),
        ("macos", "x86_64") => Some("mac-os"),
        ("macos", "aarch64") => Some("mac-os-arm64"),
        ("linux", "x86_64") => Some("linux"),
        ("linux", "x86") => Some("linux-i386"),
        _ => None,
    }
}

#[derive(Deserialize)]
struct RuntimeEntry {
    manifest: RuntimeManifestRef,
    version: RuntimeVersion,
}

#[derive(Deserialize)]
struct RuntimeManifestRef {
    sha1: String,
    url: String,
}

#[derive(Deserialize)]
struct RuntimeVersion {
    name: String,
}

#[derive(Deserialize)]
struct RuntimeFiles {
    files: HashMap<String, RuntimeFile>,
}

#[derive(Deserialize)]
struct RuntimeFile {
    #[serde(rename = "type")]
    kind: String,
    #[serde(default)]
    executable: bool,
    downloads: Option<RuntimeDownloads>,
    #[cfg_attr(windows, allow(dead_code))]
    target: Option<String>,
}

#[derive(Deserialize)]
struct RuntimeDownloads {
    raw: RuntimeRaw,
}

#[derive(Deserialize)]
struct RuntimeRaw {
    sha1: String,
    size: u64,
    url: String,
}

#[derive(serde::Serialize, Deserialize)]
struct RuntimeMarker {
    component: String,
    version: String,
    manifest_sha1: String,
}

fn find_java_in(dir: &Path) -> Option<PathBuf> {
    let mut found = BTreeSet::new();
    scan_for_java(dir, 0, &mut found);
    found.into_iter().next()
}

/// Ensures the managed runtime for `component` is installed and intact, and
/// returns the path of its Java binary.
pub async fn ensure_managed_runtime(
    state: &AppState,
    component: &str,
    tracker: &Arc<ProgressTracker>,
    cancel: &CancellationToken,
) -> BloomResult<PathBuf> {
    validate_id(component)?;
    let platform = platform_key()
        .ok_or_else(|| BloomError::Java("automatic Java download is not available for this platform".into()))?;
    let dir = state.paths().runtimes().join(component);
    let marker_path = dir.join(".bloom-runtime.json");

    tracker.stage(Stage::Preparing, format!("Resolving Java runtime {component}"));
    let index: HashMap<String, HashMap<String, Vec<RuntimeEntry>>> =
        match state.downloader.fetch_json(RUNTIME_INDEX_URL).await {
            Ok(i) => i,
            Err(e) => {
                // Offline: trust an existing, previously verified install.
                if marker_path.exists() {
                    if let Some(java) = find_java_in(&dir) {
                        tracing::warn!(error = %e, "offline, using installed Java runtime");
                        return Ok(java);
                    }
                }
                return Err(e);
            }
        };
    let entry = index
        .get(platform)
        .and_then(|p| p.get(component))
        .and_then(|v| v.first())
        .ok_or_else(|| {
            BloomError::Java(format!(
                "Mojang does not publish the Java runtime \"{component}\" for {platform}; select a compatible Java installation manually"
            ))
        })?;

    let manifest_bytes = state
        .downloader
        .fetch_verified(&entry.manifest.url, Some(&entry.manifest.sha1))
        .await?;
    let manifest: RuntimeFiles = serde_json::from_slice(&manifest_bytes)?;

    let mut items = Vec::new();
    let mut executables = Vec::new();
    let mut links = Vec::new();
    for (rel, file) in &manifest.files {
        let path = safe_join(&dir, rel)?;
        match file.kind.as_str() {
            "directory" => std::fs::create_dir_all(&path)?,
            "file" => {
                let Some(dl) = &file.downloads else { continue };
                if file.executable {
                    executables.push(path.clone());
                }
                items.push(DownloadItem {
                    url: dl.raw.url.clone(),
                    path,
                    sha1: Some(dl.raw.sha1.clone()),
                    size: Some(dl.raw.size),
                    label: rel.rsplit('/').next().unwrap_or(rel).to_string(),
                });
            }
            "link" => links.push((path, file.target.clone())),
            _ => {}
        }
    }

    ensure_files(
        state,
        items,
        tracker,
        cancel,
        &format!("Checking Java {}", entry.version.name),
        &format!("Downloading Java {}", entry.version.name),
    )
    .await?;

    tracker.stage(Stage::Installing, format!("Installing Java {}", entry.version.name));
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        for exe in &executables {
            if let Ok(meta) = std::fs::metadata(exe) {
                let mut perms = meta.permissions();
                perms.set_mode(0o755);
                let _ = std::fs::set_permissions(exe, perms);
            }
        }
        for (path, target) in &links {
            if let Some(t) = target {
                if !path.exists() {
                    let _ = std::os::unix::fs::symlink(t, path);
                }
            }
        }
    }
    #[cfg(not(unix))]
    let _ = (&executables, &links);

    write_atomic(
        &marker_path,
        &serde_json::to_vec(&RuntimeMarker {
            component: component.to_string(),
            version: entry.version.name.clone(),
            manifest_sha1: entry.manifest.sha1.clone(),
        })?,
    )?;

    let java = find_java_in(&dir)
        .ok_or_else(|| BloomError::Java(format!("the runtime {component} does not contain a Java binary")))?;
    let p = probe(&java).await?;
    state.db.upsert_java_runtime(&JavaRuntime {
        path: java.to_string_lossy().to_string(),
        version: p.version,
        major: p.major,
        vendor: p.vendor,
        arch: p.arch,
        source: "managed".into(),
        component: Some(component.to_string()),
    })?;
    Ok(java)
}

/// Removes a managed runtime directory.
pub async fn remove_managed_runtime(state: &AppState, component: &str) -> BloomResult<()> {
    validate_id(component)?;
    let dir = state.paths().runtimes().join(component);
    if dir.exists() {
        tokio::fs::remove_dir_all(&dir).await?;
    }
    state.db.file_cache_remove_prefix(&dir.to_string_lossy())?;
    for rt in state.db.list_java_runtimes()? {
        if rt.component.as_deref() == Some(component) && rt.source == "managed" {
            state.db.remove_java_runtime(&rt.path)?;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn major_version_parsing() {
        assert_eq!(parse_major("1.8.0_51"), Some(8));
        assert_eq!(parse_major("17.0.2"), Some(17));
        assert_eq!(parse_major("21"), Some(21));
        assert_eq!(parse_major("25.0.1+8-LTS"), Some(25));
        assert_eq!(parse_major("garbage"), None);
    }

    #[test]
    fn properties_parsing() {
        let out = "Property settings:\n    java.version = 21.0.7\n    java.vendor = Microsoft\n    os.arch = amd64\n";
        let p = parse_properties(out);
        assert_eq!(p.get("java.version").map(String::as_str), Some("21.0.7"));
        assert_eq!(p.get("os.arch").map(String::as_str), Some("amd64"));
    }
}
