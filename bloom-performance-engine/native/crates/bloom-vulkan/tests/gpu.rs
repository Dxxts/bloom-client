//! End to end on the real GPU: device, allocator, upload, shader
//! compilation, pipeline, descriptors, a frame in flight, GPU timestamps and
//! readback. Skipped (with a message) on machines without Vulkan.

use ash::vk;
use bloom_vulkan::descriptor::{DescriptorAllocator, LayoutCache};
use bloom_vulkan::device::{Device, Preference};
use bloom_vulkan::diagnostics::GpuTimer;
use bloom_vulkan::frame::Frames;
use bloom_vulkan::instance::Instance;
use bloom_vulkan::memory::Allocator;
use bloom_vulkan::pipeline::{PipelineCache, ShaderCache};
use bloom_vulkan::target::{Targets, DEPTH_FORMAT, OFFSCREEN_FORMAT};
use bloom_vulkan::terrain::{view_projection, DrawMode, SectionMesh, TerrainRenderer};

/// One vertex in the `BLOCK` format.
fn vertex(p: [f32; 3], rgba: [u8; 4], light: (i16, i16)) -> Vec<u8> {
    let mut v = Vec::with_capacity(28);
    for c in p {
        v.extend_from_slice(&c.to_le_bytes());
    }
    v.extend_from_slice(&rgba);
    v.extend_from_slice(&0.0f32.to_le_bytes());
    v.extend_from_slice(&0.0f32.to_le_bytes());
    v.extend_from_slice(&light.0.to_le_bytes());
    v.extend_from_slice(&light.1.to_le_bytes());
    v
}

#[test]
fn renders_a_quad_offscreen_and_reads_it_back() {
    let instance = match Instance::new(c"bloom-vulkan-test", true) {
        Ok(i) => i,
        Err(e) => {
            eprintln!("skipped: {e}");
            return;
        }
    };
    let device = match Device::new(&instance, &Preference::Auto, false) {
        Ok(d) => d,
        Err(e) => {
            eprintln!("skipped: {e}");
            return;
        }
    };
    let d = &device.raw;
    let mut allocator = Allocator::new(d.clone(), device.memory_properties);
    let mut layouts = LayoutCache::default();
    let mut descriptors = DescriptorAllocator::new(8);
    let mut shaders = ShaderCache::default();
    let mut pipelines = PipelineCache::new(d).unwrap();

    // A red quad at z = 0 facing the camera, full sky light; section origin (1, 0, 0).
    let mut quad = Vec::new();
    for p in [[-1.5, -0.5, 0.0], [-0.5, -0.5, 0.0], [-0.5, 0.5, 0.0], [-1.5, 0.5, 0.0]] {
        quad.extend(vertex(p, [255, 0, 0, 255], (0, 240)));
    }
    let sections = [SectionMesh { origin: [1.0, 0.0, 0.0], vertices: quad }];
    let extent = vk::Extent2D { width: 64, height: 64 };
    let terrain = TerrainRenderer::new(
        d,
        device.queue,
        device.queue_family,
        &mut allocator,
        &mut layouts,
        &mut descriptors,
        &mut shaders,
        &mut pipelines,
        OFFSCREEN_FORMAT,
        DEPTH_FORMAT,
        vk::ImageLayout::TRANSFER_SRC_OPTIMAL,
        2,
        &sections,
    )
    .expect("terrain renderer");
    let targets = Targets::offscreen(d, &mut allocator, terrain.render_pass, extent).unwrap();
    let mut frames = Frames::new(d, device.queue_family, 2).unwrap();
    let mut timer = GpuTimer::new(&device, 2).unwrap();

    let vp = view_projection([0.0, 0.0, 3.0], [0.0, 0.0, 0.0], 1.0, 1.0, 0.1, 100.0);
    for mode in [DrawMode::Direct, DrawMode::MultiDrawIndirect] {
        let slot = frames.slot();
        let cmd = frames.begin(d).unwrap();
        if let Some(t) = timer.as_mut() {
            t.begin(d, cmd, slot);
        }
        let counters = terrain.record(d, cmd, slot, targets.framebuffers[0], extent, &vp, mode).unwrap();
        if let Some(t) = timer.as_mut() {
            t.end(d, cmd, slot);
        }
        frames.submit(d, device.queue, false).unwrap();
        assert_eq!(counters.draws, 1);
        device.wait_idle();
        if let Some(t) = timer.as_mut() {
            assert!(t.read(d, slot).is_some_and(|ms| ms >= 0.0));
        }

        let pixels = targets.read_color(d, &mut allocator, device.queue, device.queue_family).unwrap();
        let at = |x: u32, y: u32| {
            let i = ((y * extent.width + x) * 4) as usize;
            [pixels[i], pixels[i + 1], pixels[i + 2]] // B, G, R
        };
        // Center: the red quad (lit, checker 0.85..1.0); corner: the sky clear color.
        let center = at(32, 32);
        assert!(center[2] > 180 && center[1] < 30 && center[0] < 30, "{mode:?}: center pixel {center:?}");
        let corner = at(1, 1);
        assert!(corner[0] > 200 && corner[2] < 140, "{mode:?}: corner pixel {corner:?}");
    }
    assert_eq!(pipelines.created, 1);
    assert!(allocator.stats().device_local_used_bytes > 0);
    let log = instance.debug_log.lock().unwrap();
    assert_eq!(log.errors, 0, "validation errors: {:?}", log.last);
    drop(log);

    device.wait_idle();
    frames.destroy(d);
    if let Some(mut t) = timer {
        t.destroy(d);
    }
    targets.destroy(d, &mut allocator);
    terrain.destroy(d, &mut allocator);
    pipelines.destroy(d);
    shaders.destroy(d);
    descriptors.destroy(d);
    layouts.destroy(d);
    allocator.destroy();
}
