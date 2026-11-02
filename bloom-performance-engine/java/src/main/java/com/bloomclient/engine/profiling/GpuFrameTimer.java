package com.bloomclient.engine.profiling;

import com.bloomclient.engine.BloomEngine;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.GpuDevice;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;

/**
 * GPU time per frame from {@code renderpearl} timestamp queries, on OpenGL
 * and Vulkan alike: a timestamp when {@code Minecraft.runTick} starts and one
 * when it ends, read back a few frames later (the GPU runs behind the CPU).
 *
 * <p>The value is the GPU time between the two timestamps, so it includes
 * the gaps where the GPU waits for the CPU; in a GPU-bound frame it is the
 * GPU frame time. Vanilla's {@code TimerQuery} measures the same interval.
 *
 * <p>Active only while something asks for it ({@link #request}: the Bloom F3
 * entries, the benchmark), so a normal session pays nothing.
 */
public final class GpuFrameTimer {
	private static final int FRAMES = 4;
	/** Frames without a request after which the queries stop. */
	private static final int IDLE_FRAMES = 120;

	private static @Nullable GpuQueryPool pool;
	private static final boolean[] pending = new boolean[FRAMES];
	private static int frame;
	private static int lastRequest = Integer.MIN_VALUE / 2;
	private static boolean forced;
	private static boolean began;
	private static volatile double lastMs = Double.NaN;
	private static double sumMs;
	private static double maxMs;
	private static long samples;
	private static volatile boolean unavailable;

	private GpuFrameTimer() {
	}

	/** Keeps the timer running for the next {@value #IDLE_FRAMES} frames. */
	public static void request() {
		lastRequest = frame;
	}

	/** Keeps the timer running until called with {@code false} (benchmarks). */
	public static void force(boolean on) {
		forced = on;
	}

	private static boolean wanted() {
		return !unavailable && (forced || frame - lastRequest < IDLE_FRAMES);
	}

	public static void beginFrame() {
		began = false;
		if (!wanted()) {
			close();
			return;
		}
		try {
			GpuDevice device = RenderSystem.getDevice();
			if (pool == null) {
				pool = device.createTimestampQueryPool(FRAMES * 2);
			}
			int slot = frame % FRAMES;
			if (pending[slot]) {
				collect(device, slot);
			}
			device.createCommandEncoder().writeTimestamp(pool, slot * 2);
			began = true;
		} catch (Throwable t) {
			disable(t);
		}
	}

	public static void endFrame() {
		try {
			if (began && pool != null) {
				int slot = frame % FRAMES;
				RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pool, slot * 2 + 1);
				pending[slot] = true;
			}
		} catch (Throwable t) {
			disable(t);
		} finally {
			frame++;
		}
	}

	private static void collect(GpuDevice device, int slot) {
		pending[slot] = false;
		OptionalLong[] values = pool.getValues(slot * 2, 2);
		if (values.length == 2 && values[0].isPresent() && values[1].isPresent()) {
			long ticks = values[1].getAsLong() - values[0].getAsLong();
			double ms = ticks * (double) device.getDeviceInfo().timestampPeriod() / 1e6;
			if (ms >= 0) {
				lastMs = ms;
				sumMs += ms;
				maxMs = Math.max(maxMs, ms);
				samples++;
			}
		}
	}

	private static void disable(Throwable t) {
		unavailable = true;
		close();
		BloomEngine.LOGGER.warn("Bloom Engine: GPU timestamps unavailable, GPU time will not be measured", t);
	}

	private static void close() {
		if (pool != null) {
			try {
				pool.close();
			} catch (Throwable ignored) {
				// Device already gone.
			}
			pool = null;
			java.util.Arrays.fill(pending, false);
			lastMs = Double.NaN;
		}
	}

	public static boolean unavailable() {
		return unavailable;
	}

	/** Last measured GPU frame time, NaN if none. */
	public static double lastMs() {
		return lastMs;
	}

	public static void reset() {
		sumMs = 0;
		maxMs = 0;
		samples = 0;
	}

	public static java.util.Map<String, Object> snapshot() {
		java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
		out.put("samples", samples);
		if (unavailable) out.put("unavailable", true);
		if (samples > 0) {
			out.put("meanMs", sumMs / samples);
			out.put("maxMs", maxMs);
		}
		return out;
	}
}
