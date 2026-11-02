//! Read-only view over a section snapshot written by Java.

use bloom_core::light;
use bloom_core::protocol::*;

#[derive(Debug, PartialEq, Eq)]
pub enum SnapshotError {
    BadHeader,
    Truncated,
    BadIndex,
}

/// Validated snapshot. Every accessor is bounds-checked by construction: the
/// arrays have fixed lengths checked in [`Snapshot::parse`] and coordinates
/// are range-checked by the callers' types.
pub struct Snapshot<'a> {
    pub flags: u16,
    pub epoch: u32,
    pub section: [i32; 3],
    pub cardinal: [f32; 6],
    states: &'a [u8],
    light: &'a [u8],
    models: &'a [u8],
    pub tints: &'a [u8],
    pub tint_count: usize,
    pub offsets: &'a [u8],
    pub offset_count: usize,
    pub fluids: &'a [u8],
    pub fluid_count: usize,
}

/// Per-block fluid values only Java can compute.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct FluidEntry {
    pub index: usize,
    /// `colorInWorld` of the fluid's tint source, or -1.
    pub tint: i32,
    /// `(s, c)` of `FluidRenderer` when the flow is not zero.
    pub flow: Option<(f32, f32)>,
    /// `flow` was computed; `None` then means a zero flow.
    pub flow_known: bool,
}

impl<'a> Snapshot<'a> {
    pub fn parse(bytes: &'a [u8]) -> Result<Self, SnapshotError> {
        let r = Reader::new(bytes);
        if r.u32(0) != Some(SNAPSHOT_MAGIC) || r.u16(4).map(u32::from) != Some(PROTOCOL_VERSION) {
            return Err(SnapshotError::BadHeader);
        }
        let flags = r.u16(6).ok_or(SnapshotError::Truncated)?;
        let epoch = r.u32(8).ok_or(SnapshotError::Truncated)?;
        let tint_count = r.u32(12).ok_or(SnapshotError::Truncated)? as usize;
        let offset_count = r.u32(16).ok_or(SnapshotError::Truncated)? as usize;
        let fluid_count = r.u32(SNAPSHOT_FLUID_COUNT_OFFSET).ok_or(SnapshotError::Truncated)? as usize;
        if tint_count > SECTION_VOLUME || offset_count > SECTION_VOLUME || fluid_count > SECTION_VOLUME {
            return Err(SnapshotError::BadIndex);
        }
        let section = [
            r.i32(20).ok_or(SnapshotError::Truncated)?,
            r.i32(24).ok_or(SnapshotError::Truncated)?,
            r.i32(28).ok_or(SnapshotError::Truncated)?,
        ];
        let mut cardinal = [0f32; 6];
        for (i, c) in cardinal.iter_mut().enumerate() {
            *c = r.f32(32 + i * 4).ok_or(SnapshotError::Truncated)?;
        }
        let tint_offset = SNAPSHOT_TABLES_OFFSET;
        let offsets_offset = tint_offset + tint_count * TINT_ENTRY_SIZE;
        let fluids_offset = offsets_offset + offset_count * OFFSET_ENTRY_SIZE;
        Ok(Self {
            flags,
            epoch,
            section,
            cardinal,
            states: r.slice(SNAPSHOT_STATES_OFFSET, SNAPSHOT_STATES_LEN).ok_or(SnapshotError::Truncated)?,
            light: r.slice(SNAPSHOT_LIGHT_OFFSET, SNAPSHOT_LIGHT_LEN).ok_or(SnapshotError::Truncated)?,
            models: r.slice(SNAPSHOT_MODELS_OFFSET, SNAPSHOT_MODELS_LEN).ok_or(SnapshotError::Truncated)?,
            tints: r.slice(tint_offset, tint_count * TINT_ENTRY_SIZE).ok_or(SnapshotError::Truncated)?,
            tint_count,
            offsets: r.slice(offsets_offset, offset_count * OFFSET_ENTRY_SIZE).ok_or(SnapshotError::Truncated)?,
            offset_count,
            fluids: r.slice(fluids_offset, fluid_count * FLUID_ENTRY_SIZE).ok_or(SnapshotError::Truncated)?,
            fluid_count,
        })
    }

    #[inline]
    pub fn ambient_occlusion(&self) -> bool {
        self.flags & SNAPSHOT_FLAG_AMBIENT_OCCLUSION != 0
    }

    #[inline]
    pub fn cutout_leaves(&self) -> bool {
        self.flags & SNAPSHOT_FLAG_CUTOUT_LEAVES != 0
    }

    #[inline]
    pub fn native_fluids(&self) -> bool {
        self.flags & SNAPSHOT_FLAG_NATIVE_FLUIDS != 0
    }

    /// Global state id at local coordinates in `-2..=17`.
    #[inline]
    pub fn state_id(&self, x: i32, y: i32, z: i32) -> u32 {
        debug_assert!((STATE_MIN..STATE_MIN + STATE_DIM as i32).contains(&x));
        let i = ((x - STATE_MIN) as usize)
            + ((z - STATE_MIN) as usize) * STATE_DIM
            + ((y - STATE_MIN) as usize) * STATE_DIM * STATE_DIM;
        let b = &self.states[i * 4..i * 4 + 4];
        u32::from_le_bytes([b[0], b[1], b[2], b[3]])
    }

    /// `pack(block, sky)` at local coordinates in `-1..=16`.
    #[inline]
    pub fn raw_light(&self, x: i32, y: i32, z: i32) -> i32 {
        debug_assert!((LIGHT_MIN..LIGHT_MIN + LIGHT_DIM as i32).contains(&y));
        let i = ((x - LIGHT_MIN) as usize)
            + ((z - LIGHT_MIN) as usize) * LIGHT_DIM
            + ((y - LIGHT_MIN) as usize) * LIGHT_DIM * LIGHT_DIM;
        light::unpack_snapshot_byte(self.light[i])
    }

    /// Model slot of block `index` (`x | z << 4 | y << 8`).
    #[inline]
    pub fn model(&self, index: usize) -> u32 {
        let b = &self.models[index * 4..index * 4 + 4];
        u32::from_le_bytes([b[0], b[1], b[2], b[3]])
    }

    pub fn tint_entry(&self, i: usize) -> Option<(usize, usize, [i32; MAX_TINTS])> {
        let r = Reader::new(self.tints);
        let o = i * TINT_ENTRY_SIZE;
        let index = r.u16(o)? as usize;
        let count = r.u8(o + 2)? as usize;
        let mut colors = [0i32; MAX_TINTS];
        for (k, c) in colors.iter_mut().enumerate() {
            *c = r.i32(o + 4 + k * 4)?;
        }
        Some((index, count, colors))
    }

    /// Origin of a block with an offset function (section-relative position plus offset).
    pub fn offset_entry(&self, i: usize) -> Option<(usize, [f32; 3])> {
        let r = Reader::new(self.offsets);
        let o = i * OFFSET_ENTRY_SIZE;
        Some((r.u16(o)? as usize, [r.f32(o + 4)?, r.f32(o + 8)?, r.f32(o + 12)?]))
    }

    pub fn fluid_entry(&self, i: usize) -> Option<FluidEntry> {
        let r = Reader::new(self.fluids);
        let o = i * FLUID_ENTRY_SIZE;
        let flags = r.u8(o + 2)?;
        Some(FluidEntry {
            index: r.u16(o)? as usize,
            tint: r.i32(o + 4)?,
            flow: if flags & FLUID_ENTRY_HAS_FLOW != 0 { Some((r.f32(o + 8)?, r.f32(o + 12)?)) } else { None },
            flow_known: flags & FLUID_ENTRY_FLOW_KNOWN != 0,
        })
    }
}

/// Builds snapshots in the wire format, mirroring the Java writer. Used by
/// tests and by the benchmark.
pub struct SnapshotBuilder {
    pub w: Writer,
    tints: Vec<(u16, Vec<i32>)>,
    offsets: Vec<(u16, [f32; 3])>,
    fluids: Vec<FluidEntry>,
}

impl SnapshotBuilder {
    pub fn new(epoch: u32, flags: u16, section: [i32; 3], cardinal: [f32; 6]) -> Self {
        let mut w = Writer::with_len(SNAPSHOT_TABLES_OFFSET);
        w.put_u32(0, SNAPSHOT_MAGIC);
        w.put_u16(4, PROTOCOL_VERSION as u16);
        w.put_u16(6, flags);
        w.put_u32(8, epoch);
        for (i, s) in section.iter().enumerate() {
            w.put_i32(20 + i * 4, *s);
        }
        for (i, c) in cardinal.iter().enumerate() {
            w.put_f32(32 + i * 4, *c);
        }
        Self { w, tints: Vec::new(), offsets: Vec::new(), fluids: Vec::new() }
    }

    pub fn set_state(&mut self, x: i32, y: i32, z: i32, id: u32) {
        let i = ((x - STATE_MIN) as usize)
            + ((z - STATE_MIN) as usize) * STATE_DIM
            + ((y - STATE_MIN) as usize) * STATE_DIM * STATE_DIM;
        self.w.put_u32(SNAPSHOT_STATES_OFFSET + i * 4, id);
    }

    pub fn set_light(&mut self, x: i32, y: i32, z: i32, block: u8, sky: u8) {
        let i = ((x - LIGHT_MIN) as usize)
            + ((z - LIGHT_MIN) as usize) * LIGHT_DIM
            + ((y - LIGHT_MIN) as usize) * LIGHT_DIM * LIGHT_DIM;
        self.w.put_u8(SNAPSHOT_LIGHT_OFFSET + i, (sky << 4) | block);
    }

    pub fn set_model(&mut self, index: usize, model: u32) {
        self.w.put_u32(SNAPSHOT_MODELS_OFFSET + index * 4, model);
    }

    pub fn add_tint(&mut self, index: usize, colors: &[i32]) {
        self.tints.push((index as u16, colors.to_vec()));
    }

    pub fn add_offset(&mut self, index: usize, offset: [f32; 3]) {
        self.offsets.push((index as u16, offset));
    }

    pub fn add_fluid(&mut self, entry: FluidEntry) {
        self.fluids.push(entry);
    }

    pub fn finish(mut self) -> Vec<u8> {
        self.w.put_u32(12, self.tints.len() as u32);
        self.w.put_u32(16, self.offsets.len() as u32);
        self.w.put_u32(SNAPSHOT_FLUID_COUNT_OFFSET, self.fluids.len() as u32);
        let mut bytes = self.w.bytes;
        for (index, colors) in &self.tints {
            let mut e = [0u8; TINT_ENTRY_SIZE];
            e[0..2].copy_from_slice(&index.to_le_bytes());
            e[2] = colors.len() as u8;
            for (k, c) in colors.iter().take(MAX_TINTS).enumerate() {
                e[4 + k * 4..8 + k * 4].copy_from_slice(&c.to_le_bytes());
            }
            bytes.extend_from_slice(&e);
        }
        for (index, o) in &self.offsets {
            let mut e = [0u8; OFFSET_ENTRY_SIZE];
            e[0..2].copy_from_slice(&index.to_le_bytes());
            for k in 0..3 {
                e[4 + k * 4..8 + k * 4].copy_from_slice(&o[k].to_le_bytes());
            }
            bytes.extend_from_slice(&e);
        }
        for f in &self.fluids {
            bytes.extend_from_slice(&encode_fluid_entry(f));
        }
        bytes
    }
}

pub fn encode_fluid_entry(f: &FluidEntry) -> [u8; FLUID_ENTRY_SIZE] {
    let mut e = [0u8; FLUID_ENTRY_SIZE];
    e[0..2].copy_from_slice(&(f.index as u16).to_le_bytes());
    e[4..8].copy_from_slice(&f.tint.to_le_bytes());
    if f.flow_known {
        e[2] = FLUID_ENTRY_FLOW_KNOWN;
    }
    if let Some((s, c)) = f.flow {
        e[2] |= FLUID_ENTRY_HAS_FLOW | FLUID_ENTRY_FLOW_KNOWN;
        e[8..12].copy_from_slice(&s.to_le_bytes());
        e[12..16].copy_from_slice(&c.to_le_bytes());
    }
    e
}
