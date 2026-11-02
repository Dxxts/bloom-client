//! Bloom chunk meshing engine.
//!
//! [`Mesher::mesh`] turns one section snapshot into vertex data in
//! Minecraft's `BLOCK` vertex format, separated by layer. The algorithm is a
//! line-by-line port of `ModelBlockRenderer` and `BlockModelLighter` from
//! Minecraft 26.3, so the output matches the vanilla renderer byte for byte
//! (the Java side verifies this at runtime in validation mode).
//!
//! Blocks the mesher cannot reproduce exactly are never approximated: they
//! are "demoted" and Java tesselates them with the vanilla renderer.
//!
//! Fluids (`FluidRenderer`) are meshed natively too when the snapshot asks
//! for it; see [`fluid`].

pub mod ao;
mod fluid;
pub mod snapshot;

use bloom_core::model::Model;
use bloom_core::protocol::{
    face_class, skip_rule, state_flags, DEMOTED_FLUID_BIT, LAYER_COUNT, LIGHT_DIM, LIGHT_MIN, MAX_TINTS, MODEL_JAVA, MODEL_NONE,
    PLAN_FLUID_NEEDS_FLOW, SECTION_VOLUME, VERTEX_SIZE,
};
use bloom_core::state::{StateInfo, StateTable};
use bloom_core::{color, light, Direction};
use culling::visgraph::VisGraph;
pub use snapshot::{encode_fluid_entry, FluidEntry, Snapshot, SnapshotBuilder, SnapshotError};

/// Read access to registered models (ids start at 1).
pub trait ModelSource {
    fn model(&self, id: u32) -> Option<&Model>;
}

impl ModelSource for [Model] {
    #[inline]
    fn model(&self, id: u32) -> Option<&Model> {
        self.get((id as usize).checked_sub(1)?)
    }
}

impl ModelSource for Vec<Model> {
    #[inline]
    fn model(&self, id: u32) -> Option<&Model> {
        self.as_slice().model(id)
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct MeshStats {
    pub native_blocks: u32,
    pub demoted_blocks: u32,
    pub quads: u32,
    pub culled_faces: u32,
    pub native_fluids: u32,
    pub demoted_fluids: u32,
}

#[derive(Debug, PartialEq, Eq)]
pub enum MeshError {
    Snapshot(SnapshotError),
    StaleEpoch,
}

/// Per-thread meshing context. Buffers keep their capacity between calls.
pub struct Mesher {
    pub layers: [Vec<u8>; LAYER_COUNT],
    /// Block indices handed back to Java; [`DEMOTED_FLUID_BIT`] marks a fluid.
    pub demoted: Vec<u16>,
    pub visibility: u64,
    pub stats: MeshStats,
    vis_graph: VisGraph,
    tint_slot: Box<[u16; SECTION_VOLUME]>,
    offset_slot: Box<[u16; SECTION_VOLUME]>,
    fluid_slot: Box<[u16; SECTION_VOLUME]>,
    /// Fluid blocks Java must describe (tint, flow), from [`Mesher::plan_light`].
    fluid_requests: Vec<u16>,
    /// Light positions Java filled in, from [`Mesher::plan_light`]. While
    /// active, reading any other position demotes the block.
    /// `None` only while [`Mesher::mesh`] borrows it (taken, not reallocated).
    light_mask: Option<Box<[u8; LIGHT_MASK_BYTES]>>,
    light_mask_active: bool,
}

/// One bit per light position (18³).
pub const LIGHT_MASK_BYTES: usize = (LIGHT_DIM * LIGHT_DIM * LIGHT_DIM).div_ceil(8);

#[inline]
fn light_index(x: i32, y: i32, z: i32) -> usize {
    ((x - LIGHT_MIN) as usize) + ((z - LIGHT_MIN) as usize) * LIGHT_DIM + ((y - LIGHT_MIN) as usize) * LIGHT_DIM * LIGHT_DIM
}

impl Default for Mesher {
    fn default() -> Self {
        Self::new()
    }
}

const NO_SLOT: u16 = u16::MAX;

/// Why a block went back to Java.
struct Demote;

impl Mesher {
    pub fn new() -> Self {
        Self {
            layers: Default::default(),
            demoted: Vec::new(),
            visibility: 0,
            stats: MeshStats::default(),
            vis_graph: VisGraph::new(),
            tint_slot: Box::new([NO_SLOT; SECTION_VOLUME]),
            offset_slot: Box::new([NO_SLOT; SECTION_VOLUME]),
            fluid_slot: Box::new([NO_SLOT; SECTION_VOLUME]),
            fluid_requests: Vec::new(),
            light_mask: Some(Box::new([0; LIGHT_MASK_BYTES])),
            light_mask_active: false,
        }
    }

    /// Fluid blocks the last [`Mesher::plan_light`] needs a [`FluidEntry`]
    /// for: block index, plus [`PLAN_FLUID_NEEDS_FLOW`] when the top face is
    /// drawn and the flow is needed.
    pub fn fluid_requests(&self) -> &[u16] {
        &self.fluid_requests
    }

    /// Vertex count of `layer` in the last result.
    pub fn vertex_count(&self, layer: usize) -> usize {
        self.layers[layer].len() / VERTEX_SIZE
    }

    /// First pass: marks the light positions the mesher may read, that is
    /// the 3x3x3 neighbourhood of every native block with a face that can be
    /// visible. Java then reads light only there; the snapshot's light array
    /// is not used here. The mask applies to the next [`Mesher::mesh`].
    pub fn plan_light<M: ModelSource + ?Sized>(&mut self, bytes: &[u8], states: &StateTable, models: &M) -> Result<&[u8], MeshError> {
        let snap = Snapshot::parse(bytes).map_err(MeshError::Snapshot)?;
        if snap.epoch != states.epoch {
            return Err(MeshError::StaleEpoch);
        }
        let light_mask = self.light_mask.as_mut().expect("light mask in use");
        light_mask.fill(0);
        let ctx = Ctx { snap: &snap, states, ao_enabled: false, cutout_leaves: snap.cutout_leaves(), light_mask: None };
        for index in 0..SECTION_VOLUME {
            let model_id = snap.model(index);
            if model_id == MODEL_NONE || model_id == MODEL_JAVA {
                continue;
            }
            let pos = [(index & 15) as i32, (index >> 8) as i32, ((index >> 4) & 15) as i32];
            let state = states.get(snap.state_id(pos[0], pos[1], pos[2]));
            let visible = match models.model(model_id) {
                // Unknown models are demoted later and need no light.
                None => false,
                // A face whose culling Java has to decide is marked too.
                Some(model) => model.quads.iter().any(|q| q.cull.is_none_or(|d| ctx.should_render_face(pos, state, d).unwrap_or(true))),
            };
            if visible {
                for dy in -1..=1 {
                    for dz in -1..=1 {
                        for dx in -1..=1 {
                            let i = light_index(pos[0] + dx, pos[1] + dy, pos[2] + dz);
                            light_mask[i / 8] |= 1 << (i % 8);
                        }
                    }
                }
            }
        }
        self.fluid_requests.clear();
        if snap.native_fluids() {
            for index in 0..SECTION_VOLUME {
                let pos = [(index & 15) as i32, (index >> 8) as i32, ((index >> 4) & 15) as i32];
                let state = states.get(snap.state_id(pos[0], pos[1], pos[2]));
                if !state.has_native_fluid() {
                    continue;
                }
                // Blocks that will be demoted need nothing from Java.
                let Ok(Some(shape)) = ctx.fluid_shape(pos, state) else { continue };
                if !shape.draws_anything() {
                    continue;
                }
                let flow = if shape.top.is_some() { PLAN_FLUID_NEEDS_FLOW } else { 0 };
                self.fluid_requests.push(index as u16 | flow);
                for dy in -1..=1 {
                    let i = light_index(pos[0], pos[1] + dy, pos[2]);
                    light_mask[i / 8] |= 1 << (i % 8);
                }
            }
        }
        self.light_mask_active = true;
        Ok(&self.light_mask.as_ref().expect("light mask in use")[..])
    }

    pub fn mesh<M: ModelSource + ?Sized>(&mut self, bytes: &[u8], states: &StateTable, models: &M) -> Result<(), MeshError> {
        let use_mask = std::mem::take(&mut self.light_mask_active);
        let snap = Snapshot::parse(bytes).map_err(MeshError::Snapshot)?;
        if snap.epoch != states.epoch {
            return Err(MeshError::StaleEpoch);
        }
        for layer in &mut self.layers {
            layer.clear();
        }
        self.demoted.clear();
        self.stats = MeshStats::default();
        self.vis_graph.reset();

        for i in 0..snap.tint_count {
            let (index, _, _) = snap.tint_entry(i).ok_or(MeshError::Snapshot(SnapshotError::Truncated))?;
            if index >= SECTION_VOLUME {
                self.clear_slots(&snap, i, 0);
                return Err(MeshError::Snapshot(SnapshotError::BadIndex));
            }
            self.tint_slot[index] = i as u16;
        }
        for i in 0..snap.offset_count {
            let (index, _) = snap.offset_entry(i).ok_or(MeshError::Snapshot(SnapshotError::Truncated))?;
            if index >= SECTION_VOLUME {
                self.clear_slots(&snap, snap.tint_count, i);
                return Err(MeshError::Snapshot(SnapshotError::BadIndex));
            }
            self.offset_slot[index] = i as u16;
        }
        for i in 0..snap.fluid_count {
            let entry = snap.fluid_entry(i).ok_or(MeshError::Snapshot(SnapshotError::Truncated))?;
            if entry.index >= SECTION_VOLUME {
                self.clear_slots(&snap, snap.tint_count, snap.offset_count);
                self.clear_fluid_slots(&snap, i);
                return Err(MeshError::Snapshot(SnapshotError::BadIndex));
            }
            self.fluid_slot[entry.index] = i as u16;
        }
        let native_fluids = snap.native_fluids();

        let mask = self.light_mask.take().expect("light mask in use");
        let ctx = Ctx {
            snap: &snap,
            states,
            ao_enabled: snap.ambient_occlusion(),
            cutout_leaves: snap.cutout_leaves(),
            light_mask: use_mask.then_some(&mask[..]),
        };
        for y in 0..16 {
            for z in 0..16 {
                for x in 0..16 {
                    let index = x | (z << 4) | (y << 8);
                    let state_id = snap.state_id(x as i32, y as i32, z as i32);
                    let state = states.get(state_id);
                    if state.has(state_flags::AIR) {
                        continue;
                    }
                    if state.has(state_flags::SOLID_RENDER) {
                        self.vis_graph.set_opaque(index);
                    }
                    // Vanilla order: the fluid, then the block model.
                    if native_fluids && state.has_native_fluid() {
                        self.mesh_fluid(&ctx, [x as i32, y as i32, z as i32], index, state);
                    }
                    let model_id = snap.model(index);
                    if model_id == MODEL_NONE || model_id == MODEL_JAVA {
                        continue;
                    }
                    let starts = self.layers.each_ref().map(Vec::len);
                    let result = match models.model(model_id) {
                        Some(model) => self.tesselate(&ctx, [x as i32, y as i32, z as i32], index, state, model),
                        None => Err(Demote),
                    };
                    match result {
                        Ok(()) => self.stats.native_blocks += 1,
                        Err(Demote) => {
                            for (layer, start) in self.layers.iter_mut().zip(starts) {
                                layer.truncate(start);
                            }
                            self.demoted.push(index as u16);
                            self.stats.demoted_blocks += 1;
                        }
                    }
                }
            }
        }
        self.visibility = self.vis_graph.resolve();
        self.clear_slots(&snap, snap.tint_count, snap.offset_count);
        self.clear_fluid_slots(&snap, snap.fluid_count);
        self.light_mask = Some(mask);
        Ok(())
    }

    fn mesh_fluid(&mut self, ctx: &Ctx<'_>, pos: [i32; 3], index: usize, state: &StateInfo) {
        let result = ctx.fluid_shape(pos, state).and_then(|shape| {
            let Some(shape) = shape else { return Ok(0) };
            if !shape.draws_anything() {
                return Ok(0);
            }
            let entry = match self.fluid_slot[index] {
                NO_SLOT => return Err(Demote),
                slot => ctx.snap.fluid_entry(slot as usize).ok_or(Demote)?,
            };
            if shape.top.is_some() && !entry.flow_known {
                return Err(Demote);
            }
            ctx.emit_fluid(pos, &shape, &entry, &mut self.layers)
        });
        match result {
            Ok(quads) => {
                self.stats.native_fluids += 1;
                self.stats.quads += quads;
            }
            Err(Demote) => {
                self.demoted.push(index as u16 | DEMOTED_FLUID_BIT);
                self.stats.demoted_fluids += 1;
            }
        }
    }

    fn clear_fluid_slots(&mut self, snap: &Snapshot<'_>, count: usize) {
        for i in 0..count {
            if let Some(entry) = snap.fluid_entry(i) {
                if entry.index < SECTION_VOLUME {
                    self.fluid_slot[entry.index] = NO_SLOT;
                }
            }
        }
    }

    fn clear_slots(&mut self, snap: &Snapshot<'_>, tints: usize, offsets: usize) {
        for i in 0..tints {
            if let Some((index, _, _)) = snap.tint_entry(i) {
                if index < SECTION_VOLUME {
                    self.tint_slot[index] = NO_SLOT;
                }
            }
        }
        for i in 0..offsets {
            if let Some((index, _)) = snap.offset_entry(i) {
                if index < SECTION_VOLUME {
                    self.offset_slot[index] = NO_SLOT;
                }
            }
        }
    }

    /// `ModelBlockRenderer.tesselateBlock` for one block.
    fn tesselate(&mut self, ctx: &Ctx<'_>, pos: [i32; 3], index: usize, state: &StateInfo, model: &Model) -> Result<(), Demote> {
        if state.has(state_flags::DYNAMIC) {
            return Err(Demote);
        }
        let tints = match self.tint_slot[index] {
            NO_SLOT if state.has(state_flags::TINTED) && model.max_tint_index >= 0 => return Err(Demote),
            NO_SLOT => (0, [0; MAX_TINTS]),
            slot => {
                let (_, count, colors) = ctx.snap.tint_entry(slot as usize).ok_or(Demote)?;
                (count, colors)
            }
        };
        if tints.0 > MAX_TINTS {
            return Err(Demote);
        }
        // Blocks with an offset function carry their origin, computed by Java in
        // the convention of the active renderer (vanilla: `x + (float) offset.x`).
        let origin = match self.offset_slot[index] {
            NO_SLOT => [pos[0] as f32, pos[1] as f32, pos[2] as f32],
            slot => ctx.snap.offset_entry(slot as usize).ok_or(Demote)?.1,
        };
        let use_ao = ctx.ao_enabled && state.light_emission == 0 && model.use_ao;
        let force_solid = !ctx.cutout_leaves && state.has(state_flags::LEAVES);

        // Per-direction cache of shouldRenderFace, like vanilla's bit masks.
        let mut cull_known = 0u8;
        let mut cull_render = 0u8;
        let mut flat_light: [i32; 6] = [0; 6];

        for quad in &model.quads {
            let mut colors = [0i32; 4];
            let mut lights = [0i32; 4];
            if let Some(cull) = quad.cull {
                let bit = 1u8 << cull.ordinal();
                if cull_known & bit == 0 {
                    cull_known |= bit;
                    if ctx.should_render_face(pos, state, cull)? {
                        cull_render |= bit;
                        if !use_ao {
                            let (dx, dy, dz) = cull.step();
                            flat_light[cull.ordinal()] = ctx.light_coords_with(state, pos[0] + dx, pos[1] + dy, pos[2] + dz)?;
                        }
                    } else {
                        self.stats.culled_faces += 1;
                    }
                }
                if cull_render & bit == 0 {
                    continue;
                }
                if use_ao {
                    ctx.prepare_ao(pos, state, quad, &mut colors, &mut lights)?;
                } else {
                    ctx.prepare_flat(pos, state, quad, Some(flat_light[cull.ordinal()]), &mut colors, &mut lights)?;
                }
            } else if use_ao {
                ctx.prepare_ao(pos, state, quad, &mut colors, &mut lights)?;
            } else {
                ctx.prepare_flat(pos, state, quad, None, &mut colors, &mut lights)?;
            }

            if quad.tint_index != -1 {
                if quad.tint_index < 0 {
                    return Err(Demote);
                }
                let tint = if (quad.tint_index as usize) < tints.0 {
                    tints.1[quad.tint_index as usize]
                } else {
                    color::WHITE
                };
                for c in &mut colors {
                    *c = color::multiply(*c, tint);
                }
            }

            let layer = if force_solid { 0 } else { quad.layer as usize };
            let emission = quad.light_emission as i32;
            // One 112-byte write per quad instead of 28 small ones.
            let mut bytes = [0u8; 4 * VERTEX_SIZE];
            for (v, out) in bytes.chunks_exact_mut(VERTEX_SIZE).enumerate() {
                let p = quad.positions[v];
                out[0..4].copy_from_slice(&(p[0] + origin[0]).to_le_bytes());
                out[4..8].copy_from_slice(&(p[1] + origin[1]).to_le_bytes());
                out[8..12].copy_from_slice(&(p[2] + origin[2]).to_le_bytes());
                out[12..16].copy_from_slice(&color::to_abgr(colors[v]).to_le_bytes());
                out[16..20].copy_from_slice(&quad.uvs[v][0].to_le_bytes());
                out[20..24].copy_from_slice(&quad.uvs[v][1].to_le_bytes());
                out[24..28].copy_from_slice(&light::with_emission(lights[v], emission).to_le_bytes());
            }
            self.layers[layer].extend_from_slice(&bytes);
            self.stats.quads += 1;
        }
        Ok(())
    }
}

struct Ctx<'a> {
    snap: &'a Snapshot<'a>,
    states: &'a StateTable,
    ao_enabled: bool,
    cutout_leaves: bool,
    light_mask: Option<&'a [u8]>,
}

#[inline]
fn add(p: [i32; 3], d: Direction) -> [i32; 3] {
    let (dx, dy, dz) = d.step();
    [p[0] + dx, p[1] + dy, p[2] + dz]
}

impl<'a> Ctx<'a> {
    /// Any state the table does not know makes the block go back to Java.
    #[inline]
    fn state(&self, p: [i32; 3]) -> Result<&'a StateInfo, Demote> {
        let s = self.states.get(self.snap.state_id(p[0], p[1], p[2]));
        if s.has(state_flags::UNKNOWN) {
            Err(Demote)
        } else {
            Ok(s)
        }
    }

    /// State whose shade/collision properties are read: must be trustworthy.
    #[inline]
    fn trusted_state(&self, p: [i32; 3]) -> Result<&'a StateInfo, Demote> {
        let s = self.state(p)?;
        if s.has(state_flags::DYNAMIC) {
            Err(Demote)
        } else {
            Ok(s)
        }
    }

    /// `LightCoordsUtil.getLightCoords(state, level, pos)`.
    #[inline]
    fn light_coords_with(&self, state: &StateInfo, x: i32, y: i32, z: i32) -> Result<i32, Demote> {
        if let Some(mask) = self.light_mask {
            let i = light_index(x, y, z);
            if mask[i / 8] & (1 << (i % 8)) == 0 {
                return Err(Demote);
            }
        }
        Ok(light::light_coords(
            self.snap.raw_light(x, y, z),
            state.has(state_flags::EMISSIVE_RENDERING),
            state.light_emission as i32,
        ))
    }

    #[inline]
    fn light_at(&self, state: &StateInfo, p: [i32; 3]) -> Result<i32, Demote> {
        self.light_coords_with(state, p[0], p[1], p[2])
    }

    #[inline]
    fn cardinal(&self, d: Direction) -> f32 {
        self.snap.cardinal[d.ordinal()]
    }

    /// `Block.shouldRenderFace(state, neighbor, direction)`.
    fn should_render_face(&self, pos: [i32; 3], state: &StateInfo, d: Direction) -> Result<bool, Demote> {
        let neighbor = self.state(add(pos, d))?;
        let occluder = neighbor.face_class(d.opposite());
        if occluder == face_class::BLOCK {
            return Ok(false);
        }
        let skip = match state.skip_rule {
            skip_rule::NONE => false,
            skip_rule::SAME_BLOCK => neighbor.block_id == state.block_id,
            skip_rule::LEAVES => !self.cutout_leaves && neighbor.has(state_flags::LEAVES),
            _ => return Err(Demote),
        };
        if skip {
            return Ok(false);
        }
        if occluder == face_class::EMPTY {
            return Ok(true);
        }
        let own = state.face_class(d);
        match own {
            face_class::EMPTY => Ok(true),
            // joinIsNotEmpty(block(), occluder, ONLY_FIRST), precomputed by Java.
            face_class::BLOCK => Ok(occluder != face_class::PARTIAL_COVERING),
            _ => self
                .states
                .shape_pairs
                .join_is_not_empty(state.face_shapes[d.ordinal()], neighbor.face_shapes[d.opposite().ordinal()])
                .ok_or(Demote),
        }
    }

    /// `BlockModelLighter.prepareQuadFlat`.
    fn prepare_flat(&self, pos: [i32; 3], state: &StateInfo, quad: &bloom_core::model::Quad, known_light: Option<i32>, colors: &mut [i32; 4], lights: &mut [i32; 4]) -> Result<(), Demote> {
        let light = match known_light {
            Some(l) => l,
            None => {
                let cubic = quad.face_cubic(state.has(state_flags::COLLISION_FULL_BLOCK));
                let p = if cubic { add(pos, quad.direction) } else { pos };
                self.light_at(state, p)?
            }
        };
        *lights = [light; 4];
        let shade = self.cardinal(quad.shade_override.unwrap_or(quad.direction));
        *colors = [color::gray(shade); 4];
        Ok(())
    }

    /// `BlockModelLighter.prepareQuadAmbientOcclusion`.
    fn prepare_ao(&self, center: [i32; 3], state: &StateInfo, quad: &bloom_core::model::Quad, colors: &mut [i32; 4], lights: &mut [i32; 4]) -> Result<(), Demote> {
        let face_cubic = quad.face_cubic(state.has(state_flags::COLLISION_FULL_BLOCK));
        let dir = quad.direction;
        let base = if face_cubic { add(center, dir) } else { center };
        let info = &ao::ADJACENCY[dir.ordinal()];
        let c = info.corners;

        let mut light = [0i32; 4];
        let mut shade = [0f32; 4];
        for i in 0..4 {
            let p = add(base, c[i]);
            let s = self.trusted_state(p)?;
            light[i] = self.light_at(s, p)?;
            shade[i] = s.shade_brightness;
        }
        // The `.move(direction)` after the corner offset: two steps out for cubic faces.
        let mut permeable = [false; 4];
        for i in 0..4 {
            permeable[i] = self.state(add(add(base, c[i]), dir))?.has(state_flags::LIGHT_PERMEABLE);
        }
        let corner = |a: usize, b: usize| -> Result<(f32, i32), Demote> {
            if !permeable[b] && !permeable[a] {
                // Vanilla falls back to corner 0 for every diagonal.
                Ok((shade[0], light[0]))
            } else {
                let p = add(add(base, c[a]), c[b]);
                let s = self.trusted_state(p)?;
                Ok((s.shade_brightness, self.light_at(s, p)?))
            }
        };
        let (shade02, light02) = corner(0, 2)?;
        let (shade03, light03) = corner(0, 3)?;
        let (shade12, light12) = corner(1, 2)?;
        let (shade13, light13) = corner(1, 3)?;

        let mut light_center = self.light_at(state, center)?;
        let next = add(center, dir);
        let next_state = self.state(next)?;
        if face_cubic || !next_state.has(state_flags::SOLID_RENDER) {
            light_center = self.light_at(next_state, next)?;
        }
        let shade_center = if face_cubic { self.trusted_state(base)?.shade_brightness } else { state.shade_brightness };

        let remap = ao::REMAP[dir.ordinal()];
        let ts1 = (shade[3] + shade[0] + shade03 + shade_center) * 0.25;
        let ts2 = (shade[2] + shade[0] + shade02 + shade_center) * 0.25;
        let ts3 = (shade[2] + shade[1] + shade12 + shade_center) * 0.25;
        let ts4 = (shade[3] + shade[1] + shade13 + shade_center) * 0.25;
        let tc1 = light::smooth_blend(light[3], light[0], light03, light_center);
        let tc2 = light::smooth_blend(light[2], light[0], light02, light_center);
        let tc3 = light::smooth_blend(light[2], light[1], light12, light_center);
        let tc4 = light::smooth_blend(light[3], light[1], light13, light_center);

        if quad.face_partial {
            let fs = &quad.face_shape;
            for v in 0..4 {
                let w = &info.vert_weights[v];
                let w01 = fs[w[0]] * fs[w[1]];
                let w23 = fs[w[2]] * fs[w[3]];
                let w45 = fs[w[4]] * fs[w[5]];
                let w67 = fs[w[6]] * fs[w[7]];
                let s = (ts1 * w01 + ts2 * w23 + ts3 * w45 + ts4 * w67).clamp(0.0, 1.0);
                colors[remap[v]] = color::gray(s);
                lights[remap[v]] = light::smooth_weighted_blend(tc1, tc2, tc3, tc4, w01, w23, w45, w67);
            }
        } else {
            let ts = [ts1, ts2, ts3, ts4];
            let tc = [tc1, tc2, tc3, tc4];
            for v in 0..4 {
                lights[remap[v]] = tc[v];
                colors[remap[v]] = color::gray(ts[v]);
            }
        }

        let scale = self.cardinal(quad.shade_override.unwrap_or(dir));
        for c in colors.iter_mut() {
            *c = color::scale_rgb(*c, scale);
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests;
#[cfg(test)]
mod fluid_tests;
