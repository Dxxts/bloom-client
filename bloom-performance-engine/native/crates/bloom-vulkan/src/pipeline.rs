//! Shader compilation and pipeline management.
//!
//! GLSL is compiled to SPIR-V at runtime with naga (pure Rust, validated
//! before use). Shader modules are cached by a hash of their source and
//! stage; graphics pipelines are cached by their description and created
//! through a `VkPipelineCache`, so a reload or a second identical request
//! costs a hash lookup.

use std::collections::hash_map::DefaultHasher;
use std::collections::HashMap;
use std::hash::{Hash, Hasher};
use std::time::Instant;

use ash::vk;

use crate::{Error, Result};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Stage {
    Vertex,
    Fragment,
}

/// GLSL 450 → validated SPIR-V words.
pub fn compile_glsl(source: &str, stage: Stage) -> Result<Vec<u32>> {
    let naga_stage = match stage {
        Stage::Vertex => naga::ShaderStage::Vertex,
        Stage::Fragment => naga::ShaderStage::Fragment,
    };
    let module = naga::front::glsl::Frontend::default()
        .parse(&naga::front::glsl::Options::from(naga_stage), source)
        .map_err(|e| Error::Shader(format!("{stage:?} parse: {}", e.emit_to_string(source))))?;
    let info = naga::valid::Validator::new(naga::valid::ValidationFlags::all(), naga::valid::Capabilities::empty())
        .validate(&module)
        .map_err(|e| Error::Shader(format!("{stage:?} validation: {}", e.emit_to_string(source))))?;
    let pipeline = naga::back::spv::PipelineOptions { shader_stage: naga_stage, entry_point: "main".into() };
    naga::back::spv::write_vec(&module, &info, &naga::back::spv::Options::default(), Some(&pipeline))
        .map_err(|e| Error::Shader(format!("{stage:?} SPIR-V: {e}")))
}

#[derive(Default)]
pub struct ShaderCache {
    modules: HashMap<(u64, Stage), vk::ShaderModule>,
    pub compiled: u32,
    pub hits: u32,
    pub compile_ns: u64,
}

impl ShaderCache {
    pub fn get(&mut self, device: &ash::Device, source: &str, stage: Stage) -> Result<vk::ShaderModule> {
        let mut h = DefaultHasher::new();
        source.hash(&mut h);
        let key = (h.finish(), stage);
        if let Some(&m) = self.modules.get(&key) {
            self.hits += 1;
            return Ok(m);
        }
        let t = Instant::now();
        let words = compile_glsl(source, stage)?;
        self.compile_ns += t.elapsed().as_nanos() as u64;
        self.compiled += 1;
        let info = vk::ShaderModuleCreateInfo::default().code(&words);
        // SAFETY: validated SPIR-V.
        let module = unsafe { device.create_shader_module(&info, None) }?;
        self.modules.insert(key, module);
        Ok(module)
    }

    pub fn destroy(&mut self, device: &ash::Device) {
        for (_, m) in self.modules.drain() {
            // SAFETY: modules are only needed while creating pipelines.
            unsafe { device.destroy_shader_module(m, None) };
        }
    }
}

/// Everything that identifies a graphics pipeline.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct GraphicsDesc {
    pub vertex: String,
    pub fragment: String,
    pub vertex_stride: u32,
    /// `(location, format, offset)`.
    pub attributes: Vec<(u32, vk::Format, u32)>,
    pub cull_back: bool,
    pub depth_test: bool,
    pub alpha_blend: bool,
}

pub struct PipelineCache {
    cache: vk::PipelineCache,
    pipelines: HashMap<(GraphicsDesc, vk::RenderPass, vk::PipelineLayout), vk::Pipeline>,
    pub created: u32,
    pub hits: u32,
    pub create_ns: u64,
}

impl PipelineCache {
    pub fn new(device: &ash::Device) -> Result<Self> {
        // SAFETY: empty cache.
        let cache = unsafe { device.create_pipeline_cache(&vk::PipelineCacheCreateInfo::default(), None) }?;
        Ok(Self { cache, pipelines: HashMap::new(), created: 0, hits: 0, create_ns: 0 })
    }

    pub fn graphics(
        &mut self,
        device: &ash::Device,
        shaders: &mut ShaderCache,
        desc: &GraphicsDesc,
        render_pass: vk::RenderPass,
        layout: vk::PipelineLayout,
    ) -> Result<vk::Pipeline> {
        let key = (desc.clone(), render_pass, layout);
        if let Some(&p) = self.pipelines.get(&key) {
            self.hits += 1;
            return Ok(p);
        }
        let t = Instant::now();
        let vs = shaders.get(device, &desc.vertex, Stage::Vertex)?;
        let fs = shaders.get(device, &desc.fragment, Stage::Fragment)?;
        let stages = [
            vk::PipelineShaderStageCreateInfo::default().stage(vk::ShaderStageFlags::VERTEX).module(vs).name(c"main"),
            vk::PipelineShaderStageCreateInfo::default().stage(vk::ShaderStageFlags::FRAGMENT).module(fs).name(c"main"),
        ];
        let bindings = [vk::VertexInputBindingDescription { binding: 0, stride: desc.vertex_stride, input_rate: vk::VertexInputRate::VERTEX }];
        let attributes: Vec<vk::VertexInputAttributeDescription> = desc
            .attributes
            .iter()
            .map(|&(location, format, offset)| vk::VertexInputAttributeDescription { location, binding: 0, format, offset })
            .collect();
        let vertex_input = vk::PipelineVertexInputStateCreateInfo::default().vertex_binding_descriptions(&bindings).vertex_attribute_descriptions(&attributes);
        let assembly = vk::PipelineInputAssemblyStateCreateInfo::default().topology(vk::PrimitiveTopology::TRIANGLE_LIST);
        let viewport = vk::PipelineViewportStateCreateInfo::default().viewport_count(1).scissor_count(1);
        let raster = vk::PipelineRasterizationStateCreateInfo::default()
            .polygon_mode(vk::PolygonMode::FILL)
            .cull_mode(if desc.cull_back { vk::CullModeFlags::BACK } else { vk::CullModeFlags::NONE })
            .front_face(vk::FrontFace::COUNTER_CLOCKWISE)
            .line_width(1.0);
        let multisample = vk::PipelineMultisampleStateCreateInfo::default().rasterization_samples(vk::SampleCountFlags::TYPE_1);
        let depth = vk::PipelineDepthStencilStateCreateInfo::default()
            .depth_test_enable(desc.depth_test)
            .depth_write_enable(desc.depth_test && !desc.alpha_blend)
            .depth_compare_op(vk::CompareOp::LESS_OR_EQUAL);
        let attachment = [vk::PipelineColorBlendAttachmentState::default()
            .blend_enable(desc.alpha_blend)
            .src_color_blend_factor(vk::BlendFactor::SRC_ALPHA)
            .dst_color_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA)
            .color_blend_op(vk::BlendOp::ADD)
            .src_alpha_blend_factor(vk::BlendFactor::ONE)
            .dst_alpha_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA)
            .alpha_blend_op(vk::BlendOp::ADD)
            .color_write_mask(vk::ColorComponentFlags::RGBA)];
        let blend = vk::PipelineColorBlendStateCreateInfo::default().attachments(&attachment);
        let dynamic_states = [vk::DynamicState::VIEWPORT, vk::DynamicState::SCISSOR];
        let dynamic = vk::PipelineDynamicStateCreateInfo::default().dynamic_states(&dynamic_states);
        let info = [vk::GraphicsPipelineCreateInfo::default()
            .stages(&stages)
            .vertex_input_state(&vertex_input)
            .input_assembly_state(&assembly)
            .viewport_state(&viewport)
            .rasterization_state(&raster)
            .multisample_state(&multisample)
            .depth_stencil_state(&depth)
            .color_blend_state(&blend)
            .dynamic_state(&dynamic)
            .layout(layout)
            .render_pass(render_pass)
            .subpass(0)];
        // SAFETY: every referenced object is alive.
        let pipeline = unsafe { device.create_graphics_pipelines(self.cache, &info, None) }.map_err(|(_, e)| Error::Vulkan(e))?[0];
        self.create_ns += t.elapsed().as_nanos() as u64;
        self.created += 1;
        self.pipelines.insert(key, pipeline);
        Ok(pipeline)
    }

    /// Size of the driver's pipeline cache data (what would be saved to disk).
    pub fn data_size(&self, device: &ash::Device) -> usize {
        // SAFETY: valid cache.
        unsafe { device.get_pipeline_cache_data(self.cache) }.map(|d| d.len()).unwrap_or(0)
    }

    pub fn destroy(&mut self, device: &ash::Device) {
        // SAFETY: the caller waited for the device to be idle.
        unsafe {
            for (_, p) in self.pipelines.drain() {
                device.destroy_pipeline(p, None);
            }
            device.destroy_pipeline_cache(self.cache, None);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn terrain_shaders_compile_to_valid_spirv() {
        for (src, stage) in [(crate::terrain::VERTEX_SHADER, Stage::Vertex), (crate::terrain::FRAGMENT_SHADER, Stage::Fragment)] {
            let words = compile_glsl(src, stage).unwrap_or_else(|e| panic!("{e}"));
            assert_eq!(words[0], 0x0723_0203, "SPIR-V magic");
        }
    }

    #[test]
    fn broken_glsl_reports_an_error() {
        assert!(matches!(compile_glsl("#version 450\nvoid main() { undefined_call(); }", Stage::Vertex), Err(Error::Shader(_))));
    }
}
