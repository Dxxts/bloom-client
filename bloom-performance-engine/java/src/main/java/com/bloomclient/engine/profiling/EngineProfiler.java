package com.bloomclient.engine.profiling;

import com.bloomclient.engine.BloomEngine;
import com.bloomclient.engine.bridge.NativeBridge;
import com.bloomclient.engine.bridge.Protocol;
import com.google.gson.GsonBuilder;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import org.lwjgl.system.MemoryUtil;

/**
 * Measurements of the world renderer, Java side.
 *
 * <ul>
 *   <li>Section compile time, split by path (native or vanilla), measured
 *   around the whole {@code SectionCompiler.compile} call.</li>
 *   <li>Snapshot and native mesh time of the native path.</li>
 *   <li>Frame time, FPS, 1% low and stutters on the main (render) thread.</li>
 *   <li>Heap and process CPU load.</li>
 * </ul>
 * Entity and particle stages, draw calls and GPU frame time come from
 * {@link RenderStats} and {@link GpuFrameTimer}.
 */
public final class EngineProfiler {
	private static final Samples COMPILE_NATIVE = new Samples(1 << 16);
	private static final Samples COMPILE_VANILLA = new Samples(1 << 16);
	private static final Samples SNAPSHOT = new Samples(1 << 16);
	private static final Samples NATIVE_MESH = new Samples(1 << 16);
	private static final LongAdder NATIVE_BLOCKS = new LongAdder();
	private static final LongAdder DEMOTED_BLOCKS = new LongAdder();
	private static final LongAdder NATIVE_FLUIDS = new LongAdder();
	private static final LongAdder DEMOTED_FLUIDS = new LongAdder();

	private static final int FRAMES = 1 << 15;
	private static final long[] frameTimes = new long[FRAMES];
	private static final long[] frameWork = new long[FRAMES];
	private static int frameCount;
	private static long lastFrameStart;
	private static long frameStart;

	private static Path dataDir = Path.of(".bloom");
	private static long intervalNanos = 30_000_000_000L;
	private static long lastReport = System.nanoTime();

	private EngineProfiler() {
	}

	public static void init(Path dir, int intervalSeconds) {
		dataDir = dir;
		intervalNanos = intervalSeconds * 1_000_000_000L;
	}

	public static void recordNativeSection(long snapshotNanos, long meshNanos, int nativeBlocks, int demoted, int nativeFluids, int demotedFluids) {
		SNAPSHOT.add(snapshotNanos);
		NATIVE_MESH.add(meshNanos);
		NATIVE_BLOCKS.add(nativeBlocks);
		DEMOTED_BLOCKS.add(demoted);
		NATIVE_FLUIDS.add(nativeFluids);
		DEMOTED_FLUIDS.add(demotedFluids);
	}

	/**
	 * Running totals since the last reset: native compiles (count, ns),
	 * vanilla compiles, snapshots, native meshing.
	 */
	public static long[] totals() {
		return new long[] {COMPILE_NATIVE.count(), COMPILE_NATIVE.sum(), COMPILE_VANILLA.count(), COMPILE_VANILLA.sum(), SNAPSHOT.count(), SNAPSHOT.sum(),
				NATIVE_MESH.count(), NATIVE_MESH.sum()};
	}

	public static long demotedBlocks() {
		return DEMOTED_BLOCKS.sum();
	}

	public static long demotedFluids() {
		return DEMOTED_FLUIDS.sum();
	}

	public static long vanillaSections() {
		return COMPILE_VANILLA.count();
	}

	public static void recordCompile(boolean nativePath, long nanos) {
		(nativePath ? COMPILE_NATIVE : COMPILE_VANILLA).add(nanos);
	}

	/** Main thread only. */
	public static void frameStart() {
		long now = System.nanoTime();
		if (lastFrameStart != 0) {
			frameTimes[frameCount % FRAMES] = now - lastFrameStart;
			frameWork[frameCount % FRAMES] = frameStart == 0 ? 0 : frameWorkPending;
			frameCount++;
		}
		lastFrameStart = now;
		frameStart = now;
		if (now - lastReport > intervalNanos) {
			lastReport = now;
			write(dataDir.resolve("engine-stats.json"), snapshot());
		}
	}

	private static long frameWorkPending;

	/** Main thread only. */
	public static void frameEnd() {
		frameWorkPending = System.nanoTime() - frameStart;
	}

	public static void reset() {
		COMPILE_NATIVE.reset();
		COMPILE_VANILLA.reset();
		SNAPSHOT.reset();
		NATIVE_MESH.reset();
		NATIVE_BLOCKS.reset();
		DEMOTED_BLOCKS.reset();
		NATIVE_FLUIDS.reset();
		DEMOTED_FLUIDS.reset();
		RenderStats.reset();
		GpuFrameTimer.reset();
		frameCount = 0;
		lastFrameStart = 0;
		try {
			NativeBridge.resetStats();
		} catch (Throwable ignored) {
			// Library not loaded: nothing to reset.
		}
	}

	public static Map<String, Object> snapshot() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("engineStatus", BloomEngine.status().name());
		out.put("engineReason", BloomEngine.reason());
		out.put("compileNativeMs", COMPILE_NATIVE.summaryMs());
		out.put("compileVanillaMs", COMPILE_VANILLA.summaryMs());
		out.put("snapshotMs", SNAPSHOT.summaryMs());
		out.put("nativeMeshMs", NATIVE_MESH.summaryMs());
		out.put("nativeBlocks", NATIVE_BLOCKS.sum());
		out.put("demotedBlocks", DEMOTED_BLOCKS.sum());
		out.put("nativeFluids", NATIVE_FLUIDS.sum());
		out.put("demotedFluids", DEMOTED_FLUIDS.sum());
		out.put("frames", frameSummary());
		out.put("render", RenderStats.snapshot());
		out.put("gpuFrame", GpuFrameTimer.snapshot());
		out.put("validatedSections", com.bloomclient.engine.validation.MeshValidator.passed());
		out.put("validationSkippedLightChanged", com.bloomclient.engine.validation.MeshValidator.skipped());
		Runtime rt = Runtime.getRuntime();
		out.put("heapUsedMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
		out.put("heapMaxMb", rt.maxMemory() / (1024 * 1024));
		if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
			out.put("processCpuLoad", os.getProcessCpuLoad());
		}
		out.put("native", nativeStats());
		return out;
	}

	private static Map<String, Object> frameSummary() {
		int n = Math.min(frameCount, FRAMES);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("count", n);
		if (n == 0) {
			return out;
		}
		long[] sorted = Arrays.copyOf(frameTimes, n);
		Arrays.sort(sorted);
		double total = 0;
		for (long t : sorted) total += t;
		int worst = Math.max(1, n / 100);
		double worstTotal = 0;
		for (int i = n - worst; i < n; i++) worstTotal += sorted[i];
		long median = sorted[n / 2];
		int stutters = 0;
		for (int i = 0; i < n; i++) {
			if (frameTimes[i] > median * 2.5 && frameTimes[i] > 33_000_000L) stutters++;
		}
		long[] work = Arrays.copyOf(frameWork, n);
		Arrays.sort(work);
		out.put("avgFps", n / (total / 1e9));
		out.put("onePercentLowFps", worst / (worstTotal / 1e9));
		out.put("frameTimeMs", Map.of("p50", sorted[n / 2] / 1e6, "p99", sorted[Math.min(n - 1, (int) (n * 0.99))] / 1e6, "max", sorted[n - 1] / 1e6));
		out.put("mainThreadWorkMsP50", work[n / 2] / 1e6);
		out.put("stutters", stutters);
		return out;
	}

	private static Map<String, Object> nativeStats() {
		Map<String, Object> out = new LinkedHashMap<>();
		long buf = MemoryUtil.nmemAlloc(Protocol.STATS_SLOTS * 8L);
		try {
			int n = NativeBridge.readStats(buf, Protocol.STATS_SLOTS);
			String[] names = {"sectionsMeshed", "sectionsRejected", "nativeBlocks", "demotedBlocks", "quads", "culledFaces",
					"vertexBytes", "modelsRegistered", "meshCount", "meshMeanNs", "meshP50Ns", "meshP95Ns", "meshP99Ns", "meshMaxNs", "nativeFluids", "demotedFluids"};
			for (int i = 0; i < Math.min(n, names.length); i++) {
				out.put(names[i], MemoryUtil.memGetLong(buf + i * 8L));
			}
		} catch (Throwable t) {
			out.put("unavailable", t.getClass().getSimpleName());
		} finally {
			MemoryUtil.nmemFree(buf);
		}
		return out;
	}

	public static void write(Path file, Object data) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create().toJson(data));
		} catch (Exception e) {
			BloomEngine.LOGGER.warn("Bloom Engine: cannot write {}", file, e);
		}
	}

	/** Lock-free ring of the most recent samples (nanoseconds). */
	static final class Samples {
		private final AtomicLongArray ring;
		private final AtomicInteger next = new AtomicInteger();
		private final LongAdder count = new LongAdder();
		private final LongAdder sum = new LongAdder();

		Samples(int capacity) {
			ring = new AtomicLongArray(capacity);
		}

		void add(long nanos) {
			ring.set(Math.floorMod(next.getAndIncrement(), ring.length()), nanos);
			count.increment();
			sum.add(nanos);
		}

		long count() {
			return count.sum();
		}

		long sum() {
			return sum.sum();
		}

		void reset() {
			next.set(0);
			count.reset();
			sum.reset();
		}

		Map<String, Object> summaryMs() {
			int n = (int) Math.min(count.sum(), ring.length());
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("count", count.sum());
			if (n == 0) {
				return out;
			}
			long[] v = new long[n];
			for (int i = 0; i < n; i++) v[i] = ring.get(i);
			Arrays.sort(v);
			out.put("mean", sum.sum() / (double) count.sum() / 1e6);
			out.put("p50", v[n / 2] / 1e6);
			out.put("p95", v[Math.min(n - 1, (int) (n * 0.95))] / 1e6);
			out.put("p99", v[Math.min(n - 1, (int) (n * 0.99))] / 1e6);
			out.put("max", v[n - 1] / 1e6);
			out.put("totalMs", sum.sum() / 1e6);
			return out;
		}
	}
}
