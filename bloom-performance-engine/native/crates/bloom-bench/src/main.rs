//! Offline benchmark of the native mesher.
//!
//! Scenes are generated from fixed seeds, encoded with the same wire format
//! Java writes, and meshed through the same entry point the JNI layer uses.
//! It measures the Rust side only: comparisons against the vanilla Java
//! renderer come from the in-game benchmark (`BenchmarkController`).
//!
//! Usage: `bloom-bench [--sections-x N] [--runs N] [--out FILE]`

use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Instant;

use bloom_bench::scenes::{palette, snapshot, Builds, Ocean, Scene, Terrain, Transparent};
use bloom_core::model::Model;
use bloom_core::state::StateTable;
use chunk_mesher::Mesher;
use world_processing::{default_threads, section_distance, Scheduler};

// ---- Allocation counting --------------------------------------------------------

struct Counting;
static ALLOCS: AtomicU64 = AtomicU64::new(0);
static ALLOC_BYTES: AtomicU64 = AtomicU64::new(0);

unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        ALLOCS.fetch_add(1, Ordering::Relaxed);
        ALLOC_BYTES.fetch_add(layout.size() as u64, Ordering::Relaxed);
        System.alloc(layout)
    }
    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        System.dealloc(ptr, layout)
    }
    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        ALLOCS.fetch_add(1, Ordering::Relaxed);
        ALLOC_BYTES.fetch_add(new_size as u64, Ordering::Relaxed);
        System.realloc(ptr, layout, new_size)
    }
}

#[global_allocator]
static GLOBAL: Counting = Counting;

// ---- Measurement ---------------------------------------------------------------------

struct SceneResult {
    name: &'static str,
    sections: usize,
    snapshot_bytes: usize,
    quads: u64,
    native_blocks: u64,
    native_fluids: u64,
    demoted: u64,
    vertex_bytes: u64,
    single_ns_median: f64,
    single_ns_p95: f64,
    single_ns_mean: f64,
    plan_mesh_ns_mean: f64,
    parallel_sections_per_s: f64,
    threads: usize,
    allocs_per_section_reused: f64,
    allocs_per_section_fresh: f64,
}

fn percentile(sorted: &[f64], p: f64) -> f64 {
    sorted[((sorted.len() - 1) as f64 * p).round() as usize]
}

fn run_scene(scene: Arc<dyn Scene + Send>, grid: i32, runs: usize, table: &Arc<StateTable>, models: &Arc<Vec<Model>>) -> SceneResult {
    let keys: Vec<(i32, i32, i32)> = (0..grid).flat_map(|x| (2..6).flat_map(move |y| (0..grid).map(move |z| (x, y, z)))).collect();
    let snaps: Vec<Vec<u8>> = keys.iter().map(|&(x, y, z)| snapshot(&*scene, x, y, z)).collect();
    let snapshot_bytes = snaps.iter().map(Vec::len).sum::<usize>() / snaps.len();

    // Single thread, one reused context (what each game worker does).
    let mut mesher = Mesher::new();
    let (mut quads, mut native_blocks, mut demoted, mut vertex_bytes, mut native_fluids) = (0u64, 0u64, 0u64, 0u64, 0u64);
    for s in &snaps {
        mesher.mesh(s, table, models.as_slice()).unwrap();
        quads += mesher.stats.quads as u64;
        native_blocks += mesher.stats.native_blocks as u64;
        native_fluids += mesher.stats.native_fluids as u64;
        demoted += mesher.stats.demoted_blocks as u64 + mesher.stats.demoted_fluids as u64;
        vertex_bytes += mesher.layers.iter().map(|l| l.len() as u64).sum::<u64>();
    }
    let mut per_section = Vec::with_capacity(snaps.len() * runs);
    let allocs_before = ALLOCS.load(Ordering::Relaxed);
    for _ in 0..runs {
        for s in &snaps {
            let t = Instant::now();
            mesher.mesh(s, table, models.as_slice()).unwrap();
            per_section.push(t.elapsed().as_nanos() as f64);
        }
    }
    let allocs_reused = (ALLOCS.load(Ordering::Relaxed) - allocs_before) as f64 / (snaps.len() * runs) as f64;
    let single_ns_mean = per_section.iter().sum::<f64>() / per_section.len() as f64;
    per_section.sort_by(f64::total_cmp);

    // What a game worker pays per section: the light/fluid plan, then the mesh.
    let t = Instant::now();
    for _ in 0..runs {
        for s in &snaps {
            mesher.plan_light(s, table, models.as_slice()).unwrap();
            mesher.mesh(s, table, models.as_slice()).unwrap();
        }
    }
    let plan_mesh_ns_mean = t.elapsed().as_nanos() as f64 / (snaps.len() * runs) as f64;

    // Same work with a fresh context per section (no buffer reuse).
    let allocs_before = ALLOCS.load(Ordering::Relaxed);
    for s in &snaps {
        let mut fresh = Mesher::new();
        fresh.mesh(s, table, models.as_slice()).unwrap();
    }
    let allocs_fresh = (ALLOCS.load(Ordering::Relaxed) - allocs_before) as f64 / snaps.len() as f64;

    // Parallel through the priority scheduler, one context per worker thread.
    let threads = default_threads();
    let t2 = Arc::clone(table);
    let m2 = Arc::clone(models);
    thread_local! {
        static CTX: std::cell::RefCell<Mesher> = std::cell::RefCell::new(Mesher::new());
    }
    let scheduler: Scheduler<(i32, i32, i32), Arc<Vec<u8>>, u32> = Scheduler::new(threads, section_distance, move |_: &(i32, i32, i32), snap: Arc<Vec<u8>>, _: &world_processing::JobContext<'_>| {
        CTX.with(|c| {
            let mut c = c.borrow_mut();
            c.mesh(snap.as_slice(), &t2, m2.as_slice()).ok()?;
            Some(c.stats.quads)
        })
    });
    let shared: Vec<Arc<Vec<u8>>> = snaps.into_iter().map(Arc::new).collect();
    let mut best = f64::MAX;
    for _ in 0..runs {
        let t = Instant::now();
        for (k, s) in keys.iter().zip(&shared) {
            scheduler.submit(*k, Arc::clone(s));
        }
        for _ in 0..keys.len() {
            scheduler.recv_result().expect("result");
        }
        best = best.min(t.elapsed().as_secs_f64());
    }

    SceneResult {
        name: scene.name(),
        sections: keys.len(),
        snapshot_bytes,
        quads,
        native_blocks,
        native_fluids,
        demoted,
        vertex_bytes,
        single_ns_median: percentile(&per_section, 0.5),
        single_ns_p95: percentile(&per_section, 0.95),
        single_ns_mean,
        plan_mesh_ns_mean,
        parallel_sections_per_s: keys.len() as f64 / best,
        threads,
        allocs_per_section_reused: allocs_reused,
        allocs_per_section_fresh: allocs_fresh,
    }
}

fn main() {
    let mut grid = 16;
    let mut runs = 5;
    let mut out: Option<String> = None;
    let args: Vec<String> = std::env::args().collect();
    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--sections-x" => grid = args[i + 1].parse().expect("--sections-x N"),
            "--runs" => runs = args[i + 1].parse().expect("--runs N"),
            "--out" => out = Some(args[i + 1].clone()),
            other => panic!("unknown argument {other}"),
        }
        i += 2;
    }
    let (table, models) = palette();
    let (table, models) = (Arc::new(table), Arc::new(models));
    let scenes: Vec<Arc<dyn Scene + Send>> = vec![Arc::new(Terrain), Arc::new(Builds), Arc::new(Transparent), Arc::new(Ocean)];
    let results: Vec<SceneResult> = scenes.into_iter().map(|s| run_scene(s, grid, runs, &table, &models)).collect();

    println!("{:<20} {:>8} {:>10} {:>10} {:>9} {:>8} {:>9} {:>9} {:>10} {:>10} {:>14} {:>9} {:>9}", "scene", "sections", "quads", "native", "fluids", "demoted", "mean µs", "p95 µs", "plan+mesh", "1T sec/s", "parallel sec/s", "alloc/re", "alloc/new");
    let mut json = String::from("{\n  \"tool\": \"bloom-bench\",\n");
    json += &format!("  \"cpuThreads\": {},\n  \"runs\": {},\n  \"scenes\": [\n", std::thread::available_parallelism().map_or(1, |n| n.get()), runs);
    for (n, r) in results.iter().enumerate() {
        println!(
            "{:<20} {:>8} {:>10} {:>10} {:>9} {:>8} {:>9.1} {:>9.1} {:>10.1} {:>10.0} {:>14.0} {:>9.2} {:>9.2}",
            r.name, r.sections, r.quads, r.native_blocks, r.native_fluids, r.demoted, r.single_ns_mean / 1000.0, r.single_ns_p95 / 1000.0,
            r.plan_mesh_ns_mean / 1000.0, 1e9 / r.single_ns_mean,
            r.parallel_sections_per_s, r.allocs_per_section_reused, r.allocs_per_section_fresh
        );
        json += &format!(
            "    {{\"scene\": \"{}\", \"sections\": {}, \"snapshotBytes\": {}, \"quads\": {}, \"nativeBlocks\": {}, \"nativeFluids\": {}, \"demoted\": {}, \"vertexBytes\": {}, \"singleThreadMedianUs\": {:.2}, \"singleThreadP95Us\": {:.2}, \"singleThreadMeanUs\": {:.2}, \"planPlusMeshMeanUs\": {:.2}, \"singleThreadSectionsPerS\": {:.0}, \"parallelThreads\": {}, \"parallelSectionsPerS\": {:.0}, \"allocsPerSectionReusedContext\": {:.3}, \"allocsPerSectionFreshContext\": {:.3}}}{}\n",
            r.name, r.sections, r.snapshot_bytes, r.quads, r.native_blocks, r.native_fluids, r.demoted, r.vertex_bytes,
            r.single_ns_median / 1000.0, r.single_ns_p95 / 1000.0, r.single_ns_mean / 1000.0, r.plan_mesh_ns_mean / 1000.0, 1e9 / r.single_ns_mean, r.threads, r.parallel_sections_per_s,
            r.allocs_per_section_reused, r.allocs_per_section_fresh, if n + 1 < results.len() { "," } else { "" }
        );
    }
    json += "  ]\n}\n";
    if let Some(path) = out {
        std::fs::write(&path, json).expect("write results");
        println!("results written to {path}");
    }
    let _ = ALLOC_BYTES.load(Ordering::Relaxed);
}
