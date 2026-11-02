use super::*;
use bloom_core::model::cube_face;
use bloom_core::protocol::{face_class, state_flags as f, NO_SHAPE, SNAPSHOT_FLAG_AMBIENT_OCCLUSION, SNAPSHOT_FLAG_CUTOUT_LEAVES};
use bloom_core::state::ShapePairs;

const STONE: u32 = 1;
const GLASS: u32 = 2;
const LEAVES: u32 = 3;
const GRASS: u32 = 4;
const GLOWSTONE: u32 = 5;
const DYNAMIC_BLOCK: u32 = 6;
const CARDINAL: [f32; 6] = [0.5, 1.0, 0.8, 0.8, 0.6, 0.6];

fn all_faces(class: u8) -> u16 {
    (0..6).fold(0u16, |acc, d| acc | ((class as u16) << (d * 2)))
}

fn table() -> StateTable {
    let base = StateInfo {
        flags: 0,
        block_id: 0,
        shade_brightness: 1.0,
        light_emission: 0,
        skip_rule: skip_rule::NONE,
        face_classes: all_faces(face_class::EMPTY),
        face_shapes: [NO_SHAPE; 6],
        fluid_group: 0,
        fluid_model: 0,
        fluid_amount: 0,
    };
    let full = StateInfo {
        flags: f::SOLID_RENDER | f::COLLISION_FULL_BLOCK,
        shade_brightness: 0.2,
        face_classes: all_faces(face_class::BLOCK),
        ..base
    };
    let states = vec![
        StateInfo { flags: f::AIR | f::LIGHT_PERMEABLE, ..base },
        StateInfo { block_id: 1, ..full },
        StateInfo { block_id: 2, flags: f::LIGHT_PERMEABLE | f::COLLISION_FULL_BLOCK, skip_rule: skip_rule::SAME_BLOCK, ..base },
        StateInfo {
            block_id: 3,
            flags: f::LIGHT_PERMEABLE | f::COLLISION_FULL_BLOCK | f::LEAVES | f::TINTED,
            skip_rule: skip_rule::LEAVES,
            ..base
        },
        StateInfo { block_id: 4, flags: full.flags | f::TINTED, ..full },
        StateInfo { block_id: 5, light_emission: 15, ..full },
        StateInfo { block_id: 6, flags: full.flags | f::DYNAMIC, ..full },
    ];
    StateTable::new(1, states, ShapePairs::default())
}

fn cube(layer: u8, tint: i32) -> Model {
    let quads = Direction::ALL.iter().map(|&d| cube_face(d, [0.0, 0.0, 1.0, 1.0], tint, layer)).collect();
    Model { use_ao: true, quads, max_tint_index: tint }
}

/// Model ids: 1 stone, 2 glass, 3 leaves (tinted, cutout), 4 grass (tinted), 5 glowstone.
fn models() -> Vec<Model> {
    vec![cube(0, -1), cube(2, -1), cube(1, 0), cube(0, 0), cube(0, -1)]
}

fn model_for(state: u32) -> u32 {
    match state {
        STONE | DYNAMIC_BLOCK => 1,
        GLASS => 2,
        LEAVES => 3,
        GRASS => 4,
        GLOWSTONE => 5,
        _ => MODEL_NONE,
    }
}

struct Scene {
    blocks: Vec<([i32; 3], u32)>,
    flags: u16,
    tints: Vec<(usize, Vec<i32>)>,
    offsets: Vec<(usize, [f32; 3])>,
    epoch: u32,
}

impl Scene {
    fn new(blocks: &[([i32; 3], u32)]) -> Self {
        Self { blocks: blocks.to_vec(), flags: SNAPSHOT_FLAG_AMBIENT_OCCLUSION, tints: vec![], offsets: vec![], epoch: 1 }
    }

    fn bytes(&self) -> Vec<u8> {
        let mut b = SnapshotBuilder::new(self.epoch, self.flags, [0, 0, 0], CARDINAL);
        for y in -1..17 {
            for z in -1..17 {
                for x in -1..17 {
                    b.set_light(x, y, z, 0, 15);
                }
            }
        }
        for &(p, s) in &self.blocks {
            b.set_state(p[0], p[1], p[2], s);
            if (0..16).contains(&p[0]) && (0..16).contains(&p[1]) && (0..16).contains(&p[2]) {
                b.set_model((p[0] | (p[2] << 4) | (p[1] << 8)) as usize, model_for(s));
            }
        }
        for (i, c) in &self.tints {
            b.add_tint(*i, c);
        }
        for (i, o) in &self.offsets {
            b.add_offset(*i, *o);
        }
        b.finish()
    }

    fn mesh(&self) -> Mesher {
        let mut m = Mesher::new();
        m.mesh(&self.bytes(), &table(), &models()).expect("mesh");
        m
    }
}

fn idx(p: [i32; 3]) -> usize {
    (p[0] | (p[2] << 4) | (p[1] << 8)) as usize
}

struct Vertex {
    pos: [f32; 3],
    rgba: [u8; 4],
    light: i32,
}

fn vertices(layer: &[u8]) -> Vec<Vertex> {
    layer
        .chunks_exact(VERTEX_SIZE)
        .map(|v| Vertex {
            pos: [
                f32::from_le_bytes(v[0..4].try_into().unwrap()),
                f32::from_le_bytes(v[4..8].try_into().unwrap()),
                f32::from_le_bytes(v[8..12].try_into().unwrap()),
            ],
            rgba: v[12..16].try_into().unwrap(),
            light: i32::from_le_bytes(v[24..28].try_into().unwrap()),
        })
        .collect()
}

#[test]
fn isolated_cube_has_six_lit_faces() {
    let m = Scene::new(&[([8, 8, 8], STONE)]).mesh();
    assert_eq!(m.vertex_count(0), 24);
    assert_eq!(m.stats.quads, 6);
    assert_eq!(m.stats.native_blocks, 1);
    let vs = vertices(&m.layers[0]);
    // Air neighbours: shade 1.0 everywhere, so the colour is the cardinal shade.
    let expected = [127u8, 255, 204, 204, 153, 153];
    for (face, quad) in vs.chunks(4).enumerate() {
        for v in quad {
            assert_eq!(v.rgba, [expected[face], expected[face], expected[face], 255], "face {face}");
            assert_eq!(v.light, light::pack(0, 15));
        }
    }
    // Positions are section-relative.
    assert!(vs.iter().all(|v| v.pos.iter().all(|&c| (8.0..=9.0).contains(&c))));
}

#[test]
fn shared_faces_are_culled() {
    let m = Scene::new(&[([8, 8, 8], STONE), ([9, 8, 8], STONE)]).mesh();
    assert_eq!(m.stats.quads, 10);
    assert_eq!(m.stats.culled_faces, 2);
}

#[test]
fn ambient_occlusion_darkens_the_corner_next_to_a_wall() {
    // Floor block with a wall block on top of its east neighbour: the top face's
    // east vertices are occluded.
    let m = Scene::new(&[([8, 8, 8], STONE), ([9, 9, 8], STONE)]).mesh();
    let vs = vertices(&m.layers[0]);
    // Quads are emitted DOWN, UP, ...: the floor block's top is the second quad.
    let top = &vs[4..8];
    assert!(top.iter().all(|v| v.pos[1] == 9.0));
    let east: Vec<u8> = top.iter().filter(|v| v.pos[0] == 9.0).map(|v| v.rgba[0]).collect();
    let west: Vec<u8> = top.iter().filter(|v| v.pos[0] == 8.0).map(|v| v.rgba[0]).collect();
    assert!(!east.is_empty() && !west.is_empty());
    // (0.2 + 1 + 1 + 1) / 4 = 0.8 → 204 against 255 on the open side.
    assert!(east.iter().all(|&c| c == 204), "{east:?}");
    assert!(west.iter().all(|&c| c == 255), "{west:?}");
}

#[test]
fn glass_skips_faces_against_glass_but_not_stone_against_glass() {
    let m = Scene::new(&[([8, 8, 8], GLASS), ([9, 8, 8], GLASS), ([8, 9, 8], STONE)]).mesh();
    // Glass pair: 10 faces minus the glass top under the stone (stone is a full occluder).
    assert_eq!(m.vertex_count(2) / 4, 9);
    // Stone renders all 6 faces: glass never occludes it.
    assert_eq!(m.vertex_count(0) / 4, 6);
}

#[test]
fn leaves_follow_the_cutout_option() {
    let blocks = [([8, 8, 8], LEAVES), ([9, 8, 8], LEAVES)];
    let tint = vec![(idx([8, 8, 8]), vec![0xFF48B518u32 as i32]), (idx([9, 8, 8]), vec![0xFF48B518u32 as i32])];

    let mut fast = Scene::new(&blocks);
    fast.tints = tint.clone();
    let m = fast.mesh();
    // Not cutout: leaves go to SOLID and skip faces against leaves.
    assert_eq!(m.vertex_count(1), 0);
    assert_eq!(m.vertex_count(0) / 4, 10);

    let mut fancy = Scene::new(&blocks);
    fancy.tints = tint;
    fancy.flags |= SNAPSHOT_FLAG_CUTOUT_LEAVES;
    let m = fancy.mesh();
    assert_eq!(m.vertex_count(0), 0);
    assert_eq!(m.vertex_count(1) / 4, 12);
}

#[test]
fn tint_is_multiplied_after_shading() {
    let mut s = Scene::new(&[([8, 8, 8], GRASS)]);
    let tint = 0xFF7FB238u32 as i32;
    s.tints = vec![(idx([8, 8, 8]), vec![tint])];
    let m = s.mesh();
    let up = &vertices(&m.layers[0])[4..8];
    let expected = color::to_abgr(color::multiply(color::gray(1.0), tint));
    for v in up {
        assert_eq!(i32::from_le_bytes(v.rgba), expected);
    }
}

#[test]
fn tinted_block_without_tint_values_is_demoted() {
    let m = Scene::new(&[([8, 8, 8], GRASS)]).mesh();
    assert_eq!(m.demoted, vec![idx([8, 8, 8]) as u16]);
    assert!(m.layers.iter().all(Vec::is_empty));
}

#[test]
fn emissive_blocks_use_flat_light() {
    let m = Scene::new(&[([8, 8, 8], GLOWSTONE)]).mesh();
    let vs = vertices(&m.layers[0]);
    // Light emission 15 disables AO; light is the neighbour's with the block's emission.
    assert!(vs.iter().all(|v| v.light == light::pack(15, 15)));
    assert_eq!(vs[4].rgba, [255, 255, 255, 255]);
}

#[test]
fn dynamic_neighbours_demote_and_truncate() {
    // The stone's AO reads the dynamic block's shade: Java must render it.
    let m = Scene::new(&[([8, 8, 8], STONE), ([9, 9, 8], DYNAMIC_BLOCK)]).mesh();
    assert!(m.demoted.contains(&(idx([8, 8, 8]) as u16)));
    assert!(m.demoted.contains(&(idx([9, 9, 8]) as u16)));
    assert!(m.layers[0].is_empty());
}

#[test]
fn offsets_move_vertices() {
    let mut s = Scene::new(&[([8, 8, 8], STONE)]);
    // Entries carry the origin: block position plus offset.
    s.offsets = vec![(idx([8, 8, 8]), [8.25, 8.0, 7.875])];
    let vs = vertices(&s.mesh().layers[0]);
    assert!(vs.iter().all(|v| (8.25..=9.25).contains(&v.pos[0]) && (7.875..=8.875).contains(&v.pos[2])));
}

#[test]
fn stale_epoch_is_rejected() {
    let mut s = Scene::new(&[([8, 8, 8], STONE)]);
    s.epoch = 2;
    let mut m = Mesher::new();
    assert_eq!(m.mesh(&s.bytes(), &table(), &models()), Err(MeshError::StaleEpoch));
}

#[test]
fn truncated_snapshot_is_rejected() {
    let bytes = Scene::new(&[]).bytes();
    let mut m = Mesher::new();
    assert!(matches!(m.mesh(&bytes[..1000], &table(), &models()), Err(MeshError::Snapshot(_))));
}

#[test]
fn visibility_of_a_solid_floor() {
    let blocks: Vec<([i32; 3], u32)> = (0..16).flat_map(|x| (0..16).map(move |z| ([x, 4, z], STONE))).collect();
    let m = Scene::new(&blocks).mesh();
    let up_down = culling::visgraph::visible_between(m.visibility, Direction::Up, Direction::Down);
    assert!(!up_down);
    assert_eq!(m.stats.native_blocks, 256);
    // 256 tops + 256 bottoms + 64 outer sides.
    assert_eq!(m.stats.quads, 576);
}

#[test]
fn neighbours_outside_the_section_cull_faces() {
    let m = Scene::new(&[([15, 8, 8], STONE), ([16, 8, 8], STONE)]).mesh();
    assert_eq!(m.stats.native_blocks, 1);
    assert_eq!(m.stats.quads, 5);
}

#[test]
fn reusing_the_mesher_gives_identical_results() {
    let s = Scene::new(&[([8, 8, 8], STONE), ([3, 2, 1], GLASS)]);
    let mut m = Mesher::new();
    m.mesh(&s.bytes(), &table(), &models()).unwrap();
    let first = m.layers.clone();
    m.mesh(&s.bytes(), &table(), &models()).unwrap();
    assert_eq!(first, m.layers);
}

#[test]
fn light_plan_covers_every_read_and_skips_hidden_blocks() {
    // A buried floor needs no light inside; meshing with the plan gives the
    // same bytes as meshing with every light value present.
    let mut blocks: Vec<([i32; 3], u32)> = Vec::new();
    for x in 0..16 {
        for z in 0..16 {
            for y in 0..8 {
                blocks.push(([x, y, z], STONE));
            }
        }
    }
    blocks.push(([4, 8, 4], GLASS));
    let bytes = Scene::new(&blocks).bytes();
    let mut plain = Mesher::new();
    plain.mesh(&bytes, &table(), &models()).unwrap();

    let mut planned = Mesher::new();
    let mask = planned.plan_light(&bytes, &table(), &models()).unwrap().to_vec();
    let marked: u32 = mask.iter().map(|b| b.count_ones()).sum();
    assert!(marked > 0 && marked < (LIGHT_DIM * LIGHT_DIM * LIGHT_DIM) as u32 / 2, "{marked}");
    let deep = light_index(8, 3, 8);
    assert_eq!(mask[deep / 8] & (1 << (deep % 8)), 0);
    planned.mesh(&bytes, &table(), &models()).unwrap();
    assert_eq!(planned.layers, plain.layers);
    assert!(planned.demoted.is_empty());
}
