//! Binary protocol between the Java bridge and the native engine (version 1).
//!
//! All buffers are little-endian, live in off-heap memory owned by Java and
//! are only read during the call that receives them. Layouts are documented
//! in `docs/PROTOCOLLO.md`; the constants below are the source of truth and
//! are mirrored in `com.bloomclient.engine.bridge.Protocol`.

pub const PROTOCOL_VERSION: u32 = 1;

pub const SNAPSHOT_MAGIC: u32 = u32::from_le_bytes(*b"BLMS");
pub const STATE_TABLE_MAGIC: u32 = u32::from_le_bytes(*b"BLST");
pub const SHAPE_PAIRS_MAGIC: u32 = u32::from_le_bytes(*b"BLSP");
pub const MODEL_MAGIC: u32 = u32::from_le_bytes(*b"BLMD");

// ---- Section snapshot ------------------------------------------------------

/// Block states cover local coordinates -2..=17 on every axis. Vanilla's AO
/// reads blocks two steps away along the face normal.
pub const STATE_DIM: usize = 20;
pub const STATE_MIN: i32 = -2;
/// Light covers -1..=16 on every axis.
pub const LIGHT_DIM: usize = 18;
pub const LIGHT_MIN: i32 = -1;
pub const SECTION_DIM: usize = 16;
pub const SECTION_VOLUME: usize = 4096;

pub const SNAPSHOT_HEADER_SIZE: usize = 64;
pub const SNAPSHOT_STATES_OFFSET: usize = SNAPSHOT_HEADER_SIZE;
pub const SNAPSHOT_STATES_LEN: usize = STATE_DIM * STATE_DIM * STATE_DIM * 4;
pub const SNAPSHOT_LIGHT_OFFSET: usize = SNAPSHOT_STATES_OFFSET + SNAPSHOT_STATES_LEN;
pub const SNAPSHOT_LIGHT_LEN: usize = LIGHT_DIM * LIGHT_DIM * LIGHT_DIM;
pub const SNAPSHOT_MODELS_OFFSET: usize = SNAPSHOT_LIGHT_OFFSET + SNAPSHOT_LIGHT_LEN;
pub const SNAPSHOT_MODELS_LEN: usize = SECTION_VOLUME * 4;
pub const SNAPSHOT_TABLES_OFFSET: usize = SNAPSHOT_MODELS_OFFSET + SNAPSHOT_MODELS_LEN;
pub const TINT_ENTRY_SIZE: usize = 16;
pub const MAX_TINTS: usize = 3;
pub const OFFSET_ENTRY_SIZE: usize = 16;

pub const SNAPSHOT_FLAG_AMBIENT_OCCLUSION: u16 = 1;
pub const SNAPSHOT_FLAG_CUTOUT_LEAVES: u16 = 1 << 1;

/// Model slot value: nothing to tesselate (air, invisible, block entity only).
pub const MODEL_NONE: u32 = 0;
/// Model slot value: Java tesselates this block with the vanilla renderer.
pub const MODEL_JAVA: u32 = u32::MAX;

// ---- Mesh result ------------------------------------------------------------

pub const RESULT_SIZE: usize = 128;
pub const LAYER_COUNT: usize = 3;
pub const VERTEX_SIZE: usize = 28;

/// Status codes returned by every native entry point.
pub mod status {
    pub const OK: i32 = 0;
    /// No tables installed, or the snapshot was built for an older epoch.
    pub const STALE_TABLES: i32 = 1;
    pub const INVALID_INPUT: i32 = 2;
    pub const PANIC: i32 = 3;
    pub const INTERNAL: i32 = 4;
}

// ---- State table ------------------------------------------------------------

pub const TABLE_HEADER_SIZE: usize = 16;
pub const STATE_RECORD_SIZE: usize = 32;

pub mod state_flags {
    pub const AIR: u32 = 1;
    pub const SOLID_RENDER: u32 = 1 << 1;
    pub const LIGHT_PERMEABLE: u32 = 1 << 2;
    pub const EMISSIVE_RENDERING: u32 = 1 << 3;
    pub const COLLISION_FULL_BLOCK: u32 = 1 << 4;
    /// Some property used by meshing depends on the position (or comes from
    /// code Bloom cannot verify). Blocks that read it fall back to Java.
    pub const DYNAMIC: u32 = 1 << 5;
    pub const LEAVES: u32 = 1 << 6;
    /// `BlockColors.getTintSources(state)` is not empty: Java sends tint values.
    pub const TINTED: u32 = 1 << 7;
    /// Set only on the placeholder for ids missing from the table.
    pub const UNKNOWN: u32 = 1 << 31;
}

pub mod skip_rule {
    /// `BlockBehaviour.skipRendering` is not overridden.
    pub const NONE: u8 = 0;
    /// `HalfTransparentBlock`: skip faces against the same block.
    pub const SAME_BLOCK: u8 = 1;
    /// `LeavesBlock`: skip faces against leaves unless cutout leaves are on.
    pub const LEAVES: u8 = 2;
}

/// 2-bit face occlusion class, per direction.
pub mod face_class {
    /// `getFaceOcclusionShape(d) == Shapes.empty()` (identity).
    pub const EMPTY: u8 = 0;
    /// `getFaceOcclusionShape(d) == Shapes.block()` (identity).
    pub const BLOCK: u8 = 1;
    /// Any other shape that leaves part of the unit cube uncovered.
    pub const PARTIAL: u8 = 2;
    /// Any other shape such that `joinIsNotEmpty(block(), shape, ONLY_FIRST)` is false.
    pub const PARTIAL_COVERING: u8 = 3;
}

pub const NO_SHAPE: u16 = u16::MAX;

// ---- Model registration -------------------------------------------------------

pub const MODEL_HEADER_SIZE: usize = 32;
pub const QUAD_RECORD_SIZE: usize = 96;
pub const MODEL_FLAG_USE_AO: u32 = 1;
pub const CULL_NONE: u8 = 6;
pub const NO_SHADE_OVERRIDE: u8 = 0xFF;

/// Bounds-checked little-endian reader over a byte slice.
#[derive(Clone, Copy)]
pub struct Reader<'a> {
    bytes: &'a [u8],
}

impl<'a> Reader<'a> {
    pub fn new(bytes: &'a [u8]) -> Self {
        Self { bytes }
    }

    #[inline]
    pub fn len(&self) -> usize {
        self.bytes.len()
    }

    #[inline]
    pub fn is_empty(&self) -> bool {
        self.bytes.is_empty()
    }

    #[inline]
    fn array<const N: usize>(&self, offset: usize) -> Option<[u8; N]> {
        self.bytes.get(offset..offset.checked_add(N)?)?.try_into().ok()
    }

    #[inline]
    pub fn u8(&self, offset: usize) -> Option<u8> {
        self.bytes.get(offset).copied()
    }

    #[inline]
    pub fn u16(&self, offset: usize) -> Option<u16> {
        self.array(offset).map(u16::from_le_bytes)
    }

    #[inline]
    pub fn u32(&self, offset: usize) -> Option<u32> {
        self.array(offset).map(u32::from_le_bytes)
    }

    #[inline]
    pub fn i32(&self, offset: usize) -> Option<i32> {
        self.array(offset).map(i32::from_le_bytes)
    }

    #[inline]
    pub fn f32(&self, offset: usize) -> Option<f32> {
        self.array(offset).map(f32::from_le_bytes)
    }

    pub fn slice(&self, offset: usize, len: usize) -> Option<&'a [u8]> {
        self.bytes.get(offset..offset.checked_add(len)?)
    }
}

/// Little-endian writer used by tests and by the benchmark's scene builder.
#[derive(Default)]
pub struct Writer {
    pub bytes: Vec<u8>,
}

impl Writer {
    pub fn with_len(len: usize) -> Self {
        Self { bytes: vec![0; len] }
    }

    pub fn put_u8(&mut self, offset: usize, v: u8) {
        self.bytes[offset] = v;
    }

    pub fn put_u16(&mut self, offset: usize, v: u16) {
        self.bytes[offset..offset + 2].copy_from_slice(&v.to_le_bytes());
    }

    pub fn put_u32(&mut self, offset: usize, v: u32) {
        self.bytes[offset..offset + 4].copy_from_slice(&v.to_le_bytes());
    }

    pub fn put_i32(&mut self, offset: usize, v: i32) {
        self.bytes[offset..offset + 4].copy_from_slice(&v.to_le_bytes());
    }

    pub fn put_f32(&mut self, offset: usize, v: f32) {
        self.bytes[offset..offset + 4].copy_from_slice(&v.to_le_bytes());
    }

    pub fn put_u64(&mut self, offset: usize, v: u64) {
        self.bytes[offset..offset + 8].copy_from_slice(&v.to_le_bytes());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn layout_is_aligned() {
        assert_eq!(SNAPSHOT_LIGHT_OFFSET, 32_064);
        assert_eq!(SNAPSHOT_MODELS_OFFSET % 4, 0);
        assert_eq!(SNAPSHOT_TABLES_OFFSET % 8, 0);
        assert_eq!(SNAPSHOT_TABLES_OFFSET, 54_280);
    }

    #[test]
    fn reader_rejects_out_of_bounds() {
        let data = [1u8, 0, 0, 0, 2];
        let r = Reader::new(&data);
        assert_eq!(r.u32(0), Some(1));
        assert_eq!(r.u32(2), None);
        assert_eq!(r.u32(usize::MAX - 1), None);
        assert_eq!(r.u8(4), Some(2));
        assert_eq!(r.slice(3, 3), None);
    }
}
