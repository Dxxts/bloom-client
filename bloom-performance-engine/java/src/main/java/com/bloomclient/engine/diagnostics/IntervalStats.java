package com.bloomclient.engine.diagnostics;

import com.bloomclient.engine.profiling.EngineProfiler;

/**
 * Section compile rates and mean times over the last second, from the
 * profiler's running totals (cheap enough to read every frame).
 */
final class IntervalStats {
	record Window(double nativePerSecond, double nativeMeanMs, double vanillaPerSecond, double vanillaMeanMs, double snapshotMeanMs,
			double meshMeanMs) {
	}

	private static final long PERIOD = 1_000_000_000L;

	private long start = System.nanoTime();
	private long[] base = EngineProfiler.totals();
	private Window last = new Window(0, 0, 0, 0, 0, 0);

	Window window() {
		long now = System.nanoTime();
		if (now - start < PERIOD) {
			return last;
		}
		long[] totals = EngineProfiler.totals();
		if (totals[0] < base[0] || totals[2] < base[2]) {
			// The profiler was reset (benchmark phase): start a new window.
			base = totals;
			start = now;
			return last;
		}
		double seconds = (now - start) / 1e9;
		long nativeCount = totals[0] - base[0];
		long vanillaCount = totals[2] - base[2];
		last = new Window(nativeCount / seconds, mean(totals[1] - base[1], nativeCount), vanillaCount / seconds, mean(totals[3] - base[3], vanillaCount),
				mean(totals[5] - base[5], totals[4] - base[4]), mean(totals[7] - base[7], totals[6] - base[6]));
		base = totals;
		start = now;
		return last;
	}

	private static double mean(long nanos, long count) {
		return count == 0 ? 0 : nanos / (double) count / 1e6;
	}
}
