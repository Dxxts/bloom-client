package com.bloomclient.client.mods.worldtracker;

import com.bloomclient.client.api.storage.WorldStorage;
import com.bloomclient.client.core.BloomStorage;
import com.bloomclient.client.mods.worldtracker.TrackerRecords.BiomeVisit;
import com.bloomclient.client.mods.worldtracker.TrackerRecords.DimensionStats;
import com.bloomclient.client.mods.worldtracker.TrackerRecords.HistoryPoint;
import com.bloomclient.client.mods.worldtracker.TrackerRecords.Path;
import com.bloomclient.client.mods.worldtracker.TrackerRecords.Suggestion;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Everything the World Tracker knows about one world: saved points,
 * exploration (visited chunks per dimension, position history, biomes,
 * time and distance per dimension) and the current suggestions.
 *
 * <p>Files in {@code .bloom/worlds/<world>/bloom-world-tracker/}:
 * {@code points.json}, {@code exploration.json}, {@code explored.json}
 * (visited chunks as 32×32-chunk region bitmaps, base64), each with a
 * {@code schema} field.
 */
final class WorldState {
	static final int SCHEMA = 1;

	final WorldStorage storage;
	final List<Poi> points = new ArrayList<>();
	@Nullable String selectedId;
	final ArrayDeque<HistoryPoint> history = new ArrayDeque<>();
	final Map<String, BiomeVisit> biomes = new LinkedHashMap<>();
	final Map<String, DimensionStats> dimensions = new LinkedHashMap<>();
	/** dimension → region key → 1024-bit bitmap of visited chunks. */
	final Map<String, Map<Long, long[]>> explored = new HashMap<>();
	final List<Suggestion> suggestions = new ArrayList<>();
	/** Suggestion keys the player dismissed or saved: never suggested again in this world. */
	final java.util.Set<String> dismissed = new java.util.LinkedHashSet<>();
	private final Map<String, Integer> exploredCount = new HashMap<>();
	private boolean pointsDirty, explorationDirty;

	WorldState(WorldStorage storage) {
		this.storage = storage;
	}

	// ---------------------------------------------------------------- persistence

	void load() {
		JsonObject p = storage.readObject("points.json");
		if (p != null) {
			if (p.has("selected") && !p.get("selected").isJsonNull()) selectedId = p.get("selected").getAsString();
			if (p.has("dismissed") && p.get("dismissed").isJsonArray()) {
				for (JsonElement d : p.getAsJsonArray("dismissed")) dismissed.add(d.getAsString());
			}
			JsonElement arr = p.get("points");
			if (arr != null && arr.isJsonArray()) {
				for (JsonElement e : arr.getAsJsonArray()) {
					try {
						Poi poi = BloomStorage.GSON.fromJson(e, Poi.class);
						if (poi != null) {
							poi.sanitize();
							points.add(poi);
						}
					} catch (RuntimeException ignored) {
						// Skip a malformed entry.
					}
				}
			}
		}
		JsonObject ex = storage.readObject("exploration.json");
		if (ex != null) {
			readList(ex, "history", HistoryPoint.class).forEach(history::add);
			for (BiomeVisit b : readList(ex, "biomes", BiomeVisit.class)) if (b.biome != null) biomes.put(b.biome, b);
			for (DimensionStats d : readList(ex, "dimensions", DimensionStats.class)) if (d.dim != null) dimensions.put(d.dim, d);
		}
		JsonObject chunks = storage.readObject("explored.json");
		if (chunks != null && chunks.has("dims") && chunks.get("dims").isJsonObject()) {
			Base64.Decoder dec = Base64.getDecoder();
			for (var dim : chunks.getAsJsonObject("dims").entrySet()) {
				Map<Long, long[]> regions = new HashMap<>();
				int count = 0;
				for (var region : dim.getValue().getAsJsonObject().entrySet()) {
					try {
						String[] xz = region.getKey().split(",");
						byte[] bytes = dec.decode(region.getValue().getAsString());
						if (bytes.length != 128) continue;
						long[] bits = new long[16];
						for (int i = 0; i < 16; i++) {
							long v = 0;
							for (int b = 0; b < 8; b++) v |= (bytes[i * 8 + b] & 0xFFL) << (b * 8);
							bits[i] = v;
							count += Long.bitCount(v);
						}
						regions.put(regionKey(Integer.parseInt(xz[0]), Integer.parseInt(xz[1])), bits);
					} catch (RuntimeException ignored) {
						// Skip a malformed region.
					}
				}
				explored.put(dim.getKey(), regions);
				exploredCount.put(dim.getKey(), count);
			}
		}
	}

	private static <T> List<T> readList(JsonObject root, String key, Class<T> type) {
		List<T> out = new ArrayList<>();
		JsonElement arr = root.get(key);
		if (arr == null || !arr.isJsonArray()) return out;
		for (JsonElement e : arr.getAsJsonArray()) {
			try {
				T v = BloomStorage.GSON.fromJson(e, type);
				if (v != null) out.add(v);
			} catch (RuntimeException ignored) {
				// Skip.
			}
		}
		return out;
	}

	void markPointsDirty() {
		pointsDirty = true;
	}

	void save(boolean force) {
		if (pointsDirty || force) {
			pointsDirty = false;
			JsonObject root = new JsonObject();
			root.addProperty("schema", SCHEMA);
			if (selectedId != null) root.addProperty("selected", selectedId);
			root.add("points", BloomStorage.GSON.toJsonTree(points));
			root.add("dismissed", BloomStorage.GSON.toJsonTree(new ArrayList<>(dismissed)));
			storage.write("points.json", root);
		}
		if (explorationDirty || force) {
			explorationDirty = false;
			// Snapshot on the client thread (history points and biome visits are never modified after creation),
			// serialize on the IO thread.
			List<HistoryPoint> historySnapshot = new ArrayList<>(history);
			List<BiomeVisit> biomeSnapshot = new ArrayList<>(biomes.values());
			List<DimensionStats> dimSnapshot = new ArrayList<>();
			for (DimensionStats d : dimensions.values()) {
				DimensionStats c = new DimensionStats();
				c.dim = d.dim;
				c.seconds = d.seconds;
				c.firstVisit = d.firstVisit;
				c.lastVisit = d.lastVisit;
				c.lastX = d.lastX;
				c.lastY = d.lastY;
				c.lastZ = d.lastZ;
				c.distance = d.distance;
				dimSnapshot.add(c);
			}
			storage.writeLater("exploration.json", () -> {
				JsonObject ex = new JsonObject();
				ex.addProperty("schema", SCHEMA);
				ex.add("history", BloomStorage.GSON.toJsonTree(historySnapshot));
				ex.add("biomes", BloomStorage.GSON.toJsonTree(biomeSnapshot));
				ex.add("dimensions", BloomStorage.GSON.toJsonTree(dimSnapshot));
				return BloomStorage.GSON.toJson(ex);
			});
			Map<String, Map<Long, long[]>> chunkSnapshot = new HashMap<>();
			for (var dim : explored.entrySet()) {
				Map<Long, long[]> regions = new HashMap<>();
				for (var r : dim.getValue().entrySet()) regions.put(r.getKey(), r.getValue().clone());
				chunkSnapshot.put(dim.getKey(), regions);
			}
			storage.writeLater("explored.json", () -> {
				JsonObject chunks = new JsonObject();
				chunks.addProperty("schema", SCHEMA);
				JsonObject dims = new JsonObject();
				Base64.Encoder enc = Base64.getEncoder();
				byte[] buf = new byte[128];
				for (var dim : chunkSnapshot.entrySet()) {
					JsonObject regions = new JsonObject();
					for (var r : dim.getValue().entrySet()) {
						long[] bits = r.getValue();
						for (int i = 0; i < 16; i++) for (int b = 0; b < 8; b++) buf[i * 8 + b] = (byte) (bits[i] >>> (b * 8));
						regions.addProperty(regionX(r.getKey()) + "," + regionZ(r.getKey()), enc.encodeToString(buf));
					}
					dims.add(dim.getKey(), regions);
				}
				chunks.add("dims", dims);
				return BloomStorage.GSON.toJson(chunks);
			});
		}
	}

	// ---------------------------------------------------------------- points

	@Nullable Poi selected() {
		if (selectedId == null) return null;
		for (Poi p : points) if (p.id.equals(selectedId)) return p;
		return null;
	}

	void select(@Nullable Poi poi) {
		selectedId = poi == null ? null : poi.id;
		pointsDirty = true;
	}

	void add(Poi poi) {
		poi.sanitize();
		points.add(poi);
		pointsDirty = true;
	}

	void remove(Poi poi) {
		points.remove(poi);
		if (poi.id.equals(selectedId)) selectedId = null;
		pointsDirty = true;
	}

	// ---------------------------------------------------------------- exploration

	static long regionKey(int rx, int rz) {
		return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
	}

	static int regionX(long key) {
		return (int) (key >> 32);
	}

	static int regionZ(long key) {
		return (int) key;
	}

	boolean isExplored(String dim, int cx, int cz) {
		Map<Long, long[]> regions = explored.get(dim);
		if (regions == null) return false;
		long[] bits = regions.get(regionKey(cx >> 5, cz >> 5));
		if (bits == null) return false;
		int idx = ((cz & 31) << 5) | (cx & 31);
		return (bits[idx >> 6] & (1L << (idx & 63))) != 0;
	}

	/** Marks a chunk visited; returns true if it was new. */
	boolean markExplored(String dim, int cx, int cz) {
		long[] bits = explored.computeIfAbsent(dim, k -> new HashMap<>()).computeIfAbsent(regionKey(cx >> 5, cz >> 5), k -> new long[16]);
		int idx = ((cz & 31) << 5) | (cx & 31);
		long mask = 1L << (idx & 63);
		if ((bits[idx >> 6] & mask) != 0) return false;
		bits[idx >> 6] |= mask;
		exploredCount.merge(dim, 1, Integer::sum);
		explorationDirty = true;
		return true;
	}

	int exploredChunks(String dim) {
		return exploredCount.getOrDefault(dim, 0);
	}

	void addHistory(HistoryPoint p, int max) {
		HistoryPoint last = history.peekLast();
		history.addLast(p);
		while (history.size() > max) history.removeFirst();
		DimensionStats d = dimension(p.dim, p.t);
		if (last != null && last.dim.equals(p.dim) && p.t - last.t < 120_000) {
			double dx = p.x - last.x, dy = p.y - last.y, dz = p.z - last.z;
			d.distance += Math.sqrt(dx * dx + dy * dy + dz * dz);
		}
		explorationDirty = true;
	}

	DimensionStats dimension(String dim, long now) {
		return dimensions.computeIfAbsent(dim, k -> {
			DimensionStats s = new DimensionStats();
			s.dim = k;
			s.firstVisit = now;
			return s;
		});
	}

	void tickDimension(String dim, int x, int y, int z, long now, int seconds) {
		DimensionStats d = dimension(dim, now);
		d.seconds += seconds;
		d.lastVisit = now;
		d.lastX = x;
		d.lastY = y;
		d.lastZ = z;
		explorationDirty = true;
	}

	boolean visitBiome(String biome, String dim, int x, int y, int z, long now) {
		if (biomes.containsKey(biome)) return false;
		BiomeVisit v = new BiomeVisit();
		v.biome = biome;
		v.first = now;
		v.dim = dim;
		v.x = x;
		v.y = y;
		v.z = z;
		biomes.put(biome, v);
		explorationDirty = true;
		return true;
	}

	/** Splits the history into trips: a new trip starts after a gap of {@code gapMs} or a dimension change. Newest first. */
	List<Path> paths(long gapMs, int limit) {
		List<Path> out = new ArrayList<>();
		HistoryPoint start = null, prev = null;
		double dist = 0;
		int count = 0;
		for (HistoryPoint p : history) {
			if (prev != null && (p.t - prev.t > gapMs || !p.dim.equals(prev.dim))) {
				if (count > 1) out.add(new Path(start.dim, start.t, prev.t, start.x, start.z, prev.x, prev.z, dist, count));
				start = null;
			}
			if (start == null) {
				start = p;
				dist = 0;
				count = 0;
			} else {
				double dx = p.x - prev.x, dy = p.y - prev.y, dz = p.z - prev.z;
				dist += Math.sqrt(dx * dx + dy * dy + dz * dz);
			}
			count++;
			prev = p;
		}
		if (start != null && count > 1) out.add(new Path(start.dim, start.t, prev.t, start.x, start.z, prev.x, prev.z, dist, count));
		Collections.reverse(out);
		return out.size() > limit ? out.subList(0, limit) : out;
	}

	// ---------------------------------------------------------------- suggestions

	/** Adds a suggestion unless a similar one or a saved point of the same category is within {@code radius}. */
	void suggest(Suggestion s, int radius) {
		if (dismissed.contains(s.key())) return;
		for (Suggestion o : suggestions) {
			if (o.key().equals(s.key())) return;
			if (o.category() == s.category() && o.dimension().equals(s.dimension()) && dist2(o.x(), o.z(), s.x(), s.z()) < (long) radius * radius) return;
		}
		for (Poi p : points) {
			if (p.category == s.category() && p.dimension.equals(s.dimension()) && dist2(p.x, p.z, s.x(), s.z()) < (long) radius * radius) return;
		}
		suggestions.addFirst(s);
		while (suggestions.size() > 20) suggestions.removeLast();
	}

	void dismiss(Suggestion s) {
		suggestions.remove(s);
		dismissed.add(s.key());
		while (dismissed.size() > 500) dismissed.remove(dismissed.iterator().next());
		pointsDirty = true;
	}

	static long dist2(int x1, int z1, int x2, int z2) {
		long dx = x1 - x2, dz = z1 - z2;
		return dx * dx + dz * dz;
	}
}
