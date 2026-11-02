//! Render targets: an offscreen color + depth pair, or framebuffers over
//! swapchain images (sharing one depth image).

use ash::vk;

use crate::frame::one_time_submit;
use crate::memory::{Allocator, MemoryLocation};
use crate::resources::{Buffer, Image};
use crate::Result;

pub const DEPTH_FORMAT: vk::Format = vk::Format::D32_SFLOAT;
pub const OFFSCREEN_FORMAT: vk::Format = vk::Format::B8G8R8A8_UNORM;

pub struct Targets {
    pub extent: vk::Extent2D,
    pub color: Option<Image>,
    pub depth: Image,
    pub framebuffers: Vec<vk::Framebuffer>,
}

impl Targets {
    /// One offscreen color image (ends in `TRANSFER_SRC_OPTIMAL`, see the render pass) and depth.
    pub fn offscreen(device: &ash::Device, allocator: &mut Allocator, render_pass: vk::RenderPass, extent: vk::Extent2D) -> Result<Self> {
        let color = Image::attachment(
            device,
            allocator,
            OFFSCREEN_FORMAT,
            extent,
            vk::ImageUsageFlags::COLOR_ATTACHMENT | vk::ImageUsageFlags::TRANSFER_SRC,
            vk::ImageAspectFlags::COLOR,
        )?;
        let depth = Image::attachment(device, allocator, DEPTH_FORMAT, extent, vk::ImageUsageFlags::DEPTH_STENCIL_ATTACHMENT, vk::ImageAspectFlags::DEPTH)?;
        let fb = framebuffer(device, render_pass, color.view, depth.view, extent)?;
        Ok(Self { extent, color: Some(color), depth, framebuffers: vec![fb] })
    }

    /// Framebuffers over the swapchain's image views.
    pub fn swapchain(device: &ash::Device, allocator: &mut Allocator, render_pass: vk::RenderPass, views: &[vk::ImageView], extent: vk::Extent2D) -> Result<Self> {
        let depth = Image::attachment(device, allocator, DEPTH_FORMAT, extent, vk::ImageUsageFlags::DEPTH_STENCIL_ATTACHMENT, vk::ImageAspectFlags::DEPTH)?;
        let framebuffers = views.iter().map(|&v| framebuffer(device, render_pass, v, depth.view, extent)).collect::<Result<Vec<_>>>()?;
        Ok(Self { extent, color: None, depth, framebuffers })
    }

    /// Copies the offscreen color image to host memory (BGRA8). The image
    /// must be in `TRANSFER_SRC_OPTIMAL` and no longer written.
    pub fn read_color(&self, device: &ash::Device, allocator: &mut Allocator, queue: vk::Queue, queue_family: u32) -> Result<Vec<u8>> {
        let color = self.color.as_ref().expect("offscreen target");
        let size = self.extent.width as u64 * self.extent.height as u64 * 4;
        let readback = Buffer::new(device, allocator, size, vk::BufferUsageFlags::TRANSFER_DST, MemoryLocation::GpuToCpu)?;
        let result = one_time_submit(device, queue, queue_family, |cmd| {
            let region = [vk::BufferImageCopy::default()
                .image_subresource(vk::ImageSubresourceLayers { aspect_mask: vk::ImageAspectFlags::COLOR, mip_level: 0, base_array_layer: 0, layer_count: 1 })
                .image_extent(vk::Extent3D { width: self.extent.width, height: self.extent.height, depth: 1 })];
            // SAFETY: valid image in TRANSFER_SRC_OPTIMAL and a large enough buffer.
            unsafe { device.cmd_copy_image_to_buffer(cmd, color.raw, vk::ImageLayout::TRANSFER_SRC_OPTIMAL, readback.raw, &region) };
        })
        .and_then(|()| readback.read(size as usize));
        readback.destroy(device, allocator);
        result
    }

    pub fn destroy(self, device: &ash::Device, allocator: &mut Allocator) {
        for fb in self.framebuffers {
            // SAFETY: the caller waited for the device to be idle.
            unsafe { device.destroy_framebuffer(fb, None) };
        }
        if let Some(c) = self.color {
            c.destroy(device, allocator);
        }
        self.depth.destroy(device, allocator);
    }
}

fn framebuffer(device: &ash::Device, render_pass: vk::RenderPass, color: vk::ImageView, depth: vk::ImageView, extent: vk::Extent2D) -> Result<vk::Framebuffer> {
    let attachments = [color, depth];
    let info = vk::FramebufferCreateInfo::default().render_pass(render_pass).attachments(&attachments).width(extent.width).height(extent.height).layers(1);
    // SAFETY: views compatible with the render pass.
    Ok(unsafe { device.create_framebuffer(&info, None) }?)
}
