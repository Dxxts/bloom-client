//! GPU memory allocation.
//!
//! Vulkan limits the number of `vkAllocateMemory` calls (often 4096), so
//! buffers and images are sub-allocated from large blocks, one list of
//! blocks per memory type. Inside a block, [`RangeAllocator`] keeps the free
//! ranges sorted by offset: first fit with alignment, coalescing on free.
//! Requests larger than half a block get a dedicated allocation.

use ash::vk;

use crate::{Error, Result};

/// Free-range bookkeeping of one block. Pure logic, no Vulkan calls.
#[derive(Debug)]
pub struct RangeAllocator {
    size: u64,
    /// Free ranges `(offset, len)`, sorted by offset, never adjacent.
    free: Vec<(u64, u64)>,
    used: u64,
}

impl RangeAllocator {
    pub fn new(size: u64) -> Self {
        Self { size, free: vec![(0, size)], used: 0 }
    }

    pub fn size(&self) -> u64 {
        self.size
    }

    pub fn used(&self) -> u64 {
        self.used
    }

    pub fn is_empty(&self) -> bool {
        self.used == 0
    }

    /// First fit. Returns the aligned offset; the padding before it stays free.
    pub fn allocate(&mut self, size: u64, alignment: u64) -> Option<u64> {
        let alignment = alignment.max(1);
        for i in 0..self.free.len() {
            let (start, len) = self.free[i];
            let aligned = start.div_ceil(alignment) * alignment;
            let padding = aligned - start;
            if len < padding || len - padding < size {
                continue;
            }
            let tail = len - padding - size;
            match (padding > 0, tail > 0) {
                (false, false) => {
                    self.free.remove(i);
                }
                (false, true) => self.free[i] = (aligned + size, tail),
                (true, false) => self.free[i] = (start, padding),
                (true, true) => {
                    self.free[i] = (start, padding);
                    self.free.insert(i + 1, (aligned + size, tail));
                }
            }
            self.used += size;
            return Some(aligned);
        }
        None
    }

    /// Returns `[offset, offset + size)` to the free list, merging neighbours.
    pub fn free(&mut self, offset: u64, size: u64) {
        let i = self.free.partition_point(|&(o, _)| o < offset);
        debug_assert!(i == 0 || self.free[i - 1].0 + self.free[i - 1].1 <= offset, "double free");
        self.free.insert(i, (offset, size));
        self.used -= size;
        // Merge with the next range, then with the previous one.
        if i + 1 < self.free.len() && offset + size == self.free[i + 1].0 {
            self.free[i].1 += self.free[i + 1].1;
            self.free.remove(i + 1);
        }
        if i > 0 && self.free[i - 1].0 + self.free[i - 1].1 == offset {
            self.free[i - 1].1 += self.free[i].1;
            self.free.remove(i);
        }
    }

    pub fn largest_free(&self) -> u64 {
        self.free.iter().map(|r| r.1).max().unwrap_or(0)
    }
}

/// Where a resource lives; maps to memory property flags.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MemoryLocation {
    /// Device-local, not visible to the CPU (vertex buffers, images).
    GpuOnly,
    /// Host-visible and coherent, written by the CPU (staging, uniforms).
    CpuToGpu,
    /// Host-visible, cached when possible, read by the CPU (readback).
    GpuToCpu,
}

#[derive(Debug)]
pub struct Allocation {
    pub memory: vk::DeviceMemory,
    pub offset: u64,
    pub size: u64,
    pub memory_type: u32,
    /// CPU pointer to the start of this allocation when host-visible.
    pub mapped: Option<std::ptr::NonNull<u8>>,
    block: Option<usize>,
}

// The mapped pointer is only dereferenced by the owner of the allocation.
unsafe impl Send for Allocation {}

struct Block {
    memory: vk::DeviceMemory,
    ranges: RangeAllocator,
    mapped: Option<std::ptr::NonNull<u8>>,
    memory_type: u32,
}

#[derive(Clone, Copy, Debug, Default)]
pub struct MemoryStats {
    pub blocks: usize,
    pub dedicated: usize,
    pub reserved_bytes: u64,
    pub used_bytes: u64,
    pub device_local_used_bytes: u64,
}

pub struct Allocator {
    device: ash::Device,
    properties: vk::PhysicalDeviceMemoryProperties,
    block_size: u64,
    blocks: Vec<Option<Block>>,
    dedicated: usize,
    dedicated_bytes: u64,
    dedicated_device_local: u64,
}

impl Allocator {
    pub fn new(device: ash::Device, properties: vk::PhysicalDeviceMemoryProperties) -> Self {
        Self { device, properties, block_size: 64 << 20, blocks: Vec::new(), dedicated: 0, dedicated_bytes: 0, dedicated_device_local: 0 }
    }

    pub fn properties(&self) -> &vk::PhysicalDeviceMemoryProperties {
        &self.properties
    }

    fn find_type(&self, bits: u32, location: MemoryLocation) -> Option<u32> {
        let (required, preferred) = match location {
            MemoryLocation::GpuOnly => (vk::MemoryPropertyFlags::DEVICE_LOCAL, vk::MemoryPropertyFlags::empty()),
            MemoryLocation::CpuToGpu => (
                vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
                vk::MemoryPropertyFlags::DEVICE_LOCAL,
            ),
            MemoryLocation::GpuToCpu => (vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT, vk::MemoryPropertyFlags::HOST_CACHED),
        };
        let types = &self.properties.memory_types[..self.properties.memory_type_count as usize];
        let candidates = || (0..types.len() as u32).filter(|&i| bits & (1 << i) != 0 && types[i as usize].property_flags.contains(required));
        candidates().find(|&i| types[i as usize].property_flags.contains(preferred)).or_else(|| candidates().next())
    }

    fn is_device_local(&self, memory_type: u32) -> bool {
        self.properties.memory_types[memory_type as usize].property_flags.contains(vk::MemoryPropertyFlags::DEVICE_LOCAL)
    }

    fn host_visible(&self, memory_type: u32) -> bool {
        self.properties.memory_types[memory_type as usize].property_flags.contains(vk::MemoryPropertyFlags::HOST_VISIBLE)
    }

    pub fn allocate(&mut self, requirements: vk::MemoryRequirements, location: MemoryLocation) -> Result<Allocation> {
        let memory_type = self.find_type(requirements.memory_type_bits, location).ok_or(Error::NoMemoryType)?;
        if requirements.size > self.block_size / 2 {
            return self.allocate_dedicated(requirements.size, memory_type);
        }
        for (index, slot) in self.blocks.iter_mut().enumerate() {
            let Some(block) = slot else { continue };
            if block.memory_type != memory_type {
                continue;
            }
            if let Some(offset) = block.ranges.allocate(requirements.size, requirements.alignment) {
                return Ok(Allocation {
                    memory: block.memory,
                    offset,
                    size: requirements.size,
                    memory_type,
                    mapped: block.mapped.map(|p| unsafe { p.add(offset as usize) }),
                    block: Some(index),
                });
            }
        }
        let memory = self.allocate_memory(self.block_size, memory_type)?;
        let mapped = self.map(memory, memory_type)?;
        let mut ranges = RangeAllocator::new(self.block_size);
        let offset = ranges.allocate(requirements.size, requirements.alignment).expect("fresh block fits");
        let block = Block { memory, ranges, mapped, memory_type };
        let index = match self.blocks.iter().position(Option::is_none) {
            Some(i) => {
                self.blocks[i] = Some(block);
                i
            }
            None => {
                self.blocks.push(Some(block));
                self.blocks.len() - 1
            }
        };
        Ok(Allocation { memory, offset, size: requirements.size, memory_type, mapped: mapped.map(|p| unsafe { p.add(offset as usize) }), block: Some(index) })
    }

    fn allocate_dedicated(&mut self, size: u64, memory_type: u32) -> Result<Allocation> {
        let memory = self.allocate_memory(size, memory_type)?;
        let mapped = self.map(memory, memory_type)?;
        self.dedicated += 1;
        self.dedicated_bytes += size;
        if self.is_device_local(memory_type) {
            self.dedicated_device_local += size;
        }
        Ok(Allocation { memory, offset: 0, size, memory_type, mapped, block: None })
    }

    fn allocate_memory(&self, size: u64, memory_type: u32) -> Result<vk::DeviceMemory> {
        let info = vk::MemoryAllocateInfo::default().allocation_size(size).memory_type_index(memory_type);
        // SAFETY: valid device and allocate info.
        unsafe { self.device.allocate_memory(&info, None) }.map_err(Error::Vulkan)
    }

    fn map(&self, memory: vk::DeviceMemory, memory_type: u32) -> Result<Option<std::ptr::NonNull<u8>>> {
        if !self.host_visible(memory_type) {
            return Ok(None);
        }
        // SAFETY: freshly allocated host-visible memory, mapped once for its lifetime.
        let ptr = unsafe { self.device.map_memory(memory, 0, vk::WHOLE_SIZE, vk::MemoryMapFlags::empty()) }.map_err(Error::Vulkan)?;
        Ok(std::ptr::NonNull::new(ptr.cast()))
    }

    pub fn free(&mut self, allocation: Allocation) {
        match allocation.block {
            Some(index) => {
                let empty = {
                    let block = self.blocks[index].as_mut().expect("live block");
                    block.ranges.free(allocation.offset, allocation.size);
                    block.ranges.is_empty()
                };
                // Keep one empty block per type to avoid allocation churn; release extra ones.
                let spare = self
                    .blocks
                    .iter()
                    .enumerate()
                    .any(|(i, b)| i != index && b.as_ref().is_some_and(|b| b.memory_type == allocation.memory_type && b.ranges.is_empty()));
                if empty && spare {
                    let block = self.blocks[index].take().expect("live block");
                    // SAFETY: no allocation references the block any more.
                    unsafe { self.device.free_memory(block.memory, None) };
                }
            }
            None => {
                self.dedicated -= 1;
                self.dedicated_bytes -= allocation.size;
                if self.is_device_local(allocation.memory_type) {
                    self.dedicated_device_local -= allocation.size;
                }
                // SAFETY: the dedicated allocation is owned by this handle.
                unsafe { self.device.free_memory(allocation.memory, None) };
            }
        }
    }

    pub fn stats(&self) -> MemoryStats {
        let mut s = MemoryStats { dedicated: self.dedicated, ..Default::default() };
        s.reserved_bytes = self.dedicated_bytes;
        s.used_bytes = self.dedicated_bytes;
        s.device_local_used_bytes = self.dedicated_device_local;
        for block in self.blocks.iter().flatten() {
            s.blocks += 1;
            s.reserved_bytes += block.ranges.size();
            s.used_bytes += block.ranges.used();
            if self.is_device_local(block.memory_type) {
                s.device_local_used_bytes += block.ranges.used();
            }
        }
        s
    }

    /// Frees every block. Call after every allocation was freed and the device is idle.
    pub fn destroy(&mut self) {
        for block in self.blocks.drain(..).flatten() {
            // SAFETY: the caller guarantees the device is idle.
            unsafe { self.device.free_memory(block.memory, None) };
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn first_fit_respects_alignment_and_keeps_padding_free() {
        let mut r = RangeAllocator::new(1024);
        assert_eq!(r.allocate(10, 1), Some(0));
        assert_eq!(r.allocate(100, 256), Some(256));
        // The padding [10, 256) is still usable.
        assert_eq!(r.allocate(200, 2), Some(10));
        assert_eq!(r.used(), 310);
        assert_eq!(r.allocate(2000, 1), None);
    }

    #[test]
    fn freeing_coalesces_neighbours() {
        let mut r = RangeAllocator::new(300);
        let a = r.allocate(100, 1).unwrap();
        let b = r.allocate(100, 1).unwrap();
        let c = r.allocate(100, 1).unwrap();
        r.free(a, 100);
        r.free(c, 100);
        assert_eq!(r.largest_free(), 100);
        r.free(b, 100);
        assert_eq!(r.largest_free(), 300);
        assert!(r.is_empty());
        assert_eq!(r.allocate(300, 1), Some(0));
    }

    #[test]
    fn randomized_allocations_never_overlap() {
        let mut r = RangeAllocator::new(1 << 20);
        let mut live: Vec<(u64, u64)> = Vec::new();
        let mut seed = 0x1234_5678u32;
        let mut next = || {
            seed ^= seed << 13;
            seed ^= seed >> 17;
            seed ^= seed << 5;
            seed
        };
        for _ in 0..5000 {
            if next() % 3 != 0 || live.is_empty() {
                let size = (next() % 4096 + 1) as u64;
                let align = 1u64 << (next() % 9);
                if let Some(o) = r.allocate(size, align) {
                    assert_eq!(o % align, 0);
                    for &(lo, ls) in &live {
                        assert!(o + size <= lo || lo + ls <= o, "overlap");
                    }
                    live.push((o, size));
                }
            } else {
                let i = next() as usize % live.len();
                let (o, s) = live.swap_remove(i);
                r.free(o, s);
            }
            assert_eq!(r.used(), live.iter().map(|l| l.1).sum::<u64>());
        }
        for (o, s) in live.drain(..) {
            r.free(o, s);
        }
        assert_eq!(r.largest_free(), 1 << 20);
    }
}
