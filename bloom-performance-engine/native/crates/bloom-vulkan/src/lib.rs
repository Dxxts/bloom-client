//! Bloom's own Vulkan backend. **Experimental, not used by the game.**
//!
//! Minecraft 26.3 already renders through its own Vulkan backend
//! (`renderpearl`), whose `VkDevice` cannot share resources with a second
//! device without explicit interop (external memory, shared semaphores).
//! This crate is therefore a standalone backend: it renders the meshes the
//! Bloom mesher produces, so that a Bloom-owned Vulkan path can be measured
//! (see `bloom-gpu-bench`) before any in-game use. OpenGL and Minecraft's
//! Vulkan stay the game's backends.
//!
//! | Module | What it does |
//! |---|---|
//! | [`instance`] | Loader, instance, validation layer and debug messages when available |
//! | [`device`] | Physical device enumeration, scoring and selection; logical device and queues |
//! | [`memory`] | Block sub-allocator per memory type, dedicated allocations, statistics |
//! | [`resources`] | Buffers and images bound to allocations, staging uploads |
//! | [`frame`] | Command pools, frames in flight (fences, semaphores), deferred destruction |
//! | [`descriptor`] | Descriptor set layout cache and growing descriptor pools |
//! | [`pipeline`] | GLSL → SPIR-V compilation, shader module and pipeline caches |
//! | [`swapchain`] | Win32 window, surface and swapchain (re)creation |
//! | [`target`] | Offscreen and swapchain render targets, readback |
//! | [`diagnostics`] | Device report, memory budget, GPU timestamps, counters |
//! | [`terrain`] | Renderer for Bloom section meshes (`BLOCK` vertex format) |

pub mod descriptor;
pub mod device;
pub mod diagnostics;
pub mod frame;
pub mod instance;
pub mod memory;
pub mod pipeline;
pub mod resources;
pub mod swapchain;
pub mod target;
pub mod terrain;

use ash::vk;

#[derive(Debug)]
pub enum Error {
    /// `vulkan-1.dll` could not be loaded.
    Loader(String),
    Vulkan(vk::Result),
    NoSuitableDevice(String),
    NoMemoryType,
    Shader(String),
    Window(String),
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Error::Loader(e) => write!(f, "Vulkan loader: {e}"),
            Error::Vulkan(r) => write!(f, "Vulkan error {r:?}"),
            Error::NoSuitableDevice(why) => write!(f, "no suitable Vulkan device: {why}"),
            Error::NoMemoryType => write!(f, "no memory type matches the request"),
            Error::Shader(e) => write!(f, "shader: {e}"),
            Error::Window(e) => write!(f, "window: {e}"),
        }
    }
}

impl std::error::Error for Error {}

impl From<vk::Result> for Error {
    fn from(r: vk::Result) -> Self {
        Error::Vulkan(r)
    }
}

pub type Result<T> = std::result::Result<T, Error>;
