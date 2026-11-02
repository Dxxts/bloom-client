package com.bloomclient.client.core;

import com.bloomclient.client.BloomClient;
import com.bloomclient.client.api.BloomMod;
import com.bloomclient.client.api.ModCategory;
import com.bloomclient.client.ui.Tr;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Registry and lifecycle of the Bloom mods. Enabled/disabled state is stored
 * in {@code config/bloom/mods.json}:
 *
 * <pre>{"schema": 1, "enabled": {"bloom-world-tracker": true, ...}}</pre>
 *
 * Only mods compiled into Bloom are registered here; nothing is downloaded or
 * loaded from outside.
 */
public final class ModManager {
	private final EventBus bus = new EventBus();
	private final List<ModContainer> mods = new ArrayList<>();
	private final Map<String, ModContainer> byId = new LinkedHashMap<>();
	private final Path stateFile = BloomStorage.configDir().resolve("mods.json");
	private final Map<String, Boolean> savedState = new LinkedHashMap<>();
	private boolean initialized;

	public EventBus bus() {
		return bus;
	}

	public void register(BloomMod mod) {
		if (initialized) throw new IllegalStateException("Bloom mods must be registered before initialization");
		String id = mod.metadata().id();
		if (byId.containsKey(id)) throw new IllegalArgumentException("Duplicate Bloom mod id " + id);
		ModContainer c = new ModContainer(mod, this);
		mods.add(c);
		byId.put(id, c);
	}

	public void initialize() {
		initialized = true;
		JsonObject root = BloomStorage.readObject(stateFile);
		if (root != null && root.has("enabled") && root.get("enabled").isJsonObject()) {
			root.getAsJsonObject("enabled").entrySet().forEach(e -> {
				try {
					savedState.put(e.getKey(), e.getValue().getAsBoolean());
				} catch (RuntimeException ignored) {
					// Ignore malformed entries.
				}
			});
		}
		for (ModContainer c : mods) {
			c.initialize();
		}
		for (ModContainer c : mods) {
			boolean wanted = savedState.getOrDefault(c.metadata().id(), c.mod().enabledByDefault());
			if (wanted && c.state() != ModContainer.State.FAILED) {
				c.enable();
			}
		}
		saveState();
	}

	public List<ModContainer> all() {
		return Collections.unmodifiableList(mods);
	}

	public @Nullable ModContainer get(String id) {
		return byId.get(id);
	}

	public int enabledCount() {
		int n = 0;
		for (ModContainer c : mods) if (c.isEnabled()) n++;
		return n;
	}

	public List<ModContainer> inCategory(@Nullable ModCategory category) {
		if (category == null) return all();
		List<ModContainer> out = new ArrayList<>();
		for (ModContainer c : mods) if (c.metadata().category() == category) out.add(c);
		return out;
	}

	/** Turns a mod on or off at the player's request and remembers the choice. */
	public boolean setEnabled(ModContainer c, boolean enabled) {
		boolean ok = true;
		if (enabled) {
			if (c.state() == ModContainer.State.FAILED) return false;
			ok = c.enable();
		} else {
			c.disable();
		}
		savedState.put(c.metadata().id(), enabled && ok);
		saveState();
		BloomClient.hud().onModStateChanged();
		return ok;
	}

	void disableAfterErrors(ModContainer c) {
		c.disable();
		BloomClient.hud().onModStateChanged();
		BloomClient.LOGGER.warn("Bloom mod {} was turned off after repeated errors: {}", c.metadata().id(), c.failure());
		c.notify(Tr.t("bloom.mods.disabled_after_errors", c.metadata().displayName()));
		// The player's choice is kept: the mod starts again next session.
	}

	public void saveSettings() {
		for (ModContainer c : mods) c.settings().saveIfDirty();
	}

	private void saveState() {
		JsonObject root = new JsonObject();
		root.addProperty("schema", 1);
		JsonObject enabled = new JsonObject();
		savedState.forEach(enabled::addProperty);
		for (ModContainer c : mods) {
			if (!enabled.has(c.metadata().id())) enabled.addProperty(c.metadata().id(), c.isEnabled());
		}
		root.add("enabled", enabled);
		BloomStorage.writeAsync(stateFile, BloomStorage.GSON.toJson(root));
	}

	public void shutdown() {
		for (ModContainer c : mods) {
			c.shutdown();
		}
	}
}
