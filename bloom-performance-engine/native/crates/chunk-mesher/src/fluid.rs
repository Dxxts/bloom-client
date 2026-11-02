//! Exact port of `FluidRenderer.tesselate` from Minecraft 26.3.
//!
//! Everything vanilla reads is a function of the block states around the
//! fluid, except the biome tint and the flow vector, which Java computes and
//! sends as a [`FluidEntry`]. Cases Bloom cannot reproduce exactly are
//! demoted to Java:
//!
//! - a partial occlusion shape on a face vanilla tests
//!   (`Shapes.blockOccludes` against the fluid's height);
//! - a neighbouring fluid of an unverified class, because vanilla calls
//!   `isSame` on the neighbour's fluid type.
//!
//! Comparing our own fluid type against any other is exact: vanilla's
//! `WaterFluid.isSame`/`LavaFluid.isSame` only compare identities, which the
//! fluid groups encode.

use super::{add, Ctx, Demote};
use crate::snapshot::FluidEntry;
use bloom_core::protocol::{face_class, fluid_group, state_flags, VERTEX_SIZE};
use bloom_core::state::{FluidModel, StateInfo};
use bloom_core::{color, light, Direction};

/// `FluidRenderer.MAX_FLUID_HEIGHT`.
const MAX_FLUID_HEIGHT: f32 = 0.888_888_9;
const OFFS: f32 = 0.001;
/// `Direction.Plane.HORIZONTAL`.
const HORIZONTAL: [Direction; 4] = [Direction::North, Direction::East, Direction::South, Direction::West];

/// Which faces vanilla draws and at which heights.
pub(crate) struct FluidShape<'t> {
    pub model: &'t FluidModel,
    /// `Some(addBackFace)` when the top face is drawn.
    pub top: Option<bool>,
    pub down: bool,
    /// Per [`HORIZONTAL`] side: `Some(isOverlay)` when drawn.
    pub sides: [Option<bool>; 4],
    /// North-west, south-west, south-east, north-east, after the top face
    /// lowered them by 0.001 (sides use the lowered values, as in vanilla).
    pub heights: [f32; 4],
    pub bottom_offs: f32,
}

impl FluidShape<'_> {
    pub fn draws_anything(&self) -> bool {
        self.top.is_some() || self.down || self.sides.iter().any(Option::is_some)
    }
}

/// `isFaceOccludedByState(direction, height, state)`.
fn occluded_by_state(d: Direction, height: f32, state: &StateInfo) -> Result<bool, Demote> {
    match state.face_class(d.opposite()) {
        face_class::EMPTY => Ok(false),
        face_class::BLOCK => Ok(d != Direction::Up || height == 1.0),
        // Shapes.blockOccludes(box(0, 0, 0, 1, height, 1), occluder, d).
        _ => Err(Demote),
    }
}

/// `isFaceOccludedBySelf(state, direction)`.
fn occluded_by_self(state: &StateInfo, d: Direction) -> Result<bool, Demote> {
    occluded_by_state(d.opposite(), 1.0, state)
}

/// `neighbor.getType().isSame(fluid.getType())`: runs the neighbour's code.
fn neighbor_same(group: u8, neighbor: &StateInfo) -> Result<bool, Demote> {
    match neighbor.fluid_group {
        fluid_group::UNVERIFIED => Err(Demote),
        g => Ok(g == group),
    }
}

impl<'a> Ctx<'a> {
    /// `FluidRenderer.getHeight(level, type, pos, state, fluidState)`.
    fn fluid_height(&self, group: u8, p: [i32; 3], s: &StateInfo) -> Result<f32, Demote> {
        if s.fluid_group == group {
            let above = self.state(add(p, Direction::Up))?;
            Ok(if above.fluid_group == group { 1.0 } else { s.fluid_amount as f32 / 9.0 })
        } else if !s.has(state_flags::SOLID) {
            Ok(0.0)
        } else {
            Ok(-1.0)
        }
    }

    /// `FluidRenderer.calculateAverageHeight`.
    fn average_height(&self, group: u8, height_self: f32, height2: f32, height1: f32, corner: [i32; 3]) -> Result<f32, Demote> {
        if height1 >= 1.0 || height2 >= 1.0 {
            return Ok(1.0);
        }
        let mut weighted = [0.0f32; 2];
        if height1 > 0.0 || height2 > 0.0 {
            let height_corner = self.fluid_height(group, corner, self.state(corner)?)?;
            if height_corner >= 1.0 {
                return Ok(1.0);
            }
            add_weighted_height(&mut weighted, height_corner);
        }
        add_weighted_height(&mut weighted, height_self);
        add_weighted_height(&mut weighted, height1);
        add_weighted_height(&mut weighted, height2);
        Ok(weighted[0] / weighted[1])
    }

    /// `FluidState.shouldRenderBackwardUpFace(level, above)`.
    fn backward_up_face(&self, group: u8, above: [i32; 3]) -> Result<bool, Demote> {
        for ox in -1..=1 {
            for oz in -1..=1 {
                let p = [above[0] + ox, above[1], above[2] + oz];
                let s = self.state(p)?;
                if !neighbor_same(group, s)? && !s.has(state_flags::SOLID_RENDER) {
                    return Ok(true);
                }
            }
        }
        Ok(false)
    }

    /// Light of `FluidRenderer.getLightCoords(level, pos)`.
    fn fluid_light(&self, p: [i32; 3]) -> Result<i32, Demote> {
        let here = self.light_at(self.state(p)?, p)?;
        let up = add(p, Direction::Up);
        let above = self.light_at(self.state(up)?, up)?;
        // LightCoordsUtil.max
        Ok(light::pack(light::block(here).max(light::block(above)), light::sky(here).max(light::sky(above))))
    }

    /// The face decisions and heights of `tesselate`, without the values
    /// only Java knows. `None`: vanilla draws nothing.
    pub(crate) fn fluid_shape(&self, pos: [i32; 3], state: &StateInfo) -> Result<Option<FluidShape<'a>>, Demote> {
        let g = state.fluid_group;
        let down = self.state(add(pos, Direction::Down))?;
        let up = self.state(add(pos, Direction::Up))?;
        let north = self.state(add(pos, Direction::North))?;
        let south = self.state(add(pos, Direction::South))?;
        let west = self.state(add(pos, Direction::West))?;
        let east = self.state(add(pos, Direction::East))?;
        // shouldRenderFace: !isNeighborSameFluid && !isFaceOccludedBySelf
        let should_render = |n: &StateInfo, d: Direction| -> Result<bool, Demote> { Ok(!neighbor_same(g, n)? && !occluded_by_self(state, d)?) };
        let render_up = !neighbor_same(g, up)?;
        let render_down = should_render(down, Direction::Down)? && !occluded_by_state(Direction::Down, MAX_FLUID_HEIGHT, down)?;
        let render_north = should_render(north, Direction::North)?;
        let render_south = should_render(south, Direction::South)?;
        let render_west = should_render(west, Direction::West)?;
        let render_east = should_render(east, Direction::East)?;
        if !(render_up || render_down || render_east || render_west || render_north || render_south) {
            return Ok(None);
        }
        let model = self.states.fluid_model(state.fluid_model).ok_or(Demote)?;

        let height_self = self.fluid_height(g, pos, state)?;
        let (mut ne, mut nw, mut se, mut sw);
        if height_self >= 1.0 {
            (ne, nw, se, sw) = (1.0, 1.0, 1.0, 1.0);
        } else {
            let height_north = self.fluid_height(g, add(pos, Direction::North), north)?;
            let height_south = self.fluid_height(g, add(pos, Direction::South), south)?;
            let height_east = self.fluid_height(g, add(pos, Direction::East), east)?;
            let height_west = self.fluid_height(g, add(pos, Direction::West), west)?;
            let n = add(pos, Direction::North);
            let s = add(pos, Direction::South);
            ne = self.average_height(g, height_self, height_north, height_east, add(n, Direction::East))?;
            nw = self.average_height(g, height_self, height_north, height_west, add(n, Direction::West))?;
            se = self.average_height(g, height_self, height_south, height_east, add(s, Direction::East))?;
            sw = self.average_height(g, height_self, height_south, height_west, add(s, Direction::West))?;
        }

        let bottom_offs = if render_down { OFFS } else { 0.0 };
        let mut top = None;
        if render_up && !occluded_by_state(Direction::Up, nw.min(sw).min(se.min(ne)), up)? {
            nw -= OFFS;
            sw -= OFFS;
            se -= OFFS;
            ne -= OFFS;
            top = Some(self.backward_up_face(g, add(pos, Direction::Up))?);
        }

        let mut sides = [None; 4];
        for (i, d) in HORIZONTAL.into_iter().enumerate() {
            let (render, neighbor, hh0, hh1) = match d {
                Direction::North => (render_north, north, nw, ne),
                Direction::South => (render_south, south, se, sw),
                Direction::West => (render_west, west, sw, nw),
                _ => (render_east, east, ne, se),
            };
            if render && !occluded_by_state(d, hh0.max(hh1), neighbor)? {
                sides[i] = Some(model.overlay.is_some() && neighbor.has(state_flags::OVERLAY_NEIGHBOR));
            }
        }
        Ok(Some(FluidShape { model, top, down: render_down, sides, heights: [nw, sw, se, ne], bottom_offs }))
    }

    /// Writes the quads of one fluid block. Returns the number of quads.
    pub(crate) fn emit_fluid(&self, pos: [i32; 3], shape: &FluidShape<'_>, entry: &FluidEntry, layers: &mut [Vec<u8>]) -> Result<u32, Demote> {
        let model = shape.model;
        let out = &mut layers[model.layer as usize];
        let start = out.len();
        let mut quads = 0;
        let result = (|| -> Result<(), Demote> {
            let (x, y, z) = (pos[0] as f32, pos[1] as f32, pos[2] as f32);
            let tint = entry.tint;
            let [nw, sw, se, ne] = shape.heights;
            let bo = shape.bottom_offs;

            if let Some(backward) = shape.top {
                let (u00, v00, u01, v01, u10, v10, u11, v11) = match entry.flow {
                    None => {
                        let sp = model.still;
                        (sp.u0, sp.v0, sp.u0, sp.v1, sp.u1, sp.v1, sp.u1, sp.v0)
                    }
                    Some((s, c)) => {
                        let sp = model.flowing;
                        // Grouping read from the 26.3 bytecode (always 0.5 + (a op b));
                        // decompilers print some of these without the parentheses.
                        (
                            sp.u(0.5 + (-c - s)),
                            sp.v(0.5 + (-c + s)),
                            sp.u(0.5 + (-c + s)),
                            sp.v(0.5 + (c + s)),
                            sp.u(0.5 + (c + s)),
                            sp.v(0.5 + (c - s)),
                            sp.u(0.5 + (c - s)),
                            sp.v(0.5 + (-c - s)),
                        )
                    }
                };
                let light = self.fluid_light(pos)?;
                let color = color::scale_rgb(tint, self.cardinal(Direction::Up));
                quads += face(
                    out,
                    [[x + 0.0, y + nw, z + 0.0, u00, v00], [x + 0.0, y + sw, z + 1.0, u01, v01], [x + 1.0, y + se, z + 1.0, u10, v10], [x + 1.0, y + ne, z + 0.0, u11, v11]],
                    color,
                    light,
                    backward,
                );
            }

            if shape.down {
                let sp = model.still;
                let light = self.fluid_light(add(pos, Direction::Down))?;
                let color = color::scale_rgb(tint, self.cardinal(Direction::Down));
                quads += face(
                    out,
                    [[x, y + bo, z, sp.u0, sp.v0], [x + 1.0, y + bo, z, sp.u1, sp.v0], [x + 1.0, y + bo, z + 1.0, sp.u1, sp.v1], [x, y + bo, z + 1.0, sp.u0, sp.v1]],
                    color,
                    light,
                    false,
                );
            }

            if shape.sides.iter().any(Option::is_some) {
                let side_light = self.fluid_light(pos)?;
                for (i, d) in HORIZONTAL.into_iter().enumerate() {
                    let Some(overlay) = shape.sides[i] else { continue };
                    let (hh0, hh1, x0, z0, x1, z1) = match d {
                        Direction::North => (nw, ne, x, z + OFFS, x + 1.0, z + OFFS),
                        Direction::South => (se, sw, x + 1.0, z + 1.0 - OFFS, x, z + 1.0 - OFFS),
                        Direction::West => (sw, nw, x + OFFS, z + 1.0, x + OFFS, z),
                        _ => (ne, se, x + 1.0 - OFFS, z, x + 1.0 - OFFS, z + 1.0),
                    };
                    let sp = match (overlay, model.overlay) {
                        (true, Some(o)) => o,
                        _ => model.flowing,
                    };
                    let u0 = sp.u(0.0);
                    let u1 = sp.u(0.5);
                    let v01 = sp.v((1.0 - hh0) * 0.5);
                    let v02 = sp.v((1.0 - hh1) * 0.5);
                    let v1 = sp.v(0.5);
                    let shade_side = if matches!(d, Direction::North | Direction::South) {
                        self.cardinal(Direction::North)
                    } else {
                        self.cardinal(Direction::West)
                    };
                    let color = color::scale_rgb(tint, self.cardinal(Direction::Up) * shade_side);
                    quads += face(
                        out,
                        [[x0, y + hh0, z0, u0, v01], [x1, y + hh1, z1, u1, v02], [x1, y + bo, z1, u1, v1], [x0, y + bo, z0, u0, v1]],
                        color,
                        side_light,
                        !overlay,
                    );
                }
            }
            Ok(())
        })();
        if result.is_err() {
            out.truncate(start);
        }
        result.map(|()| quads)
    }
}

/// `FluidRenderer.addWeightedHeight`.
fn add_weighted_height(weighted: &mut [f32; 2], height: f32) {
    if height >= 0.8 {
        weighted[0] += height * 10.0;
        weighted[1] += 10.0;
    } else if height >= 0.0 {
        weighted[0] += height;
        weighted[1] += 1.0;
    }
}

/// `FluidRenderer.addFace`: four vertices, plus the reversed back face.
fn face(out: &mut Vec<u8>, v: [[f32; 5]; 4], color: i32, light: i32, back_face: bool) -> u32 {
    let mut bytes = [0u8; 8 * VERTEX_SIZE];
    let order: &[usize] = if back_face { &[0, 1, 2, 3, 0, 3, 2, 1] } else { &[0, 1, 2, 3] };
    let abgr = color::to_abgr(color).to_le_bytes();
    for (slot, &k) in order.iter().enumerate() {
        let o = &mut bytes[slot * VERTEX_SIZE..(slot + 1) * VERTEX_SIZE];
        o[0..4].copy_from_slice(&v[k][0].to_le_bytes());
        o[4..8].copy_from_slice(&v[k][1].to_le_bytes());
        o[8..12].copy_from_slice(&v[k][2].to_le_bytes());
        o[12..16].copy_from_slice(&abgr);
        o[16..20].copy_from_slice(&v[k][3].to_le_bytes());
        o[20..24].copy_from_slice(&v[k][4].to_le_bytes());
        o[24..28].copy_from_slice(&light.to_le_bytes());
    }
    out.extend_from_slice(&bytes[..order.len() * VERTEX_SIZE]);
    order.len() as u32 / 4
}
