//! GPU benchmark of the experimental Bloom Vulkan backend.
//!
//! Meshes a grid of sections of a synthetic scene with the Bloom mesher
//! (same scenes as `bloom-bench`), uploads them once, then renders N frames
//! with a camera turning around the grid and measures GPU time (timestamps),
//! CPU recording time, draw calls and video memory. Direct draws (one per
//! section) and a single multi-draw indirect call are compared.
//!
//! This measures Bloom's own backend in isolation. It is not a comparison
//! with Minecraft's renderer, which draws textured, sorted, multi-pass frames.
//!
//! ```text
//! bloom-gpu-bench [--scene terrain|ocean|builds] [--sections-x N] [--frames N]
//!                 [--present] [--vsync] [--device auto|INDEX|NAME] [--validation] [--out FILE]
//! ```

use std::time::Instant;

use ash::vk;
use bloom_bench::scenes::{palette, snapshot, Builds, Ocean, Scene, Terrain};
use bloom_vulkan::descriptor::{DescriptorAllocator, LayoutCache};
use bloom_vulkan::device::{enumerate, Device, Preference};
use bloom_vulkan::diagnostics::{memory_budget, report, GpuTimer};
use bloom_vulkan::frame::{DeletionQueue, Frames, Garbage};
use bloom_vulkan::instance::Instance;
use bloom_vulkan::memory::Allocator;
use bloom_vulkan::pipeline::{PipelineCache, ShaderCache};
use bloom_vulkan::swapchain::{Swapchain, Window};
use bloom_vulkan::target::{Targets, DEPTH_FORMAT, OFFSCREEN_FORMAT};
use bloom_vulkan::terrain::{view_projection, DrawMode, SectionMesh, TerrainRenderer};
use chunk_mesher::Mesher;

const FRAMES_IN_FLIGHT: usize = 2;

struct Args {
    scene: String,
    grid: i32,
    frames: u32,
    present: bool,
    vsync: bool,
    device: String,
    validation: bool,
    out: Option<String>,
}

fn args() -> Args {
    let mut a = Args { scene: "terrain".into(), grid: 16, frames: 600, present: false, vsync: false, device: "auto".into(), validation: false, out: None };
    let v: Vec<String> = std::env::args().collect();
    let mut i = 1;
    while i < v.len() {
        match v[i].as_str() {
            "--scene" => a.scene = v[i + 1].clone(),
            "--sections-x" => a.grid = v[i + 1].parse().expect("--sections-x N"),
            "--frames" => a.frames = v[i + 1].parse().expect("--frames N"),
            "--device" => a.device = v[i + 1].clone(),
            "--out" => a.out = Some(v[i + 1].clone()),
            "--present" => {
                a.present = true;
                i += 1;
                continue;
            }
            "--vsync" => {
                a.vsync = true;
                i += 1;
                continue;
            }
            "--validation" => {
                a.validation = true;
                i += 1;
                continue;
            }
            "--list-devices" => {
                list_devices();
                std::process::exit(0);
            }
            other => panic!("unknown argument {other}"),
        }
        i += 2;
    }
    a
}

/// Prints every Vulkan device and whether Bloom's backend can use it (used by the launcher).
fn list_devices() {
    let instance = match Instance::new(c"bloom-gpu-bench", false) {
        Ok(i) => i,
        Err(e) => {
            println!("{{\"error\": \"{e}\"}}");
            return;
        }
    };
    let devices = enumerate(&instance).unwrap_or_default();
    let rows: Vec<String> = devices
        .iter()
        .map(|(_, c)| {
            format!(
                "{{\"index\": {}, \"name\": \"{}\", \"type\": \"{}\", \"vramMiB\": {}, \"suitable\": {}}}",
                c.index,
                c.name.replace('"', "'"),
                c.type_name(),
                c.device_local_bytes >> 20,
                c.unsuitable(false).is_none()
            )
        })
        .collect();
    println!("[{}]", rows.join(", "));
}

fn mesh_scene(scene: &dyn Scene, grid: i32) -> (Vec<SectionMesh>, f64, u64) {
    let (table, models) = palette();
    let mut mesher = Mesher::new();
    let mut sections = Vec::new();
    let mut quads = 0u64;
    let t = Instant::now();
    for x in 0..grid {
        for y in 2..6 {
            for z in 0..grid {
                let snap = snapshot(scene, x, y, z);
                mesher.plan_light(&snap, &table, models.as_slice()).expect("plan");
                mesher.mesh(&snap, &table, models.as_slice()).expect("mesh");
                quads += mesher.stats.quads as u64;
                let mut vertices = Vec::new();
                for layer in &mesher.layers {
                    vertices.extend_from_slice(layer);
                }
                sections.push(SectionMesh { origin: [(x * 16) as f32, (y * 16) as f32, (z * 16) as f32], vertices });
            }
        }
    }
    (sections, t.elapsed().as_secs_f64() * 1000.0, quads)
}

#[derive(Default)]
struct ModeResult {
    gpu_ms: Vec<f64>,
    cpu_record_ms: Vec<f64>,
    frame_ms: Vec<f64>,
    draw_calls: u32,
    draws: u32,
}

fn stats(v: &mut [f64]) -> (f64, f64, f64) {
    if v.is_empty() {
        return (0.0, 0.0, 0.0);
    }
    v.sort_by(f64::total_cmp);
    let mean = v.iter().sum::<f64>() / v.len() as f64;
    (mean, v[v.len() / 2], v[((v.len() - 1) as f64 * 0.99) as usize])
}

fn main() {
    let a = args();
    let scene: Box<dyn Scene> = match a.scene.as_str() {
        "ocean" => Box::new(Ocean),
        "builds" => Box::new(Builds),
        _ => Box::new(Terrain),
    };
    let (sections, mesh_ms, quads) = mesh_scene(&*scene, a.grid);
    println!("meshed {} sections ({} quads) in {:.0} ms on the CPU", sections.len(), quads, mesh_ms);

    let instance = Instance::new(c"bloom-gpu-bench", a.validation).expect("Vulkan instance");
    let window = if a.present { Some(Window::new("Bloom Vulkan backend (experimental)", 1600, 900).expect("window")) } else { None };
    let device = Device::new(&instance, &Preference::parse(&a.device), a.present).expect("Vulkan device");
    for line in report(&instance, &device) {
        println!("  {line}");
    }
    let d = &device.raw;
    let mut allocator = Allocator::new(d.clone(), device.memory_properties);
    let mut layouts = LayoutCache::default();
    let mut descriptors = DescriptorAllocator::new(16);
    let mut shaders = ShaderCache::default();
    let mut pipelines = PipelineCache::new(d).expect("pipeline cache");
    let mut deletion = DeletionQueue::default();

    let mut swapchain = window.as_ref().map(|w| Swapchain::new(&instance, &device, w, a.vsync).expect("swapchain"));
    let (color_format, final_layout) = match &swapchain {
        Some(s) => (s.format, vk::ImageLayout::PRESENT_SRC_KHR),
        None => (OFFSCREEN_FORMAT, vk::ImageLayout::TRANSFER_SRC_OPTIMAL),
    };
    let upload_start = Instant::now();
    let terrain = TerrainRenderer::new(
        d,
        device.queue,
        device.queue_family,
        &mut allocator,
        &mut layouts,
        &mut descriptors,
        &mut shaders,
        &mut pipelines,
        color_format,
        DEPTH_FORMAT,
        final_layout,
        FRAMES_IN_FLIGHT,
        &sections,
    )
    .expect("terrain renderer");
    let upload_ms = upload_start.elapsed().as_secs_f64() * 1000.0;
    let extent = swapchain.as_ref().map_or(vk::Extent2D { width: 1920, height: 1080 }, |s| s.extent);
    let mut targets = match &swapchain {
        Some(s) => Targets::swapchain(d, &mut allocator, terrain.render_pass, &s.views, s.extent),
        None => Targets::offscreen(d, &mut allocator, terrain.render_pass, extent),
    }
    .expect("targets");
    let mut frames = Frames::new(d, device.queue_family, FRAMES_IN_FLIGHT).expect("frames");
    let mut timer = GpuTimer::new(&device, FRAMES_IN_FLIGHT as u32).expect("timer");

    let center = [a.grid as f32 * 8.0, 64.0, a.grid as f32 * 8.0];
    let radius = a.grid as f32 * 8.0 + 40.0;
    let mut results = Vec::new();
    'modes: for mode in [DrawMode::Direct, DrawMode::MultiDrawIndirect] {
        let mut r = ModeResult::default();
        let mut pending = [None::<()>; FRAMES_IN_FLIGHT];
        for f in 0..a.frames + FRAMES_IN_FLIGHT as u32 {
            if let Some(w) = &window {
                if !w.pump() {
                    break 'modes;
                }
            }
            let frame_start = Instant::now();
            let slot = frames.slot();
            let cmd = frames.begin(d).expect("begin");
            if pending[slot].take().is_some() {
                if let Some(ms) = timer.as_mut().and_then(|t| t.read(d, slot)) {
                    r.gpu_ms.push(ms);
                }
            }
            deletion.collect(frames.completed, d, &mut allocator);
            let image = match &swapchain {
                Some(s) => match s.acquire(frames.current().image_available).expect("acquire") {
                    Some(i) => i as usize,
                    None => {
                        // Window resized: rebuild the swapchain and its framebuffers. The
                        // empty submission signals this slot's fence, which `begin` reset.
                        frames.submit(d, device.queue, false).expect("submit");
                        let size = window.as_ref().unwrap().client_size();
                        let s = swapchain.as_mut().unwrap();
                        s.create(&device, size).expect("recreate swapchain");
                        let old = std::mem::replace(&mut targets, Targets::swapchain(d, &mut allocator, terrain.render_pass, &s.views, s.extent).expect("targets"));
                        for fb in old.framebuffers {
                            deletion.push(frames.number, Garbage::Framebuffer(fb));
                        }
                        deletion.push(frames.number, Garbage::Image(old.depth));
                        continue;
                    }
                },
                None => 0,
            };
            let angle = f as f32 / a.frames as f32 * std::f32::consts::TAU;
            let eye = [center[0] + radius * angle.cos(), center[1] + 30.0, center[2] + radius * angle.sin()];
            let vp = view_projection(eye, center, 70f32.to_radians(), targets.extent.width as f32 / targets.extent.height as f32, 0.5, 2000.0);
            let record = Instant::now();
            if let Some(t) = timer.as_mut() {
                t.begin(d, cmd, slot);
            }
            let counters = terrain.record(d, cmd, slot, targets.framebuffers[image], targets.extent, &vp, mode).expect("record");
            if let Some(t) = timer.as_mut() {
                t.end(d, cmd, slot);
            }
            let record_ms = record.elapsed().as_secs_f64() * 1000.0;
            frames.submit(d, device.queue, swapchain.is_some()).expect("submit");
            if let Some(s) = &swapchain {
                let signal = frames.frames[(frames.number - 1) as usize % FRAMES_IN_FLIGHT].render_finished;
                s.present(device.queue, image as u32, signal).expect("present");
            }
            pending[slot] = Some(());
            // The first frames warm up caches and are not counted.
            if f >= FRAMES_IN_FLIGHT as u32 {
                r.cpu_record_ms.push(record_ms);
                r.frame_ms.push(frame_start.elapsed().as_secs_f64() * 1000.0);
                r.draw_calls = counters.draw_calls;
                r.draws = counters.draws;
            }
        }
        device.wait_idle();
        for (slot, done) in pending.iter_mut().enumerate() {
            if done.take().is_some() {
                if let Some(ms) = timer.as_mut().and_then(|t| t.read(d, slot)) {
                    r.gpu_ms.push(ms);
                }
            }
        }
        results.push((mode, r));
    }
    device.wait_idle();

    let mem = allocator.stats();
    let budget = memory_budget(&instance, &device);
    let usage: u64 = budget.iter().filter(|h| h.device_local).filter_map(|h| h.usage).sum();
    let validation = instance.debug_log.lock().map(|l| (l.warnings, l.errors)).unwrap_or((0, 0));
    println!(
        "{:<20} {:>10} {:>8} {:>10} {:>10} {:>10} {:>12} {:>10}",
        "mode", "draw calls", "draws", "GPU mean", "GPU p50", "GPU p99", "CPU record", "frame"
    );
    let mut json_modes = Vec::new();
    for (mode, r) in &mut results {
        let (gm, g50, g99) = stats(&mut r.gpu_ms);
        let (cm, _, _) = stats(&mut r.cpu_record_ms);
        let (fm, _, _) = stats(&mut r.frame_ms);
        println!("{:<20} {:>10} {:>8} {:>8.3}ms {:>8.3}ms {:>8.3}ms {:>10.3}ms {:>8.3}ms", format!("{mode:?}"), r.draw_calls, r.draws, gm, g50, g99, cm, fm);
        json_modes.push(format!(
            "{{\"mode\": \"{mode:?}\", \"frames\": {}, \"drawCallsPerFrame\": {}, \"drawsPerFrame\": {}, \"gpuMsMean\": {gm:.4}, \"gpuMsP50\": {g50:.4}, \"gpuMsP99\": {g99:.4}, \"cpuRecordMsMean\": {cm:.4}, \"frameMsMean\": {fm:.4}}}",
            r.frame_ms.len(),
            r.draw_calls,
            r.draws
        ));
    }
    println!(
        "memory: {} MiB used by Bloom ({} blocks, {} dedicated), device-local usage {} MiB · shaders compiled {} in {:.1} ms · pipelines {} · validation {}/{} warnings/errors",
        mem.used_bytes >> 20,
        mem.blocks,
        mem.dedicated,
        usage >> 20,
        shaders.compiled,
        shaders.compile_ns as f64 / 1e6,
        pipelines.created,
        validation.0,
        validation.1
    );
    if let Some(path) = &a.out {
        let json = format!(
            "{{\n  \"tool\": \"bloom-gpu-bench\",\n  \"device\": \"{}\",\n  \"scene\": \"{}\",\n  \"sections\": {},\n  \"quads\": {},\n  \"vertices\": {},\n  \"extent\": [{}, {}],\n  \"present\": {},\n  \"vsync\": {},\n  \"validationLayer\": {},\n  \"validationWarnings\": {},\n  \"validationErrors\": {},\n  \"cpuMeshMs\": {:.1},\n  \"uploadMs\": {:.1},\n  \"uploadedBytes\": {},\n  \"bloomMemoryUsedMiB\": {},\n  \"deviceLocalUsageMiB\": {},\n  \"shaderCompileMs\": {:.2},\n  \"modes\": [\n    {}\n  ]\n}}\n",
            device.info.name,
            scene.name(),
            sections.len(),
            quads,
            terrain.vertex_count,
            targets.extent.width,
            targets.extent.height,
            a.present,
            a.vsync,
            instance.validation,
            validation.0,
            validation.1,
            mesh_ms,
            upload_ms,
            terrain.uploaded_bytes,
            mem.used_bytes >> 20,
            usage >> 20,
            shaders.compile_ns as f64 / 1e6,
            json_modes.join(",\n    ")
        );
        std::fs::write(path, json).expect("write results");
        println!("results written to {path}");
    }

    deletion.flush(d, &mut allocator);
    frames.destroy(d);
    if let Some(mut t) = timer {
        t.destroy(d);
    }
    targets.destroy(d, &mut allocator);
    terrain.destroy(d, &mut allocator);
    if let Some(mut s) = swapchain.take() {
        s.destroy(&device);
    }
    pipelines.destroy(d);
    shaders.destroy(d);
    descriptors.destroy(d);
    layouts.destroy(d);
    allocator.destroy();
}
