//! A bounded free list for reusable buffers.
//!
//! This is not an allocator: it keeps a few already-grown `Vec`s around so
//! that jobs reuse their capacity instead of reallocating. Hit and miss
//! counters exist so the benefit can be measured (see `bloom-bench`).

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

pub trait Reusable: Default {
    /// Clears the contents but keeps the capacity.
    fn reset(&mut self);
}

impl<T> Reusable for Vec<T> {
    fn reset(&mut self) {
        self.clear();
    }
}

pub struct Pool<T: Reusable> {
    free: Mutex<Vec<T>>,
    max_free: usize,
    hits: AtomicU64,
    misses: AtomicU64,
}

impl<T: Reusable> Pool<T> {
    pub fn new(max_free: usize) -> Self {
        Self { free: Mutex::new(Vec::new()), max_free, hits: AtomicU64::new(0), misses: AtomicU64::new(0) }
    }

    pub fn take(&self) -> T {
        let reused = self.free.lock().unwrap_or_else(|e| e.into_inner()).pop();
        match reused {
            Some(item) => {
                self.hits.fetch_add(1, Ordering::Relaxed);
                item
            }
            None => {
                self.misses.fetch_add(1, Ordering::Relaxed);
                T::default()
            }
        }
    }

    pub fn give(&self, mut item: T) {
        item.reset();
        let mut free = self.free.lock().unwrap_or_else(|e| e.into_inner());
        if free.len() < self.max_free {
            free.push(item);
        }
    }

    /// `(hits, misses)` since creation.
    pub fn stats(&self) -> (u64, u64) {
        (self.hits.load(Ordering::Relaxed), self.misses.load(Ordering::Relaxed))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reuses_capacity_and_respects_bound() {
        let pool: Pool<Vec<u8>> = Pool::new(1);
        let mut a = pool.take();
        a.extend_from_slice(&[1, 2, 3]);
        let cap = a.capacity();
        pool.give(a);
        pool.give(Vec::with_capacity(8));
        let b = pool.take();
        assert!(b.is_empty());
        assert_eq!(b.capacity(), cap);
        assert_eq!(pool.stats(), (1, 1));
    }
}
