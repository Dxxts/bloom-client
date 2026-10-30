//! Extension point between "the launch command is fully built" and "the
//! process is spawned".
//!
//! Future Bloom components (performance engine, client-side modifications,
//! in-game component updater) plug in here by implementing [`LaunchHook`]:
//! they receive the complete, mutable [`LaunchCommand`] and can add JVM
//! agents, system properties, classpath entries or environment variables
//! without the core launcher knowing about them. Milestone 01 ships no hooks;
//! vanilla launches go straight through.

use std::collections::BTreeMap;
use std::path::PathBuf;

use crate::errors::BloomResult;

/// Everything needed to spawn the game process.
#[derive(Debug, Clone)]
pub struct LaunchCommand {
    pub instance_id: String,
    pub version_id: String,
    pub java: PathBuf,
    pub java_major: u32,
    pub game_dir: PathBuf,
    pub jvm_args: Vec<String>,
    pub main_class: String,
    pub game_args: Vec<String>,
    pub env: BTreeMap<String, String>,
}

impl LaunchCommand {
    /// Full argument vector passed to the Java binary.
    pub fn argv(&self) -> Vec<String> {
        let mut v = self.jvm_args.clone();
        v.push(self.main_class.clone());
        v.extend(self.game_args.iter().cloned());
        v
    }
}

pub trait LaunchHook: Send + Sync {
    /// Stable identifier, used in logs.
    fn id(&self) -> &'static str;
    /// Called once per launch, in registration order.
    fn before_spawn(&self, command: &mut LaunchCommand) -> BloomResult<()>;
}

/// Hooks active for this build, in execution order.
pub fn registered() -> Vec<Box<dyn LaunchHook>> {
    Vec::new()
}

pub fn apply_all(command: &mut LaunchCommand) -> BloomResult<()> {
    for hook in registered() {
        tracing::debug!(hook = hook.id(), "applying launch hook");
        hook.before_spawn(command)?;
    }
    Ok(())
}
