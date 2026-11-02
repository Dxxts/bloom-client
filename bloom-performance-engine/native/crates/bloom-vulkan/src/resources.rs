//! Buffers and images bound to [`Allocation`]s.
//!
//! Resources do not destroy themselves on drop (that needs the device and
//! the allocator): they are destroyed explicitly, usually through
//! [`crate::frame::DeletionQueue`] once the GPU no longer uses them.

use ash::vk;

use crate::frame::one_time_submit;
use crate::memory::{Allocation, Allocator, MemoryLocation};
use crate::{Error, Result};

pub struct Buffer {
    pub raw: vk::Buffer,
    pub size: u64,
    pub allocation: Allocation,
}

impl Buffer {
    pub fn new(device: &ash::Device, allocator: &mut Allocator, size: u64, usage: vk::BufferUsageFlags, location: MemoryLocation) -> Result<Self> {
        let info = vk::BufferCreateInfo::default().size(size.max(4)).usage(usage).sharing_mode(vk::SharingMode::EXCLUSIVE);
        // SAFETY: valid device and create info; the buffer is bound before use.
        let raw = unsafe { device.create_buffer(&info, None) }?;
        let requirements = unsafe { device.get_buffer_memory_requirements(raw) };
        let allocation = match allocator.allocate(requirements, location) {
            Ok(a) => a,
            Err(e) => {
                unsafe { device.destroy_buffer(raw, None) };
                return Err(e);
            }
        };
        unsafe { device.bind_buffer_memory(raw, allocation.memory, allocation.offset) }?;
        Ok(Self { raw, size, allocation })
    }

    /// Copies `data` into a host-visible buffer at `offset`.
    pub fn write(&self, offset: u64, data: &[u8]) -> Result<()> {
        let ptr = self.allocation.mapped.ok_or(Error::NoMemoryType)?;
        assert!(offset + data.len() as u64 <= self.size, "write past the end of the buffer");
        // SAFETY: the mapped range covers the whole buffer; host-coherent memory.
        unsafe { std::ptr::copy_nonoverlapping(data.as_ptr(), ptr.as_ptr().add(offset as usize), data.len()) };
        Ok(())
    }

    /// Reads `len` bytes from a host-visible buffer.
    pub fn read(&self, len: usize) -> Result<Vec<u8>> {
        let ptr = self.allocation.mapped.ok_or(Error::NoMemoryType)?;
        let mut out = vec![0u8; len.min(self.size as usize)];
        // SAFETY: as in `write`; the GPU finished writing (caller waited on a fence).
        unsafe { std::ptr::copy_nonoverlapping(ptr.as_ptr(), out.as_mut_ptr(), out.len()) };
        Ok(out)
    }

    pub fn destroy(self, device: &ash::Device, allocator: &mut Allocator) {
        // SAFETY: the GPU no longer uses the buffer (caller's guarantee).
        unsafe { device.destroy_buffer(self.raw, None) };
        allocator.free(self.allocation);
    }
}

/// Uploads `data` into a device-local buffer through a temporary staging
/// buffer and waits for the copy. Returns the bytes uploaded.
pub fn upload(
    device: &ash::Device,
    allocator: &mut Allocator,
    queue: vk::Queue,
    queue_family: u32,
    dst: &Buffer,
    dst_offset: u64,
    data: &[u8],
) -> Result<u64> {
    if data.is_empty() {
        return Ok(0);
    }
    let staging = Buffer::new(device, allocator, data.len() as u64, vk::BufferUsageFlags::TRANSFER_SRC, MemoryLocation::CpuToGpu)?;
    staging.write(0, data)?;
    let result = one_time_submit(device, queue, queue_family, |cmd| {
        let region = [vk::BufferCopy { src_offset: 0, dst_offset, size: data.len() as u64 }];
        // SAFETY: both buffers are alive and large enough.
        unsafe { device.cmd_copy_buffer(cmd, staging.raw, dst.raw, &region) };
    });
    staging.destroy(device, allocator);
    result.map(|()| data.len() as u64)
}

pub struct Image {
    pub raw: vk::Image,
    pub view: vk::ImageView,
    pub format: vk::Format,
    pub extent: vk::Extent2D,
    pub allocation: Allocation,
}

impl Image {
    /// A 2D render target (color or depth).
    pub fn attachment(
        device: &ash::Device,
        allocator: &mut Allocator,
        format: vk::Format,
        extent: vk::Extent2D,
        usage: vk::ImageUsageFlags,
        aspect: vk::ImageAspectFlags,
    ) -> Result<Self> {
        let info = vk::ImageCreateInfo::default()
            .image_type(vk::ImageType::TYPE_2D)
            .format(format)
            .extent(vk::Extent3D { width: extent.width, height: extent.height, depth: 1 })
            .mip_levels(1)
            .array_layers(1)
            .samples(vk::SampleCountFlags::TYPE_1)
            .tiling(vk::ImageTiling::OPTIMAL)
            .usage(usage)
            .sharing_mode(vk::SharingMode::EXCLUSIVE)
            .initial_layout(vk::ImageLayout::UNDEFINED);
        // SAFETY: valid create info; memory bound right after.
        let raw = unsafe { device.create_image(&info, None) }?;
        let requirements = unsafe { device.get_image_memory_requirements(raw) };
        let allocation = match allocator.allocate(requirements, MemoryLocation::GpuOnly) {
            Ok(a) => a,
            Err(e) => {
                unsafe { device.destroy_image(raw, None) };
                return Err(e);
            }
        };
        unsafe { device.bind_image_memory(raw, allocation.memory, allocation.offset) }?;
        let view_info = vk::ImageViewCreateInfo::default()
            .image(raw)
            .view_type(vk::ImageViewType::TYPE_2D)
            .format(format)
            .subresource_range(vk::ImageSubresourceRange { aspect_mask: aspect, base_mip_level: 0, level_count: 1, base_array_layer: 0, layer_count: 1 });
        let view = unsafe { device.create_image_view(&view_info, None) }?;
        Ok(Self { raw, view, format, extent, allocation })
    }

    pub fn destroy(self, device: &ash::Device, allocator: &mut Allocator) {
        // SAFETY: the GPU no longer uses the image (caller's guarantee).
        unsafe {
            device.destroy_image_view(self.view, None);
            device.destroy_image(self.raw, None);
        }
        allocator.free(self.allocation);
    }
}
