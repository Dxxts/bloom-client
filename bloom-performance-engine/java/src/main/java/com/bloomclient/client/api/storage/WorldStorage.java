package com.bloomclient.client.api.storage;

import com.bloomclient.client.core.BloomStorage;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * A mod's data folder for one world: {@code .bloom/worlds/<world>/<mod>/}.
 * Reads are synchronous (call them on world join); writes are atomic and
 * asynchronous.
 */
public final class WorldStorage {
	private final WorldIdentity world;
	private final Path dir;

	public WorldStorage(WorldIdentity world, Path dir) {
		this.world = world;
		this.dir = dir;
	}

	public WorldIdentity world() {
		return world;
	}

	public Path dir() {
		return dir;
	}

	public Path file(String name) {
		return dir.resolve(name);
	}

	public <T> T read(String name, Class<T> type, Supplier<T> fallback) {
		T value = BloomStorage.read(file(name), type);
		return value != null ? value : fallback.get();
	}

	public @Nullable JsonObject readObject(String name) {
		return BloomStorage.readObject(file(name));
	}

	/** Serializes {@code data} now (on the calling thread) and writes it in the background. */
	public void write(String name, Object data) {
		BloomStorage.writeJsonAsync(file(name), data);
	}

	/** Writes the JSON produced by {@code json} on the IO thread; {@code json} must only read a snapshot. */
	public void writeLater(String name, Supplier<String> json) {
		BloomStorage.writeAsync(file(name), json);
	}
}
