//! Evaluation of Mojang's `rules` arrays (used by libraries and arguments).
//!
//! Semantics match the official launcher: with no rules the item applies;
//! otherwise it starts disallowed and every matching rule sets the outcome to
//! its action, so the last matching rule wins.

use std::collections::HashMap;

use serde::Deserialize;

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Rule {
    pub action: RuleAction,
    pub os: Option<OsRule>,
    pub features: Option<HashMap<String, bool>>,
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum RuleAction {
    Allow,
    Disallow,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OsRule {
    pub name: Option<String>,
    /// Regex matched against the OS version.
    pub version: Option<String>,
    pub version_range: Option<VersionRange>,
    pub arch: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct VersionRange {
    /// Inclusive lower bound.
    pub min: Option<String>,
    /// Exclusive upper bound.
    pub max: Option<String>,
}

/// Launcher features that arguments can be conditioned on.
#[derive(Debug, Clone, Default)]
pub struct Features {
    pub demo_user: bool,
    pub custom_resolution: bool,
}

impl Features {
    fn get(&self, key: &str) -> bool {
        match key {
            "is_demo_user" => self.demo_user,
            "has_custom_resolution" => self.custom_resolution,
            // Quick Play and any unknown future feature are reported as
            // unsupported, which is always a safe answer.
            _ => false,
        }
    }
}

/// Host description used for matching; injectable for tests.
#[derive(Debug, Clone)]
pub struct Host {
    pub os: String,
    pub arch: String,
    pub version: String,
}

impl Host {
    pub fn current() -> Self {
        Self {
            os: crate::system::mojang_os_name().to_string(),
            arch: crate::system::mojang_arch().to_string(),
            version: crate::system::os_version(),
        }
    }
}

pub fn rules_allow(rules: &[Rule], features: &Features) -> bool {
    rules_allow_on(rules, features, &host())
}

fn host() -> Host {
    use std::sync::OnceLock;
    static HOST: OnceLock<Host> = OnceLock::new();
    HOST.get_or_init(Host::current).clone()
}

pub fn rules_allow_on(rules: &[Rule], features: &Features, host: &Host) -> bool {
    if rules.is_empty() {
        return true;
    }
    let mut allowed = false;
    for rule in rules {
        if rule_matches(rule, features, host) {
            allowed = rule.action == RuleAction::Allow;
        }
    }
    allowed
}

fn rule_matches(rule: &Rule, features: &Features, host: &Host) -> bool {
    if let Some(os) = &rule.os {
        if let Some(name) = &os.name {
            if name != &host.os {
                return false;
            }
        }
        if let Some(arch) = &os.arch {
            let arch_ok = arch == &host.arch
                || (arch == "x86" && host.arch == "x86")
                || (arch == "x86_64" && host.arch == "x86_64")
                || (arch == "arm64" && host.arch == "arm64")
                || (arch == "aarch64" && host.arch == "arm64");
            if !arch_ok {
                return false;
            }
        }
        if let Some(pattern) = &os.version {
            match regex::Regex::new(pattern) {
                Ok(re) if re.is_match(&host.version) => {}
                _ => return false,
            }
        }
        if let Some(range) = &os.version_range {
            if let Some(min) = &range.min {
                if compare_versions(&host.version, min) == std::cmp::Ordering::Less {
                    return false;
                }
            }
            if let Some(max) = &range.max {
                if compare_versions(&host.version, max) != std::cmp::Ordering::Less {
                    return false;
                }
            }
        }
    }
    if let Some(required) = &rule.features {
        for (key, value) in required {
            if features.get(key) != *value {
                return false;
            }
        }
    }
    true
}

/// Compares dotted numeric versions (`10.0.22631` vs `10.0.17134`); missing
/// or non-numeric components compare as zero.
pub fn compare_versions(a: &str, b: &str) -> std::cmp::Ordering {
    let parse = |s: &str| -> Vec<u64> {
        s.split(['.', '-', '_'])
            .map(|p| p.chars().take_while(|c| c.is_ascii_digit()).collect::<String>().parse().unwrap_or(0))
            .collect()
    };
    let (pa, pb) = (parse(a), parse(b));
    for i in 0..pa.len().max(pb.len()) {
        let (x, y) = (pa.get(i).copied().unwrap_or(0), pb.get(i).copied().unwrap_or(0));
        match x.cmp(&y) {
            std::cmp::Ordering::Equal => continue,
            o => return o,
        }
    }
    std::cmp::Ordering::Equal
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rules(json: &str) -> Vec<Rule> {
        serde_json::from_str(json).unwrap()
    }

    fn win11() -> Host {
        Host { os: "windows".into(), arch: "x86_64".into(), version: "10.0.22631".into() }
    }

    #[test]
    fn empty_rules_allow() {
        assert!(rules_allow_on(&[], &Features::default(), &win11()));
    }

    #[test]
    fn os_name_rules() {
        let r = rules(r#"[{"action":"allow","os":{"name":"osx"}}]"#);
        assert!(!rules_allow_on(&r, &Features::default(), &win11()));
        let r = rules(r#"[{"action":"allow"},{"action":"disallow","os":{"name":"osx"}}]"#);
        assert!(rules_allow_on(&r, &Features::default(), &win11()));
    }

    #[test]
    fn version_range_rules() {
        let zgc = rules(r#"[{"action":"allow","os":{"name":"windows","versionRange":{"min":"10.0.17134"}}}]"#);
        let g1 = rules(r#"[{"action":"allow","os":{"name":"windows","versionRange":{"max":"10.0.17134"}}}]"#);
        assert!(rules_allow_on(&zgc, &Features::default(), &win11()));
        assert!(!rules_allow_on(&g1, &Features::default(), &win11()));
        let old = Host { version: "10.0.16299".into(), ..win11() };
        assert!(!rules_allow_on(&zgc, &Features::default(), &old));
        assert!(rules_allow_on(&g1, &Features::default(), &old));
    }

    #[test]
    fn version_regex_and_arch() {
        let r = rules(r#"[{"action":"allow","os":{"name":"windows","version":"^10\\."}}]"#);
        assert!(rules_allow_on(&r, &Features::default(), &win11()));
        let r = rules(r#"[{"action":"allow","os":{"arch":"x86"}}]"#);
        assert!(!rules_allow_on(&r, &Features::default(), &win11()));
    }

    #[test]
    fn feature_rules() {
        let r = rules(r#"[{"action":"allow","features":{"has_custom_resolution":true}}]"#);
        assert!(!rules_allow_on(&r, &Features::default(), &win11()));
        let f = Features { custom_resolution: true, ..Default::default() };
        assert!(rules_allow_on(&r, &f, &win11()));
        let demo = rules(r#"[{"action":"allow","features":{"is_demo_user":true}}]"#);
        assert!(!rules_allow_on(&demo, &f, &win11()));
    }

    #[test]
    fn version_compare() {
        use std::cmp::Ordering::*;
        assert_eq!(compare_versions("10.0.22631", "10.0.17134"), Greater);
        assert_eq!(compare_versions("10.0", "10.0.0"), Equal);
        assert_eq!(compare_versions("1.8.0_51", "1.8.0_100"), Less);
    }
}
