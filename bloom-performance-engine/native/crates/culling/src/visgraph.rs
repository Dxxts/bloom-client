//! Port of `net.minecraft.client.renderer.chunk.VisGraph` and the bit layout
//! of `VisibilitySet`.
//!
//! The result is the 64-bit word of the `BitSet` inside `VisibilitySet`:
//! bit `a + b * 6` means "visible between faces `a` and `b`". `setAll(true)`
//! sets all 64 bits, like `BitSet.set(0, size())` does in vanilla.

use bloom_core::Direction;

const SIZE: usize = 4096;
const DX: usize = 1;
const DZ: usize = 16;
const DY: usize = 256;

/// Index layout shared with vanilla: `x | z << 4 | y << 8`.
#[inline]
pub const fn index(x: usize, y: usize, z: usize) -> usize {
    x | (y << 8) | (z << 4)
}

pub struct VisGraph {
    opaque: [u64; SIZE / 64],
    empty: usize,
    queue: Vec<u16>,
}

impl Default for VisGraph {
    fn default() -> Self {
        Self::new()
    }
}

impl VisGraph {
    pub fn new() -> Self {
        Self { opaque: [0; SIZE / 64], empty: SIZE, queue: Vec::with_capacity(SIZE) }
    }

    pub fn reset(&mut self) {
        self.opaque = [0; SIZE / 64];
        self.empty = SIZE;
    }

    #[inline]
    fn get(&self, i: usize) -> bool {
        self.opaque[i >> 6] & (1 << (i & 63)) != 0
    }

    #[inline]
    fn set(&mut self, i: usize) {
        self.opaque[i >> 6] |= 1 << (i & 63);
    }

    /// `VisGraph.setOpaque`. Vanilla calls it once per solid-render block.
    #[inline]
    pub fn set_opaque(&mut self, i: usize) {
        self.set(i);
        self.empty -= 1;
    }

    /// `VisGraph.resolve`. Consumes the graph state (the flood fill marks cells).
    pub fn resolve(&mut self) -> u64 {
        if SIZE - self.empty < 256 {
            return u64::MAX;
        }
        if self.empty == 0 {
            return 0;
        }
        let mut visibility = 0u64;
        // INDEX_OF_EDGES order: x, then y, then z, keeping only boundary cells.
        for x in 0..16 {
            for y in 0..16 {
                for z in 0..16 {
                    if x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15 {
                        let i = index(x, y, z);
                        if !self.get(i) {
                            let edges = self.flood_fill(i);
                            visibility |= edges_to_bits(edges);
                        }
                    }
                }
            }
        }
        visibility
    }

    fn flood_fill(&mut self, start: usize) -> u8 {
        let mut edges = 0u8;
        self.queue.clear();
        self.queue.push(start as u16);
        self.set(start);
        let mut head = 0;
        while head < self.queue.len() {
            let i = self.queue[head] as usize;
            head += 1;
            edges |= edge_mask(i);
            for d in Direction::ALL {
                if let Some(n) = neighbor(i, d) {
                    if !self.get(n) {
                        self.set(n);
                        self.queue.push(n as u16);
                    }
                }
            }
        }
        edges
    }
}

#[inline]
fn edge_mask(i: usize) -> u8 {
    let mut m = 0u8;
    let x = i & 15;
    if x == 0 {
        m |= 1 << Direction::West.ordinal();
    } else if x == 15 {
        m |= 1 << Direction::East.ordinal();
    }
    let y = (i >> 8) & 15;
    if y == 0 {
        m |= 1 << Direction::Down.ordinal();
    } else if y == 15 {
        m |= 1 << Direction::Up.ordinal();
    }
    let z = (i >> 4) & 15;
    if z == 0 {
        m |= 1 << Direction::North.ordinal();
    } else if z == 15 {
        m |= 1 << Direction::South.ordinal();
    }
    m
}

#[inline]
fn neighbor(i: usize, d: Direction) -> Option<usize> {
    match d {
        Direction::Down => ((i >> 8) & 15 != 0).then(|| i - DY),
        Direction::Up => ((i >> 8) & 15 != 15).then(|| i + DY),
        Direction::North => ((i >> 4) & 15 != 0).then(|| i - DZ),
        Direction::South => ((i >> 4) & 15 != 15).then(|| i + DZ),
        Direction::West => (i & 15 != 0).then(|| i - DX),
        Direction::East => (i & 15 != 15).then(|| i + DX),
    }
}

/// `VisibilitySet.add(Set<Direction>)`: every pair of reached faces, including
/// each face with itself.
#[inline]
fn edges_to_bits(edges: u8) -> u64 {
    let mut bits = 0u64;
    for a in 0..6 {
        if edges & (1 << a) == 0 {
            continue;
        }
        for b in 0..6 {
            if edges & (1 << b) != 0 {
                bits |= 1 << (a + b * 6);
                bits |= 1 << (b + a * 6);
            }
        }
    }
    bits
}

/// `VisibilitySet.visibilityBetween`.
#[inline]
pub fn visible_between(set: u64, a: Direction, b: Direction) -> bool {
    set & (1 << (a.ordinal() + b.ordinal() * 6)) != 0
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fill(graph: &mut VisGraph, pred: impl Fn(usize, usize, usize) -> bool) {
        for y in 0..16 {
            for z in 0..16 {
                for x in 0..16 {
                    if pred(x, y, z) {
                        graph.set_opaque(index(x, y, z));
                    }
                }
            }
        }
    }

    #[test]
    fn sparse_section_sees_everything() {
        let mut g = VisGraph::new();
        fill(&mut g, |x, y, z| x == 0 && y == 0 && z < 10);
        assert_eq!(g.resolve(), u64::MAX);
    }

    #[test]
    fn full_section_sees_nothing() {
        let mut g = VisGraph::new();
        fill(&mut g, |_, _, _| true);
        assert_eq!(g.resolve(), 0);
    }

    #[test]
    fn horizontal_wall_separates_up_and_down() {
        let mut g = VisGraph::new();
        fill(&mut g, |_, y, _| y == 8);
        let v = g.resolve();
        assert!(!visible_between(v, Direction::Up, Direction::Down));
        assert!(visible_between(v, Direction::Up, Direction::North));
        assert!(visible_between(v, Direction::Down, Direction::East));
        assert!(visible_between(v, Direction::North, Direction::South));
        assert!(visible_between(v, Direction::Up, Direction::Up));
    }

    #[test]
    fn hole_in_wall_connects_both_sides() {
        let mut g = VisGraph::new();
        fill(&mut g, |x, y, z| y == 8 && !(x == 5 && z == 5));
        let v = g.resolve();
        assert!(visible_between(v, Direction::Up, Direction::Down));
    }
}
