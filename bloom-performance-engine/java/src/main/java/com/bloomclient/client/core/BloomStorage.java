package com.bloomclient.client.core;

import com.bloomclient.client.BloomClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * Persistence layer shared by Bloom, the HUD and every Bloom mod.
 *
 * <ul>
 *   <li>{@code config/bloom/}: settings and layouts (small, user-facing JSON);</li>
 *   <li>{@code .bloom/worlds/<world>/<mod>/}: per-world data;</li>
 *   <li>{@code .bloom/mods/<mod>/}: per-mod data that is not tied to a world.</li>
 * </ul>
 *
 * <p>Writes are atomic (temporary file + move) and run on one background
 * thread, so saving never stalls a frame; several writes to the same file
 * before the thread gets to it collapse into the last one. {@link #flush()}
 * waits for pending writes (world leave, shutdown).
 */
public final class BloomStorage {
	public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Map<Path, Supplier<String>> PENDING = new LinkedHashMap<>();
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Bloom-IO");
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		return t;
	});

	private BloomStorage() {
	}

	public static Path configDir() {
		return FabricLoader.getInstance().getConfigDir().resolve("bloom");
	}

	public static Path dataDir() {
		return FabricLoader.getInstance().getGameDir().resolve(".bloom");
	}

	/** Safe file/folder name: lower case, [a-z0-9._-], at most 80 characters. */
	public static String sanitize(String name) {
		String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_").replaceAll("_+", "_");
		s = s.replaceAll("^[._]+", "");
		if (s.isEmpty()) s = "unnamed";
		return s.length() > 80 ? s.substring(0, 80) : s;
	}

	public static @Nullable JsonObject readObject(Path file) {
		try {
			if (!Files.isRegularFile(file)) return null;
			JsonElement e = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
			return e.isJsonObject() ? e.getAsJsonObject() : null;
		} catch (Exception e) {
			BloomClient.LOGGER.warn("Bloom: cannot read {}; keeping a copy and using defaults", file, e);
			backupBroken(file);
			return null;
		}
	}

	public static <T> @Nullable T read(Path file, Class<T> type) {
		JsonObject obj = readObject(file);
		if (obj == null) return null;
		try {
			return GSON.fromJson(obj, type);
		} catch (RuntimeException e) {
			BloomClient.LOGGER.warn("Bloom: {} has an unexpected format; keeping a copy and using defaults", file, e);
			backupBroken(file);
			return null;
		}
	}

	private static void backupBroken(Path file) {
		try {
			Files.copy(file, file.resolveSibling(file.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ignored) {
			// Best effort.
		}
	}

	/** Writes now, on the calling thread. */
	public static void writeNow(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, content, StandardCharsets.UTF_8);
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Queues an atomic write on the IO thread. {@code content} must already be serialized. */
	public static void writeAsync(Path file, String content) {
		writeAsync(file, () -> content);
	}

	/**
	 * Queues an atomic write whose content is produced on the IO thread. The
	 * supplier must only read data that the caller no longer mutates (pass a
	 * snapshot), so large files are serialized without stalling a frame.
	 */
	public static void writeAsync(Path file, Supplier<String> content) {
		boolean schedule;
		synchronized (PENDING) {
			schedule = PENDING.isEmpty();
			PENDING.put(file, content);
		}
		if (schedule) {
			IO.execute(BloomStorage::drain);
		}
	}

	public static void writeJsonAsync(Path file, Object data) {
		writeAsync(file, GSON.toJson(data));
	}

	private static void drain() {
		while (true) {
			Map.Entry<Path, Supplier<String>> next;
			synchronized (PENDING) {
				var it = PENDING.entrySet().iterator();
				if (!it.hasNext()) return;
				next = it.next();
				it.remove();
			}
			try {
				writeNow(next.getKey(), next.getValue().get());
			} catch (IOException | RuntimeException e) {
				BloomClient.LOGGER.error("Bloom: cannot save {}", next.getKey(), e);
			}
		}
	}

	/** Waits until every queued write is on disk (at most a few seconds). */
	public static void flush() {
		try {
			IO.submit(BloomStorage::drain).get(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			BloomClient.LOGGER.warn("Bloom: pending saves did not complete in time", e);
			drain();
		}
	}
}
