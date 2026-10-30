//! Parallel section processing.
//!
//! [`Scheduler`] runs jobs on a fixed number of threads and always picks the
//! pending job closest to the current focus (the camera). It guarantees:
//!
//! - **No duplicates:** one pending job per key; resubmitting replaces it.
//! - **World updates:** every submit bumps the key's generation. A job that
//!   finishes after a newer submit is reported as stale, never as current.
//! - **Cancellation:** cancelled keys are dropped from the queue, and running
//!   jobs see the cancellation through [`JobContext::is_cancelled`].
//! - **Bounded CPU use:** `threads` workers, by default one less than the
//!   available cores, so the caller's own thread keeps a core.
//!
//! In game, Minecraft's own section queue already provides these guarantees
//! for compile tasks, and Bloom meshes inside those tasks (see
//! `docs/ARCHITETTURA.md`). This scheduler drives the offline benchmark and is
//! the base for a fully native pipeline.

use std::collections::{BinaryHeap, HashMap};
use std::hash::Hash;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::JoinHandle;

/// Default worker count: available cores minus one, at least one.
pub fn default_threads() -> usize {
    std::thread::available_parallelism().map_or(1, |n| n.get().saturating_sub(1).max(1))
}

pub struct JobContext<'a> {
    cancelled: &'a AtomicBool,
}

impl JobContext<'_> {
    #[inline]
    pub fn is_cancelled(&self) -> bool {
        self.cancelled.load(Ordering::Relaxed)
    }
}

#[derive(Debug)]
pub struct Completed<K, R> {
    pub key: K,
    pub generation: u64,
    /// `None` if the job noticed it was cancelled and stopped early.
    pub result: Option<R>,
}

struct Entry<K> {
    priority: f32,
    seq: u64,
    key: K,
}

impl<K> PartialEq for Entry<K> {
    fn eq(&self, other: &Self) -> bool {
        self.seq == other.seq
    }
}
impl<K> Eq for Entry<K> {}
impl<K> PartialOrd for Entry<K> {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        Some(self.cmp(other))
    }
}
impl<K> Ord for Entry<K> {
    /// Max-heap order: lowest priority value (closest) first, then oldest.
    fn cmp(&self, other: &Self) -> std::cmp::Ordering {
        other.priority.total_cmp(&self.priority).then_with(|| other.seq.cmp(&self.seq))
    }
}

struct Pending<J> {
    job: J,
    generation: u64,
    seq: u64,
}

struct State<K, J> {
    heap: BinaryHeap<Entry<K>>,
    pending: HashMap<K, Pending<J>>,
    running: HashMap<K, Arc<AtomicBool>>,
    generations: HashMap<K, u64>,
    focus: [f64; 3],
    seq: u64,
    shutdown: bool,
}

type PriorityFn<K> = dyn Fn(&K, [f64; 3]) -> f32 + Send + Sync;
type WorkFn<K, J, R> = dyn Fn(&K, J, &JobContext<'_>) -> Option<R> + Send + Sync;

struct Shared<K, J> {
    state: Mutex<State<K, J>>,
    wake: Condvar,
}

pub struct Scheduler<K, J, R> {
    shared: Arc<Shared<K, J>>,
    priority: Arc<PriorityFn<K>>,
    workers: Vec<JoinHandle<()>>,
    results: Receiver<Completed<K, R>>,
}

impl<K, J, R> Scheduler<K, J, R>
where
    K: Copy + Eq + Hash + Send + 'static,
    J: Send + 'static,
    R: Send + 'static,
{
    pub fn new(
        threads: usize,
        priority: impl Fn(&K, [f64; 3]) -> f32 + Send + Sync + 'static,
        work: impl Fn(&K, J, &JobContext<'_>) -> Option<R> + Send + Sync + 'static,
    ) -> Self {
        let shared = Arc::new(Shared {
            state: Mutex::new(State {
                heap: BinaryHeap::new(),
                pending: HashMap::new(),
                running: HashMap::new(),
                generations: HashMap::new(),
                focus: [0.0; 3],
                seq: 0,
                shutdown: false,
            }),
            wake: Condvar::new(),
        });
        let work: Arc<WorkFn<K, J, R>> = Arc::new(work);
        let (tx, rx) = mpsc::channel();
        let workers = (0..threads.max(1))
            .map(|i| {
                let shared = Arc::clone(&shared);
                let work = Arc::clone(&work);
                let tx: Sender<Completed<K, R>> = tx.clone();
                std::thread::Builder::new()
                    .name(format!("bloom-worker-{i}"))
                    .spawn(move || worker_loop(&shared, &*work, &tx))
                    .expect("spawn worker")
            })
            .collect();
        Self { shared, priority: Arc::new(priority), workers, results: rx }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, State<K, J>> {
        self.shared.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Queues `job` for `key`, replacing any pending job for the same key.
    /// Returns the new generation of `key`.
    pub fn submit(&self, key: K, job: J) -> u64 {
        let mut st = self.lock();
        let generation = {
            let g = st.generations.entry(key).or_insert(0);
            *g += 1;
            *g
        };
        st.seq += 1;
        let seq = st.seq;
        let priority = (self.priority)(&key, st.focus);
        st.pending.insert(key, Pending { job, generation, seq });
        st.heap.push(Entry { priority, seq, key });
        drop(st);
        self.shared.wake.notify_one();
        generation
    }

    /// Drops the pending job for `key` and signals a running one to stop.
    pub fn cancel(&self, key: K) {
        let mut st = self.lock();
        st.pending.remove(&key);
        if let Some(flag) = st.running.get(&key) {
            flag.store(true, Ordering::Relaxed);
        }
        *st.generations.entry(key).or_insert(0) += 1;
    }

    /// Moves the focus and re-prioritises every pending job.
    pub fn set_focus(&self, focus: [f64; 3]) {
        let mut st = self.lock();
        st.focus = focus;
        let st = &mut *st;
        let mut heap = BinaryHeap::with_capacity(st.pending.len());
        for (key, p) in &st.pending {
            heap.push(Entry { priority: (self.priority)(key, focus), seq: p.seq, key: *key });
        }
        st.heap = heap;
    }

    /// Whether `generation` is still the latest for `key`.
    pub fn is_current(&self, key: &K, generation: u64) -> bool {
        self.lock().generations.get(key) == Some(&generation)
    }

    pub fn queued(&self) -> usize {
        self.lock().pending.len()
    }

    pub fn try_result(&self) -> Option<Completed<K, R>> {
        self.results.try_recv().ok()
    }

    pub fn recv_result(&self) -> Option<Completed<K, R>> {
        self.results.recv().ok()
    }
}

fn worker_loop<K, J, R>(shared: &Shared<K, J>, work: &WorkFn<K, J, R>, tx: &Sender<Completed<K, R>>)
where
    K: Copy + Eq + Hash,
{
    loop {
        let (key, job, generation, cancelled) = {
            let mut st = shared.state.lock().unwrap_or_else(|e| e.into_inner());
            loop {
                if st.shutdown {
                    return;
                }
                // Heap entries whose job was replaced, cancelled or taken are skipped.
                let mut found = None;
                while let Some(entry) = st.heap.pop() {
                    if st.pending.get(&entry.key).is_some_and(|p| p.seq == entry.seq) {
                        found = Some(entry.key);
                        break;
                    }
                }
                if let Some(key) = found {
                    let p = st.pending.remove(&key).expect("pending entry");
                    let cancelled = Arc::new(AtomicBool::new(false));
                    st.running.insert(key, Arc::clone(&cancelled));
                    break (key, p.job, p.generation, cancelled);
                }
                st = shared.wake.wait(st).unwrap_or_else(|e| e.into_inner());
            }
        };
        let ctx = JobContext { cancelled: &cancelled };
        let result = work(&key, job, &ctx);
        {
            let mut st = shared.state.lock().unwrap_or_else(|e| e.into_inner());
            if st.running.get(&key).is_some_and(|f| Arc::ptr_eq(f, &cancelled)) {
                st.running.remove(&key);
            }
        }
        let result = if cancelled.load(Ordering::Relaxed) { None } else { result };
        if tx.send(Completed { key, generation, result }).is_err() {
            return;
        }
    }
}

impl<K, J, R> Drop for Scheduler<K, J, R> {
    fn drop(&mut self) {
        self.shared.state.lock().unwrap_or_else(|e| e.into_inner()).shutdown = true;
        self.shared.wake.notify_all();
        for w in self.workers.drain(..) {
            let _ = w.join();
        }
    }
}

/// Distance priority for section keys `(x, y, z)` in section coordinates.
pub fn section_distance(key: &(i32, i32, i32), focus: [f64; 3]) -> f32 {
    let c = [key.0 as f64 * 16.0 + 8.0, key.1 as f64 * 16.0 + 8.0, key.2 as f64 * 16.0 + 8.0];
    let d = [c[0] - focus[0], c[1] - focus[1], c[2] - focus[2]];
    (d[0] * d[0] + d[1] * d[1] + d[2] * d[2]) as f32
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicUsize;
    use std::time::Duration;

    #[test]
    fn closest_jobs_run_first() {
        let gate = Arc::new((Mutex::new(false), Condvar::new()));
        let g = Arc::clone(&gate);
        let order = Arc::new(Mutex::new(Vec::new()));
        let o = Arc::clone(&order);
        let s: Scheduler<(i32, i32, i32), (), ()> = Scheduler::new(1, section_distance, move |k, _, _| {
            // The first job blocks until every job is queued.
            let (lock, cv) = &*g;
            let mut open = lock.lock().unwrap();
            while !*open {
                open = cv.wait(open).unwrap();
            }
            o.lock().unwrap().push(*k);
            Some(())
        });
        s.submit((100, 0, 0), ());
        std::thread::sleep(Duration::from_millis(50));
        for k in [(9, 0, 0), (1, 0, 0), (5, 0, 0)] {
            s.submit(k, ());
        }
        *gate.0.lock().unwrap() = true;
        gate.1.notify_all();
        for _ in 0..4 {
            s.recv_result().unwrap();
        }
        assert_eq!(*order.lock().unwrap(), vec![(100, 0, 0), (1, 0, 0), (5, 0, 0), (9, 0, 0)]);
    }

    #[test]
    fn resubmitting_deduplicates_and_bumps_generation() {
        let runs = Arc::new(AtomicUsize::new(0));
        let r = Arc::clone(&runs);
        let s: Scheduler<u32, u32, u32> = Scheduler::new(1, |_, _| 0.0, move |_, job, _| {
            r.fetch_add(1, Ordering::SeqCst);
            std::thread::sleep(Duration::from_millis(20));
            Some(job)
        });
        s.submit(0, 0); // occupies the worker
        std::thread::sleep(Duration::from_millis(5));
        s.submit(1, 10);
        s.submit(1, 11);
        let g = s.submit(1, 12);
        let mut results = Vec::new();
        for _ in 0..2 {
            results.push(s.recv_result().unwrap());
        }
        let last = results.iter().find(|c| c.key == 1).unwrap();
        assert_eq!(last.result, Some(12));
        assert_eq!(last.generation, g);
        assert!(s.is_current(&1, g));
        assert_eq!(runs.load(Ordering::SeqCst), 2);
    }

    #[test]
    fn cancellation_reaches_running_jobs_and_marks_them_stale() {
        let s: Scheduler<u32, (), ()> = Scheduler::new(1, |_, _| 0.0, |_, _, ctx| {
            for _ in 0..200 {
                if ctx.is_cancelled() {
                    return None;
                }
                std::thread::sleep(Duration::from_millis(1));
            }
            Some(())
        });
        let g = s.submit(7, ());
        std::thread::sleep(Duration::from_millis(20));
        s.cancel(7);
        let done = s.recv_result().unwrap();
        assert_eq!(done.result, None);
        assert!(!s.is_current(&7, g));
    }

    #[test]
    fn focus_changes_reorder_pending_jobs() {
        let order = Arc::new(Mutex::new(Vec::new()));
        let o = Arc::clone(&order);
        let s: Scheduler<(i32, i32, i32), (), ()> = Scheduler::new(1, section_distance, move |k, _, _| {
            std::thread::sleep(Duration::from_millis(10));
            o.lock().unwrap().push(*k);
            Some(())
        });
        s.submit((50, 0, 0), ());
        std::thread::sleep(Duration::from_millis(3));
        s.submit((0, 0, 0), ());
        s.submit((40, 0, 0), ());
        s.set_focus([40.0 * 16.0, 0.0, 0.0]);
        for _ in 0..3 {
            s.recv_result().unwrap();
        }
        assert_eq!(order.lock().unwrap()[1], (40, 0, 0));
    }
}
