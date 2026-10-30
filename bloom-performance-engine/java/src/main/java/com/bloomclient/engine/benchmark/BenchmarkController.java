package com.bloomclient.engine.benchmark;

import com.bloomclient.engine.BloomEngine;
import com.bloomclient.engine.profiling.EngineProfiler;
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
	private enum Phase { WAIT_WORLD, WARMUP, REMESH, ROTATE, DONE }

	private static final String SCENE = System.getProperty("bloom.benchmark");
	private static final long WARMUP_NS = Long.getLong("bloom.benchmark.warmup", 10) * 1_000_000_000L;
	private static final long DURATION_NS = Long.getLong("bloom.benchmark.duration", 30) * 1_000_000_000L;

	private static Phase phase = Phase.WAIT_WORLD;
	private static long phaseStart;
	private static long remeshNanos;
	private static float startYaw;
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

	private static void finish(Minecraft mc) {
		phase = Phase.DONE;
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
		String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		String engine = BloomEngine.active() ? "bloom-native" : "vanilla";
		EngineProfiler.write(BloomEngine.dataDir().resolve("benchmarks").resolve(SCENE + "-" + engine + "-" + stamp + ".json"), out);
		BloomEngine.LOGGER.info("Bloom benchmark '{}' finished", SCENE);
		mc.stop();
	}
}
