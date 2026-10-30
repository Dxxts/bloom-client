//! Per-instance content: mods (`<instance>/mods`) and resource packs
//! (`<instance>/resourcepacks`, activation stored in the instance's own
//! `options.txt`, exactly where Minecraft reads it).
//!
//! No content is downloaded from external platforms in this milestone; files
//! are imported from disk through the native file picker.

use std::io::Read;
use std::path::{Path, PathBuf};

use base64::Engine;

use crate::errors::{BloomError, BloomResult};
use crate::models::{ModEntry, ResourcePackEntry};
use crate::services::state::AppState;
use crate::system::paths::validate_file_name;

const MAX_META_BYTES: u64 = 1024 * 1024;
const MAX_ICON_BYTES: u64 = 512 * 1024;
const DISABLED_SUFFIX: &str = ".disabled";

fn modified_at(meta: &std::fs::Metadata) -> i64 {
    meta.modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

fn read_zip_entry<R: Read + std::io::Seek>(zip: &mut zip::ZipArchive<R>, name: &str, limit: u64) -> Option<Vec<u8>> {
    let entry = zip.by_name(name).ok()?;
    if entry.size() > limit {
        return None;
    }
    let mut buf = Vec::with_capacity(entry.size() as usize);
    entry.take(limit).read_to_end(&mut buf).ok()?;
    Some(buf)
}

// ---------------------------------------------------------------------------
// Mods
// ---------------------------------------------------------------------------

pub fn mods_dir(state: &AppState, instance_id: &str) -> BloomResult<PathBuf> {
    Ok(super::instance_dir(state, instance_id)?.join("mods"))
}

#[derive(Default)]
struct ModMeta {
    id: Option<String>,
    name: Option<String>,
    version: Option<String>,
    description: Option<String>,
    authors: Vec<String>,
    loader: Option<String>,
}

fn json_people(v: Option<&serde_json::Value>) -> Vec<String> {
    v.and_then(|a| a.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|p| p.as_str().map(String::from).or_else(|| p.get("name")?.as_str().map(String::from)))
                .collect()
        })
        .unwrap_or_default()
}

fn str_field(v: &serde_json::Value, key: &str) -> Option<String> {
    v.get(key).and_then(|x| x.as_str()).map(|s| s.trim().to_string()).filter(|s| !s.is_empty())
}

/// Minimal reader for the first `[[mods]]` table of a Forge/NeoForge
/// `mods.toml`. Supports `key = "value"` and `key = '''multi-line'''`.
fn parse_mods_toml(text: &str) -> ModMeta {
    let mut meta = ModMeta::default();
    let mut in_mods = false;
    let mut lines = text.lines();
    let mut global_authors = None;
    while let Some(line) = lines.next() {
        let trimmed = line.trim();
        if trimmed.starts_with("[[") || trimmed.starts_with('[') {
            if in_mods && trimmed != "[[mods]]" {
                break;
            }
            in_mods = trimmed == "[[mods]]";
            continue;
        }
        let Some((key, value)) = trimmed.split_once('=') else { continue };
        let key = key.trim();
        let mut value = value.trim().to_string();
        if value.starts_with("'''") || value.starts_with("\"\"\"") {
            let delim = &value[..3].to_string();
            let mut acc = value[3..].to_string();
            while !acc.contains(delim.as_str()) {
                match lines.next() {
                    Some(l) => {
                        acc.push('\n');
                        acc.push_str(l);
                    }
                    None => break,
                }
            }
            value = acc.split(delim.as_str()).next().unwrap_or_default().trim().to_string();
        } else {
            if let Some(hash) = value.find(" #") {
                value.truncate(hash);
            }
            value = value.trim().trim_matches('"').trim_matches('\'').to_string();
        }
        match (in_mods, key) {
            (true, "modId") => meta.id = Some(value),
            (true, "displayName") => meta.name = Some(value),
            (true, "version") => meta.version = Some(value),
            (true, "description") => meta.description = Some(value),
            (true, "authors") => meta.authors = vec![value],
            (false, "authors") => global_authors = Some(value),
            _ => {}
        }
    }
    if meta.authors.is_empty() {
        if let Some(a) = global_authors {
            meta.authors = vec![a];
        }
    }
    // `${file.jarVersion}` is resolved from the manifest by Forge; show it raw otherwise.
    meta
}

fn read_mod_meta(path: &Path) -> ModMeta {
    let Ok(file) = std::fs::File::open(path) else { return ModMeta::default() };
    let Ok(mut zip) = zip::ZipArchive::new(std::io::BufReader::new(file)) else { return ModMeta::default() };

    if let Some(bytes) = read_zip_entry(&mut zip, "fabric.mod.json", MAX_META_BYTES) {
        if let Ok(v) = serde_json::from_slice::<serde_json::Value>(&bytes) {
            return ModMeta {
                id: str_field(&v, "id"),
                name: str_field(&v, "name"),
                version: str_field(&v, "version"),
                description: str_field(&v, "description"),
                authors: json_people(v.get("authors")),
                loader: Some("fabric".into()),
            };
        }
    }
    if let Some(bytes) = read_zip_entry(&mut zip, "quilt.mod.json", MAX_META_BYTES) {
        if let Ok(v) = serde_json::from_slice::<serde_json::Value>(&bytes) {
            let ql = v.get("quilt_loader").cloned().unwrap_or_default();
            let md = ql.get("metadata").cloned().unwrap_or_default();
            let contributors = md
                .get("contributors")
                .and_then(|c| c.as_object())
                .map(|o| o.keys().cloned().collect())
                .unwrap_or_default();
            return ModMeta {
                id: str_field(&ql, "id"),
                name: str_field(&md, "name"),
                version: str_field(&ql, "version"),
                description: str_field(&md, "description"),
                authors: contributors,
                loader: Some("quilt".into()),
            };
        }
    }
    for (entry, loader) in [("META-INF/neoforge.mods.toml", "neoforge"), ("META-INF/mods.toml", "forge")] {
        if let Some(bytes) = read_zip_entry(&mut zip, entry, MAX_META_BYTES) {
            let mut m = parse_mods_toml(&String::from_utf8_lossy(&bytes));
            m.loader = Some(loader.into());
            return m;
        }
    }
    if let Some(bytes) = read_zip_entry(&mut zip, "mcmod.info", MAX_META_BYTES) {
        if let Ok(v) = serde_json::from_slice::<serde_json::Value>(&bytes) {
            let first = v
                .as_array()
                .and_then(|a| a.first().cloned())
                .or_else(|| v.get("modList").and_then(|l| l.as_array()).and_then(|a| a.first().cloned()));
            if let Some(m) = first {
                return ModMeta {
                    id: str_field(&m, "modid"),
                    name: str_field(&m, "name"),
                    version: str_field(&m, "version"),
                    description: str_field(&m, "description"),
                    authors: json_people(m.get("authorList").or_else(|| m.get("authors"))),
                    loader: Some("forge".into()),
                };
            }
        }
    }
    ModMeta::default()
}

pub async fn list_mods(state: &AppState, instance_id: &str) -> BloomResult<Vec<ModEntry>> {
    let dir = mods_dir(state, instance_id)?;
    tokio::task::spawn_blocking(move || {
        std::fs::create_dir_all(&dir)?;
        let mut out = Vec::new();
        for entry in std::fs::read_dir(&dir)?.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            let lower = name.to_ascii_lowercase();
            let enabled = lower.ends_with(".jar");
            if !enabled && !lower.ends_with(".jar.disabled") {
                continue;
            }
            let Ok(meta) = entry.metadata() else { continue };
            if !meta.is_file() {
                continue;
            }
            let m = read_mod_meta(&entry.path());
            out.push(ModEntry {
                file_name: name,
                enabled,
                size: meta.len(),
                modified_at: modified_at(&meta),
                mod_id: m.id,
                name: m.name,
                version: m.version,
                description: m.description,
                authors: m.authors,
                loader: m.loader,
            });
        }
        out.sort_by_key(|m| m.name.clone().unwrap_or_else(|| m.file_name.clone()).to_lowercase());
        Ok(out)
    })
    .await?
}

fn ensure_not_running(state: &AppState, instance_id: &str) -> BloomResult<()> {
    if state.sessions.is_busy(instance_id) {
        return Err(BloomError::AlreadyRunning("close the game before changing its content".into()));
    }
    Ok(())
}

pub fn set_mod_enabled(state: &AppState, instance_id: &str, file_name: &str, enabled: bool) -> BloomResult<String> {
    ensure_not_running(state, instance_id)?;
    validate_file_name(file_name)?;
    let dir = mods_dir(state, instance_id)?;
    let current = dir.join(file_name);
    if !current.is_file() {
        return Err(BloomError::NotFound("mod file not found".into()));
    }
    let base = file_name.strip_suffix(DISABLED_SUFFIX).unwrap_or(file_name);
    let target_name = if enabled { base.to_string() } else { format!("{base}{DISABLED_SUFFIX}") };
    if target_name == file_name {
        return Ok(target_name);
    }
    let target = dir.join(&target_name);
    if target.exists() {
        return Err(BloomError::InvalidInput(format!("{target_name} already exists")));
    }
    std::fs::rename(current, target)?;
    Ok(target_name)
}

pub fn delete_mod(state: &AppState, instance_id: &str, file_name: &str) -> BloomResult<()> {
    ensure_not_running(state, instance_id)?;
    validate_file_name(file_name)?;
    let path = mods_dir(state, instance_id)?.join(file_name);
    if !path.is_file() {
        return Err(BloomError::NotFound("mod file not found".into()));
    }
    std::fs::remove_file(path)?;
    Ok(())
}

/// Copies user-selected `.jar` files into the instance's mods folder.
pub fn import_mods(state: &AppState, instance_id: &str, files: &[PathBuf]) -> BloomResult<usize> {
    ensure_not_running(state, instance_id)?;
    let dir = mods_dir(state, instance_id)?;
    std::fs::create_dir_all(&dir)?;
    let mut n = 0;
    for src in files {
        let name = src.file_name().and_then(|n| n.to_str()).unwrap_or_default().to_string();
        validate_file_name(&name)?;
        if !name.to_ascii_lowercase().ends_with(".jar") {
            return Err(BloomError::InvalidInput(format!("{name} is not a .jar file")));
        }
        // Must be a readable zip archive.
        let f = std::fs::File::open(src)?;
        zip::ZipArchive::new(std::io::BufReader::new(f))
            .map_err(|_| BloomError::InvalidInput(format!("{name} is not a valid mod archive")))?;
        std::fs::copy(src, dir.join(&name))?;
        n += 1;
    }
    Ok(n)
}

// ---------------------------------------------------------------------------
// Resource packs
// ---------------------------------------------------------------------------

pub fn packs_dir(state: &AppState, instance_id: &str) -> BloomResult<PathBuf> {
    Ok(super::instance_dir(state, instance_id)?.join("resourcepacks"))
}

fn options_path(state: &AppState, instance_id: &str) -> BloomResult<PathBuf> {
    Ok(super::instance_dir(state, instance_id)?.join("options.txt"))
}

/// Reads the `resourcePacks:[...]` list from options.txt.
fn read_enabled_packs(options: &Path) -> Vec<String> {
    let Ok(text) = std::fs::read_to_string(options) else { return Vec::new() };
    text.lines()
        .find_map(|l| l.strip_prefix("resourcePacks:"))
        .and_then(|v| serde_json::from_str::<Vec<String>>(v.trim()).ok())
        .unwrap_or_default()
}

fn write_enabled_packs(options: &Path, packs: &[String]) -> BloomResult<()> {
    let value = format!("resourcePacks:{}", serde_json::to_string(packs)?);
    let text = std::fs::read_to_string(options).unwrap_or_default();
    let mut found = false;
    let mut lines: Vec<String> = text
        .lines()
        .map(|l| {
            if l.starts_with("resourcePacks:") {
                found = true;
                value.clone()
            } else {
                l.to_string()
            }
        })
        .collect();
    if !found {
        lines.push(value);
    }
    crate::system::paths::write_atomic(options, (lines.join("\n") + "\n").as_bytes())
}

/// Flattens a pack.mcmeta description (plain string or text component).
fn flatten_text(v: &serde_json::Value) -> String {
    match v {
        serde_json::Value::String(s) => s.clone(),
        serde_json::Value::Array(a) => a.iter().map(flatten_text).collect(),
        serde_json::Value::Object(o) => {
            let mut s = o.get("text").and_then(|t| t.as_str()).unwrap_or_default().to_string();
            if let Some(extra) = o.get("extra") {
                s.push_str(&flatten_text(extra));
            }
            s
        }
        _ => String::new(),
    }
}

/// Removes legacy `§x` formatting codes.
fn strip_formatting(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut chars = s.chars();
    while let Some(c) = chars.next() {
        if c == '§' {
            chars.next();
        } else {
            out.push(c);
        }
    }
    out
}

fn parse_mcmeta(bytes: &[u8]) -> (Option<String>, Option<i64>) {
    // Some packs ship a UTF-8 BOM.
    let bytes = bytes.strip_prefix(&[0xEF, 0xBB, 0xBF]).unwrap_or(bytes);
    let Ok(v) = serde_json::from_slice::<serde_json::Value>(bytes) else { return (None, None) };
    let pack = v.get("pack");
    let desc = pack.and_then(|p| p.get("description")).map(|d| strip_formatting(&flatten_text(d)));
    let format = pack.and_then(|p| p.get("pack_format")).and_then(|f| f.as_i64());
    (desc.filter(|d| !d.trim().is_empty()), format)
}

fn png_data_url(bytes: &[u8]) -> Option<String> {
    bytes
        .starts_with(&[0x89, b'P', b'N', b'G'])
        .then(|| format!("data:image/png;base64,{}", base64::engine::general_purpose::STANDARD.encode(bytes)))
}

pub async fn list_resource_packs(state: &AppState, instance_id: &str) -> BloomResult<Vec<ResourcePackEntry>> {
    let dir = packs_dir(state, instance_id)?;
    let options = options_path(state, instance_id)?;
    tokio::task::spawn_blocking(move || {
        std::fs::create_dir_all(&dir)?;
        let enabled = read_enabled_packs(&options);
        let mut out = Vec::new();
        for entry in std::fs::read_dir(&dir)?.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            let Ok(meta) = entry.metadata() else { continue };
            let path = entry.path();
            let (mcmeta, icon, size) = if meta.is_dir() {
                let mcmeta = std::fs::read(path.join("pack.mcmeta")).ok();
                if mcmeta.is_none() {
                    continue;
                }
                let icon = std::fs::metadata(path.join("pack.png"))
                    .ok()
                    .filter(|m| m.len() <= MAX_ICON_BYTES)
                    .and_then(|_| std::fs::read(path.join("pack.png")).ok());
                (mcmeta, icon, crate::system::paths::dir_size(&path))
            } else if name.to_ascii_lowercase().ends_with(".zip") {
                let Ok(f) = std::fs::File::open(&path) else { continue };
                let Ok(mut zip) = zip::ZipArchive::new(std::io::BufReader::new(f)) else { continue };
                (
                    read_zip_entry(&mut zip, "pack.mcmeta", MAX_META_BYTES),
                    read_zip_entry(&mut zip, "pack.png", MAX_ICON_BYTES),
                    meta.len(),
                )
            } else {
                continue;
            };
            let (description, pack_format) = mcmeta.as_deref().map(parse_mcmeta).unwrap_or((None, None));
            out.push(ResourcePackEntry {
                enabled: enabled.iter().any(|e| e == &format!("file/{name}")),
                file_name: name,
                is_directory: meta.is_dir(),
                size,
                modified_at: modified_at(&meta),
                description,
                pack_format,
                icon_data_url: icon.as_deref().and_then(png_data_url),
            });
        }
        out.sort_by_key(|p| p.file_name.to_lowercase());
        Ok(out)
    })
    .await?
}

pub fn set_pack_enabled(state: &AppState, instance_id: &str, file_name: &str, enabled: bool) -> BloomResult<()> {
    ensure_not_running(state, instance_id)?;
    validate_file_name(file_name)?;
    if !packs_dir(state, instance_id)?.join(file_name).exists() {
        return Err(BloomError::NotFound("resource pack not found".into()));
    }
    let options = options_path(state, instance_id)?;
    let mut packs = read_enabled_packs(&options);
    if packs.is_empty() {
        packs.push("vanilla".into());
    }
    let key = format!("file/{file_name}");
    packs.retain(|p| p != &key);
    if enabled {
        // Later entries have higher priority in Minecraft; new packs go on top.
        packs.push(key);
    }
    write_enabled_packs(&options, &packs)
}

pub fn delete_pack(state: &AppState, instance_id: &str, file_name: &str) -> BloomResult<()> {
    ensure_not_running(state, instance_id)?;
    validate_file_name(file_name)?;
    let path = packs_dir(state, instance_id)?.join(file_name);
    if path.is_dir() {
        std::fs::remove_dir_all(&path)?;
    } else if path.is_file() {
        std::fs::remove_file(&path)?;
    } else {
        return Err(BloomError::NotFound("resource pack not found".into()));
    }
    let options = options_path(state, instance_id)?;
    let mut packs = read_enabled_packs(&options);
    let before = packs.len();
    packs.retain(|p| p != &format!("file/{file_name}"));
    if packs.len() != before {
        write_enabled_packs(&options, &packs)?;
    }
    Ok(())
}

/// Imports ZIP resource packs after checking they contain a `pack.mcmeta`.
pub fn import_packs(state: &AppState, instance_id: &str, files: &[PathBuf]) -> BloomResult<usize> {
    ensure_not_running(state, instance_id)?;
    let dir = packs_dir(state, instance_id)?;
    std::fs::create_dir_all(&dir)?;
    let mut n = 0;
    for src in files {
        let name = src.file_name().and_then(|n| n.to_str()).unwrap_or_default().to_string();
        validate_file_name(&name)?;
        if !name.to_ascii_lowercase().ends_with(".zip") {
            return Err(BloomError::InvalidInput(format!("{name} is not a .zip file")));
        }
        let f = std::fs::File::open(src)?;
        let mut zip = zip::ZipArchive::new(std::io::BufReader::new(f))
            .map_err(|_| BloomError::InvalidInput(format!("{name} is not a valid ZIP archive")))?;
        if zip.by_name("pack.mcmeta").is_err() {
            return Err(BloomError::InvalidInput(format!(
                "{name} is not a resource pack (pack.mcmeta not found at the root)"
            )));
        }
        std::fs::copy(src, dir.join(&name))?;
        n += 1;
    }
    Ok(n)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mods_toml_parsing() {
        let toml = r#"
modLoader="javafml"
authors="Global Team"
[[mods]]
modId="examplemod" # comment
version="1.2.3"
displayName="Example Mod"
description='''
A multi-line
description.
'''
[[dependencies.examplemod]]
modId="forge"
"#;
        let m = parse_mods_toml(toml);
        assert_eq!(m.id.as_deref(), Some("examplemod"));
        assert_eq!(m.version.as_deref(), Some("1.2.3"));
        assert_eq!(m.name.as_deref(), Some("Example Mod"));
        assert_eq!(m.description.as_deref(), Some("A multi-line\ndescription."));
        assert_eq!(m.authors, vec!["Global Team".to_string()]);
    }

    #[test]
    fn mcmeta_descriptions() {
        let (d, f) = parse_mcmeta(r#"{"pack":{"pack_format":34,"description":"§aGreen pack"}}"#.as_bytes());
        assert_eq!(d.as_deref(), Some("Green pack"));
        assert_eq!(f, Some(34));
        let (d, _) = parse_mcmeta(br#"{"pack":{"pack_format":1,"description":[{"text":"A"},{"text":"B","extra":[{"text":"C"}]}]}}"#);
        assert_eq!(d.as_deref(), Some("ABC"));
    }

    #[test]
    fn options_resource_pack_roundtrip() {
        let dir = tempfile::tempdir().unwrap();
        let options = dir.path().join("options.txt");
        std::fs::write(&options, "fov:0.0\nresourcePacks:[\"vanilla\"]\nlang:en_us\n").unwrap();
        assert_eq!(read_enabled_packs(&options), vec!["vanilla"]);
        write_enabled_packs(&options, &["vanilla".into(), "file/x.zip".into()]).unwrap();
        let text = std::fs::read_to_string(&options).unwrap();
        assert!(text.contains("fov:0.0"));
        assert!(text.contains("lang:en_us"));
        assert_eq!(read_enabled_packs(&options), vec!["vanilla", "file/x.zip"]);
    }
}
