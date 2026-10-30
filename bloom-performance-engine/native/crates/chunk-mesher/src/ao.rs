//! Tables of `BlockModelLighter.AdjacencyInfo` and `AmbientVertexRemap`,
//! copied from Minecraft 26.3.

use bloom_core::model::size_info::*;
use bloom_core::Direction::{self, *};

pub struct Adjacency {
    pub corners: [Direction; 4],
    pub vert_weights: [[usize; 8]; 4],
}

pub const ADJACENCY: [Adjacency; 6] = [
    // DOWN
    Adjacency {
        corners: [West, East, North, South],
        vert_weights: [
            [FLIP_WEST, SOUTH, FLIP_WEST, FLIP_SOUTH, WEST, FLIP_SOUTH, WEST, SOUTH],
            [FLIP_WEST, NORTH, FLIP_WEST, FLIP_NORTH, WEST, FLIP_NORTH, WEST, NORTH],
            [FLIP_EAST, NORTH, FLIP_EAST, FLIP_NORTH, EAST, FLIP_NORTH, EAST, NORTH],
            [FLIP_EAST, SOUTH, FLIP_EAST, FLIP_SOUTH, EAST, FLIP_SOUTH, EAST, SOUTH],
        ],
    },
    // UP
    Adjacency {
        corners: [East, West, North, South],
        vert_weights: [
            [EAST, SOUTH, EAST, FLIP_SOUTH, FLIP_EAST, FLIP_SOUTH, FLIP_EAST, SOUTH],
            [EAST, NORTH, EAST, FLIP_NORTH, FLIP_EAST, FLIP_NORTH, FLIP_EAST, NORTH],
            [WEST, NORTH, WEST, FLIP_NORTH, FLIP_WEST, FLIP_NORTH, FLIP_WEST, NORTH],
            [WEST, SOUTH, WEST, FLIP_SOUTH, FLIP_WEST, FLIP_SOUTH, FLIP_WEST, SOUTH],
        ],
    },
    // NORTH
    Adjacency {
        corners: [Up, Down, East, West],
        vert_weights: [
            [UP, FLIP_WEST, UP, WEST, FLIP_UP, WEST, FLIP_UP, FLIP_WEST],
            [UP, FLIP_EAST, UP, EAST, FLIP_UP, EAST, FLIP_UP, FLIP_EAST],
            [DOWN, FLIP_EAST, DOWN, EAST, FLIP_DOWN, EAST, FLIP_DOWN, FLIP_EAST],
            [DOWN, FLIP_WEST, DOWN, WEST, FLIP_DOWN, WEST, FLIP_DOWN, FLIP_WEST],
        ],
    },
    // SOUTH
    Adjacency {
        corners: [West, East, Down, Up],
        vert_weights: [
            [UP, FLIP_WEST, FLIP_UP, FLIP_WEST, FLIP_UP, WEST, UP, WEST],
            [DOWN, FLIP_WEST, FLIP_DOWN, FLIP_WEST, FLIP_DOWN, WEST, DOWN, WEST],
            [DOWN, FLIP_EAST, FLIP_DOWN, FLIP_EAST, FLIP_DOWN, EAST, DOWN, EAST],
            [UP, FLIP_EAST, FLIP_UP, FLIP_EAST, FLIP_UP, EAST, UP, EAST],
        ],
    },
    // WEST
    Adjacency {
        corners: [Up, Down, North, South],
        vert_weights: [
            [UP, SOUTH, UP, FLIP_SOUTH, FLIP_UP, FLIP_SOUTH, FLIP_UP, SOUTH],
            [UP, NORTH, UP, FLIP_NORTH, FLIP_UP, FLIP_NORTH, FLIP_UP, NORTH],
            [DOWN, NORTH, DOWN, FLIP_NORTH, FLIP_DOWN, FLIP_NORTH, FLIP_DOWN, NORTH],
            [DOWN, SOUTH, DOWN, FLIP_SOUTH, FLIP_DOWN, FLIP_SOUTH, FLIP_DOWN, SOUTH],
        ],
    },
    // EAST
    Adjacency {
        corners: [Down, Up, North, South],
        vert_weights: [
            [FLIP_DOWN, SOUTH, FLIP_DOWN, FLIP_SOUTH, DOWN, FLIP_SOUTH, DOWN, SOUTH],
            [FLIP_DOWN, NORTH, FLIP_DOWN, FLIP_NORTH, DOWN, FLIP_NORTH, DOWN, NORTH],
            [FLIP_UP, NORTH, FLIP_UP, FLIP_NORTH, UP, FLIP_NORTH, UP, NORTH],
            [FLIP_UP, SOUTH, FLIP_UP, FLIP_SOUTH, UP, FLIP_SOUTH, UP, SOUTH],
        ],
    },
];

/// `AmbientVertexRemap`: output vertex for computed corner 0..3.
pub const REMAP: [[usize; 4]; 6] = [
    [0, 1, 2, 3], // DOWN
    [2, 3, 0, 1], // UP
    [3, 0, 1, 2], // NORTH
    [0, 1, 2, 3], // SOUTH
    [3, 0, 1, 2], // WEST
    [1, 2, 3, 0], // EAST
];
