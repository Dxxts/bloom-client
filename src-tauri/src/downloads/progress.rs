//! Real progress tracking. Counters are updated by download workers and a
//! single reporter task turns them into throttled UI events, so the frontend
//! never receives more than ~8 events per second regardless of file count.

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use tokio_util::sync::CancellationToken;

use crate::models::{ProgressEvent, Stage};

pub type ProgressSink = Arc<dyn Fn(ProgressEvent) + Send + Sync>;

pub struct ProgressTracker {
    task_id: String,
    sink: ProgressSink,
    stage: Mutex<(Stage, String)>,
    pub files_done: AtomicU64,
    pub files_total: AtomicU64,
    pub bytes_done: AtomicU64,
    pub bytes_total: AtomicU64,
    current_file: Mutex<Option<String>>,
    speed: Mutex<SpeedWindow>,
}

struct SpeedWindow {
    last_bytes: u64,
    last_at: Instant,
    bps: f64,
}

impl ProgressTracker {
    pub fn new(task_id: impl Into<String>, sink: ProgressSink) -> Arc<Self> {
        Arc::new(Self {
            task_id: task_id.into(),
            sink,
            stage: Mutex::new((Stage::Preparing, String::new())),
            files_done: AtomicU64::new(0),
            files_total: AtomicU64::new(0),
            bytes_done: AtomicU64::new(0),
            bytes_total: AtomicU64::new(0),
            current_file: Mutex::new(None),
            speed: Mutex::new(SpeedWindow { last_bytes: 0, last_at: Instant::now(), bps: 0.0 }),
        })
    }

    /// Switches to a new stage, resetting counters, and emits immediately.
    pub fn stage(&self, stage: Stage, message: impl Into<String>) {
        *self.stage.lock().unwrap_or_else(|e| e.into_inner()) = (stage, message.into());
        self.files_done.store(0, Ordering::Relaxed);
        self.files_total.store(0, Ordering::Relaxed);
        self.bytes_done.store(0, Ordering::Relaxed);
        self.bytes_total.store(0, Ordering::Relaxed);
        *self.current_file.lock().unwrap_or_else(|e| e.into_inner()) = None;
        {
            let mut s = self.speed.lock().unwrap_or_else(|e| e.into_inner());
            *s = SpeedWindow { last_bytes: 0, last_at: Instant::now(), bps: 0.0 };
        }
        self.emit();
    }

    /// Updates only the message of the current stage.
    pub fn message(&self, message: impl Into<String>) {
        self.stage.lock().unwrap_or_else(|e| e.into_inner()).1 = message.into();
        self.emit();
    }

    pub fn set_totals(&self, files: u64, bytes: u64) {
        self.files_total.store(files, Ordering::Relaxed);
        self.bytes_total.store(bytes, Ordering::Relaxed);
    }

    pub fn add_bytes(&self, n: u64) {
        self.bytes_done.fetch_add(n, Ordering::Relaxed);
    }

    pub fn sub_bytes(&self, n: u64) {
        let mut current = self.bytes_done.load(Ordering::Relaxed);
        loop {
            match self.bytes_done.compare_exchange_weak(
                current,
                current.saturating_sub(n),
                Ordering::Relaxed,
                Ordering::Relaxed,
            ) {
                Ok(_) => break,
                Err(actual) => current = actual,
            }
        }
    }

    pub fn file_done(&self) {
        self.files_done.fetch_add(1, Ordering::Relaxed);
    }

    pub fn set_current(&self, name: &str) {
        *self.current_file.lock().unwrap_or_else(|e| e.into_inner()) = Some(name.to_string());
    }

    pub fn snapshot(&self) -> ProgressEvent {
        let (stage, message) = self.stage.lock().unwrap_or_else(|e| e.into_inner()).clone();
        let bytes_done = self.bytes_done.load(Ordering::Relaxed);
        let speed = {
            let mut s = self.speed.lock().unwrap_or_else(|e| e.into_inner());
            let elapsed = s.last_at.elapsed().as_secs_f64();
            if elapsed >= 0.5 {
                let delta = bytes_done.saturating_sub(s.last_bytes) as f64;
                let instant = delta / elapsed;
                // Exponential smoothing keeps the number readable.
                s.bps = if s.bps == 0.0 { instant } else { s.bps * 0.6 + instant * 0.4 };
                s.last_bytes = bytes_done;
                s.last_at = Instant::now();
            }
            s.bps as u64
        };
        ProgressEvent {
            task_id: self.task_id.clone(),
            stage,
            message,
            files_done: self.files_done.load(Ordering::Relaxed),
            files_total: self.files_total.load(Ordering::Relaxed),
            bytes_done,
            bytes_total: self.bytes_total.load(Ordering::Relaxed),
            speed_bps: speed,
            current_file: self.current_file.lock().unwrap_or_else(|e| e.into_inner()).clone(),
        }
    }

    pub fn emit(&self) {
        (self.sink)(self.snapshot());
    }

    /// Spawns a reporter that emits a snapshot every 125 ms until `stop` is
    /// cancelled. Emits a final snapshot when stopping.
    pub fn spawn_reporter(self: &Arc<Self>, stop: CancellationToken) -> tokio::task::JoinHandle<()> {
        let me = self.clone();
        tokio::spawn(async move {
            let mut ticker = tokio::time::interval(Duration::from_millis(125));
            ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            loop {
                tokio::select! {
                    _ = stop.cancelled() => { me.emit(); break; }
                    _ = ticker.tick() => me.emit(),
                }
            }
        })
    }
}
