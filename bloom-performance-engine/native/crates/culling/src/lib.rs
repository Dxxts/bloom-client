//! Culling for the world renderer.
//!
//! - [`visgraph`]: exact port of vanilla's `VisGraph`, used in game for every
//!   natively meshed section (occlusion between section faces).
//! - [`frustum`]: plane extraction and batched AABB tests. In game the
//!   vanilla frustum stays in charge (see `docs/ARCHITETTURA.md`); this module
//!   is used by the benchmark and by the future native renderer.

pub mod frustum;
pub mod visgraph;
