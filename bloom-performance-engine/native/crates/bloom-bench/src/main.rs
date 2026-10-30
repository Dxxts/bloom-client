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

use bloom_core::model::{cube_face, Model, Quad};
use bloom_core::protocol::{face_class, skip_rule, state_flags as f, MODEL_JAVA, MODEL_NONE, NO_SHAPE, SNAPSHOT_FLAG_AMBIENT_OCCLUSION};
use bloom_core::state::{ShapePairs, StateInfo, StateTable};
use bloom_core::Direction;
use chunk_mesher::{Mesher, SnapshotBuilder};
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

// ---- Synthetic block palette -------------------------------------------------------

#[derive(Clone, Copy, PartialEq, Eq)]
#[repr(u32)]
enum B {
    Air = 0,
    Stone,
    Dirt,
    Grass,
    Water,
    Log,
    Leaves,
    Glass,
    StainedGlass,
    Bricks,
    Slab,
    Torch,
}

const CARDINAL: [f32; 6] = [0.5, 1.0, 0.8, 0.8, 0.6, 0.6];

fn faces(class: u8) -> u16 {
    (0..6).fold(0, |a, d| a | ((class as u16) << (d * 2)))
}

fn palette() -> (StateTable, Vec<Model>) {
    let air = StateInfo {
        flags: f::AIR | f::LIGHT_PERMEABLE,
        block_id: 0,
        shade_brightness: 1.0,
        light_emission: 0,
        skip_rule: skip_rule::NONE,
        face_classes: faces(face_class::EMPTY),
        face_shapes: [NO_SHAPE; 6],
    };
    let full = |id: u32| StateInfo {
        flags: f::SOLID_RENDER | f::COLLISION_FULL_BLOCK,
        block_id: id,
        shade_brightness: 0.2,
        face_classes: faces(face_class::BLOCK),
        ..air
    };
    let see_through = |id: u32, extra: u32, rule: u8| StateInfo {
        flags: f::LIGHT_PERMEABLE | f::COLLISION_FULL_BLOCK | extra,
        block_id: id,
        skip_rule: rule,
        ..air
    };
    // Bottom slab: full DOWN face, partial sides (shape 0), empty top.
    let mut slab_faces = faces(face_class::PARTIAL);
    slab_faces &= !(3 << (Direction::Down.ordinal() * 2));
    slab_faces |= (face_class::BLOCK as u16) << (Direction::Down.ordinal() * 2);
    slab_faces &= !(3 << (Direction::Up.ordinal() * 2));
    let mut slab_shapes = [0u16; 6];
    slab_shapes[Direction::Down.ordinal()] = NO_SHAPE;
    slab_shapes[Direction::Up.ordinal()] = NO_SHAPE;
    let states = vec![
        air,
        full(1),
        full(2),
        StateInfo { flags: full(3).flags | f::TINTED, ..full(3) },
        StateInfo { flags: f::LIGHT_PERMEABLE, block_id: 4, ..air },
        full(5),
        see_through(6, f::LEAVES | f::TINTED, skip_rule::LEAVES),
        see_through(7, 0, skip_rule::SAME_BLOCK),
        see_through(8, 0, skip_rule::SAME_BLOCK),
        full(9),
        StateInfo { flags: f::LIGHT_PERMEABLE, block_id: 10, face_classes: slab_faces, face_shapes: slab_shapes, ..air },
        StateInfo { flags: f::LIGHT_PERMEABLE, block_id: 11, light_emission: 14, ..air },
    ];
    // One partial shape (half-height side); two half sides cull each other.
    let table = StateTable::new(1, states, ShapePairs::from_fn(1, |_, _| false));

    let uv = |i: u32| {
        let u = (i % 16) as f32 / 16.0;
        let v = (i / 16) as f32 / 16.0;
        [u, v, u + 1.0 / 16.0, v + 1.0 / 16.0]
    };
    let cube = |tex: u32, tint: i32, layer: u8| -> Model {
        Model { use_ao: true, quads: Direction::ALL.iter().map(|&d| cube_face(d, uv(tex), tint, layer)).collect(), max_tint_index: tint }
    };
    // Grass: base cube plus a tinted overlay on the sides, like vanilla's model.
    let mut grass = cube(3, -1, 0);
    for d in [Direction::North, Direction::South, Direction::West, Direction::East] {
        grass.quads.push(cube_face(d, uv(4), 0, 1));
    }
    grass.quads[1].tint_index = 0;
    grass.max_tint_index = 0;
    let slab = {
        let mut quads = Vec::new();
        for d in Direction::ALL {
            let mut q = cube_face(d, uv(20), -1, 0);
            for p in &mut q.positions {
                p[1] = p[1].min(0.5);
            }
            let cull = if d == Direction::Up { None } else { Some(d) };
            quads.push(Quad::new(q.positions, q.uvs, -1, d, cull, 0, None, 0));
        }
        Model { use_ao: true, quads, max_tint_index: -1 }
    };
    let torch = {
        let mut quads = Vec::new();
        for d in [Direction::North, Direction::South, Direction::West, Direction::East, Direction::Up] {
            let q = cube_face(d, uv(30), -1, 1);
            let pos = q.positions.map(|p| [0.4375 + p[0] * 0.125, p[1] * 0.625, 0.4375 + p[2] * 0.125]);
            quads.push(Quad::new(pos, q.uvs, -1, d, None, 1, None, 0));
        }
        Model { use_ao: false, quads, max_tint_index: -1 }
    };
    let models = vec![
        cube(1, -1, 0),  // 1 stone
        cube(2, -1, 0),  // 2 dirt
        grass,           // 3 grass
        cube(5, -1, 0),  // 4 log
        cube(6, 0, 1),   // 5 leaves
        cube(7, -1, 1),  // 6 glass
        cube(8, -1, 2),  // 7 stained glass
        cube(9, -1, 0),  // 8 bricks
        slab,            // 9 slab
        torch,           // 10 torch
    ];
    (table, models)
}

fn model_of(b: B) -> u32 {
    match b {
        B::Air => MODEL_NONE,
        B::Water => MODEL_JAVA, // fluids stay on the Java renderer
        B::Stone => 1,
        B::Dirt => 2,
        B::Grass => 3,
        B::Log => 4,
        B::Leaves => 5,
        B::Glass => 6,
        B::StainedGlass => 7,
        B::Bricks => 8,
        B::Slab => 9,
        B::Torch => 10,
    }
}

// ---- Scenes ------------------------------------------------------------------------

/// Deterministic hash noise.
fn hash(x: i32, z: i32, seed: u32) -> u32 {
    let mut h = (x as u32).wrapping_mul(0x9E37_79B1) ^ (z as u32).wrapping_mul(0x85EB_CA77) ^ seed.wrapping_mul(0xC2B2_AE3D);
    h ^= h >> 15;
    h = h.wrapping_mul(0x2C1B_3C6D);
    h ^= h >> 12;
    h
}

fn smooth_noise(x: f32, z: f32, seed: u32) -> f32 {
    let (x0, z0) = (x.floor() as i32, z.floor() as i32);
    let (fx, fz) = (x - x0 as f32, z - z0 as f32);
    let v = |dx, dz| (hash(x0 + dx, z0 + dz, seed) & 0xFFFF) as f32 / 65535.0;
    let (sx, sz) = (fx * fx * (3.0 - 2.0 * fx), fz * fz * (3.0 - 2.0 * fz));
    let a = v(0, 0) + (v(1, 0) - v(0, 0)) * sx;
    let b = v(0, 1) + (v(1, 1) - v(0, 1)) * sx;
    a + (b - a) * sz
}

trait Scene: Sync {
    fn name(&self) -> &'static str;
    fn block(&self, x: i32, y: i32, z: i32) -> B;
}

struct Terrain;
impl Scene for Terrain {
    fn name(&self) -> &'static str {
        "natural_terrain"
    }
    fn block(&self, x: i32, y: i32, z: i32) -> B {
        let h = (40.0 + smooth_noise(x as f32 / 24.0, z as f32 / 24.0, 7) * 18.0 + smooth_noise(x as f32 / 7.0, z as f32 / 7.0, 9) * 4.0) as i32;
        // Trees on a sparse grid.
        let (tx, tz) = (x.div_euclid(9) * 9 + 4, z.div_euclid(9) * 9 + 4);
        let th = (40.0 + smooth_noise(tx as f32 / 24.0, tz as f32 / 24.0, 7) * 18.0 + smooth_noise(tx as f32 / 7.0, tz as f32 / 7.0, 9) * 4.0) as i32;
        let has_tree = th > 46 && hash(tx, tz, 3) % 3 == 0;
        if has_tree {
            let (dx, dz, dy) = ((x - tx).abs(), (z - tz).abs(), y - th);
            if dx == 0 && dz == 0 && (1..=5).contains(&dy) {
                return B::Log;
            }
            if (3..=6).contains(&dy) && dx <= 2 && dz <= 2 && !(dx == 2 && dz == 2) {
                return B::Leaves;
            }
        }
        if y > h {
            return if y <= 44 { B::Water } else { B::Air };
        }
        if y == h {
            return if h <= 45 { B::Dirt } else { B::Grass };
        }
        if y > h - 4 {
            return B::Dirt;
        }
        // Caves.
        if smooth_noise(x as f32 / 9.0 + y as f32 * 0.37, z as f32 / 9.0 - y as f32 * 0.21, 11) > 0.72 {
            return B::Air;
        }
        B::Stone
    }
}

struct Builds;
impl Scene for Builds {
    fn name(&self) -> &'static str {
        "large_builds"
    }
    fn block(&self, x: i32, y: i32, z: i32) -> B {
        if y < 32 {
            return B::Stone;
        }
        let (cx, cz) = (x.rem_euclid(24), z.rem_euclid(24));
        let height = 32 + (hash(x.div_euclid(24), z.div_euclid(24), 5) % 30) as i32 + 8;
        let wall = (cx == 2 || cx == 21) && (2..=21).contains(&cz) || (cz == 2 || cz == 21) && (2..=21).contains(&cx);
        let floor = (2..=21).contains(&cx) && (2..=21).contains(&cz) && (y - 32) % 6 == 0;
        if y > height {
            return B::Air;
        }
        if wall {
            let window = (y - 32) % 6 >= 2 && (y - 32) % 6 <= 4 && (cx + cz) % 4 < 2;
            return if window { B::Glass } else { B::Bricks };
        }
        if floor {
            return if (cx + cz) % 2 == 0 { B::Slab } else { B::Bricks };
        }
        if (2..=21).contains(&cx) && (2..=21).contains(&cz) && (y - 32) % 6 == 1 && cx % 5 == 0 && cz % 5 == 0 {
            return B::Torch;
        }
        B::Air
    }
}

struct Transparent;
impl Scene for Transparent {
    fn name(&self) -> &'static str {
        "transparent_blocks"
    }
    fn block(&self, x: i32, y: i32, z: i32) -> B {
        if y < 32 {
            return B::Stone;
        }
        if y > 80 {
            return B::Air;
        }
        match hash(x, z, y as u32) % 7 {
            0 | 1 => B::Leaves,
            2 => B::Glass,
            3 => B::StainedGlass,
            4 => B::Water,
            _ => B::Air,
        }
    }
}

fn snapshot(scene: &dyn Scene, sx: i32, sy: i32, sz: i32) -> Vec<u8> {
    let mut b = SnapshotBuilder::new(1, SNAPSHOT_FLAG_AMBIENT_OCCLUSION, [sx, sy, sz], CARDINAL);
    let (ox, oy, oz) = (sx * 16, sy * 16, sz * 16);
    for y in -2..18 {
        for z in -2..18 {
            for x in -2..18 {
                b.set_state(x, y, z, scene.block(ox + x, oy + y, oz + z) as u32);
            }
        }
    }
    for y in -1..17 {
        for z in -1..17 {
            for x in -1..17 {
                let wy = oy + y;
                // Simple sky light: full above ground level, darker below.
                let sky = if scene.block(ox + x, wy, oz + z) == B::Air && wy > 40 { 15 } else { (wy.clamp(0, 45) / 3) as u8 };
                b.set_light(x, y, z, 0, sky.min(15));
            }
        }
    }
    for y in 0..16 {
        for z in 0..16 {
            for x in 0..16 {
                let blk = scene.block(ox + x, oy + y, oz + z);
                let index = (x | (z << 4) | (y << 8)) as usize;
                b.set_model(index, model_of(blk));
                if matches!(blk, B::Grass | B::Leaves) {
                    let tint = 0xFF00_0000u32 as i32 | ((hash(ox + x, oz + z, 1) & 0x3F3F3F) as i32 + 0x406020);
                    b.add_tint(index, &[tint]);
                }
            }
        }
    }
    b.finish()
}

// ---- Measurement ---------------------------------------------------------------------

struct SceneResult {
    name: &'static str,
    sections: usize,
    snapshot_bytes: usize,
    quads: u64,
    native_blocks: u64,
    demoted: u64,
    vertex_bytes: u64,
    single_ns_median: f64,
    single_ns_p95: f64,
    single_ns_mean: f64,
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
    let (mut quads, mut native_blocks, mut demoted, mut vertex_bytes) = (0u64, 0u64, 0u64, 0u64);
    for s in &snaps {
        mesher.mesh(s, table, models.as_slice()).unwrap();
        quads += mesher.stats.quads as u64;
        native_blocks += mesher.stats.native_blocks as u64;
        demoted += mesher.stats.demoted_blocks as u64;
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
        demoted,
        vertex_bytes,
        single_ns_median: percentile(&per_section, 0.5),
        single_ns_p95: percentile(&per_section, 0.95),
        single_ns_mean,
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
    let scenes: Vec<Arc<dyn Scene + Send>> = vec![Arc::new(Terrain), Arc::new(Builds), Arc::new(Transparent)];
    let results: Vec<SceneResult> = scenes.into_iter().map(|s| run_scene(s, grid, runs, &table, &models)).collect();

    println!("{:<20} {:>8} {:>10} {:>10} {:>10} {:>10} {:>10} {:>10} {:>14} {:>9} {:>9}", "scene", "sections", "quads", "native", "demoted", "mean µs", "p95 µs", "1T sec/s", "parallel sec/s", "alloc/re", "alloc/new");
    let mut json = String::from("{\n  \"tool\": \"bloom-bench\",\n");
    json += &format!("  \"cpuThreads\": {},\n  \"runs\": {},\n  \"scenes\": [\n", std::thread::available_parallelism().map_or(1, |n| n.get()), runs);
    for (n, r) in results.iter().enumerate() {
        println!(
            "{:<20} {:>8} {:>10} {:>10} {:>10} {:>10.1} {:>10.1} {:>10.0} {:>14.0} {:>9.2} {:>9.2}",
            r.name, r.sections, r.quads, r.native_blocks, r.demoted, r.single_ns_mean / 1000.0, r.single_ns_p95 / 1000.0, 1e9 / r.single_ns_mean,
            r.parallel_sections_per_s, r.allocs_per_section_reused, r.allocs_per_section_fresh
        );
        json += &format!(
            "    {{\"scene\": \"{}\", \"sections\": {}, \"snapshotBytes\": {}, \"quads\": {}, \"nativeBlocks\": {}, \"demotedBlocks\": {}, \"vertexBytes\": {}, \"singleThreadMedianUs\": {:.2}, \"singleThreadP95Us\": {:.2}, \"singleThreadMeanUs\": {:.2}, \"singleThreadSectionsPerS\": {:.0}, \"parallelThreads\": {}, \"parallelSectionsPerS\": {:.0}, \"allocsPerSectionReusedContext\": {:.3}, \"allocsPerSectionFreshContext\": {:.3}}}{}\n",
            r.name, r.sections, r.snapshot_bytes, r.quads, r.native_blocks, r.demoted, r.vertex_bytes,
            r.single_ns_median / 1000.0, r.single_ns_p95 / 1000.0, r.single_ns_mean / 1000.0, 1e9 / r.single_ns_mean, r.threads, r.parallel_sections_per_s,
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
