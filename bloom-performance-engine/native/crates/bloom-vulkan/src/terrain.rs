//! Renderer for Bloom section meshes.
//!
//! Vertices stay in Minecraft's `BLOCK` format (28 bytes: position
//! `RGB32_FLOAT`, color `RGBA8_UNORM`, UV `RG32_FLOAT`, light `RG16_SINT`),
//! exactly as the mesher writes them. All sections share one device-local
//! vertex buffer and one quad index buffer; the section origins live in a
//! storage buffer indexed by `gl_InstanceIndex` (the draw's first instance),
//! so the same draw list works with one draw call per section or with a
//! single multi-draw indirect call.
//!
//! Textures are not bound (there is no atlas outside the game): the
//! fragment shader shades by vertex color and light with a checker derived
//! from the atlas UVs. Translucent layers are drawn like opaque ones.

use ash::vk;

use crate::descriptor::{DescriptorAllocator, LayoutCache};
use crate::diagnostics::Counters;
use crate::memory::{Allocator, MemoryLocation};
use crate::pipeline::{GraphicsDesc, PipelineCache, ShaderCache};
use crate::resources::{upload, Buffer};
use crate::Result;

pub const VERTEX_SIZE: u32 = 28;

pub const VERTEX_SHADER: &str = r#"#version 450
layout(location = 0) in vec3 a_pos;
layout(location = 1) in vec4 a_color;
layout(location = 2) in vec2 a_uv;
layout(location = 3) in ivec2 a_light;

layout(set = 0, binding = 0) uniform Camera { mat4 view_proj; } camera;
layout(set = 0, binding = 1) readonly buffer Origins { vec4 origin[]; } origins;

layout(location = 0) out vec4 v_color;
layout(location = 1) out vec2 v_uv;

void main() {
    vec3 world = a_pos + origins.origin[gl_InstanceIndex].xyz;
    gl_Position = camera.view_proj * vec4(world, 1.0);
    // Packed light: block * 16 and sky * 16 (0..240).
    float light = max(float(a_light.x), float(a_light.y)) / 240.0;
    v_color = vec4(a_color.rgb * (0.2 + 0.8 * light), a_color.a);
    v_uv = a_uv;
}
"#;

pub const FRAGMENT_SHADER: &str = r#"#version 450
layout(location = 0) in vec4 v_color;
layout(location = 1) in vec2 v_uv;
layout(location = 0) out vec4 o_color;

void main() {
    // Stand-in for the atlas fetch: a checker at texel scale.
    vec2 cell = floor(v_uv * 256.0);
    float checker = mod(cell.x + cell.y, 2.0);
    o_color = vec4(v_color.rgb * (0.85 + 0.15 * checker), 1.0);
}
"#;

/// One section's mesh: vertices of all its layers, and where it sits.
pub struct SectionMesh {
    pub origin: [f32; 3],
    pub vertices: Vec<u8>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DrawMode {
    /// One `vkCmdDrawIndexed` per section (like vanilla's separate draws).
    Direct,
    /// One `vkCmdDrawIndexedIndirect` for every section.
    MultiDrawIndirect,
}

struct Draw {
    index_count: u32,
    vertex_offset: i32,
    instance: u32,
}

pub struct TerrainRenderer {
    pub render_pass: vk::RenderPass,
    layout: vk::PipelineLayout,
    set_layout: vk::DescriptorSetLayout,
    pipeline: vk::Pipeline,
    vertices: Buffer,
    indices: Buffer,
    origins: Buffer,
    indirect: Buffer,
    cameras: Vec<Buffer>,
    sets: Vec<vk::DescriptorSet>,
    draws: Vec<Draw>,
    pub vertex_count: u64,
    pub uploaded_bytes: u64,
}

impl TerrainRenderer {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        device: &ash::Device,
        queue: vk::Queue,
        queue_family: u32,
        allocator: &mut Allocator,
        layouts: &mut LayoutCache,
        descriptors: &mut DescriptorAllocator,
        shaders: &mut ShaderCache,
        pipelines: &mut PipelineCache,
        color_format: vk::Format,
        depth_format: vk::Format,
        final_layout: vk::ImageLayout,
        frames_in_flight: usize,
        sections: &[SectionMesh],
    ) -> Result<Self> {
        let render_pass = create_render_pass(device, color_format, depth_format, final_layout)?;
        let bindings = [
            vk::DescriptorSetLayoutBinding::default()
                .binding(0)
                .descriptor_type(vk::DescriptorType::UNIFORM_BUFFER)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::VERTEX),
            vk::DescriptorSetLayoutBinding::default()
                .binding(1)
                .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::VERTEX),
        ];
        let set_layout = layouts.get(device, &bindings)?;
        let set_layouts = [set_layout];
        // SAFETY: valid set layout.
        let layout = unsafe { device.create_pipeline_layout(&vk::PipelineLayoutCreateInfo::default().set_layouts(&set_layouts), None) }?;
        let desc = GraphicsDesc {
            vertex: VERTEX_SHADER.into(),
            fragment: FRAGMENT_SHADER.into(),
            vertex_stride: VERTEX_SIZE,
            attributes: vec![
                (0, vk::Format::R32G32B32_SFLOAT, 0),
                (1, vk::Format::R8G8B8A8_UNORM, 12),
                (2, vk::Format::R32G32_SFLOAT, 16),
                (3, vk::Format::R16G16_SINT, 24),
            ],
            cull_back: false,
            depth_test: true,
            alpha_blend: false,
        };
        let pipeline = pipelines.graphics(device, shaders, &desc, render_pass, layout)?;

        // Geometry: all sections back to back; quads become two triangles.
        let mut all = Vec::new();
        let mut draws = Vec::new();
        let mut origins = Vec::new();
        let mut max_quads = 0u32;
        for (i, s) in sections.iter().enumerate() {
            let vertices = (s.vertices.len() / VERTEX_SIZE as usize) as u32;
            origins.extend_from_slice(&[s.origin[0], s.origin[1], s.origin[2], 0.0]);
            if vertices == 0 {
                continue;
            }
            draws.push(Draw { index_count: vertices / 4 * 6, vertex_offset: (all.len() / VERTEX_SIZE as usize) as i32, instance: i as u32 });
            max_quads = max_quads.max(vertices / 4);
            all.extend_from_slice(&s.vertices);
        }
        let index_data: Vec<u8> = (0..max_quads.max(1))
            .flat_map(|q| {
                let b = q * 4;
                [b, b + 1, b + 2, b + 2, b + 3, b]
            })
            .flat_map(u32::to_le_bytes)
            .collect();
        let origin_bytes: Vec<u8> = origins.iter().flat_map(|f| f.to_le_bytes()).collect();
        let indirect_bytes: Vec<u8> = draws
            .iter()
            .flat_map(|d| {
                let cmd = vk::DrawIndexedIndirectCommand {
                    index_count: d.index_count,
                    instance_count: 1,
                    first_index: 0,
                    vertex_offset: d.vertex_offset,
                    first_instance: d.instance,
                };
                // SAFETY: plain-old-data struct.
                unsafe { std::slice::from_raw_parts((&cmd as *const vk::DrawIndexedIndirectCommand).cast::<u8>(), std::mem::size_of_val(&cmd)) }.to_vec()
            })
            .collect();

        let dst = vk::BufferUsageFlags::TRANSFER_DST;
        let vertices = Buffer::new(device, allocator, all.len().max(4) as u64, vk::BufferUsageFlags::VERTEX_BUFFER | dst, MemoryLocation::GpuOnly)?;
        let indices = Buffer::new(device, allocator, index_data.len() as u64, vk::BufferUsageFlags::INDEX_BUFFER | dst, MemoryLocation::GpuOnly)?;
        let origins_buf = Buffer::new(device, allocator, origin_bytes.len().max(16) as u64, vk::BufferUsageFlags::STORAGE_BUFFER | dst, MemoryLocation::GpuOnly)?;
        let indirect = Buffer::new(device, allocator, indirect_bytes.len().max(20) as u64, vk::BufferUsageFlags::INDIRECT_BUFFER | dst, MemoryLocation::GpuOnly)?;
        let mut uploaded = 0;
        uploaded += upload(device, allocator, queue, queue_family, &vertices, 0, &all)?;
        uploaded += upload(device, allocator, queue, queue_family, &indices, 0, &index_data)?;
        uploaded += upload(device, allocator, queue, queue_family, &origins_buf, 0, &origin_bytes)?;
        uploaded += upload(device, allocator, queue, queue_family, &indirect, 0, &indirect_bytes)?;

        let mut cameras = Vec::new();
        let mut sets = Vec::new();
        for _ in 0..frames_in_flight {
            let camera = Buffer::new(device, allocator, 64, vk::BufferUsageFlags::UNIFORM_BUFFER, MemoryLocation::CpuToGpu)?;
            let set = descriptors.allocate(device, set_layout)?;
            let ubo = [vk::DescriptorBufferInfo { buffer: camera.raw, offset: 0, range: 64 }];
            let ssbo = [vk::DescriptorBufferInfo { buffer: origins_buf.raw, offset: 0, range: vk::WHOLE_SIZE }];
            let writes = [
                vk::WriteDescriptorSet::default().dst_set(set).dst_binding(0).descriptor_type(vk::DescriptorType::UNIFORM_BUFFER).buffer_info(&ubo),
                vk::WriteDescriptorSet::default().dst_set(set).dst_binding(1).descriptor_type(vk::DescriptorType::STORAGE_BUFFER).buffer_info(&ssbo),
            ];
            // SAFETY: the buffers outlive the set.
            unsafe { device.update_descriptor_sets(&writes, &[]) };
            cameras.push(camera);
            sets.push(set);
        }
        Ok(Self {
            render_pass,
            layout,
            set_layout,
            pipeline,
            vertices,
            indices,
            origins: origins_buf,
            indirect,
            cameras,
            sets,
            draws,
            vertex_count: (all.len() / VERTEX_SIZE as usize) as u64,
            uploaded_bytes: uploaded,
        })
    }

    pub fn draw_count(&self) -> usize {
        self.draws.len()
    }

    /// Records the render pass into `cmd` for frame slot `slot`.
    #[allow(clippy::too_many_arguments)]
    pub fn record(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        slot: usize,
        framebuffer: vk::Framebuffer,
        extent: vk::Extent2D,
        view_proj: &[f32; 16],
        mode: DrawMode,
    ) -> Result<Counters> {
        let bytes: Vec<u8> = view_proj.iter().flat_map(|f| f.to_le_bytes()).collect();
        self.cameras[slot].write(0, &bytes)?;
        let clear = [
            vk::ClearValue { color: vk::ClearColorValue { float32: [0.47, 0.65, 1.0, 1.0] } },
            vk::ClearValue { depth_stencil: vk::ClearDepthStencilValue { depth: 1.0, stencil: 0 } },
        ];
        let area = vk::Rect2D { offset: vk::Offset2D::default(), extent };
        let begin = vk::RenderPassBeginInfo::default().render_pass(self.render_pass).framebuffer(framebuffer).render_area(area).clear_values(&clear);
        let mut c = Counters::default();
        // SAFETY: `cmd` is recording; every bound object is alive.
        unsafe {
            device.cmd_begin_render_pass(cmd, &begin, vk::SubpassContents::INLINE);
            device.cmd_set_viewport(cmd, 0, &[vk::Viewport { x: 0.0, y: 0.0, width: extent.width as f32, height: extent.height as f32, min_depth: 0.0, max_depth: 1.0 }]);
            device.cmd_set_scissor(cmd, 0, &[area]);
            device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, self.pipeline);
            c.pipeline_binds += 1;
            device.cmd_bind_descriptor_sets(cmd, vk::PipelineBindPoint::GRAPHICS, self.layout, 0, &[self.sets[slot]], &[]);
            c.descriptor_binds += 1;
            device.cmd_bind_vertex_buffers(cmd, 0, &[self.vertices.raw], &[0]);
            device.cmd_bind_index_buffer(cmd, self.indices.raw, 0, vk::IndexType::UINT32);
            match mode {
                DrawMode::Direct => {
                    for d in &self.draws {
                        device.cmd_draw_indexed(cmd, d.index_count, 1, 0, d.vertex_offset, d.instance);
                        c.draw_calls += 1;
                        c.draws += 1;
                    }
                }
                DrawMode::MultiDrawIndirect => {
                    if !self.draws.is_empty() {
                        let stride = std::mem::size_of::<vk::DrawIndexedIndirectCommand>() as u32;
                        device.cmd_draw_indexed_indirect(cmd, self.indirect.raw, 0, self.draws.len() as u32, stride);
                        c.draw_calls += 1;
                        c.draws += self.draws.len() as u32;
                    }
                }
            }
            device.cmd_end_render_pass(cmd);
        }
        c.vertices = self.vertex_count;
        Ok(c)
    }

    pub fn destroy(self, device: &ash::Device, allocator: &mut Allocator) {
        // SAFETY: the caller waited for the device to be idle.
        unsafe {
            device.destroy_pipeline_layout(self.layout, None);
            device.destroy_render_pass(self.render_pass, None);
        }
        let _ = (self.pipeline, self.set_layout);
        self.vertices.destroy(device, allocator);
        self.indices.destroy(device, allocator);
        self.origins.destroy(device, allocator);
        self.indirect.destroy(device, allocator);
        for c in self.cameras {
            c.destroy(device, allocator);
        }
    }
}

fn create_render_pass(device: &ash::Device, color: vk::Format, depth: vk::Format, final_layout: vk::ImageLayout) -> Result<vk::RenderPass> {
    let attachments = [
        vk::AttachmentDescription::default()
            .format(color)
            .samples(vk::SampleCountFlags::TYPE_1)
            .load_op(vk::AttachmentLoadOp::CLEAR)
            .store_op(vk::AttachmentStoreOp::STORE)
            .stencil_load_op(vk::AttachmentLoadOp::DONT_CARE)
            .stencil_store_op(vk::AttachmentStoreOp::DONT_CARE)
            .initial_layout(vk::ImageLayout::UNDEFINED)
            .final_layout(final_layout),
        vk::AttachmentDescription::default()
            .format(depth)
            .samples(vk::SampleCountFlags::TYPE_1)
            .load_op(vk::AttachmentLoadOp::CLEAR)
            .store_op(vk::AttachmentStoreOp::DONT_CARE)
            .stencil_load_op(vk::AttachmentLoadOp::DONT_CARE)
            .stencil_store_op(vk::AttachmentStoreOp::DONT_CARE)
            .initial_layout(vk::ImageLayout::UNDEFINED)
            .final_layout(vk::ImageLayout::DEPTH_STENCIL_ATTACHMENT_OPTIMAL),
    ];
    let color_ref = [vk::AttachmentReference { attachment: 0, layout: vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL }];
    let depth_ref = vk::AttachmentReference { attachment: 1, layout: vk::ImageLayout::DEPTH_STENCIL_ATTACHMENT_OPTIMAL };
    let subpass = [vk::SubpassDescription::default()
        .pipeline_bind_point(vk::PipelineBindPoint::GRAPHICS)
        .color_attachments(&color_ref)
        .depth_stencil_attachment(&depth_ref)];
    let deps = [vk::SubpassDependency {
        src_subpass: vk::SUBPASS_EXTERNAL,
        dst_subpass: 0,
        src_stage_mask: vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT | vk::PipelineStageFlags::EARLY_FRAGMENT_TESTS,
        dst_stage_mask: vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT | vk::PipelineStageFlags::EARLY_FRAGMENT_TESTS,
        src_access_mask: vk::AccessFlags::empty(),
        dst_access_mask: vk::AccessFlags::COLOR_ATTACHMENT_WRITE | vk::AccessFlags::DEPTH_STENCIL_ATTACHMENT_WRITE,
        dependency_flags: vk::DependencyFlags::empty(),
    }];
    let info = vk::RenderPassCreateInfo::default().attachments(&attachments).subpasses(&subpass).dependencies(&deps);
    // SAFETY: valid create info.
    Ok(unsafe { device.create_render_pass(&info, None) }?)
}

/// Column-major `projection * view` for a camera at `eye` looking at `target`.
pub fn view_projection(eye: [f32; 3], target: [f32; 3], fov_y: f32, aspect: f32, near: f32, far: f32) -> [f32; 16] {
    let sub = |a: [f32; 3], b: [f32; 3]| [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
    let dot = |a: [f32; 3], b: [f32; 3]| a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    let cross = |a: [f32; 3], b: [f32; 3]| [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
    let norm = |a: [f32; 3]| {
        let l = dot(a, a).sqrt();
        [a[0] / l, a[1] / l, a[2] / l]
    };
    let f = norm(sub(target, eye));
    let s = norm(cross(f, [0.0, 1.0, 0.0]));
    let u = cross(s, f);
    let view = [
        s[0], u[0], -f[0], 0.0, //
        s[1], u[1], -f[1], 0.0, //
        s[2], u[2], -f[2], 0.0, //
        -dot(s, eye), -dot(u, eye), dot(f, eye), 1.0,
    ];
    // Vulkan clip space: y down, depth 0..1.
    let t = 1.0 / (fov_y / 2.0).tan();
    let proj = [
        t / aspect, 0.0, 0.0, 0.0, //
        0.0, -t, 0.0, 0.0, //
        0.0, 0.0, far / (near - far), -1.0, //
        0.0, 0.0, near * far / (near - far), 0.0,
    ];
    let mut out = [0.0f32; 16];
    for c in 0..4 {
        for r in 0..4 {
            out[c * 4 + r] = (0..4).map(|k| proj[k * 4 + r] * view[c * 4 + k]).sum();
        }
    }
    out
}
