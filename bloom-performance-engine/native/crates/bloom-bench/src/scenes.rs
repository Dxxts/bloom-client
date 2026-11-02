//! Deterministic synthetic scenes shared by the CPU benchmark (`bloom-bench`)
//! and the GPU benchmark (`bloom-gpu-bench`): a block palette with its state
//! table and models, scene generators, and the snapshot encoder that mirrors
//! what Java writes (including the fluid entries Java adds after the plan).

use bloom_core::model::{cube_face, Model, Quad};
use bloom_core::protocol::{
    face_class, skip_rule, state_flags as f, MODEL_NONE, NO_SHAPE, PLAN_FLUID_NEEDS_FLOW, SNAPSHOT_FLAG_AMBIENT_OCCLUSION,
    SNAPSHOT_FLAG_NATIVE_FLUIDS,
};
use bloom_core::state::{FluidModel, ShapePairs, Sprite, StateInfo, StateTable};
use bloom_core::Direction;
use chunk_mesher::{FluidEntry, Mesher, SnapshotBuilder};

// ---- Synthetic block palette -------------------------------------------------------

#[derive(Clone, Copy, PartialEq, Eq)]
#[repr(u32)]
pub enum B {
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

pub const CARDINAL: [f32; 6] = [0.5, 1.0, 0.8, 0.8, 0.6, 0.6];

pub fn faces(class: u8) -> u16 {
    (0..6).fold(0, |a, d| a | ((class as u16) << (d * 2)))
}

pub fn palette() -> (StateTable, Vec<Model>) {
    let air = StateInfo {
        flags: f::AIR | f::LIGHT_PERMEABLE,
        block_id: 0,
        shade_brightness: 1.0,
        light_emission: 0,
        skip_rule: skip_rule::NONE,
        face_classes: faces(face_class::EMPTY),
        face_shapes: [NO_SHAPE; 6],
        fluid_group: 0,
        fluid_model: 0,
        fluid_amount: 0,
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
        // Water source: a native fluid (group 1, model 0, amount 8).
        StateInfo { flags: f::LIGHT_PERMEABLE, block_id: 4, fluid_group: 1, fluid_model: 0, fluid_amount: 8, ..air },
        full(5),
        see_through(6, f::LEAVES | f::TINTED, skip_rule::LEAVES),
        see_through(7, 0, skip_rule::SAME_BLOCK),
        see_through(8, 0, skip_rule::SAME_BLOCK),
        full(9),
        StateInfo { flags: f::LIGHT_PERMEABLE, block_id: 10, face_classes: slab_faces, face_shapes: slab_shapes, ..air },
        StateInfo { flags: f::LIGHT_PERMEABLE, block_id: 11, light_emission: 14, ..air },
    ];
    // One partial shape (half-height side); two half sides cull each other.
    let water = FluidModel {
        layer: 2,
        tinted: true,
        still: Sprite { u0: 0.0, u1: 0.0625, v0: 0.75, v1: 0.8125 },
        flowing: Sprite { u0: 0.0625, u1: 0.1875, v0: 0.75, v1: 0.875 },
        overlay: Some(Sprite { u0: 0.1875, u1: 0.25, v0: 0.75, v1: 0.8125 }),
    };
    let table = StateTable::new(1, states, ShapePairs::from_fn(1, |_, _| false)).with_fluid_models(vec![water]);

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

pub fn model_of(b: B) -> u32 {
    match b {
        B::Air => MODEL_NONE,
        B::Water => MODEL_NONE, // the block has no model; its fluid is meshed natively
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
pub fn hash(x: i32, z: i32, seed: u32) -> u32 {
    let mut h = (x as u32).wrapping_mul(0x9E37_79B1) ^ (z as u32).wrapping_mul(0x85EB_CA77) ^ seed.wrapping_mul(0xC2B2_AE3D);
    h ^= h >> 15;
    h = h.wrapping_mul(0x2C1B_3C6D);
    h ^= h >> 12;
    h
}

pub fn smooth_noise(x: f32, z: f32, seed: u32) -> f32 {
    let (x0, z0) = (x.floor() as i32, z.floor() as i32);
    let (fx, fz) = (x - x0 as f32, z - z0 as f32);
    let v = |dx, dz| (hash(x0 + dx, z0 + dz, seed) & 0xFFFF) as f32 / 65535.0;
    let (sx, sz) = (fx * fx * (3.0 - 2.0 * fx), fz * fz * (3.0 - 2.0 * fz));
    let a = v(0, 0) + (v(1, 0) - v(0, 0)) * sx;
    let b = v(0, 1) + (v(1, 1) - v(0, 1)) * sx;
    a + (b - a) * sz
}

pub trait Scene: Sync {
    fn name(&self) -> &'static str;
    fn block(&self, x: i32, y: i32, z: i32) -> B;
}

pub struct Terrain;
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

pub struct Builds;
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

pub struct Transparent;
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

/// Ocean: water down to an uneven sea floor, the scene where fluids dominate.
pub struct Ocean;
impl Scene for Ocean {
    fn name(&self) -> &'static str {
        "ocean"
    }
    fn block(&self, x: i32, y: i32, z: i32) -> B {
        let floor = (30.0 + smooth_noise(x as f32 / 20.0, z as f32 / 20.0, 13) * 14.0) as i32;
        if y > 62 {
            B::Air
        } else if y > floor {
            B::Water
        } else if y == floor {
            B::Dirt
        } else {
            B::Stone
        }
    }
}

/// The snapshot Java would send: states, light, models and tints, then (after
/// the native plan) one entry per fluid block the plan asked about.
pub fn snapshot(scene: &dyn Scene, sx: i32, sy: i32, sz: i32) -> Vec<u8> {
    let (table, models) = palette();
    let base = snapshot_without_fluids(scene, sx, sy, sz, &[]);
    let mut planner = Mesher::new();
    planner.plan_light(&base, &table, models.as_slice()).expect("plan");
    let entries: Vec<FluidEntry> = planner
        .fluid_requests()
        .iter()
        .map(|&r| FluidEntry {
            index: (r & !PLAN_FLUID_NEEDS_FLOW) as usize,
            tint: 0xFF3F_76E4u32 as i32,
            // Still water: the flow is zero, but Java computed it.
            flow: None,
            flow_known: r & PLAN_FLUID_NEEDS_FLOW != 0,
        })
        .collect();
    snapshot_without_fluids(scene, sx, sy, sz, &entries)
}

fn snapshot_without_fluids(scene: &dyn Scene, sx: i32, sy: i32, sz: i32, fluids: &[FluidEntry]) -> Vec<u8> {
    let mut b = SnapshotBuilder::new(1, SNAPSHOT_FLAG_AMBIENT_OCCLUSION | SNAPSHOT_FLAG_NATIVE_FLUIDS, [sx, sy, sz], CARDINAL);
    for e in fluids {
        b.add_fluid(*e);
    }
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

