//! Filesystem layout and path validation.
//!
//! All paths that originate from untrusted sources (remote manifests, archive
//! entries, frontend input) must go through [`safe_join`] or
//! [`validate_file_name`] before they touch the disk.

use std::path::{Component, Path, PathBuf};

use crate::errors::{BloomError, BloomResult};

/// Directories that hold shared, content-addressed game data. Mutable,
/// per-instance data lives exclusively inside each instance directory.
#[derive(Debug, Clone)]
pub struct GamePaths {
    pub root: PathBuf,
    pub instances: PathBuf,
}

impl GamePaths {
    pub fn new(root: PathBuf, instances: PathBuf) -> Self {
        Self { root, instances }
    }

    pub fn meta(&self) -> PathBuf {
        self.root.join("meta")
    }
    pub fn versions(&self) -> PathBuf {
        self.root.join("versions")
    }
    pub fn version_dir(&self, id: &str) -> BloomResult<PathBuf> {
        safe_join(&self.versions(), id)
    }
    pub fn libraries(&self) -> PathBuf {
        self.root.join("libraries")
    }
    pub fn assets(&self) -> PathBuf {
        self.root.join("assets")
    }
    pub fn runtimes(&self) -> PathBuf {
        self.root.join("runtimes")
    }
    pub fn skins(&self) -> PathBuf {
        self.root.join("cache").join("skins")
    }
    pub fn instance_dir(&self, id: &str) -> BloomResult<PathBuf> {
        validate_id(id)?;
        Ok(self.instances.join(id))
    }

    pub fn ensure(&self) -> BloomResult<()> {
        for dir in [
            self.meta(),
            self.versions(),
            self.libraries(),
            self.assets(),
            self.runtimes(),
            self.skins(),
            self.instances.clone(),
        ] {
            std::fs::create_dir_all(dir)?;
        }
        Ok(())
    }
}

/// Joins a relative, untrusted path onto `base`, rejecting absolute paths,
/// drive prefixes and any `..` component. Forward slashes are accepted on all
/// platforms because Mojang manifests always use them.
pub fn safe_join(base: &Path, relative: &str) -> BloomResult<PathBuf> {
    if relative.is_empty() {
        return Err(BloomError::InvalidInput("empty path".into()));
    }
    if relative.contains('\0') {
        return Err(BloomError::InvalidInput("path contains a NUL byte".into()));
    }
    let normalized = relative.replace('\\', "/");
    let mut out = base.to_path_buf();
    for part in normalized.split('/') {
        if part.is_empty() || part == "." {
            continue;
        }
        let comp_path = Path::new(part);
        let mut comps = comp_path.components();
        match (comps.next(), comps.next()) {
            (Some(Component::Normal(c)), None) => out.push(c),
            _ => {
                return Err(BloomError::InvalidInput(format!(
                    "unsafe path rejected: {relative}"
                )))
            }
        }
    }
    if out == base {
        return Err(BloomError::InvalidInput(format!("unsafe path rejected: {relative}")));
    }
    Ok(out)
}

/// Validates a single file name (no separators, no traversal, no reserved
/// Windows device names).
pub fn validate_file_name(name: &str) -> BloomResult<()> {
    const RESERVED: [&str; 22] = [
        "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7",
        "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    ];
    let invalid = name.is_empty()
        || name.len() > 255
        || name == "."
        || name == ".."
        || name.chars().any(|c| matches!(c, '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|') || c.is_control())
        || name.ends_with(' ')
        || name.ends_with('.');
    let stem = name.split('.').next().unwrap_or_default().to_ascii_uppercase();
    if invalid || RESERVED.contains(&stem.as_str()) {
        return Err(BloomError::InvalidInput(format!("invalid file name: {name}")));
    }
    Ok(())
}

/// Validates identifiers used as directory names (instance ids, version ids).
pub fn validate_id(id: &str) -> BloomResult<()> {
    let ok = !id.is_empty()
        && id.len() <= 128
        && id
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.' | ' ' | '+'))
        && id != "."
        && id != "..";
    if ok {
        Ok(())
    } else {
        Err(BloomError::InvalidInput(format!("invalid identifier: {id}")))
    }
}

/// Resolves a user-provided absolute directory path, making sure it is
/// absolute and does not contain parent traversal.
pub fn validate_user_dir(input: &str) -> BloomResult<PathBuf> {
    let p = PathBuf::from(input.trim());
    if !p.is_absolute() {
        return Err(BloomError::InvalidInput("the directory must be an absolute path".into()));
    }
    if p.components().any(|c| matches!(c, Component::ParentDir)) {
        return Err(BloomError::InvalidInput("the directory must not contain '..'".into()));
    }
    Ok(p)
}

/// Recursively computes the size of a directory. Errors on single entries are
/// ignored so that a locked file does not break the whole computation.
pub fn dir_size(path: &Path) -> u64 {
    let mut total = 0;
    let mut stack = vec![path.to_path_buf()];
    while let Some(dir) = stack.pop() {
        let Ok(entries) = std::fs::read_dir(&dir) else { continue };
        for entry in entries.flatten() {
            let Ok(meta) = entry.metadata() else { continue };
            if meta.is_dir() {
                stack.push(entry.path());
            } else {
                total += meta.len();
            }
        }
    }
    total
}

/// Writes a file atomically (temporary file + rename) so readers never see
/// a partially written file.
pub fn write_atomic(path: &Path, bytes: &[u8]) -> BloomResult<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let mut tmp_name = path.file_name().map(|n| n.to_os_string()).unwrap_or_default();
    tmp_name.push(format!(".tmp-{}", std::process::id()));
    let tmp = path.with_file_name(tmp_name);
    std::fs::write(&tmp, bytes)?;
    std::fs::rename(&tmp, path).inspect_err(|_| {
        let _ = std::fs::remove_file(&tmp);
    })?;
    Ok(())
}

/// Recursively copies a directory tree. Symlinks are skipped.
pub fn copy_dir_all(src: &Path, dst: &Path) -> BloomResult<()> {
    std::fs::create_dir_all(dst)?;
    for entry in std::fs::read_dir(src)? {
        let entry = entry?;
        let ty = entry.file_type()?;
        let target = dst.join(entry.file_name());
        if ty.is_dir() {
            copy_dir_all(&entry.path(), &target)?;
        } else if ty.is_file() {
            std::fs::copy(entry.path(), target)?;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn safe_join_accepts_nested_relative_paths() {
        let base = Path::new("/base");
        let p = safe_join(base, "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar").unwrap();
        assert!(p.starts_with(base));
        assert!(p.ends_with("lwjgl-3.3.3.jar"));
    }

    #[test]
    fn safe_join_rejects_traversal_and_absolute() {
        let base = Path::new("/base");
        assert!(safe_join(base, "../etc/passwd").is_err());
        assert!(safe_join(base, "a/../../b").is_err());
        assert!(safe_join(base, "").is_err());
        assert!(safe_join(base, "a\0b").is_err());
        #[cfg(windows)]
        {
            assert!(safe_join(base, "C:/Windows/system32").is_err());
            assert!(safe_join(base, "C:evil").is_err());
        }
    }

    #[test]
    fn file_name_validation() {
        assert!(validate_file_name("sodium-0.5.jar").is_ok());
        assert!(validate_file_name("Faithful 32x.zip").is_ok());
        assert!(validate_file_name("../x.jar").is_err());
        assert!(validate_file_name("a/b.jar").is_err());
        assert!(validate_file_name("CON.txt").is_err());
        assert!(validate_file_name("").is_err());
    }

    #[test]
    fn id_validation() {
        assert!(validate_id("1.21.4").is_ok());
        assert!(validate_id("24w14a").is_ok());
        assert!(validate_id("..").is_err());
        assert!(validate_id("a/b").is_err());
    }
}
