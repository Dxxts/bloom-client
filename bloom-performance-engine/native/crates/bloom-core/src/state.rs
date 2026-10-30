//! Per-`BlockState` properties that meshing needs, indexed by the global
//! block state id (`Block.BLOCK_STATE_REGISTRY`).

use crate::protocol::{
    face_class, state_flags, Reader, NO_SHAPE, SHAPE_PAIRS_MAGIC, STATE_RECORD_SIZE, STATE_TABLE_MAGIC,
    TABLE_HEADER_SIZE,
};
use crate::Direction;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct StateInfo {
    pub flags: u32,
    pub block_id: u32,
    pub shade_brightness: f32,
    pub light_emission: u8,
    pub skip_rule: u8,
    /// Two bits per direction, see [`face_class`].
    pub face_classes: u16,
    pub face_shapes: [u16; 6],
}

impl StateInfo {
    /// Placeholder for ids Java never sent. Marked dynamic, so any block that
    /// touches it is handed back to Java instead of guessing.
    pub const UNKNOWN: StateInfo = StateInfo {
        flags: state_flags::DYNAMIC | state_flags::UNKNOWN,
        block_id: u32::MAX,
        shade_brightness: 1.0,
        light_emission: 0,
        skip_rule: 0,
        face_classes: 0,
        face_shapes: [NO_SHAPE; 6],
    };

    #[inline]
    pub fn has(&self, flag: u32) -> bool {
        self.flags & flag != 0
    }

    #[inline]
    pub fn face_class(&self, d: Direction) -> u8 {
        ((self.face_classes >> (d.ordinal() * 2)) & 3) as u8
    }
}

#[derive(Debug, PartialEq, Eq)]
pub enum TableError {
    BadHeader,
    Truncated,
    BadShapeId,
}

/// Results of `Shapes.joinIsNotEmpty(a, b, ONLY_FIRST)` for every pair of
/// partial face shapes, precomputed by Java.
#[derive(Default)]
pub struct ShapePairs {
    count: usize,
    bits: Vec<u8>,
}

impl ShapePairs {
    pub fn parse(bytes: &[u8], epoch: u32) -> Result<Self, TableError> {
        if bytes.is_empty() {
            return Ok(Self::default());
        }
        let r = Reader::new(bytes);
        if r.u32(0) != Some(SHAPE_PAIRS_MAGIC) || r.u32(12) != Some(epoch) {
            return Err(TableError::BadHeader);
        }
        let count = r.u32(8).ok_or(TableError::Truncated)? as usize;
        let len = count.checked_mul(count).ok_or(TableError::Truncated)?.div_ceil(8);
        let bits = r.slice(TABLE_HEADER_SIZE, len).ok_or(TableError::Truncated)?.to_vec();
        Ok(Self { count, bits })
    }

    pub fn from_fn(count: usize, f: impl Fn(usize, usize) -> bool) -> Self {
        let mut bits = vec![0u8; (count * count).div_ceil(8)];
        for a in 0..count {
            for b in 0..count {
                if f(a, b) {
                    let i = a * count + b;
                    bits[i / 8] |= 1 << (i % 8);
                }
            }
        }
        Self { count, bits }
    }

    pub fn len(&self) -> usize {
        self.count
    }

    pub fn is_empty(&self) -> bool {
        self.count == 0
    }

    /// `None` when either id is unknown: the caller must not guess.
    #[inline]
    pub fn join_is_not_empty(&self, a: u16, b: u16) -> Option<bool> {
        let (a, b) = (a as usize, b as usize);
        if a >= self.count || b >= self.count {
            return None;
        }
        let i = a * self.count + b;
        Some(self.bits[i / 8] & (1 << (i % 8)) != 0)
    }
}

pub struct StateTable {
    pub epoch: u32,
    states: Vec<StateInfo>,
    pub shape_pairs: ShapePairs,
}

impl StateTable {
    pub fn new(epoch: u32, states: Vec<StateInfo>, shape_pairs: ShapePairs) -> Self {
        Self { epoch, states, shape_pairs }
    }

    pub fn parse(state_bytes: &[u8], pair_bytes: &[u8]) -> Result<Self, TableError> {
        let r = Reader::new(state_bytes);
        if r.u32(0) != Some(STATE_TABLE_MAGIC) || r.u32(4) != Some(crate::protocol::PROTOCOL_VERSION) {
            return Err(TableError::BadHeader);
        }
        let count = r.u32(8).ok_or(TableError::Truncated)? as usize;
        let epoch = r.u32(12).ok_or(TableError::Truncated)?;
        let needed = count
            .checked_mul(STATE_RECORD_SIZE)
            .and_then(|n| n.checked_add(TABLE_HEADER_SIZE))
            .ok_or(TableError::Truncated)?;
        if state_bytes.len() < needed {
            return Err(TableError::Truncated);
        }
        let shape_pairs = ShapePairs::parse(pair_bytes, epoch)?;
        let mut states = Vec::with_capacity(count);
        for i in 0..count {
            let o = TABLE_HEADER_SIZE + i * STATE_RECORD_SIZE;
            let mut face_shapes = [NO_SHAPE; 6];
            for (d, shape) in face_shapes.iter_mut().enumerate() {
                *shape = r.u16(o + 16 + d * 2).ok_or(TableError::Truncated)?;
            }
            let info = StateInfo {
                flags: r.u32(o).ok_or(TableError::Truncated)?,
                block_id: r.u32(o + 4).ok_or(TableError::Truncated)?,
                shade_brightness: r.f32(o + 8).ok_or(TableError::Truncated)?,
                light_emission: r.u8(o + 12).ok_or(TableError::Truncated)?,
                skip_rule: r.u8(o + 13).ok_or(TableError::Truncated)?,
                face_classes: r.u16(o + 14).ok_or(TableError::Truncated)?,
                face_shapes,
            };
            for d in Direction::ALL {
                let class = info.face_class(d);
                let shape = info.face_shapes[d.ordinal()];
                let partial = class == face_class::PARTIAL || class == face_class::PARTIAL_COVERING;
                if partial && shape != NO_SHAPE && shape as usize >= shape_pairs.len() && !shape_pairs.is_empty() {
                    return Err(TableError::BadShapeId);
                }
            }
            states.push(info);
        }
        Ok(Self { epoch, states, shape_pairs })
    }

    #[inline]
    pub fn get(&self, id: u32) -> &StateInfo {
        self.states.get(id as usize).unwrap_or(&StateInfo::UNKNOWN)
    }

    pub fn len(&self) -> usize {
        self.states.len()
    }

    pub fn is_empty(&self) -> bool {
        self.states.is_empty()
    }
}

/// Encodes a state table in the wire format (used by tests and benchmarks).
pub fn encode_state_table(epoch: u32, states: &[StateInfo]) -> Vec<u8> {
    let mut w = crate::protocol::Writer::with_len(TABLE_HEADER_SIZE + states.len() * STATE_RECORD_SIZE);
    w.put_u32(0, STATE_TABLE_MAGIC);
    w.put_u32(4, crate::protocol::PROTOCOL_VERSION);
    w.put_u32(8, states.len() as u32);
    w.put_u32(12, epoch);
    for (i, s) in states.iter().enumerate() {
        let o = TABLE_HEADER_SIZE + i * STATE_RECORD_SIZE;
        w.put_u32(o, s.flags);
        w.put_u32(o + 4, s.block_id);
        w.put_f32(o + 8, s.shade_brightness);
        w.put_u8(o + 12, s.light_emission);
        w.put_u8(o + 13, s.skip_rule);
        w.put_u16(o + 14, s.face_classes);
        for d in 0..6 {
            w.put_u16(o + 16 + d * 2, s.face_shapes[d]);
        }
    }
    w.bytes
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn state_table_roundtrip_and_unknown_ids() {
        let stone = StateInfo {
            flags: state_flags::SOLID_RENDER | state_flags::COLLISION_FULL_BLOCK,
            block_id: 1,
            shade_brightness: 0.2,
            light_emission: 0,
            skip_rule: 0,
            face_classes: 0b01_01_01_01_01_01,
            face_shapes: [NO_SHAPE; 6],
        };
        let bytes = encode_state_table(7, &[StateInfo { flags: state_flags::AIR, ..stone }, stone]);
        let table = StateTable::parse(&bytes, &[]).unwrap();
        assert_eq!(table.epoch, 7);
        assert_eq!(*table.get(1), stone);
        assert_eq!(table.get(1).face_class(Direction::East), face_class::BLOCK);
        assert!(table.get(99).has(state_flags::DYNAMIC));
        assert_eq!(StateTable::parse(&bytes[..20], &[]).err(), Some(TableError::Truncated));
    }

    #[test]
    fn shape_pairs_lookup() {
        let pairs = ShapePairs::from_fn(3, |a, b| a != b);
        assert_eq!(pairs.join_is_not_empty(0, 0), Some(false));
        assert_eq!(pairs.join_is_not_empty(0, 2), Some(true));
        assert_eq!(pairs.join_is_not_empty(3, 0), None);
    }
}
