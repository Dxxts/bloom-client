//! Native fluids: face decisions, heights, sprites and every demotion rule.
//! Bit-exactness against vanilla is checked in game by `MeshValidator`.

use super::*;
use bloom_core::protocol::{face_class, fluid_group, state_flags as f, NO_SHAPE, SNAPSHOT_FLAG_NATIVE_FLUIDS};
use bloom_core::state::{FluidModel, ShapePairs, Sprite};

const AIR: u32 = 0;
const STONE: u32 = 1;
const WATER: u32 = 2;
const WATER_LEVEL_4: u32 = 3;
const GLASS: u32 = 4;
const SLAB: u32 = 5;
const MOD_FLUID: u32 = 6;
const LAVA: u32 = 7;
const CARDINAL: [f32; 6] = [0.5, 1.0, 0.8, 0.8, 0.6, 0.6];
const TRANSLUCENT: usize = 2;
const SOLID: usize = 0;
const SKY: i32 = 15 << 20;

const STILL: Sprite = Sprite { u0: 0.0, u1: 0.0625, v0: 0.5, v1: 0.5625 };
const FLOWING: Sprite = Sprite { u0: 0.25, u1: 0.375, v0: 0.0, v1: 0.125 };
const OVERLAY: Sprite = Sprite { u0: 0.75, u1: 0.8125, v0: 0.75, v1: 0.8125 };

fn faces(class: u8) -> u16 {
    (0..6).fold(0u16, |acc, d| acc | ((class as u16) << (d * 2)))
}

fn table() -> StateTable {
    let air = StateInfo {
        flags: f::AIR | f::LIGHT_PERMEABLE,
        block_id: 0,
        shade_brightness: 1.0,
        light_emission: 0,
        skip_rule: 0,
        face_classes: faces(face_class::EMPTY),
        face_shapes: [NO_SHAPE; 6],
        fluid_group: fluid_group::NONE,
        fluid_model: 0,
        fluid_amount: 0,
    };
    let stone = StateInfo {
        flags: f::SOLID_RENDER | f::COLLISION_FULL_BLOCK | f::SOLID,
        block_id: 1,
        face_classes: faces(face_class::BLOCK),
        ..air
    };
    let water = StateInfo { flags: f::LIGHT_PERMEABLE, block_id: 2, fluid_group: 1, fluid_model: 0, fluid_amount: 8, ..air };
    let mut slab_faces = faces(face_class::PARTIAL);
    slab_faces &= !(3 << (Direction::Down.ordinal() * 2));
    slab_faces |= (face_class::BLOCK as u16) << (Direction::Down.ordinal() * 2);
    let states = vec![
        air,
        stone,
        water,
        StateInfo { fluid_amount: 4, ..water },
        StateInfo { flags: f::LIGHT_PERMEABLE | f::COLLISION_FULL_BLOCK | f::SOLID | f::OVERLAY_NEIGHBOR, block_id: 4, ..air },
        StateInfo { flags: f::SOLID, block_id: 5, face_classes: slab_faces, face_shapes: [0; 6], ..air },
        StateInfo { block_id: 6, fluid_group: fluid_group::UNVERIFIED, ..air },
        StateInfo { flags: 0, block_id: 7, fluid_group: 2, fluid_model: 1, fluid_amount: 8, light_emission: 15, ..air },
    ];
    StateTable::new(1, states, ShapePairs::from_fn(1, |_, _| true)).with_fluid_models(vec![
        FluidModel { layer: TRANSLUCENT as u8, tinted: true, still: STILL, flowing: FLOWING, overlay: Some(OVERLAY) },
        FluidModel { layer: SOLID as u8, tinted: false, still: STILL, flowing: FLOWING, overlay: None },
    ])
}

fn idx(p: [i32; 3]) -> usize {
    (p[0] | (p[2] << 4) | (p[1] << 8)) as usize
}

struct Scene {
    blocks: Vec<([i32; 3], u32)>,
    fluids: Vec<FluidEntry>,
    flags: u16,
}

impl Scene {
    fn new(blocks: &[([i32; 3], u32)]) -> Self {
        Self { blocks: blocks.to_vec(), fluids: vec![], flags: SNAPSHOT_FLAG_NATIVE_FLUIDS }
    }

    /// Java's answer to the plan: tint and (when asked) a zero flow.
    fn with_planned_entries(mut self, tint: i32) -> Self {
        let mut m = Mesher::new();
        m.plan_light(&self.bytes(), &table(), &Vec::<Model>::new()).unwrap();
        self.fluids = m
            .fluid_requests()
            .iter()
            .map(|&r| FluidEntry {
                index: (r & !PLAN_FLUID_NEEDS_FLOW) as usize,
                tint,
                flow: None,
                flow_known: r & PLAN_FLUID_NEEDS_FLOW != 0,
            })
            .collect();
        self
    }

    fn bytes(&self) -> Vec<u8> {
        let mut b = SnapshotBuilder::new(1, self.flags, [0, 0, 0], CARDINAL);
        for y in -1..17 {
            for z in -1..17 {
                for x in -1..17 {
                    b.set_light(x, y, z, 0, 15);
                }
            }
        }
        for &(p, s) in &self.blocks {
            b.set_state(p[0], p[1], p[2], s);
        }
        for e in &self.fluids {
            b.add_fluid(*e);
        }
        b.finish()
    }

    fn mesh(&self) -> Mesher {
        let mut m = Mesher::new();
        let bytes = self.bytes();
        m.plan_light(&bytes, &table(), &Vec::<Model>::new()).unwrap();
        m.mesh(&bytes, &table(), &Vec::<Model>::new()).expect("mesh");
        m
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
struct V {
    pos: [f32; 3],
    abgr: i32,
    uv: [f32; 2],
    light: i32,
}

fn vertices(layer: &[u8]) -> Vec<V> {
    layer
        .chunks_exact(VERTEX_SIZE)
        .map(|c| {
            let f = |o: usize| f32::from_le_bytes(c[o..o + 4].try_into().unwrap());
            let i = |o: usize| i32::from_le_bytes(c[o..o + 4].try_into().unwrap());
            V { pos: [f(0), f(4), f(8)], abgr: i(12), uv: [f(16), f(20)], light: i(24) }
        })
        .collect()
}

#[test]
fn lone_water_source_draws_every_face_with_vanilla_heights() {
    let m = Scene::new(&[([5, 5, 5], WATER)]).with_planned_entries(-1).mesh();
    assert_eq!(m.stats.native_fluids, 1);
    assert_eq!(m.stats.demoted_fluids, 0);
    let v = vertices(&m.layers[TRANSLUCENT]);
    // Top with back face (2), bottom (1), four sides with back faces (8).
    assert_eq!(v.len(), 11 * 4);
    // Every corner averages the block (8/9, weight 10) with two empty neighbours (0, weight 1 each).
    let corner = (8.0f32 / 9.0 * 10.0) / 12.0 - 0.001;
    assert_eq!(v[0].pos, [5.0, 5.0 + corner, 5.0]);
    assert_eq!(v[0].uv, [STILL.u0, STILL.v0]);
    assert_eq!(v[0].light, SKY);
    assert_eq!(v[0].abgr, -1);
    // The back face repeats vertex 0 and reverses the rest.
    assert_eq!(v[4], v[0]);
    assert_eq!(v[5], v[3]);
    // Bottom face at y + 0.001 (the down face is drawn), shaded by the DOWN cardinal light.
    assert_eq!(v[8].pos, [5.0, 5.001, 5.0]);
    assert_eq!(v[8].abgr, color::to_abgr(color::scale_rgb(-1, 0.5)));
    // First side is NORTH: z + 0.001, flowing sprite, shade up * north.
    assert_eq!(v[12].pos, [5.0, 5.0 + corner, 5.001]);
    assert_eq!(v[12].uv, [FLOWING.u(0.0), FLOWING.v((1.0 - corner) * 0.5)]);
    assert_eq!(v[12].abgr, color::to_abgr(color::scale_rgb(-1, 1.0 * 0.8)));
}

#[test]
fn submerged_water_draws_nothing_and_needs_nothing_from_java() {
    let mut blocks = vec![];
    for y in 4..=6 {
        for z in 4..=6 {
            for x in 4..=6 {
                blocks.push(([x, y, z], WATER));
            }
        }
    }
    let scene = Scene::new(&blocks).with_planned_entries(-1);
    assert!(!scene.fluids.iter().any(|e| e.index == idx([5, 5, 5])));
    let m = scene.mesh();
    assert_eq!(m.stats.native_fluids, 27);
    // Water under water reaches full height: no top for the lower layers.
    let v = vertices(&m.layers[TRANSLUCENT]);
    assert!(v.iter().all(|v| v.pos[1] <= 7.0));
    assert!(v.iter().any(|v| v.pos[1] == 6.0), "sides of lower blocks reach y + 1");
}

#[test]
fn faces_against_full_blocks_and_same_fluid_are_culled() {
    let m = Scene::new(&[([5, 5, 5], WATER), ([5, 5, 4], STONE), ([6, 5, 5], WATER), ([5, 4, 5], STONE)])
        .with_planned_entries(-1)
        .mesh();
    assert_eq!(m.stats.native_fluids, 2);
    let v = vertices(&m.layers[TRANSLUCENT]);
    // No vertex of block (5,5,5) lies on its north face or on the shared east face.
    assert!(!v.iter().any(|v| v.pos[2] == 5.001 && v.pos[0] < 6.0));
    assert!(!v.iter().any(|v| v.pos[0] == 5.999));
    // The bottom against stone is culled: nothing at y = 5.0 or 5.001 for x in 5..6.
    assert!(!v.iter().any(|v| v.pos[1] <= 5.001 && v.pos[0] < 6.0 && v.pos[2] > 5.0 && v.pos[2] < 6.0 && v.pos[0] > 5.0));
}

#[test]
fn glass_neighbour_uses_the_overlay_without_back_face() {
    let m = Scene::new(&[([5, 5, 5], WATER), ([5, 5, 4], GLASS)]).with_planned_entries(-1).mesh();
    let v = vertices(&m.layers[TRANSLUCENT]);
    // Top 2 + bottom 1 + north overlay 1 + three sides 2 each.
    assert_eq!(v.len(), 10 * 4);
    let north = v.iter().find(|v| v.pos[2] == 5.001).unwrap();
    assert_eq!(north.uv[0], OVERLAY.u(0.0));
}

#[test]
fn flowing_top_uses_the_flow_terms_from_java() {
    let (s, c) = (0.1f32, -0.2f32);
    let mut scene = Scene::new(&[([5, 5, 5], WATER_LEVEL_4)]).with_planned_entries(0xFF3F76E4u32 as i32);
    for e in &mut scene.fluids {
        e.flow = Some((s, c));
    }
    let m = scene.mesh();
    let v = vertices(&m.layers[TRANSLUCENT]);
    assert_eq!(v[0].uv, [FLOWING.u(0.5 + (-c - s)), FLOWING.v(0.5 + (-c + s))]);
    assert_eq!(v[2].uv, [FLOWING.u(0.5 + (c + s)), FLOWING.v(0.5 + (c - s))]);
    assert_eq!(v[0].abgr, color::to_abgr(color::scale_rgb(0xFF3F76E4u32 as i32, 1.0)));
    // Level 4: own height 4/9 (weight 1) with two empty neighbours.
    assert_eq!(v[0].pos[1], 5.0 + (4.0f32 / 9.0) / 3.0 - 0.001);
}

#[test]
fn lava_goes_to_its_own_layer_with_emissive_light() {
    let m = Scene::new(&[([5, 5, 5], LAVA)]).with_planned_entries(-1).mesh();
    assert!(m.layers[TRANSLUCENT].is_empty());
    let v = vertices(&m.layers[SOLID]);
    assert!(!v.is_empty());
    // Emission 15 lifts block light to 15 (max with the block above).
    assert_eq!(v[0].light, light::pack(15, 15));
}

#[test]
fn unreproducible_cases_are_demoted_to_java() {
    let fluid = |p: [i32; 3]| idx(p) as u16 | DEMOTED_FLUID_BIT;
    // Partial occluder next to the fluid.
    let m = Scene::new(&[([5, 5, 5], WATER), ([5, 5, 4], SLAB)]).with_planned_entries(-1).mesh();
    assert_eq!(m.demoted, vec![fluid([5, 5, 5])]);
    assert!(m.layers[TRANSLUCENT].is_empty());
    // A fluid of an unverified class next to it.
    let m = Scene::new(&[([5, 5, 5], WATER), ([6, 5, 5], MOD_FLUID)]).with_planned_entries(-1).mesh();
    assert_eq!(m.demoted, vec![fluid([5, 5, 5])]);
    // Java did not send the block's entry.
    let m = Scene::new(&[([5, 5, 5], WATER)]).mesh();
    assert_eq!(m.demoted, vec![fluid([5, 5, 5])]);
    // The entry is there but the flow was not computed.
    let mut scene = Scene::new(&[([5, 5, 5], WATER)]).with_planned_entries(-1);
    scene.fluids[0].flow_known = false;
    assert_eq!(scene.mesh().demoted, vec![fluid([5, 5, 5])]);
}

#[test]
fn plan_requests_flow_and_light_around_visible_fluids() {
    let scene = Scene::new(&[([5, 5, 5], WATER), ([5, 4, 5], WATER), ([5, 3, 5], STONE)]);
    let mut m = Mesher::new();
    let mask = m.plan_light(&scene.bytes(), &table(), &Vec::<Model>::new()).unwrap().to_vec();
    let mut requests = m.fluid_requests().to_vec();
    requests.sort();
    // The upper block shows its top (flow needed); the lower one only its sides.
    assert_eq!(requests, vec![idx([5, 4, 5]) as u16, idx([5, 5, 5]) as u16 | PLAN_FLUID_NEEDS_FLOW]);
    for p in [[5, 6, 5], [5, 5, 5], [5, 4, 5], [5, 3, 5]] {
        let i = light_index(p[0], p[1], p[2]);
        assert!(mask[i / 8] & (1 << (i % 8)) != 0, "light at {p:?}");
    }
}

#[test]
fn fluids_stay_in_java_unless_the_snapshot_asks() {
    let mut scene = Scene::new(&[([5, 5, 5], WATER), ([5, 5, 4], SLAB)]).with_planned_entries(-1);
    scene.flags = 0;
    let m = scene.mesh();
    assert_eq!(m.stats.native_fluids, 0);
    assert!(m.demoted.is_empty());
    assert!(m.layers.iter().all(Vec::is_empty));
    let _ = AIR;
}
