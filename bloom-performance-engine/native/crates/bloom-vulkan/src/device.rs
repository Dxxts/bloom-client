//! Physical device selection and the logical device.

use std::ffi::CStr;

use ash::{ext, khr, vk};

use crate::instance::Instance;
use crate::{Error, Result};

/// What the selection knows about one physical device.
#[derive(Clone, Debug)]
pub struct Candidate {
    pub index: usize,
    pub name: String,
    pub device_type: vk::PhysicalDeviceType,
    pub api_version: u32,
    pub driver_version: u32,
    pub vendor_id: u32,
    pub device_local_bytes: u64,
    pub graphics_queue: Option<u32>,
    pub has_swapchain: bool,
    pub has_memory_budget: bool,
    pub timestamp_period: f32,
    pub timestamps_supported: bool,
}

impl Candidate {
    /// `None` when the device can run the backend, otherwise why not.
    pub fn unsuitable(&self, need_present: bool) -> Option<String> {
        if vk::api_version_major(self.api_version) == 1 && vk::api_version_minor(self.api_version) < 2 {
            return Some(format!("Vulkan {}.{} < 1.2", vk::api_version_major(self.api_version), vk::api_version_minor(self.api_version)));
        }
        if self.graphics_queue.is_none() {
            return Some("no graphics queue".into());
        }
        if need_present && !self.has_swapchain {
            return Some("no VK_KHR_swapchain".into());
        }
        None
    }

    fn score(&self) -> u64 {
        let kind = match self.device_type {
            vk::PhysicalDeviceType::DISCRETE_GPU => 4,
            vk::PhysicalDeviceType::INTEGRATED_GPU => 3,
            vk::PhysicalDeviceType::VIRTUAL_GPU => 2,
            vk::PhysicalDeviceType::CPU => 1,
            _ => 0,
        };
        kind * (1 << 40) + self.device_local_bytes.min((1 << 40) - 1)
    }

    pub fn type_name(&self) -> &'static str {
        match self.device_type {
            vk::PhysicalDeviceType::DISCRETE_GPU => "discrete",
            vk::PhysicalDeviceType::INTEGRATED_GPU => "integrated",
            vk::PhysicalDeviceType::VIRTUAL_GPU => "virtual",
            vk::PhysicalDeviceType::CPU => "cpu",
            _ => "other",
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Preference {
    /// Best suitable device: discrete > integrated > virtual > CPU, then most VRAM.
    Auto,
    Index(usize),
    /// Case-insensitive substring of the device name.
    Name(String),
}

impl Preference {
    pub fn parse(s: &str) -> Self {
        let s = s.trim();
        if s.is_empty() || s.eq_ignore_ascii_case("auto") {
            Preference::Auto
        } else if let Ok(i) = s.parse() {
            Preference::Index(i)
        } else {
            Preference::Name(s.to_lowercase())
        }
    }
}

/// Picks a device. An explicit preference that matches an unsuitable device,
/// or nothing, is an error rather than a silent fallback.
pub fn select(candidates: &[Candidate], preference: &Preference, need_present: bool) -> Result<usize> {
    let suitable = |c: &&Candidate| c.unsuitable(need_present).is_none();
    let chosen = match preference {
        Preference::Auto => candidates.iter().filter(suitable).max_by_key(|c| c.score()),
        Preference::Index(i) => candidates.iter().find(|c| c.index == *i),
        Preference::Name(n) => candidates.iter().find(|c| c.name.to_lowercase().contains(n)),
    };
    match chosen {
        None => Err(Error::NoSuitableDevice(match preference {
            Preference::Auto => "none of the devices is suitable".into(),
            other => format!("no device matches {other:?}"),
        })),
        Some(c) => match c.unsuitable(need_present) {
            Some(why) => Err(Error::NoSuitableDevice(format!("{}: {why}", c.name))),
            None => Ok(c.index),
        },
    }
}

/// Describes every physical device of the instance.
pub fn enumerate(instance: &Instance) -> Result<Vec<(vk::PhysicalDevice, Candidate)>> {
    let raw = &instance.raw;
    // SAFETY: plain queries on a live instance.
    let devices = unsafe { raw.enumerate_physical_devices() }?;
    let mut out = Vec::with_capacity(devices.len());
    for (index, &pd) in devices.iter().enumerate() {
        let props = unsafe { raw.get_physical_device_properties(pd) };
        let memory = unsafe { raw.get_physical_device_memory_properties(pd) };
        let queues = unsafe { raw.get_physical_device_queue_family_properties(pd) };
        let extensions = unsafe { raw.enumerate_device_extension_properties(pd) }.unwrap_or_default();
        let has = |name: &CStr| extensions.iter().any(|e| e.extension_name_as_c_str() == Ok(name));
        let graphics_queue = queues.iter().position(|q| q.queue_flags.contains(vk::QueueFlags::GRAPHICS)).map(|i| i as u32);
        let device_local_bytes = memory.memory_heaps[..memory.memory_heap_count as usize]
            .iter()
            .filter(|h| h.flags.contains(vk::MemoryHeapFlags::DEVICE_LOCAL))
            .map(|h| h.size)
            .sum();
        let timestamps_supported = graphics_queue.is_some_and(|q| queues[q as usize].timestamp_valid_bits > 0) && props.limits.timestamp_period > 0.0;
        out.push((
            pd,
            Candidate {
                index,
                name: props.device_name_as_c_str().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default(),
                device_type: props.device_type,
                api_version: props.api_version,
                driver_version: props.driver_version,
                vendor_id: props.vendor_id,
                device_local_bytes,
                graphics_queue,
                has_swapchain: has(khr::swapchain::NAME),
                has_memory_budget: has(ext::memory_budget::NAME),
                timestamp_period: props.limits.timestamp_period,
                timestamps_supported,
            },
        ));
    }
    Ok(out)
}

pub struct Device {
    pub physical: vk::PhysicalDevice,
    pub info: Candidate,
    pub raw: ash::Device,
    pub queue: vk::Queue,
    pub queue_family: u32,
    pub properties: vk::PhysicalDeviceProperties,
    pub memory_properties: vk::PhysicalDeviceMemoryProperties,
    pub swapchain_enabled: bool,
}

impl Device {
    pub fn new(instance: &Instance, preference: &Preference, need_present: bool) -> Result<Self> {
        let all = enumerate(instance)?;
        let candidates: Vec<Candidate> = all.iter().map(|(_, c)| c.clone()).collect();
        let index = select(&candidates, preference, need_present)?;
        let (physical, info) = all.into_iter().find(|(_, c)| c.index == index).expect("selected device exists");
        let queue_family = info.graphics_queue.expect("suitable device has a graphics queue");
        let priorities = [1.0f32];
        let queue_info = [vk::DeviceQueueCreateInfo::default().queue_family_index(queue_family).queue_priorities(&priorities)];
        let mut extensions = Vec::new();
        let swapchain_enabled = info.has_swapchain && need_present;
        if swapchain_enabled {
            extensions.push(khr::swapchain::NAME.as_ptr());
        }
        if info.has_memory_budget {
            extensions.push(ext::memory_budget::NAME.as_ptr());
        }
        let features = vk::PhysicalDeviceFeatures::default().multi_draw_indirect(true).draw_indirect_first_instance(true);
        let create = vk::DeviceCreateInfo::default().queue_create_infos(&queue_info).enabled_extension_names(&extensions).enabled_features(&features);
        // SAFETY: valid physical device and create info.
        let raw = unsafe { instance.raw.create_device(physical, &create, None) }?;
        let queue = unsafe { raw.get_device_queue(queue_family, 0) };
        let properties = unsafe { instance.raw.get_physical_device_properties(physical) };
        let memory_properties = unsafe { instance.raw.get_physical_device_memory_properties(physical) };
        Ok(Self { physical, info, raw, queue, queue_family, properties, memory_properties, swapchain_enabled })
    }

    pub fn wait_idle(&self) {
        // SAFETY: the device is alive.
        let _ = unsafe { self.raw.device_wait_idle() };
    }
}

impl Drop for Device {
    fn drop(&mut self) {
        // SAFETY: owners of child objects destroy them before the device.
        unsafe {
            let _ = self.raw.device_wait_idle();
            self.raw.destroy_device(None);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn candidate(index: usize, name: &str, ty: vk::PhysicalDeviceType, vram: u64) -> Candidate {
        Candidate {
            index,
            name: name.into(),
            device_type: ty,
            api_version: vk::make_api_version(0, 1, 3, 0),
            driver_version: 0,
            vendor_id: 0,
            device_local_bytes: vram,
            graphics_queue: Some(0),
            has_swapchain: true,
            has_memory_budget: true,
            timestamp_period: 1.0,
            timestamps_supported: true,
        }
    }

    #[test]
    fn auto_prefers_discrete_then_memory() {
        let devices = [
            candidate(0, "Intel UHD 630", vk::PhysicalDeviceType::INTEGRATED_GPU, 8 << 30),
            candidate(1, "NVIDIA GeForce GTX 1660 SUPER", vk::PhysicalDeviceType::DISCRETE_GPU, 6 << 30),
            candidate(2, "llvmpipe", vk::PhysicalDeviceType::CPU, 16 << 30),
        ];
        assert_eq!(select(&devices, &Preference::Auto, true).unwrap(), 1);
    }

    #[test]
    fn explicit_preferences_match_by_index_or_name() {
        let devices = [
            candidate(0, "Intel UHD 630", vk::PhysicalDeviceType::INTEGRATED_GPU, 1 << 30),
            candidate(1, "NVIDIA GeForce GTX 1660 SUPER", vk::PhysicalDeviceType::DISCRETE_GPU, 6 << 30),
        ];
        assert_eq!(select(&devices, &Preference::parse("0"), true).unwrap(), 0);
        assert_eq!(select(&devices, &Preference::parse("intel"), true).unwrap(), 0);
        assert_eq!(select(&devices, &Preference::parse(" auto "), true).unwrap(), 1);
        assert!(matches!(select(&devices, &Preference::parse("radeon"), true), Err(Error::NoSuitableDevice(_))));
    }

    #[test]
    fn unsuitable_devices_are_refused_even_when_asked_for() {
        let mut old = candidate(0, "Old GPU", vk::PhysicalDeviceType::DISCRETE_GPU, 4 << 30);
        old.api_version = vk::make_api_version(0, 1, 1, 0);
        let mut headless = candidate(1, "Compute card", vk::PhysicalDeviceType::DISCRETE_GPU, 4 << 30);
        headless.has_swapchain = false;
        let devices = [old, headless];
        assert!(select(&devices, &Preference::Index(0), false).is_err());
        assert!(select(&devices, &Preference::Index(1), true).is_err());
        // Without presentation the compute card is fine.
        assert_eq!(select(&devices, &Preference::Auto, false).unwrap(), 1);
    }
}
