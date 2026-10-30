//! Registry of launch sessions, one per instance. It is the single source of
//! truth for "is this instance busy?", which prevents accidental duplicate
//! launches, and it keeps a bounded buffer of recent game output so the log
//! viewer can be opened at any time.

use std::collections::{HashMap, VecDeque};
use std::sync::Mutex;

use tauri::{AppHandle, Emitter};
use tokio_util::sync::CancellationToken;

use crate::errors::{BloomError, BloomResult};
use crate::models::{GameSession, LogLine, ProgressEvent, Stage};
use crate::services::events;

const MAX_LOG_LINES: usize = 5000;

struct Entry {
    session: GameSession,
    cancel: CancellationToken,
    logs: VecDeque<LogLine>,
}

#[derive(Default)]
pub struct SessionRegistry {
    inner: Mutex<HashMap<String, Entry>>,
}

fn is_active(stage: Stage) -> bool {
    !matches!(stage, Stage::Exited | Stage::Crashed | Stage::Failed | Stage::Cancelled)
}

impl SessionRegistry {
    fn lock(&self) -> std::sync::MutexGuard<'_, HashMap<String, Entry>> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Registers a new session or fails if one is already in progress for
    /// this instance.
    pub fn begin(&self, app: &AppHandle, instance_id: &str) -> BloomResult<CancellationToken> {
        let mut map = self.lock();
        if let Some(existing) = map.get(instance_id) {
            if is_active(existing.session.stage) {
                return Err(BloomError::AlreadyRunning(match existing.session.stage {
                    Stage::Running => "this instance is already running".into(),
                    _ => "this instance is already being prepared".into(),
                }));
            }
        }
        let cancel = CancellationToken::new();
        let session = GameSession {
            instance_id: instance_id.to_string(),
            stage: Stage::Preparing,
            message: "Preparing...".into(),
            pid: None,
            started_at: None,
            exit_code: None,
            last_progress: None,
        };
        let _ = app.emit(events::GAME_STATE, &session);
        map.insert(instance_id.to_string(), Entry { session, cancel: cancel.clone(), logs: VecDeque::new() });
        Ok(cancel)
    }

    pub fn set_stage(&self, app: &AppHandle, instance_id: &str, stage: Stage, message: impl Into<String>) {
        let snapshot = {
            let mut map = self.lock();
            let Some(e) = map.get_mut(instance_id) else { return };
            e.session.stage = stage;
            e.session.message = message.into();
            if !matches!(stage, Stage::CheckingFiles | Stage::Downloading | Stage::Installing) {
                e.session.last_progress = None;
            }
            e.session.clone()
        };
        let _ = app.emit(events::GAME_STATE, snapshot);
    }

    /// Stores the latest progress snapshot; the event itself is emitted by
    /// the caller (it is the same payload used for standalone installs).
    pub fn set_progress(&self, instance_id: &str, progress: &ProgressEvent) {
        let mut map = self.lock();
        if let Some(e) = map.get_mut(instance_id) {
            e.session.stage = progress.stage;
            e.session.message = progress.message.clone();
            e.session.last_progress = Some(progress.clone());
        }
    }

    pub fn set_running(&self, app: &AppHandle, instance_id: &str, pid: Option<u32>, started_at: i64) {
        let snapshot = {
            let mut map = self.lock();
            let Some(e) = map.get_mut(instance_id) else { return };
            e.session.stage = Stage::Running;
            e.session.message = "Game Running!".into();
            e.session.pid = pid;
            e.session.started_at = Some(started_at);
            e.session.last_progress = None;
            e.session.clone()
        };
        let _ = app.emit(events::GAME_STATE, snapshot);
    }

    pub fn finish(&self, app: &AppHandle, instance_id: &str, stage: Stage, message: impl Into<String>, exit_code: Option<i32>) {
        let snapshot = {
            let mut map = self.lock();
            let Some(e) = map.get_mut(instance_id) else { return };
            e.session.stage = stage;
            e.session.message = message.into();
            e.session.exit_code = exit_code;
            e.session.pid = None;
            e.session.last_progress = None;
            e.session.clone()
        };
        let _ = app.emit(events::GAME_STATE, snapshot);
    }

    pub fn push_logs(&self, instance_id: &str, lines: &[LogLine]) {
        let mut map = self.lock();
        if let Some(e) = map.get_mut(instance_id) {
            for l in lines {
                if e.logs.len() == MAX_LOG_LINES {
                    e.logs.pop_front();
                }
                e.logs.push_back(l.clone());
            }
        }
    }

    pub fn logs(&self, instance_id: &str) -> Vec<LogLine> {
        self.lock().get(instance_id).map(|e| e.logs.iter().cloned().collect()).unwrap_or_default()
    }

    pub fn clear_logs(&self, instance_id: &str) {
        if let Some(e) = self.lock().get_mut(instance_id) {
            e.logs.clear();
        }
    }

    pub fn list(&self) -> Vec<GameSession> {
        self.lock().values().map(|e| e.session.clone()).collect()
    }

    pub fn is_busy(&self, instance_id: &str) -> bool {
        self.lock().get(instance_id).is_some_and(|e| is_active(e.session.stage))
    }

    pub fn any_active(&self) -> bool {
        self.lock().values().any(|e| is_active(e.session.stage))
    }

    /// Requests cancellation (aborts preparation or terminates the game).
    pub fn cancel(&self, instance_id: &str) -> bool {
        match self.lock().get(instance_id) {
            Some(e) if is_active(e.session.stage) => {
                e.cancel.cancel();
                true
            }
            _ => false,
        }
    }

    /// Drops a finished session (e.g. when its instance is deleted).
    pub fn remove(&self, instance_id: &str) {
        let mut map = self.lock();
        if map.get(instance_id).is_some_and(|e| !is_active(e.session.stage)) {
            map.remove(instance_id);
        }
    }
}
