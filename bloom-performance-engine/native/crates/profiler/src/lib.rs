//! Lock-free counters and latency histograms.
//!
//! Recording is a handful of relaxed atomic adds, so it stays on in release
//! builds. The histogram is log-linear: 8 linear sub-buckets per power of
//! two, which bounds the relative error of a percentile to 12.5%.

use std::sync::atomic::{AtomicU64, Ordering};

const SUB_BUCKETS: usize = 8;
const BUCKETS: usize = 64 * SUB_BUCKETS;

pub struct Histogram {
    buckets: Box<[AtomicU64]>,
    count: AtomicU64,
    sum: AtomicU64,
    max: AtomicU64,
}

impl Default for Histogram {
    fn default() -> Self {
        Self::new()
    }
}

#[inline]
fn bucket_of(value: u64) -> usize {
    if value < SUB_BUCKETS as u64 {
        return value as usize;
    }
    let exp = 63 - value.leading_zeros() as usize; // >= 3
    let sub = ((value >> (exp - 3)) & (SUB_BUCKETS as u64 - 1)) as usize;
    (exp - 2) * SUB_BUCKETS + sub
}

/// Upper bound of the values that fall into `bucket`.
#[inline]
fn bucket_upper(bucket: usize) -> u64 {
    if bucket < SUB_BUCKETS {
        return bucket as u64;
    }
    let exp = bucket / SUB_BUCKETS + 2;
    let sub = (bucket % SUB_BUCKETS) as u64;
    let base = 1u64 << exp;
    let width = 1u64 << (exp - 3);
    (base + sub * width).saturating_add(width - 1)
}

impl Histogram {
    pub fn new() -> Self {
        Self {
            buckets: (0..BUCKETS).map(|_| AtomicU64::new(0)).collect(),
            count: AtomicU64::new(0),
            sum: AtomicU64::new(0),
            max: AtomicU64::new(0),
        }
    }

    #[inline]
    pub fn record(&self, value: u64) {
        self.buckets[bucket_of(value)].fetch_add(1, Ordering::Relaxed);
        self.count.fetch_add(1, Ordering::Relaxed);
        self.sum.fetch_add(value, Ordering::Relaxed);
        self.max.fetch_max(value, Ordering::Relaxed);
    }

    pub fn reset(&self) {
        for b in self.buckets.iter() {
            b.store(0, Ordering::Relaxed);
        }
        self.count.store(0, Ordering::Relaxed);
        self.sum.store(0, Ordering::Relaxed);
        self.max.store(0, Ordering::Relaxed);
    }

    pub fn summary(&self) -> Summary {
        let counts: Vec<u64> = self.buckets.iter().map(|b| b.load(Ordering::Relaxed)).collect();
        let count: u64 = counts.iter().sum();
        let max = self.max.load(Ordering::Relaxed);
        let percentile = |p: f64| -> u64 {
            if count == 0 {
                return 0;
            }
            let rank = ((p * count as f64).ceil() as u64).max(1);
            let mut seen = 0;
            for (i, c) in counts.iter().enumerate() {
                seen += c;
                if seen >= rank {
                    return bucket_upper(i).min(max);
                }
            }
            max
        };
        Summary {
            count,
            mean: if count == 0 { 0 } else { self.sum.load(Ordering::Relaxed) / count },
            p50: percentile(0.50),
            p95: percentile(0.95),
            p99: percentile(0.99),
            max,
        }
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Summary {
    pub count: u64,
    pub mean: u64,
    pub p50: u64,
    pub p95: u64,
    pub p99: u64,
    pub max: u64,
}

/// Counters of the native meshing path, shared by all threads.
#[derive(Default)]
pub struct EngineProfiler {
    pub sections_meshed: AtomicU64,
    pub sections_rejected: AtomicU64,
    pub native_blocks: AtomicU64,
    pub demoted_blocks: AtomicU64,
    pub quads: AtomicU64,
    pub culled_faces: AtomicU64,
    pub vertex_bytes: AtomicU64,
    pub models_registered: AtomicU64,
    /// Time inside `meshSection`, in nanoseconds.
    pub mesh_ns: Histogram,
}

/// Number of `u64` slots written by [`EngineProfiler::export`].
pub const EXPORT_SLOTS: usize = 16;

impl EngineProfiler {
    pub fn reset(&self) {
        for c in [
            &self.sections_meshed,
            &self.sections_rejected,
            &self.native_blocks,
            &self.demoted_blocks,
            &self.quads,
            &self.culled_faces,
            &self.vertex_bytes,
        ] {
            c.store(0, Ordering::Relaxed);
        }
        self.mesh_ns.reset();
    }

    /// Fixed layout read by `com.bloomclient.engine.bridge.NativeStats`.
    pub fn export(&self) -> [u64; EXPORT_SLOTS] {
        let s = self.mesh_ns.summary();
        let l = |c: &AtomicU64| c.load(Ordering::Relaxed);
        [
            l(&self.sections_meshed),
            l(&self.sections_rejected),
            l(&self.native_blocks),
            l(&self.demoted_blocks),
            l(&self.quads),
            l(&self.culled_faces),
            l(&self.vertex_bytes),
            l(&self.models_registered),
            s.count,
            s.mean,
            s.p50,
            s.p95,
            s.p99,
            s.max,
            0,
            0,
        ]
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn buckets_are_monotonic_and_contain_their_values() {
        let mut last = 0;
        for v in (0..100_000u64).chain([u64::MAX / 2, u64::MAX]) {
            let b = bucket_of(v);
            assert!(b >= last || v == 0);
            assert!(bucket_upper(b) >= v, "{v} in {b}");
            last = b;
        }
    }

    #[test]
    fn percentiles_are_within_bucket_error() {
        let h = Histogram::new();
        for v in 1..=1000u64 {
            h.record(v * 1000);
        }
        let s = h.summary();
        assert_eq!(s.count, 1000);
        assert_eq!(s.max, 1_000_000);
        assert_eq!(s.mean, 500_500);
        for (p, exact) in [(s.p50, 500_000.0), (s.p95, 950_000.0), (s.p99, 990_000.0)] {
            let err = (p as f64 - exact) / exact;
            assert!((0.0..=0.125).contains(&err), "{p} vs {exact}");
        }
        h.reset();
        assert_eq!(h.summary(), Summary::default());
    }
}
