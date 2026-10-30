//! `net.minecraft.core.Direction` with the same ordinals as vanilla.

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum Direction {
    Down = 0,
    Up = 1,
    North = 2,
    South = 3,
    West = 4,
    East = 5,
}

impl Direction {
    /// `Direction.values()` order, which vanilla iterates when tesselating.
    pub const ALL: [Direction; 6] = [
        Direction::Down,
        Direction::Up,
        Direction::North,
        Direction::South,
        Direction::West,
        Direction::East,
    ];

    #[inline]
    pub const fn from_ordinal(ordinal: u8) -> Option<Direction> {
        match ordinal {
            0 => Some(Direction::Down),
            1 => Some(Direction::Up),
            2 => Some(Direction::North),
            3 => Some(Direction::South),
            4 => Some(Direction::West),
            5 => Some(Direction::East),
            _ => None,
        }
    }

    #[inline]
    pub const fn ordinal(self) -> usize {
        self as usize
    }

    #[inline]
    pub const fn opposite(self) -> Direction {
        match self {
            Direction::Down => Direction::Up,
            Direction::Up => Direction::Down,
            Direction::North => Direction::South,
            Direction::South => Direction::North,
            Direction::West => Direction::East,
            Direction::East => Direction::West,
        }
    }

    /// Unit step `(dx, dy, dz)`.
    #[inline]
    pub const fn step(self) -> (i32, i32, i32) {
        match self {
            Direction::Down => (0, -1, 0),
            Direction::Up => (0, 1, 0),
            Direction::North => (0, 0, -1),
            Direction::South => (0, 0, 1),
            Direction::West => (-1, 0, 0),
            Direction::East => (1, 0, 0),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn opposite_is_involution_and_steps_cancel() {
        for d in Direction::ALL {
            assert_eq!(d.opposite().opposite(), d);
            let (x, y, z) = d.step();
            let (ox, oy, oz) = d.opposite().step();
            assert_eq!((x + ox, y + oy, z + oz), (0, 0, 0));
            assert_eq!(Direction::from_ordinal(d as u8), Some(d));
        }
    }
}
