//! Static block model geometry registered by Java.
//!
//! One model is the ordered list of quads a block produces for one choice of
//! model parts (`BlockStateModel.collectParts`). Quads keep the vanilla
//! iteration order (part by part; directions `DOWN..EAST`, then unculled),
//! because coplanar overlays such as grass sides depend on draw order.

use crate::protocol::{
    Reader, CULL_NONE, MODEL_FLAG_USE_AO, MODEL_HEADER_SIZE, MODEL_MAGIC, NO_SHADE_OVERRIDE, PROTOCOL_VERSION,
    QUAD_RECORD_SIZE,
};
use crate::Direction;

/// Indices into [`Quad::face_shape`], same as vanilla's `BlockModelLighter.SizeInfo`.
pub mod size_info {
    pub const DOWN: usize = 0;
    pub const UP: usize = 1;
    pub const NORTH: usize = 2;
    pub const SOUTH: usize = 3;
    pub const WEST: usize = 4;
    pub const EAST: usize = 5;
    pub const FLIP_DOWN: usize = 6;
    pub const FLIP_UP: usize = 7;
    pub const FLIP_NORTH: usize = 8;
    pub const FLIP_SOUTH: usize = 9;
    pub const FLIP_WEST: usize = 10;
    pub const FLIP_EAST: usize = 11;
}

#[derive(Clone, Debug, PartialEq)]
pub struct Quad {
    pub positions: [[f32; 3]; 4],
    pub uvs: [[f32; 2]; 4],
    pub tint_index: i32,
    pub direction: Direction,
    /// Face this quad is culled against, `None` for unculled quads.
    pub cull: Option<Direction>,
    pub layer: u8,
    pub shade_override: Option<Direction>,
    pub light_emission: u8,
    // Precomputed parts of `BlockModelLighter.prepareQuadShape`.
    pub face_shape: [f32; 12],
    pub face_partial: bool,
    /// The quad is planar on its own axis.
    pub planar: bool,
    /// Planar and on the block boundary: cubic regardless of the collision shape.
    pub on_boundary: bool,
}

impl Quad {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        positions: [[f32; 3]; 4],
        uvs: [[f32; 2]; 4],
        tint_index: i32,
        direction: Direction,
        cull: Option<Direction>,
        layer: u8,
        shade_override: Option<Direction>,
        light_emission: u8,
    ) -> Self {
        let (mut min_x, mut min_y, mut min_z) = (32.0f32, 32.0f32, 32.0f32);
        let (mut max_x, mut max_y, mut max_z) = (-32.0f32, -32.0f32, -32.0f32);
        for p in &positions {
            // Math.min/Math.max on floats; positions are never NaN.
            min_x = min_x.min(p[0]);
            min_y = min_y.min(p[1]);
            min_z = min_z.min(p[2]);
            max_x = max_x.max(p[0]);
            max_y = max_y.max(p[1]);
            max_z = max_z.max(p[2]);
        }
        let mut face_shape = [0.0f32; 12];
        face_shape[size_info::WEST] = min_x;
        face_shape[size_info::EAST] = max_x;
        face_shape[size_info::DOWN] = min_y;
        face_shape[size_info::UP] = max_y;
        face_shape[size_info::NORTH] = min_z;
        face_shape[size_info::SOUTH] = max_z;
        face_shape[size_info::FLIP_WEST] = 1.0 - min_x;
        face_shape[size_info::FLIP_EAST] = 1.0 - max_x;
        face_shape[size_info::FLIP_DOWN] = 1.0 - min_y;
        face_shape[size_info::FLIP_UP] = 1.0 - max_y;
        face_shape[size_info::FLIP_NORTH] = 1.0 - min_z;
        face_shape[size_info::FLIP_SOUTH] = 1.0 - max_z;

        const MIN_EPS: f32 = 1.0E-4;
        const MAX_EPS: f32 = 0.9999;
        let face_partial = match direction {
            Direction::Down | Direction::Up => min_x >= MIN_EPS || min_z >= MIN_EPS || max_x <= MAX_EPS || max_z <= MAX_EPS,
            Direction::North | Direction::South => {
                min_x >= MIN_EPS || min_y >= MIN_EPS || max_x <= MAX_EPS || max_y <= MAX_EPS
            }
            Direction::West | Direction::East => min_y >= MIN_EPS || min_z >= MIN_EPS || max_y <= MAX_EPS || max_z <= MAX_EPS,
        };
        let (planar, on_boundary) = match direction {
            Direction::Down => (min_y == max_y, min_y < MIN_EPS),
            Direction::Up => (min_y == max_y, max_y > MAX_EPS),
            Direction::North => (min_z == max_z, min_z < MIN_EPS),
            Direction::South => (min_z == max_z, max_z > MAX_EPS),
            Direction::West => (min_x == max_x, min_x < MIN_EPS),
            Direction::East => (min_x == max_x, max_x > MAX_EPS),
        };
        Self {
            positions,
            uvs,
            tint_index,
            direction,
            cull,
            layer,
            shade_override,
            light_emission,
            face_shape,
            face_partial,
            planar,
            on_boundary: planar && on_boundary,
        }
    }

    /// `faceCubic` of `prepareQuadShape`, given `state.isCollisionShapeFullBlock`.
    #[inline]
    pub fn face_cubic(&self, collision_full_block: bool) -> bool {
        self.on_boundary || (self.planar && collision_full_block)
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct Model {
    /// `useAmbientOcclusion()` of the first part, as vanilla checks.
    pub use_ao: bool,
    pub quads: Vec<Quad>,
    /// Highest `tintIndex` used by any quad, or -1.
    pub max_tint_index: i32,
}

#[derive(Debug, PartialEq, Eq)]
pub enum ModelError {
    BadHeader,
    StaleEpoch,
    Truncated,
    BadValue,
}

impl Model {
    /// Parses a registration buffer. Returns the epoch it was built for.
    pub fn parse(bytes: &[u8]) -> Result<(u32, Model), ModelError> {
        let r = Reader::new(bytes);
        if r.u32(0) != Some(MODEL_MAGIC) || r.u32(4) != Some(PROTOCOL_VERSION) {
            return Err(ModelError::BadHeader);
        }
        let epoch = r.u32(8).ok_or(ModelError::Truncated)?;
        let quad_count = r.u32(12).ok_or(ModelError::Truncated)? as usize;
        let flags = r.u32(16).ok_or(ModelError::Truncated)?;
        let needed = quad_count
            .checked_mul(QUAD_RECORD_SIZE)
            .and_then(|n| n.checked_add(MODEL_HEADER_SIZE))
            .ok_or(ModelError::Truncated)?;
        if bytes.len() < needed {
            return Err(ModelError::Truncated);
        }
        let mut quads = Vec::with_capacity(quad_count);
        let mut max_tint_index = -1;
        for q in 0..quad_count {
            let o = MODEL_HEADER_SIZE + q * QUAD_RECORD_SIZE;
            let mut positions = [[0f32; 3]; 4];
            let mut uvs = [[0f32; 2]; 4];
            for v in 0..4 {
                for c in 0..3 {
                    positions[v][c] = r.f32(o + v * 12 + c * 4).ok_or(ModelError::Truncated)?;
                }
                for c in 0..2 {
                    uvs[v][c] = r.f32(o + 48 + v * 8 + c * 4).ok_or(ModelError::Truncated)?;
                }
            }
            if positions.iter().flatten().any(|p| !p.is_finite()) {
                return Err(ModelError::BadValue);
            }
            let tint_index = r.i32(o + 80).ok_or(ModelError::Truncated)?;
            let direction = Direction::from_ordinal(r.u8(o + 84).ok_or(ModelError::Truncated)?).ok_or(ModelError::BadValue)?;
            let cull = match r.u8(o + 85).ok_or(ModelError::Truncated)? {
                CULL_NONE => None,
                d => Some(Direction::from_ordinal(d).ok_or(ModelError::BadValue)?),
            };
            let layer = r.u8(o + 86).ok_or(ModelError::Truncated)?;
            if layer as usize >= crate::protocol::LAYER_COUNT {
                return Err(ModelError::BadValue);
            }
            let shade_override = match r.u8(o + 87).ok_or(ModelError::Truncated)? {
                NO_SHADE_OVERRIDE => None,
                d => Some(Direction::from_ordinal(d).ok_or(ModelError::BadValue)?),
            };
            let light_emission = r.u8(o + 88).ok_or(ModelError::Truncated)?;
            if light_emission > 15 {
                return Err(ModelError::BadValue);
            }
            max_tint_index = max_tint_index.max(tint_index);
            quads.push(Quad::new(positions, uvs, tint_index, direction, cull, layer, shade_override, light_emission));
        }
        Ok((epoch, Model { use_ao: flags & MODEL_FLAG_USE_AO != 0, quads, max_tint_index }))
    }
}

/// Encodes a model in the wire format (used by tests and benchmarks).
pub fn encode_model(epoch: u32, use_ao: bool, quads: &[Quad]) -> Vec<u8> {
    let mut w = crate::protocol::Writer::with_len(MODEL_HEADER_SIZE + quads.len() * QUAD_RECORD_SIZE);
    w.put_u32(0, MODEL_MAGIC);
    w.put_u32(4, PROTOCOL_VERSION);
    w.put_u32(8, epoch);
    w.put_u32(12, quads.len() as u32);
    w.put_u32(16, if use_ao { MODEL_FLAG_USE_AO } else { 0 });
    for (i, q) in quads.iter().enumerate() {
        let o = MODEL_HEADER_SIZE + i * QUAD_RECORD_SIZE;
        for v in 0..4 {
            for c in 0..3 {
                w.put_f32(o + v * 12 + c * 4, q.positions[v][c]);
            }
            for c in 0..2 {
                w.put_f32(o + 48 + v * 8 + c * 4, q.uvs[v][c]);
            }
        }
        w.put_i32(o + 80, q.tint_index);
        w.put_u8(o + 84, q.direction as u8);
        w.put_u8(o + 85, q.cull.map_or(CULL_NONE, |d| d as u8));
        w.put_u8(o + 86, q.layer);
        w.put_u8(o + 87, q.shade_override.map_or(NO_SHADE_OVERRIDE, |d| d as u8));
        w.put_u8(o + 88, q.light_emission);
    }
    w.bytes
}

/// A full-cube face for tests and synthetic benchmark scenes. In game, every
/// quad comes from Minecraft's baked models through Java.
pub fn cube_face(direction: Direction, uv: [f32; 4], tint_index: i32, layer: u8) -> Quad {
    let [u0, v0, u1, v1] = uv;
    let positions = match direction {
        Direction::Down => [[0.0, 0.0, 1.0], [0.0, 0.0, 0.0], [1.0, 0.0, 0.0], [1.0, 0.0, 1.0]],
        Direction::Up => [[0.0, 1.0, 0.0], [0.0, 1.0, 1.0], [1.0, 1.0, 1.0], [1.0, 1.0, 0.0]],
        Direction::North => [[1.0, 1.0, 0.0], [1.0, 0.0, 0.0], [0.0, 0.0, 0.0], [0.0, 1.0, 0.0]],
        Direction::South => [[0.0, 1.0, 1.0], [0.0, 0.0, 1.0], [1.0, 0.0, 1.0], [1.0, 1.0, 1.0]],
        Direction::West => [[0.0, 1.0, 0.0], [0.0, 0.0, 0.0], [0.0, 0.0, 1.0], [0.0, 1.0, 1.0]],
        Direction::East => [[1.0, 1.0, 1.0], [1.0, 0.0, 1.0], [1.0, 0.0, 0.0], [1.0, 1.0, 0.0]],
    };
    let uvs = [[u0, v0], [u0, v1], [u1, v1], [u1, v0]];
    Quad::new(positions, uvs, tint_index, direction, Some(direction), layer, None, 0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cube_faces_are_cubic_and_full() {
        for d in Direction::ALL {
            let q = cube_face(d, [0.0, 0.0, 1.0, 1.0], -1, 0);
            assert!(q.planar && q.on_boundary, "{d:?}");
            assert!(!q.face_partial, "{d:?}");
        }
    }

    #[test]
    fn slab_top_is_planar_but_not_on_boundary() {
        let mut q = cube_face(Direction::Up, [0.0, 0.0, 1.0, 1.0], -1, 0);
        for p in &mut q.positions {
            p[1] = 0.5;
        }
        let q = Quad::new(q.positions, q.uvs, -1, Direction::Up, None, 0, None, 0);
        assert!(q.planar && !q.on_boundary);
        assert!(q.face_cubic(true) && !q.face_cubic(false));
    }

    #[test]
    fn model_roundtrip() {
        let quads: Vec<Quad> = Direction::ALL.iter().map(|&d| cube_face(d, [0.1, 0.2, 0.3, 0.4], 0, 1)).collect();
        let bytes = encode_model(3, true, &quads);
        let (epoch, model) = Model::parse(&bytes).unwrap();
        assert_eq!(epoch, 3);
        assert!(model.use_ao);
        assert_eq!(model.quads, quads);
        assert_eq!(model.max_tint_index, 0);
        assert_eq!(Model::parse(&bytes[..40]).err(), Some(ModelError::Truncated));
    }
}
