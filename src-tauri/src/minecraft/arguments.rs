//! JVM and game argument construction from version metadata.
//!
//! Nothing here is version specific: arguments come from the manifest
//! (`arguments.jvm/game` or legacy `minecraftArguments`) and placeholders are
//! substituted from [`LaunchVars`].

use std::collections::HashMap;

use super::rules::Features;
use super::version::VersionJson;

pub type LaunchVars = HashMap<&'static str, String>;

/// Replaces every `${name}` with its value. Unknown placeholders are kept
/// verbatim so problems are visible in logs rather than silently dropped.
pub fn substitute(input: &str, vars: &LaunchVars) -> String {
    let mut out = String::with_capacity(input.len());
    let mut rest = input;
    while let Some(start) = rest.find("${") {
        out.push_str(&rest[..start]);
        let after = &rest[start + 2..];
        match after.find('}') {
            Some(end) => {
                let key = &after[..end];
                match vars.get(key) {
                    Some(v) => out.push_str(v),
                    None => {
                        out.push_str("${");
                        out.push_str(key);
                        out.push('}');
                    }
                }
                rest = &after[end + 1..];
            }
            None => {
                out.push_str(&rest[start..]);
                rest = "";
            }
        }
    }
    out.push_str(rest);
    out
}

pub struct BuiltArguments {
    pub jvm: Vec<String>,
    pub game: Vec<String>,
}

/// Builds JVM and game arguments.
///
/// `use_default_user_jvm` adds the manifest's `default-user-jvm` flags (GC
/// tuning) except memory flags, which Bloom always controls.
pub fn build(
    version: &VersionJson,
    vars: &LaunchVars,
    features: &Features,
    use_default_user_jvm: bool,
) -> BuiltArguments {
    let mut jvm = Vec::new();
    let mut game = Vec::new();

    match &version.arguments {
        Some(args) if !args.jvm.is_empty() || !args.game.is_empty() => {
            if use_default_user_jvm {
                for a in args.default_user_jvm.iter().flat_map(|a| a.resolve(features)) {
                    let lower = a.to_ascii_lowercase();
                    if !lower.starts_with("-xmx") && !lower.starts_with("-xms") {
                        jvm.push(substitute(&a, vars));
                    }
                }
            }
            if args.jvm.is_empty() {
                jvm.extend(legacy_jvm_args().iter().map(|a| substitute(a, vars)));
            } else {
                jvm.extend(args.jvm.iter().flat_map(|a| a.resolve(features)).map(|a| substitute(&a, vars)));
            }
            game.extend(args.game.iter().flat_map(|a| a.resolve(features)).map(|a| substitute(&a, vars)));
            // Profiles merged from a loader may already carry game arguments
            // in the legacy string as well.
            if let Some(legacy) = &version.minecraft_arguments {
                if args.game.is_empty() {
                    game.extend(legacy.split_whitespace().map(|a| substitute(a, vars)));
                }
            }
        }
        _ => {
            jvm.extend(legacy_jvm_args().iter().map(|a| substitute(a, vars)));
            if let Some(legacy) = &version.minecraft_arguments {
                game.extend(legacy.split_whitespace().map(|a| substitute(a, vars)));
            }
            if features.custom_resolution {
                game.extend([
                    "--width".to_string(),
                    vars.get("resolution_width").cloned().unwrap_or_default(),
                    "--height".to_string(),
                    vars.get("resolution_height").cloned().unwrap_or_default(),
                ]);
            }
        }
    }

    BuiltArguments { jvm, game }
}

/// Defaults used by the official launcher for versions that predate the
/// `arguments` object (pre-1.13).
fn legacy_jvm_args() -> Vec<&'static str> {
    let mut v = Vec::new();
    match crate::system::mojang_os_name() {
        "windows" => {
            v.push("-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump");
            if crate::system::os_version().starts_with("10.") {
                v.push("-Dos.name=Windows 10");
                v.push("-Dos.version=10.0");
            }
        }
        "osx" => v.push("-XstartOnFirstThread"),
        _ => {}
    }
    if crate::system::mojang_arch() == "x86" {
        v.push("-Xss1M");
    }
    v.extend([
        "-Djava.library.path=${natives_directory}",
        "-Dminecraft.launcher.brand=${launcher_name}",
        "-Dminecraft.launcher.version=${launcher_version}",
        "-cp",
        "${classpath}",
    ]);
    v
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn substitutes_known_and_keeps_unknown() {
        let mut vars = LaunchVars::new();
        vars.insert("a", "1".into());
        assert_eq!(substitute("x${a}y${b}z", &vars), "x1y${b}z");
        assert_eq!(substitute("${a}", &vars), "1");
        assert_eq!(substitute("broken${a", &vars), "broken${a");
        assert_eq!(substitute("-Djava.library.path=${a}/java", &vars), "-Djava.library.path=1/java");
    }

    #[test]
    fn legacy_versions_get_default_jvm_args_and_resolution() {
        let v: VersionJson = serde_json::from_str(
            r#"{"id":"1.8.9","mainClass":"net.minecraft.client.main.Main",
                "minecraftArguments":"--username ${auth_player_name} --version ${version_name}"}"#,
        )
        .unwrap();
        let mut vars = LaunchVars::new();
        vars.insert("auth_player_name", "Steve".into());
        vars.insert("version_name", "1.8.9".into());
        vars.insert("classpath", "a.jar".into());
        vars.insert("natives_directory", "/n".into());
        vars.insert("resolution_width", "800".into());
        vars.insert("resolution_height", "600".into());
        let f = Features { custom_resolution: true, ..Default::default() };
        let built = build(&v, &vars, &f, true);
        assert_eq!(built.game, vec!["--username", "Steve", "--version", "1.8.9", "--width", "800", "--height", "600"]);
        assert!(built.jvm.contains(&"-Djava.library.path=/n".to_string()));
        let cp = built.jvm.iter().position(|a| a == "-cp").unwrap();
        assert_eq!(built.jvm[cp + 1], "a.jar");
    }

    #[test]
    fn default_user_jvm_skips_memory_flags() {
        let v: VersionJson = serde_json::from_str(
            r#"{"id":"x","mainClass":"M","arguments":{"game":[],"jvm":["-cp","${classpath}"],
                "default-user-jvm":[{"value":["-Xms2G","-Xmx4G","-XX:+UseStringDeduplication"]}]}}"#,
        )
        .unwrap();
        let vars = LaunchVars::new();
        let built = build(&v, &vars, &Features::default(), true);
        assert_eq!(built.jvm[0], "-XX:+UseStringDeduplication");
        assert!(!built.jvm.iter().any(|a| a.starts_with("-Xm")));
        let built = build(&v, &vars, &Features::default(), false);
        assert_eq!(built.jvm[0], "-cp");
    }
}
