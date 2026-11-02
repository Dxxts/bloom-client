package com.bloomclient.client.mods.worldtracker;

/** Plain records persisted by the World Tracker. */
final class TrackerRecords {
	private TrackerRecords() {
	}

	/** One sampled position. {@code t} in epoch milliseconds. */
	static final class HistoryPoint {
		long t;
		String dim;
		int x, y, z;

		HistoryPoint() {
		}

		HistoryPoint(long t, String dim, int x, int y, int z) {
			this.t = t;
			this.dim = dim;
			this.x = x;
			this.y = y;
			this.z = z;
		}
	}

	/** First visit of a biome. */
	static final class BiomeVisit {
		String biome;
		long first;
		String dim;
		int x, y, z;
	}

	/** Per-dimension statistics. */
	static final class DimensionStats {
		String dim;
		long seconds;
		long firstVisit;
		long lastVisit;
		int lastX, lastY, lastZ;
		double distance;
	}

	/** A position the tracker noticed and the player may save (never saved automatically). */
	record Suggestion(String key, PoiCategory category, String name, String dimension, int x, int y, int z, String reason) {
	}

	/** A continuous trip reconstructed from the history. */
	record Path(String dim, long start, long end, int fromX, int fromZ, int toX, int toZ, double distance, int points) {
	}
}
