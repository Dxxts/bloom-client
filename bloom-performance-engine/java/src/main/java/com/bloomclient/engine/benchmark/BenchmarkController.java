package com.bloomclient.engine.benchmark;

import com.bloomclient.engine.BloomEngine;
import com.bloomclient.engine.profiling.EngineProfiler;
import com.bloomclient.engine.profiling.GpuFrameTimer;
import com.bloomclient.engine.profiling.VramProbe;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;

/**
 * Repeatable in-game benchmark, active only with {@code -Dbloom.benchmark=<scene>}.
 *
 * <p>Load the scene's world with {@code --quickPlaySingleplayer <world>}. The
 * controller then:
 * <ol>
 *   <li>waits {@code bloom.benchmark.warmup} seconds (default 10);</li>
 *   <li>resets the level renderer and measures the time until every section
 *   is compiled (world load / full remesh);</li>
 *   <li>turns the camera 360° over {@code bloom.benchmark.duration} seconds
 *   (default 30) and records frame statistics;</li>
 *   <li>writes {@code .bloom/benchmarks/<scene>-<engine>-<timestamp>.json}
 *   and closes the game.</li>
 * </ol>
 * Run it once with {@code -Dbloom.engine.enabled=false} (vanilla) and once
 * without, with identical options, world and hardware.
 */
public final class BenchmarkController {
	private enum Phase { WAIT_WORLD, WARMUP, REMESH, ROTATE, SCREENSHOT, DONE }

	private static final String SCENE = System.getProperty("bloom.benchmark");
	private static final long WARMUP_NS = Long.getLong("bloom.benchmark.warmup", 10) * 1_000_000_000L;
	private static final long DURATION_NS = Long.getLong("bloom.benchmark.duration", 30) * 1_000_000_000L;

	private static Phase phase = Phase.WAIT_WORLD;
	private static long phaseStart;
	private static long remeshNanos;
	private static float startYaw;
	private static boolean screenshotTaken;
	private static final boolean SCREENSHOT = Boolean.parseBoolean(System.getProperty("bloom.benchmark.screenshot", "true"));
	private static final Map<String, Object> results = new LinkedHashMap<>();

	private BenchmarkController() {
	}

	/** Main thread, once per frame. */
	public static void tick(Minecraft mc) {
		if (SCENE == null || phase == Phase.DONE) {
			return;
		}
		long now = System.nanoTime();
		switch (phase) {
			case WAIT_WORLD -> {
				if (mc.level != null && mc.player != null) {
					GpuFrameTimer.force(true);
					next(Phase.WARMUP, now);
				}
			}
			case WARMUP -> {
				if (now - phaseStart > WARMUP_NS) {
					EngineProfiler.reset();
					// Same as F3+A: every section is recompiled.
					mc.levelExtractor.allChanged();
					next(Phase.REMESH, now);
				}
			}
			case REMESH -> {
				boolean done = mc.levelRenderer.sectionRenderDispatcher().isQueueEmpty() && mc.levelRenderer.hasRenderedAllSections();
				boolean timedOut = now - phaseStart > 120_000_000_000L;
				if (done && now - phaseStart > 500_000_000L || timedOut) {
					remeshNanos = now - phaseStart;
					results.put("remeshTimedOut", timedOut);
					results.put("remesh", EngineProfiler.snapshot());
					EngineProfiler.reset();
					startYaw = mc.player.getYRot();
					next(Phase.ROTATE, now);
				}
			}
			case ROTATE -> {
				float t = (now - phaseStart) / (float) DURATION_NS;
				mc.player.setYRot(startYaw + 360.0F * Math.min(t, 1.0F));
				mc.player.setXRot(0.0F);
				if (t >= 1.0F) {
					finish(mc);
					if (SCREENSHOT) {
						// After the measurements: the F3 overlay costs frame time.
						mc.debugEntries.setOverlayVisible(true);
						next(Phase.SCREENSHOT, now);
					} else {
						phase = Phase.DONE;
						mc.stop();
					}
				}
			}
			case SCREENSHOT -> {
				if (!screenshotTaken && now - phaseStart > 1_500_000_000L) {
					screenshotTaken = true;
					net.minecraft.client.Screenshot.grab(mc.gameDirectory, "bloom-f3-" + engineLabel() + ".png", mc.gameRenderer.mainRenderTarget(), 1, message -> {});
				}
				if (now - phaseStart > 3_000_000_000L) {
					phase = Phase.DONE;
					mc.stop();
				}
			}
			default -> {
			}
		}
	}

	private static void next(Phase p, long now) {
		phase = p;
		phaseStart = now;
	}

	private static String engineLabel() {
		String backend = com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().backendName().toLowerCase(java.util.Locale.ROOT).contains("vulkan")
				? "vulkan" : "opengl";
		return (BloomEngine.active() ? "bloom-native" : "vanilla") + "-" + backend;
	}

	private static void finish(Minecraft mc) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("scene", SCENE);
		out.put("minecraft", SharedConstants.getCurrentVersion().name());
		out.put("engine", BloomEngine.status().name());
		out.put("engineReason", BloomEngine.reason());
		out.put("renderDistance", mc.options.renderDistance().get());
		out.put("graphicsBackendOption", String.valueOf(mc.options.preferredGraphicsBackend().get()));
		out.put("cpuThreads", Runtime.getRuntime().availableProcessors());
		out.put("maxHeapMb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
		out.put("remeshAllSectionsMs", remeshNanos / 1e6);
		out.putAll(results);
		out.put("rotate", EngineProfiler.snapshot());
		VramProbe.Sample vram = VramProbe.sample();
		out.put("vram", java.util.Map.of("usedMiB", vram.usedMiB(), "totalMiB", vram.totalMiB(), "source", vram.source()));
		out.put("gpuDevice", com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().name());
		out.put("gpuBackend", com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().backendName());
		out.put("nativeFluids", BloomEngine.config().nativeFluids);
		out.put("mods", net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods().stream().map(m -> m.getMetadata().getId() + "@" + m.getMetadata().getVersion().getFriendlyString()).filter(s -> !s.startsWith("java@") && !s.startsWith("minecraft@")).sorted().toList());
		String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		EngineProfiler.write(BloomEngine.dataDir().resolve("benchmarks").resolve(SCENE + "-" + engineLabel() + "-" + stamp + ".json"), out);
		BloomEngine.LOGGER.info("Bloom benchmark '{}' finished", SCENE);
	}
}
