//! Strongly typed model of Mojang's version JSON (`versions/<id>/<id>.json`).
//!
//! Only fields Bloom uses are modelled; unknown fields are ignored so new
//! manifest revisions do not break parsing.

use std::collections::HashMap;

use serde::Deserialize;

use super::rules::{rules_allow, Features, Rule};
use crate::errors::{BloomError, BloomResult};

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VersionJson {
    pub id: String,
    #[serde(rename = "type")]
    pub release_type: Option<String>,
    pub release_time: Option<String>,
    #[serde(default)]
    pub main_class: String,
    pub inherits_from: Option<String>,
    pub arguments: Option<Arguments>,
    pub minecraft_arguments: Option<String>,
    pub asset_index: Option<AssetIndexRef>,
    pub assets: Option<String>,
    pub downloads: Option<VersionDownloads>,
    #[serde(default)]
    pub libraries: Vec<Library>,
    pub java_version: Option<JavaVersionReq>,
    pub logging: Option<Logging>,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct Arguments {
    #[serde(default)]
    pub game: Vec<ArgValue>,
    #[serde(default)]
    pub jvm: Vec<ArgValue>,
    /// JVM arguments the official launcher pre-fills for users that did not
    /// customize theirs (memory and GC flags).
    #[serde(default, rename = "default-user-jvm")]
    pub default_user_jvm: Vec<ArgValue>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(untagged)]
pub enum ArgValue {
    Plain(String),
    Conditional {
        /// Absent in some entries (e.g. the first `default-user-jvm` block),
        /// meaning the value always applies.
        #[serde(default)]
        rules: Vec<Rule>,
        value: StringOrVec,
    },
}

#[derive(Debug, Clone, Deserialize)]
#[serde(untagged)]
pub enum StringOrVec {
    One(String),
    Many(Vec<String>),
}

impl ArgValue {
    /// Expands the argument into zero or more raw strings, evaluating rules.
    pub fn resolve(&self, features: &Features) -> Vec<String> {
        match self {
            ArgValue::Plain(s) => vec![s.clone()],
            ArgValue::Conditional { rules, value } => {
                if !rules_allow(rules, features) {
                    return Vec::new();
                }
                match value {
                    StringOrVec::One(s) => vec![s.clone()],
                    StringOrVec::Many(v) => v.clone(),
                }
            }
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AssetIndexRef {
    pub id: String,
    pub sha1: String,
    pub size: u64,
    pub url: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct VersionDownloads {
    pub client: Option<DownloadInfo>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct DownloadInfo {
    pub path: Option<String>,
    pub sha1: Option<String>,
    pub size: Option<u64>,
    pub url: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Library {
    pub name: String,
    pub downloads: Option<LibraryDownloads>,
    pub rules: Option<Vec<Rule>>,
    pub natives: Option<HashMap<String, String>>,
    pub extract: Option<ExtractRules>,
    /// Maven repository base used by third-party profiles without
    /// `downloads` (e.g. mod loaders).
    pub url: Option<String>,
    pub sha1: Option<String>,
    pub size: Option<u64>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct LibraryDownloads {
    pub artifact: Option<DownloadInfo>,
    pub classifiers: Option<HashMap<String, DownloadInfo>>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ExtractRules {
    #[serde(default)]
    pub exclude: Vec<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JavaVersionReq {
    pub component: String,
    pub major_version: u32,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Logging {
    pub client: Option<LoggingEntry>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct LoggingEntry {
    pub argument: String,
    pub file: LoggingFile,
}

#[derive(Debug, Clone, Deserialize)]
pub struct LoggingFile {
    pub id: String,
    pub sha1: String,
    pub size: u64,
    pub url: String,
}

/// Parsed maven coordinate `group:artifact:version[:classifier][@ext]`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MavenCoord {
    pub group: String,
    pub artifact: String,
    pub version: String,
    pub classifier: Option<String>,
    pub ext: String,
}

impl MavenCoord {
    pub fn parse(name: &str) -> BloomResult<Self> {
        let (coords, ext) = match name.split_once('@') {
            Some((c, e)) => (c, e.to_string()),
            None => (name, "jar".to_string()),
        };
        let parts: Vec<&str> = coords.split(':').collect();
        if parts.len() < 3 || parts.iter().any(|p| p.is_empty()) {
            return Err(BloomError::Parse(format!("invalid library name: {name}")));
        }
        Ok(Self {
            group: parts[0].to_string(),
            artifact: parts[1].to_string(),
            version: parts[2].to_string(),
            classifier: parts.get(3).map(|s| s.to_string()),
            ext,
        })
    }

    /// Relative repository path, e.g. `org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar`.
    pub fn path(&self) -> String {
        self.path_with_classifier(self.classifier.as_deref())
    }

    pub fn path_with_classifier(&self, classifier: Option<&str>) -> String {
        let file = match classifier {
            Some(c) => format!("{}-{}-{}.{}", self.artifact, self.version, c, self.ext),
            None => format!("{}-{}.{}", self.artifact, self.version, self.ext),
        };
        format!("{}/{}/{}/{}", self.group.replace('.', "/"), self.artifact, self.version, file)
    }

    /// Key used to deduplicate libraries across a merged profile
    /// (`group:artifact:classifier`).
    pub fn identity(&self) -> String {
        format!("{}:{}:{}", self.group, self.artifact, self.classifier.clone().unwrap_or_default())
    }
}

impl Library {
    pub fn applies(&self, features: &Features) -> bool {
        match &self.rules {
            Some(rules) => rules_allow(rules, features),
            None => true,
        }
    }

    /// Classifier of the legacy native artifact for the current OS, if any.
    pub fn native_classifier(&self) -> Option<String> {
        let natives = self.natives.as_ref()?;
        let key = crate::system::mojang_os_name();
        natives
            .get(key)
            .map(|c| c.replace("${arch}", crate::system::arch_bits()))
    }

    /// Whether a modern (1.19+) natives library targets a different CPU
    /// architecture than ours, based on its classifier suffix.
    pub fn is_foreign_arch_native(&self) -> bool {
        let Ok(coord) = MavenCoord::parse(&self.name) else { return false };
        let Some(classifier) = coord.classifier else { return false };
        if !classifier.starts_with("natives-") {
            return false;
        }
        let arch = crate::system::mojang_arch();
        let suffix_arch = if classifier.ends_with("-arm64") || classifier.ends_with("-aarch64") {
            Some("arm64")
        } else if classifier.ends_with("-x86") {
            Some("x86")
        } else {
            None
        };
        match suffix_arch {
            Some(a) => a != arch,
            None => false,
        }
    }
}

impl VersionJson {
    pub fn java_requirement(&self) -> (u32, String) {
        match &self.java_version {
            Some(j) => (j.major_version, j.component.clone()),
            // Versions without javaVersion predate 1.17 and run on Java 8.
            None => (8, "jre-legacy".to_string()),
        }
    }

    pub fn asset_index_id(&self) -> String {
        self.asset_index
            .as_ref()
            .map(|a| a.id.clone())
            .or_else(|| self.assets.clone())
            .unwrap_or_else(|| "legacy".into())
    }

    /// Merges a parent profile (from `inheritsFrom`) into this one, following
    /// the semantics of the official launcher: the child wins on scalar
    /// fields, arguments are concatenated (parent first) and libraries are
    /// combined with the child taking precedence on identical coordinates.
    pub fn merge_parent(mut self, parent: VersionJson) -> VersionJson {
        let mut libraries = self.libraries.clone();
        let child_ids: std::collections::HashSet<String> = libraries
            .iter()
            .filter_map(|l| MavenCoord::parse(&l.name).ok().map(|c| c.identity()))
            .collect();
        for lib in parent.libraries {
            let id = MavenCoord::parse(&lib.name).map(|c| c.identity()).unwrap_or_default();
            if !child_ids.contains(&id) {
                libraries.push(lib);
            }
        }
        self.libraries = libraries;
        self.arguments = match (parent.arguments, self.arguments.take()) {
            (Some(p), Some(c)) => Some(Arguments {
                game: [p.game, c.game].concat(),
                jvm: [p.jvm, c.jvm].concat(),
                default_user_jvm: if c.default_user_jvm.is_empty() { p.default_user_jvm } else { c.default_user_jvm },
            }),
            (p, c) => c.or(p),
        };
        if self.main_class.is_empty() {
            self.main_class = parent.main_class;
        }
        self.minecraft_arguments = self.minecraft_arguments.or(parent.minecraft_arguments);
        self.asset_index = self.asset_index.or(parent.asset_index);
        self.assets = self.assets.or(parent.assets);
        self.downloads = self.downloads.or(parent.downloads);
        self.java_version = self.java_version.or(parent.java_version);
        self.logging = self.logging.or(parent.logging);
        self.release_type = self.release_type.or(parent.release_type);
        self.release_time = self.release_time.or(parent.release_time);
        self.inherits_from = None;
        self
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn maven_paths() {
        let c = MavenCoord::parse("org.lwjgl:lwjgl:3.3.3:natives-windows").unwrap();
        assert_eq!(c.path(), "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar");
        let c = MavenCoord::parse("net.fabricmc:intermediary:1.21@zip").unwrap();
        assert_eq!(c.path(), "net/fabricmc/intermediary/1.21/intermediary-1.21.zip");
        assert!(MavenCoord::parse("broken").is_err());
    }

    #[test]
    fn parses_modern_and_legacy_arguments() {
        let modern = r#"{
            "id": "x", "mainClass": "M",
            "arguments": {
                "game": ["--username", "${auth_player_name}",
                    {"rules": [{"action": "allow", "features": {"has_custom_resolution": true}}],
                     "value": ["--width", "${resolution_width}"]}],
                "jvm": [{"rules": [{"action": "allow", "os": {"name": "osx"}}], "value": "-XstartOnFirstThread"}, "-cp", "${classpath}"]
            }
        }"#;
        let v: VersionJson = serde_json::from_str(modern).unwrap();
        let args = v.arguments.unwrap();
        let f = Features { custom_resolution: true, ..Default::default() };
        let game: Vec<String> = args.game.iter().flat_map(|a| a.resolve(&f)).collect();
        assert_eq!(game, vec!["--username", "${auth_player_name}", "--width", "${resolution_width}"]);
        let legacy = r#"{"id": "1.8.9", "mainClass": "M", "minecraftArguments": "--username ${auth_player_name}"}"#;
        let v: VersionJson = serde_json::from_str(legacy).unwrap();
        assert_eq!(v.java_requirement(), (8, "jre-legacy".to_string()));
    }

    #[test]
    fn foreign_arch_natives_are_detected() {
        let lib = |name: &str| Library {
            name: name.into(),
            downloads: None,
            rules: None,
            natives: None,
            extract: None,
            url: None,
            sha1: None,
            size: None,
        };
        if crate::system::mojang_arch() == "x86_64" {
            assert!(lib("org.lwjgl:lwjgl:3.3.3:natives-windows-arm64").is_foreign_arch_native());
            assert!(lib("org.lwjgl:lwjgl:3.3.3:natives-windows-x86").is_foreign_arch_native());
            assert!(!lib("org.lwjgl:lwjgl:3.3.3:natives-windows").is_foreign_arch_native());
        }
        assert!(!lib("org.lwjgl:lwjgl:3.3.3").is_foreign_arch_native());
    }
}
