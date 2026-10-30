//! Tauri command layer: thin, validated entry points that delegate to the
//! services. This is the only surface the frontend can call.

pub mod accounts;
pub mod app;
pub mod instances;
pub mod java;
pub mod launch;
pub mod system;
pub mod versions;
