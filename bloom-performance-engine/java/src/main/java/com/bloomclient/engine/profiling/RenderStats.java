package com.bloomclient.engine.profiling;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Per-frame measurements of the parts of the frame Bloom does not replace:
 * entity, block entity and particle work, feature preparation, frame graph
 * execution, and the draw calls that reach {@code renderpearl} (OpenGL or
 * Vulkan alike).
 *
 * <p>Everything here runs on the render thread (Minecraft's main thread), so
 * plain fields are enough. Costs per frame: two {@code System.nanoTime()}
 * calls per stage and one increment per draw call.
 */
public final class RenderStats {
	public enum Stage {
		EXTRACT_ENTITIES("entity extract"),
		EXTRACT_BLOCK_ENTITIES("block entity extract"),
		EXTRACT_PARTICLES("particle extract"),
		SUBMIT_ENTITIES("entity submit"),
		SUBMIT_BLOCK_ENTITIES("block entity submit"),
		SUBMIT_PARTICLES("particle submit"),
		PREPARE_FEATURES("feature prepare"),
		EXECUTE_FRAME_GRAPH("frame graph");

		public final String label;

		Stage(String label) {
			this.label = label;
		}
	}

	private static final Stage[] STAGES = Stage.values();
	/** Frames kept for the on-screen averages. */
	private static final int RING = 128;

	private static final long[] current = new long[STAGES.length];
	private static final long[][] ring = new long[STAGES.length][RING];
	private static final int[] drawCallRing = new int[RING];
	private static final int[] drawRing = new int[RING];
	private static int frame;
	private static int drawCalls;
	private static int draws;
	private static int entities;
	private static int blockEntities;

	// Totals since the last reset (benchmark periods).
	private static final long[] totalNanos = new long[STAGES.length];
	private static final long[] maxNanos = new long[STAGES.length];
	private static long totalFrames;
	private static long totalDrawCalls;
	private static long totalDraws;
	private static long totalEntities;
	private static long totalBlockEntities;

	private RenderStats() {
	}

	public static long begin() {
		return System.nanoTime();
	}

	public static void end(Stage stage, long start) {
		current[stage.ordinal()] += System.nanoTime() - start;
	}

	/** One API call that issues {@code count} draws (multi-draw and indirect calls issue several). */
	public static void drawCall(int count) {
		drawCalls++;
		draws += count;
	}

	public static void entities(int rendered) {
		entities = rendered;
	}

	public static void blockEntities(int rendered) {
		blockEntities = rendered;
	}

	/** Called once per frame, before the next frame starts. */
	public static void endFrame() {
		int slot = frame % RING;
		for (int s = 0; s < STAGES.length; s++) {
			long v = current[s];
			ring[s][slot] = v;
			totalNanos[s] += v;
			if (v > maxNanos[s]) maxNanos[s] = v;
			current[s] = 0;
		}
		drawCallRing[slot] = drawCalls;
		drawRing[slot] = draws;
		totalDrawCalls += drawCalls;
		totalDraws += draws;
		totalEntities += entities;
		totalBlockEntities += blockEntities;
		totalFrames++;
		drawCalls = 0;
		draws = 0;
		frame++;
	}

	public static void reset() {
		for (int s = 0; s < STAGES.length; s++) {
			totalNanos[s] = 0;
			maxNanos[s] = 0;
		}
		totalFrames = totalDrawCalls = totalDraws = totalEntities = totalBlockEntities = 0;
	}

	private static int window() {
		return Math.min(frame, RING);
	}

	/** Mean milliseconds per frame over the last {@value #RING} frames. */
	public static double recentMs(Stage stage) {
		int n = window();
		if (n == 0) return 0;
		long sum = 0;
		for (int i = 0; i < n; i++) sum += ring[stage.ordinal()][i];
		return sum / (double) n / 1e6;
	}

	public static double recentDrawCalls() {
		return mean(drawCallRing);
	}

	public static double recentDraws() {
		return mean(drawRing);
	}

	private static double mean(int[] values) {
		int n = window();
		if (n == 0) return 0;
		long sum = 0;
		for (int i = 0; i < n; i++) sum += values[i];
		return sum / (double) n;
	}

	public static int lastEntities() {
		return entities;
	}

	public static int lastBlockEntities() {
		return blockEntities;
	}

	/** Averages since the last {@link #reset}, for reports and benchmarks. */
	public static Map<String, Object> snapshot() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("frames", totalFrames);
		if (totalFrames == 0) {
			return out;
		}
		Map<String, Object> stages = new LinkedHashMap<>();
		for (Stage s : STAGES) {
			stages.put(s.name().toLowerCase(Locale.ROOT), Map.of("meanMs", totalNanos[s.ordinal()] / (double) totalFrames / 1e6, "maxMs",
					maxNanos[s.ordinal()] / 1e6));
		}
		out.put("stagesPerFrame", stages);
		out.put("drawCallsPerFrame", totalDrawCalls / (double) totalFrames);
		out.put("drawsPerFrame", totalDraws / (double) totalFrames);
		out.put("entitiesPerFrame", totalEntities / (double) totalFrames);
		out.put("blockEntitiesPerFrame", totalBlockEntities / (double) totalFrames);
		return out;
	}
}
