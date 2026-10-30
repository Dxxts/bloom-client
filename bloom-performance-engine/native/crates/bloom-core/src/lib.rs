//! Shared building blocks of the Bloom Native World Engine.
//!
//! Everything that must match Minecraft bit for bit lives here: directions
//! with vanilla ordinals, ARGB arithmetic, light coordinate packing. The
//! binary protocol between Java and Rust is defined in [`protocol`] and
//! decoded into [`state::StateTable`] and [`model::ModelTable`].

pub mod color;
pub mod direction;
pub mod light;
pub mod model;
pub mod pool;
pub mod protocol;
pub mod state;

pub use direction::Direction;
