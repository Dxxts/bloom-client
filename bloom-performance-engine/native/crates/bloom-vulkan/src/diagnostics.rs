//! GPU diagnostics: device report, memory budget, timestamps, counters.

use ash::vk;

use crate::device::Device;
use crate::instance::Instance;
use crate::Result;

/// Per-frame counters of the commands Bloom records.
#[derive(Clone, Copy, Debug, Default)]
pub struct Counters {
    /// API calls that draw (`vkCmdDrawIndexed`, `vkCmdDrawIndexedIndirect`).
    pub draw_calls: u32,
    /// Draws issued by those calls (an indirect call issues several).
    pub draws: u32,
    pub pipeline_binds: u32,
    pub descriptor_binds: u32,
    pub vertices: u64,
}

#[derive(Clone, Copy, Debug)]
pub struct HeapBudget {
    pub size: u64,
    pub device_local: bool,
    /// From `VK_EXT_memory_budget` when available, else the heap size.
    pub budget: u64,
    /// Process usage from `VK_EXT_memory_budget`; `None` without the extension.
    pub usage: Option<u64>,
}

pub fn memory_budget(instance: &Instance, device: &Device) -> Vec<HeapBudget> {
    let props = device.memory_properties;
    let heaps = props.memory_heap_count as usize;
    let mut budget = vk::PhysicalDeviceMemoryBudgetPropertiesEXT::default();
    let have_budget = device.info.has_memory_budget;
    if have_budget {
        let mut props2 = vk::PhysicalDeviceMemoryProperties2::default().push_next(&mut budget);
        // SAFETY: Vulkan 1.1+ query with a valid pNext chain.
        unsafe { instance.raw.get_physical_device_memory_properties2(device.physical, &mut props2) };
    }
    (0..heaps)
        .map(|i| HeapBudget {
            size: props.memory_heaps[i].size,
            device_local: props.memory_heaps[i].flags.contains(vk::MemoryHeapFlags::DEVICE_LOCAL),
            budget: if have_budget { budget.heap_budget[i] } else { props.memory_heaps[i].size },
            usage: have_budget.then(|| budget.heap_usage[i]),
        })
        .collect()
}

/// GPU time per frame from two timestamps per frame slot.
pub struct GpuTimer {
    pool: vk::QueryPool,
    slots: u32,
    period_ns: f64,
    written: Vec<bool>,
}

impl GpuTimer {
    /// `None` when the queue has no timestamp support.
    pub fn new(device: &Device, slots: u32) -> Result<Option<Self>> {
        if !device.info.timestamps_supported {
            return Ok(None);
        }
        let info = vk::QueryPoolCreateInfo::default().query_type(vk::QueryType::TIMESTAMP).query_count(slots * 2);
        // SAFETY: valid create info.
        let pool = unsafe { device.raw.create_query_pool(&info, None) }?;
        Ok(Some(Self { pool, slots, period_ns: device.info.timestamp_period as f64, written: vec![false; slots as usize] }))
    }

    pub fn begin(&mut self, device: &ash::Device, cmd: vk::CommandBuffer, slot: usize) {
        let first = slot as u32 * 2;
        // SAFETY: recording into a command buffer in the recording state.
        unsafe {
            device.cmd_reset_query_pool(cmd, self.pool, first, 2);
            device.cmd_write_timestamp(cmd, vk::PipelineStageFlags::TOP_OF_PIPE, self.pool, first);
        }
    }

    pub fn end(&mut self, device: &ash::Device, cmd: vk::CommandBuffer, slot: usize) {
        // SAFETY: as in `begin`.
        unsafe { device.cmd_write_timestamp(cmd, vk::PipelineStageFlags::BOTTOM_OF_PIPE, self.pool, slot as u32 * 2 + 1) };
        self.written[slot] = true;
    }

    /// Milliseconds of the slot's last frame. Call after its fence signalled.
    pub fn read(&mut self, device: &ash::Device, slot: usize) -> Option<f64> {
        if !std::mem::take(&mut self.written[slot]) {
            return None;
        }
        let mut data = [0u64; 2];
        // SAFETY: both queries were written by a completed submission.
        unsafe { device.get_query_pool_results(self.pool, slot as u32 * 2, &mut data, vk::QueryResultFlags::TYPE_64) }.ok()?;
        Some(data[1].wrapping_sub(data[0]) as f64 * self.period_ns / 1e6)
    }

    pub fn slots(&self) -> u32 {
        self.slots
    }

    pub fn destroy(&mut self, device: &ash::Device) {
        // SAFETY: no submission uses the pool any more.
        unsafe { device.destroy_query_pool(self.pool, None) };
    }
}

fn version(v: u32) -> String {
    format!("{}.{}.{}", vk::api_version_major(v), vk::api_version_minor(v), vk::api_version_patch(v))
}

/// Human-readable description of the selected device and the alternatives.
pub fn report(instance: &Instance, device: &Device) -> Vec<String> {
    let i = &device.info;
    let l = &device.properties.limits;
    let mut out = vec![
        format!("device: {} ({}, vendor 0x{:04x}, index {})", i.name, i.type_name(), i.vendor_id, i.index),
        format!("Vulkan {} · driver 0x{:08x}", version(i.api_version), i.driver_version),
        format!(
            "limits: max image 2D {}, max push constants {} B, max draw indirect count {}, timestamp period {} ns",
            l.max_image_dimension2_d, l.max_push_constants_size, l.max_draw_indirect_count, l.timestamp_period
        ),
        format!(
            "extensions: swapchain {}, memory budget {}, validation {}",
            device.swapchain_enabled,
            i.has_memory_budget,
            if instance.validation { "on" } else { "off" }
        ),
    ];
    for (n, h) in memory_budget(instance, device).iter().enumerate() {
        out.push(format!(
            "heap {n}: {} MiB{}, budget {} MiB, usage {}",
            h.size >> 20,
            if h.device_local { " device-local" } else { "" },
            h.budget >> 20,
            h.usage.map_or("n/a".into(), |u| format!("{} MiB", u >> 20))
        ));
    }
    out
}
